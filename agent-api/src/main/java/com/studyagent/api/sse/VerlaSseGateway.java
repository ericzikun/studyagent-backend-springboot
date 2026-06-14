package com.studyagent.api.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Verla SSE 网关（PR-16）
 * <p>
 * 详见 docs/verla-Java侧MVP技术方案.md §13。\
 * <ul>
 *     <li>{@link #register(Long, Long)}：按 conversationId 注册 SseEmitter，并按 Last-Event-ID 补发</li>
 *     <li>{@link #publish(Long, VerlaSseEventPayload)}：广播一条事件到该 conversation 全部连接</li>
 *     <li>定时心跳（{@link #heartbeat()}）：每 15s 发 {@code event:ping}，避免被前置代理切线</li>
 * </ul>
 * <p>
 * 同一 tab 一个连接，承载该 tab 所有 turn / session 的事件；连接级别的所有权校验由 controller 完成。\
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VerlaSseGateway implements VerlaSsePublisher {

    /** 单个 conversation 的最大并发连接数，防一个 tab 滥开 */
    private static final int MAX_EMITTERS_PER_CONV = 8;
    /** 补发批次大小，避免回放过久 */
    private static final int REPLAY_BATCH = 200;
    /** SSE 写入超时：0 表示永不超时（依赖前端断开 + 心跳维持） */
    private static final long EMITTER_TIMEOUT_MS = 0L;

    private final VerlaEventInboxRepository inboxRepository;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();

    @PostConstruct
    void registerGauge() {
        meterRegistry.gauge("verla.sse.connections.total", Tags.empty(), this,
                g -> g.emitters.values().stream().mapToInt(List::size).sum());
        meterRegistry.gauge("verla.sse.conversations.total", Tags.empty(), this,
                g -> g.emitters.size());
    }

    @PreDestroy
    void closeAll() {
        emitters.forEach((cid, list) -> {
            for (SseEmitter e : list) {
                try {
                    e.complete();
                } catch (Exception ignored) {
                    // ignored
                }
            }
        });
        emitters.clear();
    }

    /**
     * 注册一个新的 SseEmitter；补发该 conv 中 inbox.id &gt; lastEventId（或 lastEventId 缺失时从 0）的已 PROCESSED 事件。
     */
    public SseEmitter register(Long conversationId, Long lastEventId) {
        SseEmitter em = new SseEmitter(EMITTER_TIMEOUT_MS);
        CopyOnWriteArrayList<SseEmitter> list = emitters.computeIfAbsent(
                conversationId, k -> new CopyOnWriteArrayList<>());
        if (list.size() >= MAX_EMITTERS_PER_CONV) {
            // 强制踢掉最老的连接，防内存堆积
            try {
                SseEmitter old = list.remove(0);
                old.complete();
            } catch (Exception ignored) {
                // ignored
            }
        }
        list.add(em);
        em.onCompletion(() -> remove(conversationId, em));
        em.onTimeout(() -> remove(conversationId, em));
        em.onError(t -> remove(conversationId, em));

        try {
            em.send(SseEmitter.event().name("ready").data("ok"));
        } catch (Exception e) {
            log.warn("[Verla/sse] initial ready frame failed cid={}: {}", conversationId, e.getMessage());
            remove(conversationId, em);
            return em;
        }

        // 补发：lastEventId>0 时只补该游标之后的事件；否则从 0 回放本会话已 PROCESSED 记录。
        // 避免「事件先于 SSE 连接落库」时 live publish 因无 emitter 被丢弃、客户端永远收不到（如 Humanizer 很快结束）。
        long replayAfter = (lastEventId != null && lastEventId > 0) ? lastEventId : 0L;
        try {
            replay(conversationId, replayAfter, em);
        } catch (Exception e) {
            log.warn("[Verla/sse] replay failed cid={} after={}: {}",
                    conversationId, replayAfter, e.getMessage());
        }
        return em;
    }

    @Override
    public void publish(Long conversationId, VerlaSseEventPayload payload) {
        if (conversationId == null || payload == null) {
            return;
        }
        CopyOnWriteArrayList<SseEmitter> list = emitters.get(conversationId);
        if (list == null || list.isEmpty()) {
            log.warn(
                    "[Verla/sse] publish skipped — no SSE subscribers (event already in inbox): cid={} type={} inboxRowId={}",
                    conversationId,
                    payload.getType(),
                    payload.getId());
            return;
        }
        Iterator<SseEmitter> it = list.iterator();
        while (it.hasNext()) {
            SseEmitter em = it.next();
            sendOne(em, payload, conversationId);
        }
    }

    /** 心跳：每 15s 给所有 emitter 发一个 ping，保活 + 探活。 */
    @Scheduled(fixedDelay = 15_000L)
    public void heartbeat() {
        if (emitters.isEmpty()) {
            return;
        }
        emitters.forEach((cid, list) -> {
            for (SseEmitter em : list) {
                try {
                    em.send(SseEmitter.event().name("ping").data("h"));
                } catch (Exception e) {
                    remove(cid, em);
                }
            }
        });
    }

    private void replay(Long conversationId, long afterId, SseEmitter em) {
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
                sendOne(em, p, conversationId);
                cursor = row.getId();
            }
            if (page.size() < REPLAY_BATCH) {
                break;
            }
        }
    }

    /**
     * 首轮全量补发（afterId=0）时跳过已在 /messages 历史里的 Plan 流式/收敛事件，减轻与 hydrate 重复；
     * Agent/Humanizer 等仍原样补发。
     */
    private static boolean shouldOmitPlanIntentReplay(String eventType) {
        if (eventType == null) {
            return false;
        }
        return "PLAN_INTENT_STREAM_CHUNK".equals(eventType)
                || "PLAN_INTENT_RESOLVED".equals(eventType);
    }

    private void sendOne(SseEmitter em, VerlaSseEventPayload payload, Long conversationId) {
        try {
            em.send(SseEmitter.event()
                    .id(String.valueOf(payload.getId()))
                    .name("verla")
                    .data(payload, MediaType.APPLICATION_JSON));
            log.info("[RDS-debug][java][sse] send_success cid={} sessionId={} type={} inboxRowId={}",
                    conversationId,
                    payload.getSessionId(),
                    payload.getType(),
                    payload.getId());
        } catch (IOException ioe) {
            log.debug("[Verla/sse] send io fail (likely disconnected) cid={} id={}: {}",
                    conversationId, payload.getId(), ioe.getMessage());
            remove(conversationId, em);
        } catch (Exception e) {
            log.warn("[Verla/sse] send unexpected fail cid={} id={}: {}",
                    conversationId, payload.getId(), e.getMessage());
            remove(conversationId, em);
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

    private void remove(Long conversationId, SseEmitter em) {
        CopyOnWriteArrayList<SseEmitter> list = emitters.get(conversationId);
        if (list == null) {
            return;
        }
        list.remove(em);
        if (list.isEmpty()) {
            emitters.remove(conversationId, list);
        }
    }
}
