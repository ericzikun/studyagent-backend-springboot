package com.studyagent.api.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.api.config.VerlaSseProperties;
import com.studyagent.common.exception.RateLimitExceededException;
import com.studyagent.common.verla.envelope.VerlaEventEnvelope;
import com.studyagent.service.application.verla.VerlaFrontendPayloadSanitizer;
import com.studyagent.service.application.verla.sse.VerlaSseEventPayload;
import com.studyagent.service.application.verla.sse.VerlaSsePublisher;
import com.studyagent.service.domain.verla.VerlaEventInbox;
import com.studyagent.service.domain.verla.repo.VerlaEventInboxRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Verla SSE 网关（PR-16）
 * <p>
 * 详见 docs/verla-Java侧MVP技术方案.md §13 / docs/V2/SSE多Tab连接瓶颈分析与修复方案.md
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VerlaSseGateway implements VerlaSsePublisher {

    private static final int REPLAY_BATCH = 200;

    private final VerlaEventInboxRepository inboxRepository;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final VerlaSseProperties sseProperties;

    private final Map<Long, CopyOnWriteArrayList<EmitterRegistration>> emitters = new ConcurrentHashMap<>();
    private final AtomicLong registrationsTotal = new AtomicLong();
    private final AtomicLong idleClosedTotal = new AtomicLong();
    private final AtomicLong userLimitRejectedTotal = new AtomicLong();

    @PostConstruct
    void registerGauge() {
        meterRegistry.gauge("verla.sse.connections.total", Tags.empty(), this,
                g -> g.emitters.values().stream().mapToInt(List::size).sum());
        meterRegistry.gauge("verla.sse.conversations.total", Tags.empty(), this,
                g -> g.emitters.size());
        meterRegistry.gauge("verla.sse.registrations.total", Tags.empty(), registrationsTotal,
                AtomicLong::get);
        meterRegistry.gauge("verla.sse.idle_closed.total", Tags.empty(), idleClosedTotal,
                AtomicLong::get);
        meterRegistry.gauge("verla.sse.user_limit_rejected.total", Tags.empty(), userLimitRejectedTotal,
                AtomicLong::get);
    }

    @PreDestroy
    void closeAll() {
        emitters.forEach((cid, list) -> {
            for (EmitterRegistration reg : list) {
                completeQuietly(reg.emitter);
            }
        });
        emitters.clear();
    }

    /**
     * 注册一个新的 SseEmitter；补发该 conv 中 inbox.id &gt; lastEventId（或 lastEventId 缺失时从 0）的已 PROCESSED 事件。
     */
    public SseEmitter register(Long conversationId, Long lastEventId, String clerkUserId) {
        enforceUserConnectionLimit(clerkUserId);

        long now = System.currentTimeMillis();
        SseEmitter em = new SseEmitter(sseProperties.getEmitterTimeoutMs());
        long replayAfter = (lastEventId != null && lastEventId > 0) ? lastEventId : 0L;
        EmitterRegistration reg = new EmitterRegistration(em, clerkUserId, now, replayAfter);
        CopyOnWriteArrayList<EmitterRegistration> list = emitters.computeIfAbsent(
                conversationId, k -> new CopyOnWriteArrayList<>());
        evictOldestIfNeeded(conversationId, list);
        list.add(reg);
        registrationsTotal.incrementAndGet();

        em.onCompletion(() -> remove(conversationId, reg));
        em.onTimeout(() -> {
            log.debug("[Verla/sse] emitter timeout cid={} user={}", conversationId, clerkUserId);
            remove(conversationId, reg);
        });
        em.onError(t -> remove(conversationId, reg));

        try {
            em.send(SseEmitter.event().name("ready").data("ok"));
        } catch (Exception e) {
            log.warn("[Verla/sse] initial ready frame failed cid={}: {}", conversationId, e.getMessage());
            remove(conversationId, reg);
            return em;
        }

        boolean replaySucceeded = false;
        try {
            replaySucceeded = replay(conversationId, replayAfter, reg);
        } catch (Exception e) {
            log.warn("[Verla/sse] replay failed cid={} after={}: {}",
                    conversationId, replayAfter, e.getMessage());
        }
        if (!replaySucceeded || !finishReplay(reg, conversationId)) {
            completeQuietly(em);
            remove(conversationId, reg);
        }
        return em;
    }

    @Override
    public void publish(Long conversationId, VerlaSseEventPayload payload) {
        if (conversationId == null || payload == null) {
            return;
        }
        CopyOnWriteArrayList<EmitterRegistration> list = emitters.get(conversationId);
        if (list == null || list.isEmpty()) {
            log.warn(
                    "[Verla/sse] publish skipped — no SSE subscribers (event already in inbox): cid={} type={} inboxRowId={}",
                    conversationId,
                    payload.getType(),
                    payload.getId());
            return;
        }
        Iterator<EmitterRegistration> it = list.iterator();
        while (it.hasNext()) {
            EmitterRegistration reg = it.next();
            if (sendLive(reg, payload, conversationId)) {
                reg.touchBusinessWrite();
            }
        }
    }

    @Scheduled(fixedDelayString = "${verla.sse.heartbeat-interval-ms:15000}")
    public void heartbeat() {
        if (emitters.isEmpty()) {
            return;
        }
        emitters.forEach((cid, list) -> {
            for (EmitterRegistration reg : list) {
                try {
                    sendHeartbeat(reg);
                } catch (Exception e) {
                    remove(cid, reg);
                }
            }
        });
    }

    /** 关闭长时间无业务事件的 SSE 连接，释放 Tomcat 连接与内存。 */
    @Scheduled(fixedDelayString = "${verla.sse.idle-sweep-interval-ms:60000}")
    public void sweepIdleConnections() {
        long idleTimeoutMs = sseProperties.getIdleTimeoutMs();
        if (idleTimeoutMs <= 0L || emitters.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        emitters.forEach((cid, list) -> {
            for (EmitterRegistration reg : list) {
                if (now - reg.lastBusinessWriteAtMs >= idleTimeoutMs) {
                    log.info("[Verla/sse] idle close cid={} user={} idleMs={}",
                            cid, reg.clerkUserId, now - reg.lastBusinessWriteAtMs);
                    idleClosedTotal.incrementAndGet();
                    completeQuietly(reg.emitter);
                    remove(cid, reg);
                }
            }
        });
    }

    private void enforceUserConnectionLimit(String clerkUserId) {
        int maxPerUser = sseProperties.getMaxEmittersPerUser();
        if (maxPerUser <= 0 || clerkUserId == null || clerkUserId.isBlank()) {
            return;
        }
        int active = countUserConnections(clerkUserId);
        if (active >= maxPerUser) {
            userLimitRejectedTotal.incrementAndGet();
            log.warn("[Verla/sse] user connection limit reached user={} active={} max={}",
                    clerkUserId, active, maxPerUser);
            throw new RateLimitExceededException("verla-sse-subscribe");
        }
    }

    private int countUserConnections(String clerkUserId) {
        int count = 0;
        for (CopyOnWriteArrayList<EmitterRegistration> list : emitters.values()) {
            for (EmitterRegistration reg : list) {
                if (clerkUserId.equals(reg.clerkUserId)) {
                    count++;
                }
            }
        }
        return count;
    }

    private void evictOldestIfNeeded(Long conversationId, CopyOnWriteArrayList<EmitterRegistration> list) {
        int maxPerConv = Math.max(1, sseProperties.getMaxEmittersPerConversation());
        if (list.size() < maxPerConv) {
            return;
        }
        try {
            EmitterRegistration old = list.remove(0);
            completeQuietly(old.emitter);
            log.debug("[Verla/sse] evicted oldest emitter cid={} user={}", conversationId, old.clerkUserId);
        } catch (Exception ignored) {
            // ignored
        }
    }

    private boolean replay(Long conversationId, long afterId, EmitterRegistration reg) {
        long cursor = afterId;
        boolean fullCatchUp = afterId == 0L;
        while (true) {
            List<VerlaEventInbox> page = inboxRepository.findReplayByConversation(
                    conversationId, cursor, REPLAY_BATCH);
            if (page == null || page.isEmpty()) {
                break;
            }
            for (VerlaEventInbox row : page) {
                if (fullCatchUp && shouldOmitPlanIntentReplay(row.getEventType())) {
                    cursor = row.getId();
                    continue;
                }
                VerlaSseEventPayload p = toReplayPayload(row);
                if (sendReplay(reg, p, conversationId)) {
                    reg.touchBusinessWrite();
                } else {
                    return false;
                }
                cursor = row.getId();
            }
            if (page.size() < REPLAY_BATCH) {
                break;
            }
        }
        return true;
    }

    private static boolean shouldOmitPlanIntentReplay(String eventType) {
        if (eventType == null) {
            return false;
        }
        return "PLAN_INTENT_STREAM_CHUNK".equals(eventType)
                || "PLAN_INTENT_RESOLVED".equals(eventType);
    }

    /**
     * Live events published while inbox replay is running are buffered by id.
     * This prevents a newly published id=101 from reaching the browser before
     * the reconnect replay writes id=100 on the same emitter.
     */
    private boolean sendLive(EmitterRegistration reg, VerlaSseEventPayload payload, Long conversationId) {
        Long eventId = payload.getId();
        if (eventId == null || eventId <= 0L) {
            log.warn("[Verla/sse] skip live payload without positive id cid={} type={}",
                    conversationId, payload.getType());
            return false;
        }
        synchronized (reg.deliveryLock) {
            if (eventId <= reg.lastSentEventId) {
                return true;
            }
            if (reg.replaying) {
                reg.pendingLiveById.putIfAbsent(eventId, payload);
                return true;
            }
            return sendOrdered(reg, payload, conversationId);
        }
    }

    private boolean sendReplay(EmitterRegistration reg, VerlaSseEventPayload payload, Long conversationId) {
        synchronized (reg.deliveryLock) {
            return sendOrdered(reg, payload, conversationId);
        }
    }

    /** Switches one registration atomically from replay to ordered live delivery. */
    private boolean finishReplay(EmitterRegistration reg, Long conversationId) {
        synchronized (reg.deliveryLock) {
            for (VerlaSseEventPayload pending : reg.pendingLiveById.values()) {
                if (!sendOrdered(reg, pending, conversationId)) {
                    reg.pendingLiveById.clear();
                    return false;
                }
            }
            reg.pendingLiveById.clear();
            reg.replaying = false;
            return true;
        }
    }

    /** Caller must hold {@link EmitterRegistration#deliveryLock}. */
    private boolean sendOrdered(EmitterRegistration reg, VerlaSseEventPayload payload, Long conversationId) {
        Long eventId = payload.getId();
        if (eventId == null || eventId <= reg.lastSentEventId) {
            return true;
        }
        try {
            reg.emitter.send(SseEmitter.event()
                    .id(String.valueOf(eventId))
                    .name("verla")
                    .data(payload, MediaType.APPLICATION_JSON));
            reg.lastSentEventId = eventId;
            return true;
        } catch (IOException ioe) {
            log.debug("[Verla/sse] send io fail (likely disconnected) cid={} id={}: {}",
                    conversationId, payload.getId(), ioe.getMessage());
            remove(conversationId, reg);
            return false;
        } catch (Exception e) {
            log.warn("[Verla/sse] send unexpected fail cid={} id={}: {}",
                    conversationId, payload.getId(), e.getMessage());
            remove(conversationId, reg);
            return false;
        }
    }

    private void sendHeartbeat(EmitterRegistration reg) throws IOException {
        synchronized (reg.deliveryLock) {
            reg.emitter.send(SseEmitter.event().name("ping").data("h"));
        }
    }

    private VerlaSseEventPayload toReplayPayload(VerlaEventInbox row) {
        Map<String, Object> payloadMap = null;
        try {
            if (row.getPayloadJson() != null && !row.getPayloadJson().isBlank()) {
                VerlaEventEnvelope env = objectMapper.readValue(
                        row.getPayloadJson(), VerlaEventEnvelope.class);
                payloadMap = env.getPayload();
            }
        } catch (Exception e) {
            log.debug("[Verla/sse] replay parse skip id={}: {}", row.getId(), e.getMessage());
        }
        return VerlaSseEventPayload.builder()
                .id(row.getId())
                .type(row.getEventType())
                .conversationId(row.getConversationId())
                .turnId(row.getTurnId())
                .sessionId(row.getSessionId())
                .stepId(row.getStepId())
                .stepSeq(row.getStepSeq())
                .payload(VerlaFrontendPayloadSanitizer.sanitize(row.getEventType(), payloadMap))
                .build();
    }

    private void remove(Long conversationId, EmitterRegistration reg) {
        CopyOnWriteArrayList<EmitterRegistration> list = emitters.get(conversationId);
        if (list == null) {
            return;
        }
        list.remove(reg);
        if (list.isEmpty()) {
            emitters.remove(conversationId, list);
        }
    }

    private static void completeQuietly(SseEmitter em) {
        try {
            em.complete();
        } catch (Exception ignored) {
            // ignored
        }
    }

    private static final class EmitterRegistration {
        private final SseEmitter emitter;
        private final String clerkUserId;
        private final Object deliveryLock = new Object();
        private final TreeMap<Long, VerlaSseEventPayload> pendingLiveById = new TreeMap<>();
        private boolean replaying = true;
        private long lastSentEventId;
        private volatile long lastBusinessWriteAtMs;

        private EmitterRegistration(
                SseEmitter emitter,
                String clerkUserId,
                long registeredAtMs,
                long replayAfterEventId) {
            this.emitter = emitter;
            this.clerkUserId = clerkUserId;
            this.lastSentEventId = replayAfterEventId;
            this.lastBusinessWriteAtMs = registeredAtMs;
        }

        private void touchBusinessWrite() {
            lastBusinessWriteAtMs = System.currentTimeMillis();
        }
    }
}
