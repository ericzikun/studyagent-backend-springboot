package com.studyagent.service.application.verla;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.studyagent.common.api.ApiCode;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.service.application.verla.dto.VerlaSessionContextView;
import com.studyagent.service.domain.verla.VerlaConversation;
import com.studyagent.service.domain.verla.VerlaMessage;
import com.studyagent.service.domain.verla.VerlaSession;
import com.studyagent.service.domain.verla.VerlaTurn;
import com.studyagent.service.domain.verla.repo.VerlaConversationRepository;
import com.studyagent.service.domain.verla.repo.VerlaMessageRepository;
import com.studyagent.service.domain.verla.repo.VerlaSessionRepository;
import com.studyagent.service.domain.verla.repo.VerlaTurnRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Verla 内部接口的核心查询服务（PR-10）
 * <p>
 * 负责为 Py 暴露的 {@code GET /v1/internal/verla/sessions/{sid}/context} 提供一把拿全的上下文。
 * 详见 docs/verla-Java侧MVP技术方案.md §10.
 *
 * <h3>缓存策略</h3>
 * <ul>
 *     <li>三层本地 Caffeine 缓存：conv / turn / sess（MVP 不接 Redis，等 Day 6 后迁移）</li>
 *     <li>conv / turn 用 version 号做 key 后缀，写时 INCR 即天然失效</li>
 *     <li>sess 不带 version，依赖较短 TTL</li>
 * </ul>
 *
 * <h3>SingleFlight</h3>
 * 同 key 并发只允许 1 个 DB load，其它请求 {@code join()} 共享结果，避免缓存击穿。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VerlaContextQueryService {

    private final VerlaConversationRepository conversationRepository;
    private final VerlaTurnRepository turnRepository;
    private final VerlaSessionRepository sessionRepository;
    private final VerlaMessageRepository messageRepository;
    private final MeterRegistry meterRegistry;

    @Value("${verla.context-cache.conv-summary-ttl-seconds:60}")
    private long convSummaryTtlSeconds;

    @Value("${verla.context-cache.turn-meta-ttl-seconds:30}")
    private long turnMetaTtlSeconds;

    @Value("${verla.context-cache.sess-meta-ttl-seconds:10}")
    private long sessMetaTtlSeconds;

    @Value("${verla.context-cache.recent-message-limit:20}")
    private int recentMessageLimit;

    @Value("${verla.context-cache.max-entries-per-layer:5000}")
    private long maxEntriesPerLayer;

    private Cache<String, ConvSummary> convCache;
    private Cache<String, VerlaTurn> turnCache;
    private Cache<Long, VerlaSession> sessCache;

    private final ConcurrentMap<String, CompletableFuture<ConvSummary>> convInFlight = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CompletableFuture<VerlaTurn>> turnInFlight = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, CompletableFuture<VerlaSession>> sessInFlight = new ConcurrentHashMap<>();

    private Counter hitNone;
    private Counter hitSess;
    private Counter hitTurn;
    private Counter hitConv;

    @PostConstruct
    void init() {
        convCache = Caffeine.newBuilder()
                .maximumSize(maxEntriesPerLayer)
                .expireAfterWrite(Duration.ofSeconds(convSummaryTtlSeconds))
                .recordStats()
                .build();
        turnCache = Caffeine.newBuilder()
                .maximumSize(maxEntriesPerLayer)
                .expireAfterWrite(Duration.ofSeconds(turnMetaTtlSeconds))
                .recordStats()
                .build();
        sessCache = Caffeine.newBuilder()
                .maximumSize(maxEntriesPerLayer)
                .expireAfterWrite(Duration.ofSeconds(sessMetaTtlSeconds))
                .recordStats()
                .build();

        hitNone = meterRegistry.counter("verla.context.cache.hit.total", "layer", "none");
        hitSess = meterRegistry.counter("verla.context.cache.hit.total", "layer", "sess");
        hitTurn = meterRegistry.counter("verla.context.cache.hit.total", "layer", "turn");
        hitConv = meterRegistry.counter("verla.context.cache.hit.total", "layer", "conv");
        log.info("[Verla/ctx] caffeine init: convTTL={}s, turnTTL={}s, sessTTL={}s, maxEntries={}",
                convSummaryTtlSeconds, turnMetaTtlSeconds, sessMetaTtlSeconds, maxEntriesPerLayer);
    }

    /**
     * 一次性返回 session 启动所需的全部上下文。
     *
     * @param sessionId   必填
     * @param convVersion 可选；Py 带上时若与 DB 一致则可直接命中 conv 层缓存
     * @param turnVersion 可选；同上，命中 turn 层缓存
     */
    public VerlaSessionContextView getSessionContext(Long sessionId, Long convVersion, Long turnVersion) {
        if (sessionId == null) {
            throw new BusinessException(ApiCode.PARAM_ERROR, "sessionId");
        }
        VerlaSession session = loadSession(sessionId);
        if (session == null) {
            throw new BusinessException(ApiCode.TASK_NOT_FOUND, "session=" + sessionId);
        }
        VerlaTurn turn = loadTurn(session.getTurnId(), turnVersion);
        if (turn == null) {
            throw new BusinessException(ApiCode.TASK_NOT_FOUND, "turn=" + session.getTurnId());
        }
        ConvSummary convSummary = loadConvSummary(session.getConversationId(), convVersion);
        if (convSummary == null) {
            throw new BusinessException(ApiCode.TASK_NOT_FOUND, "conv=" + session.getConversationId());
        }

        List<VerlaSession> siblings = sessionRepository.findCompletedSiblings(turn.getId(), session.getId());

        String hitLayer = computeHitLayer(session, turn, convSummary, convVersion, turnVersion);
        return VerlaSessionContextView.builder()
                .conversation(convSummary.conversation)
                .turn(turn)
                .session(session)
                .upstreamSessions(siblings)
                .recentMessages(convSummary.recentMessages)
                .cacheHitLayer(hitLayer)
                .build();
    }

    // ====================================================================
    // session 层
    // ====================================================================
    private VerlaSession loadSession(Long sessionId) {
        VerlaSession cached = sessCache.getIfPresent(sessionId);
        if (cached != null) {
            return cached;
        }
        return singleFlight(sessInFlight, sessionId, () -> {
            VerlaSession s = sessionRepository.findById(sessionId);
            if (s != null) {
                sessCache.put(sessionId, s);
            }
            return s;
        });
    }

    /** 写 session 后由调用方触发，立即让本地缓存失效。 */
    public void invalidateSession(Long sessionId) {
        if (sessionId != null) {
            sessCache.invalidate(sessionId);
        }
    }

    // ====================================================================
    // turn 层
    // ====================================================================
    private VerlaTurn loadTurn(Long turnId, Long turnVersion) {
        // turn 表暂未加 version 列，先用 turnId+lastProgressAt 拼 key 做版本近似
        // PR-12 落地 verla_turns.version 后切换为 turnId:v{ver}
        String key = turnId + ":hint:" + (turnVersion == null ? "0" : turnVersion);
        VerlaTurn cached = turnCache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        return singleFlight(turnInFlight, key, () -> {
            VerlaTurn t = turnRepository.findById(turnId);
            if (t != null) {
                turnCache.put(key, t);
            }
            return t;
        });
    }

    public void invalidateTurn(Long turnId) {
        if (turnId == null) {
            return;
        }
        turnCache.asMap().keySet().removeIf(k -> k.startsWith(turnId + ":"));
    }

    // ====================================================================
    // conv 层（含最近消息聚合）
    // ====================================================================
    private ConvSummary loadConvSummary(Long convId, Long convVersion) {
        VerlaConversation peek = conversationRepository.findById(convId);
        if (peek == null) {
            return null;
        }
        Long realVer = peek.getVersion() == null ? 0L : peek.getVersion();
        long usedVer = (convVersion == null) ? realVer : convVersion;

        String key = convId + ":v" + usedVer;
        ConvSummary cached = convCache.getIfPresent(key);
        if (cached != null && Objects.equals(cached.conversation.getVersion(), realVer)) {
            return cached;
        }

        return singleFlight(convInFlight, key, () -> {
            VerlaConversation conv = conversationRepository.findById(convId);
            if (conv == null) {
                return null;
            }
            List<VerlaMessage> recent = messageRepository.findByCursor(convId, null, recentMessageLimit);
            ConvSummary s = new ConvSummary(conv, recent);
            convCache.put(convId + ":v" + (conv.getVersion() == null ? 0L : conv.getVersion()), s);
            return s;
        });
    }

    public void invalidateConv(Long convId) {
        if (convId == null) {
            return;
        }
        convCache.asMap().keySet().removeIf(k -> k.startsWith(convId + ":"));
    }

    // ====================================================================
    // SingleFlight: 同 key 并发合并为 1 次 DB load
    // ====================================================================
    private <K, V> V singleFlight(ConcurrentMap<K, CompletableFuture<V>> inflight, K key,
                                  java.util.function.Supplier<V> loader) {
        CompletableFuture<V> mine = new CompletableFuture<>();
        CompletableFuture<V> existed = inflight.putIfAbsent(key, mine);
        if (existed != null) {
            return existed.join();
        }
        try {
            V value = loader.get();
            mine.complete(value);
            return value;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inflight.remove(key, mine);
        }
    }

    // ====================================================================
    // helpers
    // ====================================================================
    private String computeHitLayer(VerlaSession s, VerlaTurn t, ConvSummary c,
                                   Long requestConvVersion, Long requestTurnVersion) {
        // 简化策略：以"是否走过 cache + 版本是否匹配"近似命中状态
        boolean convHit = requestConvVersion != null
                && c != null
                && c.conversation.getVersion() != null
                && requestConvVersion.equals(c.conversation.getVersion());
        boolean turnHit = requestTurnVersion != null;
        boolean sessHit = sessCache.getIfPresent(s.getId()) != null;

        if (convHit) {
            hitConv.increment();
            return "conv";
        }
        if (turnHit) {
            hitTurn.increment();
            return "turn";
        }
        if (sessHit) {
            hitSess.increment();
            return "sess";
        }
        hitNone.increment();
        return "none";
    }

    /** 内部聚合容器：conversation 头 + 最近 N 条消息（命中后整体复用） */
    private static final class ConvSummary {
        final VerlaConversation conversation;
        final List<VerlaMessage> recentMessages;

        ConvSummary(VerlaConversation conversation, List<VerlaMessage> recentMessages) {
            this.conversation = conversation;
            this.recentMessages = recentMessages;
        }
    }
}
