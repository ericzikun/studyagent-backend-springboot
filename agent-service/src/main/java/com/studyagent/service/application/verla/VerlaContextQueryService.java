package com.studyagent.service.application.verla;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.studyagent.common.api.ApiCode;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.service.application.verla.cache.ConversationSummaryCacheValue;
import com.studyagent.service.application.verla.cache.ConversationMessagesPageCacheValue;
import com.studyagent.service.application.verla.cache.SessionMetaCacheValue;
import com.studyagent.service.application.verla.cache.TurnMetaCacheValue;
import com.studyagent.service.application.verla.cache.VerlaCacheKeyFactory;
import com.studyagent.service.application.verla.cache.VerlaRedisContextCache;
import com.studyagent.service.application.verla.dto.VerlaConversationContextView;
import com.studyagent.service.application.verla.dto.VerlaSessionContextQueryOptions;
import com.studyagent.service.application.verla.dto.VerlaSessionContextView;
import com.studyagent.service.config.VerlaContextCacheProperties;
import com.studyagent.service.domain.verla.VerlaArtifact;
import com.studyagent.service.domain.verla.VerlaConversation;
import com.studyagent.service.domain.verla.VerlaMessage;
import com.studyagent.service.domain.verla.VerlaSession;
import com.studyagent.service.domain.verla.VerlaToolCall;
import com.studyagent.service.domain.verla.VerlaTurn;
import com.studyagent.service.domain.verla.repo.VerlaArtifactRepository;
import com.studyagent.service.domain.verla.repo.VerlaConversationRepository;
import com.studyagent.service.domain.verla.repo.VerlaMessageRepository;
import com.studyagent.service.domain.verla.repo.VerlaSessionRepository;
import com.studyagent.service.domain.verla.repo.VerlaToolCallRepository;
import com.studyagent.service.domain.verla.repo.VerlaTurnRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Verla 内部接口的核心查询服务（PR-10）
 * <p>
 * 负责为 Py 暴露的 {@code GET /v1/internal/verla/sessions/{sid}/context}
 * 与 {@code GET /v1/internal/verla/conversations/{cid}/context}。
 * 详见 docs/verla-Java侧MVP技术方案.md §10.
 *
 * <h3>缓存策略</h3>
 * <ul>
 *     <li>三层本地 Caffeine 缓存：conv / turn / sess（MVP 不接 Redis，等 Day 6 后迁移）</li>
 *     <li>conv / turn 用 version 号做 key 后缀，写时 INCR 即天然失效</li>
 *     <li>conv 层缓存 key 含 {@code messageLimit}，避免不同 limit 复用错误的消息切片</li>
 *     <li>sess 不带 version，依赖较短 TTL</li>
 *     <li>V2：artifacts / toolCalls 不做专用缓存（体量可控且变更频率中等）</li>
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
    private final VerlaArtifactRepository artifactRepository;
    private final VerlaToolCallRepository toolCallRepository;
    private final MeterRegistry meterRegistry;
    private final VerlaContextCacheProperties cacheProperties;
    private final Optional<VerlaRedisContextCache> redisContextCache;

    private Cache<String, ConvSummary> convCache;
    private Cache<Long, VerlaTurn> turnCache;
    private Cache<Long, VerlaSession> sessCache;

    private final ConcurrentMap<String, CompletableFuture<LoadedConvSummary>> convInFlight = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, CompletableFuture<VerlaTurn>> turnInFlight = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, CompletableFuture<VerlaSession>> sessInFlight = new ConcurrentHashMap<>();

    private Counter hitL1;
    private Counter hitRedis;
    private Counter hitDb;
    private Counter cacheError;
    private Counter requestTotal;
    private Counter dbFallback;
    private Counter redisReadError;
    private Counter redisWriteError;
    private Counter lockContention;
    private Counter redisCircuitSkip;
    private Timer lockWaitTimer;
    private final AtomicInteger consecutiveRedisFailures = new AtomicInteger();
    private volatile long redisCircuitOpenUntilMillis;

    @PostConstruct
    void init() {
        Duration convSummaryTtl = cacheProperties.getConvSummaryTtl();
        Duration turnMetaTtl = cacheProperties.getTurnMetaTtl();
        Duration sessMetaTtl = cacheProperties.getSessMetaTtl();
        long maxEntriesPerLayer = cacheProperties.getMaxEntriesPerLayer();
        convCache = Caffeine.newBuilder()
                .maximumSize(maxEntriesPerLayer)
                .expireAfterWrite(convSummaryTtl)
                .recordStats()
                .build();
        turnCache = Caffeine.newBuilder()
                .maximumSize(maxEntriesPerLayer)
                .expireAfterWrite(turnMetaTtl)
                .recordStats()
                .build();
        sessCache = Caffeine.newBuilder()
                .maximumSize(maxEntriesPerLayer)
                .expireAfterWrite(sessMetaTtl)
                .recordStats()
                .build();

        hitL1 = meterRegistry.counter("verla.context.cache.hit.total", "layer", "l1");
        hitRedis = meterRegistry.counter("verla.context.cache.hit.total", "layer", "redis");
        hitDb = meterRegistry.counter("verla.context.cache.hit.total", "layer", "db");
        cacheError = meterRegistry.counter("verla.cache.error.total");
        requestTotal = meterRegistry.counter("verla.context.cache.request.total");
        dbFallback = meterRegistry.counter("verla.context.cache.db.fallback.total");
        redisReadError = meterRegistry.counter("verla.cache.redis.read.error.total");
        redisWriteError = meterRegistry.counter("verla.cache.redis.write.error.total");
        lockContention = meterRegistry.counter("verla.cache.lock.contention.total");
        redisCircuitSkip = meterRegistry.counter("verla.cache.redis.circuit.skip.total");
        lockWaitTimer = meterRegistry.timer("verla.cache.lock.wait");
        log.info("[Verla/ctx] caffeine init: convTTL={}s, turnTTL={}s, sessTTL={}s, maxEntries={}",
                convSummaryTtl.toSeconds(), turnMetaTtl.toSeconds(),
                sessMetaTtl.toSeconds(), maxEntriesPerLayer);
    }

    /**
     * 一次性返回 session 启动所需的全部上下文。
     *
     * @param sessionId   必填
     * @param convVersion 可选；Py 带上时若与 DB 一致则可直接命中 conv 层缓存
     * @param turnVersion 可选；同上，命中 turn 层缓存
     */
    public VerlaSessionContextView getSessionContext(Long sessionId, Long convVersion, Long turnVersion) {
        return getSessionContext(sessionId, convVersion, turnVersion, VerlaSessionContextQueryOptions.defaults());
    }

    /**
     * V2：带 trace / summaries / artifacts / 可调消息与 trace 条数上限。
     */
    public VerlaSessionContextView getSessionContext(Long sessionId, Long convVersion, Long turnVersion,
                                                     VerlaSessionContextQueryOptions options) {
        requestTotal.increment();
        if (sessionId == null) {
            throw new BusinessException(ApiCode.PARAM_ERROR, "sessionId");
        }
        VerlaSessionContextQueryOptions opts = options == null
                ? VerlaSessionContextQueryOptions.defaults() : options;

        VerlaSession session = loadSession(sessionId);
        if (session == null) {
            throw new BusinessException(ApiCode.TASK_NOT_FOUND, "session=" + sessionId);
        }
        VerlaTurn turn = loadTurn(session.getTurnId(), turnVersion);
        if (turn == null) {
            throw new BusinessException(ApiCode.TASK_NOT_FOUND, "turn=" + session.getTurnId());
        }

        int msgLimit = clampMessageLimit(opts.getMessageLimit());
        LoadedConvSummary convLoad = loadConvSummary(session.getConversationId(), convVersion, msgLimit);
        if (convLoad == null || convLoad.summary() == null) {
            throw new BusinessException(ApiCode.TASK_NOT_FOUND, "conv=" + session.getConversationId());
        }
        ConvSummary convSummary = convLoad.summary();

        List<VerlaSession> siblings = sessionRepository.findCompletedSiblings(turn.getId(), session.getId());

        int trLimit = clampTraceLimit(opts.getTraceLimit());

        List<VerlaArtifact> artifacts = List.of();
        if (opts.isIncludeArtifacts()) {
            artifacts = artifactRepository.findByConversation(session.getConversationId());
            if (artifacts.size() > cacheProperties.getArtifactLimitDefault()) {
                artifacts = artifacts.subList(0, cacheProperties.getArtifactLimitDefault());
            }
        }

        List<VerlaToolCall> recentToolCalls = List.of();
        if (opts.isIncludeTrace()) {
            recentToolCalls = toolCallRepository.listBySession(sessionId, trLimit);
        }

        List<VerlaSessionContextView.ToolCallSummaryView> summaries = List.of();
        if (opts.isIncludeToolSummaries()) {
            summaries = toolCallRepository.listVisibleByConversation(session.getConversationId(), trLimit)
                    .stream()
                    .map(VerlaContextQueryService::toSummaryView)
                    .toList();
        }

        String hitLayer = recordHitLayer(convLoad.layer());
        logCacheOutcome("sessionContext", sessionId, convSummary.conversation.getVersion(), hitLayer);
        return VerlaSessionContextView.builder()
                .conversation(convSummary.conversation)
                .turn(turn)
                .session(session)
                .upstreamSessions(siblings)
                .recentMessages(convSummary.recentMessages)
                .artifacts(artifacts)
                .toolSummaries(summaries)
                .recentToolCalls(recentToolCalls)
                .traceIncluded(opts.isIncludeTrace())
                .cacheHitLayer(hitLayer)
                .build();
    }

    /**
     * Conversation 级 hydrate：消息历史、最新 turn、artifacts、可选 tool trace/summaries。
     * <p>
     * 消息按 id desc；{@code beforeMessageId == null} 时首屏可走 conv 摘要缓存。
     * 返回 {@link VerlaConversationContextView#getNextCursor()} 供 Py 分页直至拿满全量 history。
     */
    public VerlaConversationContextView getConversationContext(Long conversationId,
                                                             Long convVersion,
                                                             Long beforeMessageId,
                                                             VerlaSessionContextQueryOptions options) {
        requestTotal.increment();
        if (conversationId == null) {
            throw new BusinessException(ApiCode.PARAM_ERROR, "conversationId");
        }
        VerlaSessionContextQueryOptions opts =
                options == null ? VerlaSessionContextQueryOptions.defaults() : options;

        int msgLimit = clampConversationMessageLimit(opts.getMessageLimit());
        List<VerlaMessage> messages;
        VerlaConversation convForResponse;
        String hitLayer;

        if (beforeMessageId == null) {
            LoadedConvSummary convLoad = loadConvSummary(conversationId, convVersion, msgLimit);
            if (convLoad == null || convLoad.summary() == null) {
                throw new BusinessException(ApiCode.TASK_NOT_FOUND, "conversation=" + conversationId);
            }
            ConvSummary cs = convLoad.summary();
            convForResponse = cs.conversation;
            messages = cs.recentMessages;
            hitLayer = recordHitLayer(convLoad.layer());
        } else {
            VerlaConversation convHead = loadConversationEntity(conversationId);
            if (convHead == null) {
                throw new BusinessException(ApiCode.TASK_NOT_FOUND, "conversation=" + conversationId);
            }
            convForResponse = convHead;
            LoadedMessagesPage pageLoad = loadConversationMessagesPage(conversationId, convVersion, beforeMessageId, msgLimit);
            messages = pageLoad.messages();
            hitLayer = recordHitLayer(pageLoad.layer());
        }

        Long nextCursor = null;
        if (messages != null && messages.size() >= msgLimit) {
            nextCursor = messages.get(messages.size() - 1).getId();
        }

        List<VerlaTurn> latestTurns = turnRepository.findRecentByConversation(conversationId, 1);
        VerlaTurn latestTurn = latestTurns.isEmpty() ? null : latestTurns.get(0);

        int trLimit = clampTraceLimit(opts.getTraceLimit());

        List<VerlaArtifact> artifacts = List.of();
        if (opts.isIncludeArtifacts()) {
            artifacts = artifactRepository.findByConversation(conversationId);
            if (artifacts.size() > cacheProperties.getArtifactLimitDefault()) {
                artifacts = artifacts.subList(0, cacheProperties.getArtifactLimitDefault());
            }
        }

        List<VerlaToolCall> recentToolCalls = List.of();
        if (opts.isIncludeTrace()) {
            recentToolCalls = toolCallRepository.listVisibleByConversation(conversationId, trLimit);
        }

        List<VerlaSessionContextView.ToolCallSummaryView> summaries = List.of();
        if (opts.isIncludeToolSummaries()) {
            summaries = toolCallRepository.listVisibleByConversation(conversationId, trLimit).stream()
                    .map(VerlaContextQueryService::toSummaryView)
                    .toList();
        }

        logCacheOutcome("conversationContext", conversationId, convForResponse.getVersion(), hitLayer);
        return VerlaConversationContextView.builder()
                .conversation(convForResponse)
                .latestTurn(latestTurn)
                .recentMessages(messages == null ? List.of() : messages)
                .nextCursor(nextCursor)
                .artifacts(artifacts)
                .toolSummaries(summaries)
                .recentToolCalls(recentToolCalls)
                .traceIncluded(opts.isIncludeTrace())
                .cacheHitLayer(hitLayer)
                .build();
    }

    /** Conversation context：单页消息上限允许更大（与 {@link VerlaMessageRepositoryImpl#findByCursor} 上限对齐）。 */
    private int clampConversationMessageLimit(Integer req) {
        int lim = req == null ? cacheProperties.getRecentMessageLimit() : req;
        return Math.max(1, Math.min(lim, 200));
    }

    private int clampMessageLimit(Integer req) {
        int lim = req == null ? cacheProperties.getRecentMessageLimit() : req;
        return Math.max(1, Math.min(lim, 100));
    }

    private int clampTraceLimit(Integer req) {
        int lim = req == null ? cacheProperties.getTraceLimitDefault() : req;
        return Math.max(1, Math.min(lim, 200));
    }

    private static VerlaSessionContextView.ToolCallSummaryView toSummaryView(VerlaToolCall c) {
        return VerlaSessionContextView.ToolCallSummaryView.builder()
                .toolCallId(c.getToolCallId())
                .agentName(c.getAgentName())
                .toolName(c.getToolName())
                .summary(c.getSummary())
                .status(c.getStatus())
                .visibility(c.getVisibility())
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
        if (cacheProperties.isRedisEnabled() && isNegativeCached(redisKeyFactory().sessNegativeKey(sessionId))) {
            return null;
        }
        if (cacheProperties.isRedisEnabled()) {
            String redisKey = redisKeyFactory().sessMetaKey(sessionId);
            Optional<VerlaSession> redisHit = safeRedisRead("getSessionMeta", redisKey, () ->
                    redisContextCache.flatMap(cache ->
                            cache.getSessionMeta(redisKey)
                                    .map(envelope -> envelope.getData().session())));
            if (redisHit.isPresent()) {
                sessCache.put(sessionId, redisHit.get());
                return redisHit.get();
            }
        }
        return singleFlight(sessInFlight, sessionId, () -> {
            VerlaSession s = sessionRepository.findById(sessionId);
            String negativeKey = redisKeyFactory().sessNegativeKey(sessionId);
            if (s == null) {
                writeNegativeCache(negativeKey);
                return null;
            }
            clearNegativeCache(negativeKey);
            sessCache.put(sessionId, s);
            String redisKey = redisKeyFactory().sessMetaKey(sessionId);
            safeRedisWrite("putSessionMeta", redisKey, () ->
                    redisContextCache.ifPresent(cache ->
                            cache.putSessionMeta(redisKey, sessionId, new SessionMetaCacheValue(s))));
            return s;
        });
    }

    /** 写 session 后由调用方触发，立即让本地缓存失效。 */
    public void invalidateSession(Long sessionId) {
        if (sessionId != null) {
            sessCache.invalidate(sessionId);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSessionCacheSync(VerlaSessionCacheSyncEvent event) {
        refreshSessionCache(event.sessionId());
    }

    // ====================================================================
    // turn 层
    // ====================================================================
    private VerlaTurn loadTurn(Long turnId, Long turnVersion) {
        VerlaTurn cached = turnCache.getIfPresent(turnId);
        if (cached != null) {
            return cached;
        }
        if (cacheProperties.isRedisEnabled() && isNegativeCached(redisKeyFactory().turnNegativeKey(turnId))) {
            return null;
        }
        if (cacheProperties.isRedisEnabled()) {
            String redisKey = redisKeyFactory().turnMetaKey(turnId);
            Optional<VerlaTurn> redisHit = safeRedisRead("getTurnMeta", redisKey, () ->
                    redisContextCache.flatMap(cache ->
                            cache.getTurnMeta(redisKey)
                                    .map(envelope -> envelope.getData().turn())));
            if (redisHit.isPresent()) {
                turnCache.put(turnId, redisHit.get());
                return redisHit.get();
            }
        }
        return singleFlight(turnInFlight, turnId, () -> {
            VerlaTurn t = turnRepository.findById(turnId);
            String negativeKey = redisKeyFactory().turnNegativeKey(turnId);
            if (t == null) {
                writeNegativeCache(negativeKey);
                return null;
            }
            clearNegativeCache(negativeKey);
            turnCache.put(turnId, t);
            String redisKey = redisKeyFactory().turnMetaKey(turnId);
            safeRedisWrite("putTurnMeta", redisKey, () ->
                    redisContextCache.ifPresent(cache ->
                            cache.putTurnMeta(redisKey, turnId, new TurnMetaCacheValue(t))));
            return t;
        });
    }

    public void invalidateTurn(Long turnId) {
        if (turnId == null) {
            return;
        }
        turnCache.invalidate(turnId);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onTurnCacheSync(VerlaTurnCacheSyncEvent event) {
        refreshTurnCache(event.turnId());
    }

    // ====================================================================
    // conv 层（含最近消息聚合）
    // ====================================================================
    private LoadedConvSummary loadConvSummary(Long convId, Long convVersion, int messageLimit) {
        if (cacheProperties.isRedisEnabled() && isNegativeCached(redisKeyFactory().convNegativeKey(convId))) {
            return null;
        }
        if (convVersion != null) {
            String key = convSummaryLocalKey(convId, convVersion, messageLimit);
            ConvSummary cached = convCache.getIfPresent(key);
            if (cached != null) {
                return new LoadedConvSummary(cached, "l1");
            }

            Optional<LoadedConvSummary> redisHit = loadConvSummaryFromRedis(convId, convVersion, messageLimit, key);
            if (redisHit.isPresent()) {
                return redisHit.get();
            }

            return singleFlight(convInFlight, key, () ->
                    loadConvSummaryWithRedisLock(convId, convVersion, messageLimit, key));
        }

        if (cacheProperties.isRedisEnabled()) {
            String latestVersionKey = redisKeyFactory().convLatestVersionKey(convId);
            Optional<Long> latestVersion = safeRedisRead("getConversationLatestVersion", latestVersionKey, () ->
                    redisContextCache.flatMap(cache ->
                            cache.getConversationLatestVersion(latestVersionKey)));
            if (latestVersion.isPresent()) {
                String key = convSummaryLocalKey(convId, latestVersion.get(), messageLimit);
                ConvSummary cached = convCache.getIfPresent(key);
                if (cached != null) {
                    return new LoadedConvSummary(cached, "l1");
                }
                Optional<LoadedConvSummary> redisHit = loadConvSummaryFromRedis(convId, latestVersion.get(), messageLimit, key);
                if (redisHit.isPresent()) {
                    return redisHit.get();
                }
            }
        }

        VerlaConversation peek = loadConversationEntity(convId);
        if (peek == null) {
            return null;
        }
        Long realVer = peek.getVersion() == null ? 0L : peek.getVersion();
        long usedVer = (convVersion == null) ? realVer : convVersion;

        String key = convSummaryLocalKey(convId, usedVer, messageLimit);
        ConvSummary cached = convCache.getIfPresent(key);
        if (cached != null && Objects.equals(cached.conversation.getVersion(), realVer)) {
            return new LoadedConvSummary(cached, "l1");
        }

        return singleFlight(convInFlight, key, () ->
                loadConvSummaryWithRedisLock(convId, usedVer, messageLimit, key));
    }

    public void invalidateConv(Long convId) {
        if (convId == null) {
            return;
        }
        convCache.asMap().keySet().removeIf(k -> k.startsWith(convId + ":"));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onConversationCacheSync(VerlaConversationCacheSyncEvent event) {
        refreshConversationCache(event.conversationId());
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

    void refreshSessionCache(Long sessionId) {
        if (sessionId == null) {
            return;
        }
        VerlaSession session = sessionRepository.findById(sessionId);
        if (session == null) {
            invalidateSession(sessionId);
            String redisKey = redisKeyFactory().sessMetaKey(sessionId);
            safeRedisWrite("deleteSessionMeta", redisKey, () ->
                    redisContextCache.ifPresent(cache -> cache.delete(redisKey)));
            writeNegativeCache(redisKeyFactory().sessNegativeKey(sessionId));
            publishInvalidation("session", sessionId);
            return;
        }
        clearNegativeCache(redisKeyFactory().sessNegativeKey(sessionId));
        sessCache.put(sessionId, session);
        String redisKey = redisKeyFactory().sessMetaKey(sessionId);
        safeRedisWrite("putSessionMeta", redisKey, () ->
                redisContextCache.ifPresent(cache ->
                        cache.putSessionMeta(redisKey, sessionId, new SessionMetaCacheValue(session))));
        publishInvalidation("session", sessionId);
    }

    void refreshTurnCache(Long turnId) {
        if (turnId == null) {
            return;
        }
        VerlaTurn turn = turnRepository.findById(turnId);
        if (turn == null) {
            invalidateTurn(turnId);
            String redisKey = redisKeyFactory().turnMetaKey(turnId);
            safeRedisWrite("deleteTurnMeta", redisKey, () ->
                    redisContextCache.ifPresent(cache -> cache.delete(redisKey)));
            writeNegativeCache(redisKeyFactory().turnNegativeKey(turnId));
            publishInvalidation("turn", turnId);
            return;
        }
        clearNegativeCache(redisKeyFactory().turnNegativeKey(turnId));
        turnCache.put(turnId, turn);
        String redisKey = redisKeyFactory().turnMetaKey(turnId);
        safeRedisWrite("putTurnMeta", redisKey, () ->
                redisContextCache.ifPresent(cache ->
                        cache.putTurnMeta(redisKey, turnId, new TurnMetaCacheValue(turn))));
        publishInvalidation("turn", turnId);
    }

    void refreshConversationCache(Long convId) {
        if (convId == null) {
            return;
        }
        invalidateConv(convId);
        if (!cacheProperties.isRedisEnabled()) {
            return;
        }
        VerlaConversation conversation = conversationRepository.findById(convId);
        String latestVersionKey = redisKeyFactory().convLatestVersionKey(convId);
        if (conversation == null || conversation.getVersion() == null) {
            safeRedisWrite("deleteConversationLatestVersion", latestVersionKey, () ->
                    redisContextCache.ifPresent(cache -> cache.delete(latestVersionKey)));
            writeNegativeCache(redisKeyFactory().convNegativeKey(convId));
            publishInvalidation("conversation", convId);
            return;
        }
        clearNegativeCache(redisKeyFactory().convNegativeKey(convId));
        safeRedisWrite("putConversationLatestVersion", latestVersionKey, () ->
                redisContextCache.ifPresent(cache ->
                        cache.putConversationLatestVersion(latestVersionKey, conversation.getVersion())));
        publishInvalidation("conversation", convId);
    }

    public void handleRemoteInvalidation(VerlaCacheInvalidationMessage invalidation) {
        if (invalidation == null || invalidation.id() == null || invalidation.type() == null) {
            return;
        }
        switch (invalidation.type()) {
            case "session" -> invalidateSession(invalidation.id());
            case "turn" -> invalidateTurn(invalidation.id());
            case "conversation" -> invalidateConv(invalidation.id());
            default -> log.debug("[Verla/ctx] ignore unknown invalidation type={}", invalidation.type());
        }
    }

    // ====================================================================
    // helpers
    // ====================================================================
    private String recordHitLayer(String layer) {
        if ("l1".equals(layer)) {
            hitL1.increment();
            return "l1";
        }
        if ("redis".equals(layer)) {
            hitRedis.increment();
            return "redis";
        }
        hitDb.increment();
        dbFallback.increment();
        return "db";
    }

    private Optional<LoadedConvSummary> loadConvSummaryFromRedis(Long convId,
                                                                 Long convVersion,
                                                                 int messageLimit,
                                                                 String localKey) {
        if (!cacheProperties.isRedisEnabled()) {
            return Optional.empty();
        }
        String redisKey = redisKeyFactory().convSummaryKey(convId, convVersion, messageLimit);
        return safeRedisRead("getConversationSummary", redisKey, () ->
                redisContextCache.flatMap(cache -> cache.getConversationSummary(redisKey)))
                .map(envelope -> {
                    ConvSummary summary = new ConvSummary(
                            envelope.getData().conversation(),
                            envelope.getData().recentMessages());
                    convCache.put(localKey, summary);
                    return new LoadedConvSummary(summary, "redis");
                });
    }

    private LoadedConvSummary loadConvSummaryWithRedisLock(Long convId,
                                                           Long convVersion,
                                                           int messageLimit,
                                                           String localKey) {
        String redisKey = redisKeyFactory().convSummaryKey(convId, convVersion, messageLimit);
        String lockKey = redisKeyFactory().convSummaryLockKey(convId, convVersion, messageLimit);

        if (isRedisCircuitOpen()) {
            return loadConvSummaryFromDb(convId, convVersion, messageLimit, localKey);
        }

        if (cacheProperties.isRedisEnabled()) {
            Optional<VerlaRedisContextCache> cacheOpt = redisContextCache;
            if (cacheOpt.isPresent()) {
                String token = UUID.randomUUID().toString();
                boolean locked = safeRedisTryLock(lockKey, token);
                if (!locked) {
                    lockContention.increment();
                    Optional<LoadedConvSummary> retryHit = retryConversationSummaryAfterLockLoss(
                            convId, convVersion, messageLimit, localKey);
                    if (retryHit.isPresent()) {
                        return retryHit.get();
                    }
                    return loadConvSummaryFromDb(convId, convVersion, messageLimit, localKey);
                }
            }
        }
        return loadConvSummaryFromDb(convId, convVersion, messageLimit, localKey);
    }

    private LoadedConvSummary loadConvSummaryFromDb(Long convId,
                                                    Long convVersion,
                                                    int messageLimit,
                                                    String localKey) {
        VerlaConversation conv = loadConversationEntity(convId);
        if (conv == null) {
            return null;
        }
        List<VerlaMessage> recent = messageRepository.findByCursor(convId, null, messageLimit);
        ConvSummary summary = new ConvSummary(conv, recent);
        Long latestVersion = conv.getVersion() == null ? 0L : conv.getVersion();
        convCache.put(localKey == null ? convSummaryLocalKey(convId, latestVersion, messageLimit) : localKey, summary);
        if (cacheProperties.isRedisEnabled()) {
            String latestVersionKey = redisKeyFactory().convLatestVersionKey(convId);
            safeRedisWrite("putConversationLatestVersion", latestVersionKey, () ->
                    redisContextCache.ifPresent(cache ->
                            cache.putConversationLatestVersion(latestVersionKey, latestVersion)));
            String summaryKey = redisKeyFactory().convSummaryKey(convId, latestVersion, messageLimit);
            safeRedisWrite("putConversationSummary", summaryKey, () ->
                    redisContextCache.ifPresent(cache -> cache.putConversationSummary(
                            summaryKey,
                            latestVersion,
                            new ConversationSummaryCacheValue(conv, recent),
                            cacheProperties.getConvSummaryTtl())));
        }
        return new LoadedConvSummary(summary, "db");
    }

    private VerlaConversation loadConversationEntity(Long convId) {
        String negativeKey = redisKeyFactory().convNegativeKey(convId);
        if (cacheProperties.isRedisEnabled() && isNegativeCached(negativeKey)) {
            return null;
        }
        VerlaConversation conversation = conversationRepository.findById(convId);
        if (conversation == null) {
            writeNegativeCache(negativeKey);
            return null;
        }
        clearNegativeCache(negativeKey);
        return conversation;
    }

    private Optional<LoadedConvSummary> retryConversationSummaryAfterLockLoss(Long convId,
                                                                              Long convVersion,
                                                                              int messageLimit,
                                                                              String localKey) {
        long startedAtNanos = System.nanoTime();
        int attempts = Math.max(1, cacheProperties.getRedisLockRetryMaxAttempts());
        Duration delay = cacheProperties.getRedisLockRetryDelay();
        try {
            for (int i = 0; i < attempts; i++) {
                sleepQuietly(delay);
                Optional<LoadedConvSummary> redisHit = loadConvSummaryFromRedis(convId, convVersion, messageLimit, localKey);
                if (redisHit.isPresent()) {
                    return redisHit;
                }
            }
            return Optional.empty();
        } finally {
            lockWaitTimer.record(System.nanoTime() - startedAtNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }

    private void sleepQuietly(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            return;
        }
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private LoadedMessagesPage loadConversationMessagesPage(Long conversationId,
                                                            Long convVersion,
                                                            Long beforeMessageId,
                                                            int limit) {
        if (convVersion != null && cacheProperties.isRedisEnabled()) {
            String redisKey = redisKeyFactory().convMessagesKey(conversationId, convVersion, beforeMessageId, limit);
            Optional<List<VerlaMessage>> redisHit = safeRedisRead("getConversationMessagesPage", redisKey, () ->
                    redisContextCache.flatMap(cache -> cache.getConversationMessagesPage(redisKey))
                            .map(envelope -> envelope.getData().messages()));
            if (redisHit.isPresent()) {
                return new LoadedMessagesPage(redisHit.get(), "redis");
            }
            List<VerlaMessage> messages = messageRepository.findByCursor(conversationId, beforeMessageId, limit);
            safeRedisWrite("putConversationMessagesPage", redisKey, () ->
                    redisContextCache.ifPresent(cache -> cache.putConversationMessagesPage(
                            redisKey,
                            convVersion,
                            new ConversationMessagesPageCacheValue(messages),
                            beforeMessageId == null ? cacheProperties.getMessagesRecentTtl() : cacheProperties.getMessagesHistoryTtl())));
            return new LoadedMessagesPage(messages, "db");
        }
        return new LoadedMessagesPage(messageRepository.findByCursor(conversationId, beforeMessageId, limit), "db");
    }

    private <T> Optional<T> safeRedisRead(String operation, String key, Supplier<Optional<T>> action) {
        if (!cacheProperties.isRedisEnabled()) {
            return Optional.empty();
        }
        if (isRedisCircuitOpen()) {
            redisCircuitSkip.increment();
            return Optional.empty();
        }
        try {
            Optional<T> result = action.get();
            onRedisSuccess();
            return result;
        } catch (RuntimeException ex) {
            cacheError.increment();
            redisReadError.increment();
            onRedisFailure();
            log.warn("[Verla/ctx] redis read fallback: op={}, key={}, reason={}", operation, key, ex.toString());
            return Optional.empty();
        }
    }

    private boolean safeRedisTryLock(String key, String token) {
        if (!cacheProperties.isRedisEnabled()) {
            return false;
        }
        if (isRedisCircuitOpen()) {
            redisCircuitSkip.increment();
            return false;
        }
        try {
            boolean locked = redisContextCache.map(cache -> cache.tryLock(key, token)).orElse(false);
            onRedisSuccess();
            return locked;
        } catch (RuntimeException ex) {
            cacheError.increment();
            redisWriteError.increment();
            onRedisFailure();
            log.warn("[Verla/ctx] redis lock fallback: key={}, reason={}", key, ex.toString());
            return false;
        }
    }

    private void safeRedisWrite(String operation, String key, Runnable action) {
        if (!cacheProperties.isRedisEnabled()) {
            return;
        }
        if (isRedisCircuitOpen()) {
            redisCircuitSkip.increment();
            return;
        }
        try {
            action.run();
            onRedisSuccess();
        } catch (RuntimeException ex) {
            cacheError.increment();
            redisWriteError.increment();
            onRedisFailure();
            log.warn("[Verla/ctx] redis write skipped: op={}, key={}, reason={}", operation, key, ex.toString());
        }
    }

    private boolean isRedisCircuitOpen() {
        return System.currentTimeMillis() < redisCircuitOpenUntilMillis;
    }

    private void onRedisSuccess() {
        consecutiveRedisFailures.set(0);
    }

    private void onRedisFailure() {
        int failures = consecutiveRedisFailures.incrementAndGet();
        if (failures >= Math.max(1, cacheProperties.getRedisCircuitFailureThreshold())) {
            Duration openDuration = cacheProperties.getRedisCircuitOpenDuration();
            redisCircuitOpenUntilMillis = System.currentTimeMillis()
                    + (openDuration == null ? 0L : Math.max(1L, openDuration.toMillis()));
        }
    }

    private void logCacheOutcome(String scope, Long entityId, Long version, String hitLayer) {
        log.debug("[Verla/ctx] cache outcome: scope={}, id={}, version={}, hitLayer={}",
                scope, entityId, version, hitLayer);
    }

    private boolean isNegativeCached(String key) {
        return safeRedisRead("getNegativeCache", key, () ->
                redisContextCache.flatMap(cache -> cache.getRaw(key))).isPresent();
    }

    private void writeNegativeCache(String key) {
        safeRedisWrite("putNegativeCache", key, () ->
                redisContextCache.ifPresent(cache ->
                        cache.putRaw(key, "1", cacheProperties.getNegativeTtl())));
    }

    private void clearNegativeCache(String key) {
        safeRedisWrite("deleteNegativeCache", key, () ->
                redisContextCache.ifPresent(cache -> cache.delete(key)));
    }

    private void publishInvalidation(String type, Long id) {
        if (id == null || !cacheProperties.isRedisEnabled()) {
            return;
        }
        String channel = redisKeyFactory().invalidationChannel();
        String payload = "{\"type\":\"" + type + "\",\"id\":" + id + "}";
        safeRedisWrite("publishInvalidation", channel, () ->
                redisContextCache.ifPresent(cache -> cache.publish(channel, payload)));
    }

    private String convSummaryLocalKey(Long convId, Long convVersion, int messageLimit) {
        return convId + ":v" + convVersion + ":ml" + messageLimit;
    }

    private VerlaCacheKeyFactory redisKeyFactory() {
        return new VerlaCacheKeyFactory(cacheProperties);
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

    private record LoadedConvSummary(ConvSummary summary, String layer) {
    }

    private record LoadedMessagesPage(List<VerlaMessage> messages, String layer) {
    }
}
