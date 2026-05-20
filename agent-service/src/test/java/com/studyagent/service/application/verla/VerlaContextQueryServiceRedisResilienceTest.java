package com.studyagent.service.application.verla;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.studyagent.service.config.VerlaContextCacheProperties;
import com.studyagent.service.application.verla.cache.ConversationSummaryCacheValue;
import com.studyagent.service.application.verla.cache.VerlaCacheJsonCodec;
import com.studyagent.service.application.verla.cache.VerlaCacheKeyFactory;
import com.studyagent.service.application.verla.cache.VerlaRedisContextCache;
import com.studyagent.service.domain.verla.VerlaConversation;
import com.studyagent.service.domain.verla.VerlaMessage;
import com.studyagent.service.domain.verla.repo.VerlaArtifactRepository;
import com.studyagent.service.domain.verla.repo.VerlaConversationRepository;
import com.studyagent.service.domain.verla.repo.VerlaMessageRepository;
import com.studyagent.service.domain.verla.repo.VerlaSessionRepository;
import com.studyagent.service.domain.verla.repo.VerlaToolCallRepository;
import com.studyagent.service.domain.verla.repo.VerlaTurnRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

class VerlaContextQueryServiceRedisResilienceTest {

    @Test
    void singleFlight_shouldStillDeduplicateConcurrentConversationLoads() throws Exception {
        VerlaConversationRepository conversationRepository = mock(VerlaConversationRepository.class);
        VerlaTurnRepository turnRepository = mock(VerlaTurnRepository.class);
        VerlaSessionRepository sessionRepository = mock(VerlaSessionRepository.class);
        VerlaMessageRepository messageRepository = mock(VerlaMessageRepository.class);
        VerlaArtifactRepository artifactRepository = mock(VerlaArtifactRepository.class);
        VerlaToolCallRepository toolCallRepository = mock(VerlaToolCallRepository.class);

        VerlaContextCacheProperties properties = new VerlaContextCacheProperties();
        properties.setRedisEnabled(false);

        VerlaConversation conversation = VerlaConversation.builder()
                .id(101L)
                .userId("user_1")
                .title("题目讲解")
                .version(7L)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        VerlaMessage recentMessage = VerlaMessage.builder()
                .id(9001L)
                .conversationId(101L)
                .role("assistant")
                .textContent("这里是解释")
                .createdAt(LocalDateTime.now())
                .build();

        CountDownLatch loaderEntered = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);

        when(conversationRepository.findById(101L)).thenReturn(conversation);
        when(turnRepository.findRecentByConversation(101L, 1)).thenReturn(List.of());
        when(artifactRepository.findByConversation(101L)).thenReturn(List.of());
        when(toolCallRepository.listVisibleByConversation(101L, 50)).thenReturn(List.of());
        when(messageRepository.findByCursor(101L, null, 20)).thenAnswer(invocation -> {
            loaderEntered.countDown();
            if (!releaseLoader.await(3, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for second request");
            }
            return List.of(recentMessage);
        });

        VerlaContextQueryService service = new VerlaContextQueryService(
                conversationRepository,
                turnRepository,
                sessionRepository,
                messageRepository,
                artifactRepository,
                toolCallRepository,
                new SimpleMeterRegistry(),
                properties,
                Optional.empty());
        service.init();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> service.getConversationContext(101L, 7L, null, null));
            assertThat(loaderEntered.await(1, TimeUnit.SECONDS)).isTrue();
            Future<?> second = executor.submit(() -> service.getConversationContext(101L, 7L, null, null));
            releaseLoader.countDown();

            assertThat(first.get(3, TimeUnit.SECONDS)).isNotNull();
            assertThat(second.get(3, TimeUnit.SECONDS)).isNotNull();
        } finally {
            executor.shutdownNow();
        }

        verify(messageRepository, times(1)).findByCursor(101L, null, 20);
        verify(conversationRepository, times(1)).findById(101L);
    }

    @Test
    void conversationSummary_shouldUseRedisLockAroundHotLoad() {
        VerlaConversationRepository conversationRepository = mock(VerlaConversationRepository.class);
        VerlaTurnRepository turnRepository = mock(VerlaTurnRepository.class);
        VerlaSessionRepository sessionRepository = mock(VerlaSessionRepository.class);
        VerlaMessageRepository messageRepository = mock(VerlaMessageRepository.class);
        VerlaArtifactRepository artifactRepository = mock(VerlaArtifactRepository.class);
        VerlaToolCallRepository toolCallRepository = mock(VerlaToolCallRepository.class);
        VerlaRedisContextCache redisContextCache = mock(VerlaRedisContextCache.class);

        VerlaContextCacheProperties properties = new VerlaContextCacheProperties();
        properties.setRedisEnabled(true);
        VerlaCacheKeyFactory keyFactory = new VerlaCacheKeyFactory(properties);

        VerlaConversation conversation = VerlaConversation.builder()
                .id(101L)
                .userId("user_1")
                .title("题目讲解")
                .version(7L)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        VerlaMessage recentMessage = VerlaMessage.builder()
                .id(9001L)
                .conversationId(101L)
                .role("assistant")
                .textContent("这里是解释")
                .createdAt(LocalDateTime.now())
                .build();

        when(redisContextCache.getConversationSummary(keyFactory.convSummaryKey(101L, 7L, 20)))
                .thenReturn(Optional.empty());
        when(redisContextCache.tryLock(eq(keyFactory.convSummaryLockKey(101L, 7L, 20)), anyString()))
                .thenReturn(true);
        when(conversationRepository.findById(101L)).thenReturn(conversation);
        when(messageRepository.findByCursor(101L, null, 20)).thenReturn(List.of(recentMessage));
        when(turnRepository.findRecentByConversation(101L, 1)).thenReturn(List.of());
        when(artifactRepository.findByConversation(101L)).thenReturn(List.of());
        when(toolCallRepository.listVisibleByConversation(101L, 50)).thenReturn(List.of());

        VerlaContextQueryService service = new VerlaContextQueryService(
                conversationRepository,
                turnRepository,
                sessionRepository,
                messageRepository,
                artifactRepository,
                toolCallRepository,
                new SimpleMeterRegistry(),
                properties,
                Optional.of(redisContextCache));
        service.init();

        var view = service.getConversationContext(101L, 7L, null, null);

        assertThat(view.getConversation().getVersion()).isEqualTo(7L);
        verify(redisContextCache).tryLock(eq(keyFactory.convSummaryLockKey(101L, 7L, 20)), anyString());
        verify(conversationRepository).findById(101L);
        verify(messageRepository).findByCursor(101L, null, 20);
    }

    @Test
    void lockLoser_shouldShortWaitAndRetryRedisInsteadOfDirectDbLoad() {
        VerlaConversationRepository conversationRepository = mock(VerlaConversationRepository.class);
        VerlaTurnRepository turnRepository = mock(VerlaTurnRepository.class);
        VerlaSessionRepository sessionRepository = mock(VerlaSessionRepository.class);
        VerlaMessageRepository messageRepository = mock(VerlaMessageRepository.class);
        VerlaArtifactRepository artifactRepository = mock(VerlaArtifactRepository.class);
        VerlaToolCallRepository toolCallRepository = mock(VerlaToolCallRepository.class);
        VerlaRedisContextCache redisContextCache = mock(VerlaRedisContextCache.class);

        VerlaContextCacheProperties properties = new VerlaContextCacheProperties();
        properties.setRedisEnabled(true);
        VerlaCacheKeyFactory keyFactory = new VerlaCacheKeyFactory(properties);

        VerlaConversation conversation = VerlaConversation.builder()
                .id(101L)
                .userId("user_1")
                .title("题目讲解")
                .version(7L)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        VerlaMessage recentMessage = VerlaMessage.builder()
                .id(9001L)
                .conversationId(101L)
                .role("assistant")
                .textContent("这里是解释")
                .createdAt(LocalDateTime.now())
                .build();
        VerlaCacheJsonCodec.CacheEnvelope<ConversationSummaryCacheValue> envelope =
                VerlaCacheJsonCodec.CacheEnvelope.<ConversationSummaryCacheValue>builder()
                        .schemaVersion(1)
                        .version(7L)
                        .cachedAt(java.time.OffsetDateTime.now())
                        .data(new ConversationSummaryCacheValue(conversation, List.of(recentMessage)))
                        .build();

        when(redisContextCache.getConversationSummary(keyFactory.convSummaryKey(101L, 7L, 20)))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(envelope));
        when(redisContextCache.tryLock(eq(keyFactory.convSummaryLockKey(101L, 7L, 20)), anyString()))
                .thenReturn(false);
        when(turnRepository.findRecentByConversation(101L, 1)).thenReturn(List.of());
        when(artifactRepository.findByConversation(101L)).thenReturn(List.of());
        when(toolCallRepository.listVisibleByConversation(101L, 50)).thenReturn(List.of());

        VerlaContextQueryService service = new VerlaContextQueryService(
                conversationRepository,
                turnRepository,
                sessionRepository,
                messageRepository,
                artifactRepository,
                toolCallRepository,
                new SimpleMeterRegistry(),
                properties,
                Optional.of(redisContextCache));
        service.init();

        var view = service.getConversationContext(101L, 7L, null, null);

        assertThat(view.getConversation().getVersion()).isEqualTo(7L);
        verify(redisContextCache).tryLock(eq(keyFactory.convSummaryLockKey(101L, 7L, 20)), anyString());
        verify(conversationRepository, never()).findById(101L);
        verify(messageRepository, never()).findByCursor(101L, null, 20);
    }

    @Test
    void redisReadFailure_shouldFallbackToDbAndRecordError() {
        VerlaConversationRepository conversationRepository = mock(VerlaConversationRepository.class);
        VerlaTurnRepository turnRepository = mock(VerlaTurnRepository.class);
        VerlaSessionRepository sessionRepository = mock(VerlaSessionRepository.class);
        VerlaMessageRepository messageRepository = mock(VerlaMessageRepository.class);
        VerlaArtifactRepository artifactRepository = mock(VerlaArtifactRepository.class);
        VerlaToolCallRepository toolCallRepository = mock(VerlaToolCallRepository.class);
        VerlaRedisContextCache redisContextCache = mock(VerlaRedisContextCache.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        VerlaContextCacheProperties properties = new VerlaContextCacheProperties();
        properties.setRedisEnabled(true);
        VerlaCacheKeyFactory keyFactory = new VerlaCacheKeyFactory(properties);

        VerlaConversation conversation = VerlaConversation.builder()
                .id(101L)
                .userId("user_1")
                .title("题目讲解")
                .version(7L)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        VerlaMessage recentMessage = VerlaMessage.builder()
                .id(9001L)
                .conversationId(101L)
                .role("assistant")
                .textContent("这里是解释")
                .createdAt(LocalDateTime.now())
                .build();

        when(redisContextCache.getConversationSummary(keyFactory.convSummaryKey(101L, 7L, 20)))
                .thenThrow(new RuntimeException("redis down"));
        when(redisContextCache.tryLock(eq(keyFactory.convSummaryLockKey(101L, 7L, 20)), anyString()))
                .thenReturn(true);
        doThrow(new RuntimeException("redis write down")).when(redisContextCache).putConversationSummary(
                eq(keyFactory.convSummaryKey(101L, 7L, 20)),
                eq(7L),
                eq(new ConversationSummaryCacheValue(conversation, List.of(recentMessage))),
                eq(properties.getConvSummaryTtl()));
        when(conversationRepository.findById(101L)).thenReturn(conversation);
        when(messageRepository.findByCursor(101L, null, 20)).thenReturn(List.of(recentMessage));
        when(turnRepository.findRecentByConversation(101L, 1)).thenReturn(List.of());
        when(artifactRepository.findByConversation(101L)).thenReturn(List.of());
        when(toolCallRepository.listVisibleByConversation(101L, 50)).thenReturn(List.of());

        VerlaContextQueryService service = new VerlaContextQueryService(
                conversationRepository,
                turnRepository,
                sessionRepository,
                messageRepository,
                artifactRepository,
                toolCallRepository,
                meterRegistry,
                properties,
                Optional.of(redisContextCache));
        service.init();

        var view = service.getConversationContext(101L, 7L, null, null);

        assertThat(view.getConversation().getVersion()).isEqualTo(7L);
        assertThat(view.getCacheHitLayer()).isEqualTo("db");
        verify(conversationRepository).findById(101L);
        verify(messageRepository).findByCursor(101L, null, 20);
        assertThat(meterRegistry.get("verla.cache.error.total").counter().count()).isEqualTo(2.0d);
    }

    @Test
    void missingConversation_shouldWriteShortNegativeCache() {
        VerlaConversationRepository conversationRepository = mock(VerlaConversationRepository.class);
        VerlaTurnRepository turnRepository = mock(VerlaTurnRepository.class);
        VerlaSessionRepository sessionRepository = mock(VerlaSessionRepository.class);
        VerlaMessageRepository messageRepository = mock(VerlaMessageRepository.class);
        VerlaArtifactRepository artifactRepository = mock(VerlaArtifactRepository.class);
        VerlaToolCallRepository toolCallRepository = mock(VerlaToolCallRepository.class);
        VerlaRedisContextCache redisContextCache = mock(VerlaRedisContextCache.class);

        VerlaContextCacheProperties properties = new VerlaContextCacheProperties();
        properties.setRedisEnabled(true);
        VerlaCacheKeyFactory keyFactory = new VerlaCacheKeyFactory(properties);

        when(redisContextCache.getRaw(keyFactory.convNegativeKey(101L)))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of("1"));
        when(conversationRepository.findById(101L)).thenReturn(null);
        when(turnRepository.findRecentByConversation(101L, 1)).thenReturn(List.of());
        when(artifactRepository.findByConversation(101L)).thenReturn(List.of());
        when(toolCallRepository.listVisibleByConversation(101L, 50)).thenReturn(List.of());

        VerlaContextQueryService service = new VerlaContextQueryService(
                conversationRepository,
                turnRepository,
                sessionRepository,
                messageRepository,
                artifactRepository,
                toolCallRepository,
                new SimpleMeterRegistry(),
                properties,
                Optional.of(redisContextCache));
        service.init();

        assertThatThrownBy(() -> service.getConversationContext(101L, null, null, null))
                .isInstanceOf(com.studyagent.common.exception.BusinessException.class)
                .hasMessageContaining("Task not found");
        assertThatThrownBy(() -> service.getConversationContext(101L, null, null, null))
                .isInstanceOf(com.studyagent.common.exception.BusinessException.class)
                .hasMessageContaining("Task not found");

        verify(conversationRepository, times(1)).findById(101L);
        verify(redisContextCache).putRaw(keyFactory.convNegativeKey(101L), "1", properties.getNegativeTtl());
    }

    @Test
    void metrics_shouldExposeHitDbErrorAndLockCounters() {
        VerlaConversationRepository conversationRepository = mock(VerlaConversationRepository.class);
        VerlaTurnRepository turnRepository = mock(VerlaTurnRepository.class);
        VerlaSessionRepository sessionRepository = mock(VerlaSessionRepository.class);
        VerlaMessageRepository messageRepository = mock(VerlaMessageRepository.class);
        VerlaArtifactRepository artifactRepository = mock(VerlaArtifactRepository.class);
        VerlaToolCallRepository toolCallRepository = mock(VerlaToolCallRepository.class);
        VerlaRedisContextCache redisContextCache = mock(VerlaRedisContextCache.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        VerlaContextCacheProperties properties = new VerlaContextCacheProperties();
        properties.setRedisEnabled(true);
        properties.setRedisLockRetryDelay(java.time.Duration.ofMillis(5));
        VerlaCacheKeyFactory keyFactory = new VerlaCacheKeyFactory(properties);

        VerlaConversation redisConversation = VerlaConversation.builder()
                .id(101L)
                .userId("user_1")
                .title("题目讲解")
                .version(7L)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        VerlaMessage redisMessage = VerlaMessage.builder()
                .id(9001L)
                .conversationId(101L)
                .role("assistant")
                .textContent("redis hit")
                .createdAt(LocalDateTime.now())
                .build();
        VerlaCacheJsonCodec.CacheEnvelope<ConversationSummaryCacheValue> redisEnvelope =
                VerlaCacheJsonCodec.CacheEnvelope.<ConversationSummaryCacheValue>builder()
                        .schemaVersion(1)
                        .version(7L)
                        .cachedAt(java.time.OffsetDateTime.now())
                        .data(new ConversationSummaryCacheValue(redisConversation, List.of(redisMessage)))
                        .build();

        VerlaConversation dbConversation = VerlaConversation.builder()
                .id(202L)
                .userId("user_2")
                .title("错题讲解")
                .version(3L)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        VerlaMessage dbMessage = VerlaMessage.builder()
                .id(9002L)
                .conversationId(202L)
                .role("assistant")
                .textContent("db fallback")
                .createdAt(LocalDateTime.now())
                .build();

        when(redisContextCache.getConversationSummary(keyFactory.convSummaryKey(101L, 7L, 20)))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(redisEnvelope));
        when(redisContextCache.tryLock(eq(keyFactory.convSummaryLockKey(101L, 7L, 20)), anyString()))
                .thenReturn(false);

        when(redisContextCache.getConversationSummary(keyFactory.convSummaryKey(202L, 3L, 20)))
                .thenThrow(new RuntimeException("redis read down"));
        when(redisContextCache.tryLock(eq(keyFactory.convSummaryLockKey(202L, 3L, 20)), anyString()))
                .thenReturn(true);
        when(conversationRepository.findById(202L)).thenReturn(dbConversation);
        when(messageRepository.findByCursor(202L, null, 20)).thenReturn(List.of(dbMessage));

        when(turnRepository.findRecentByConversation(101L, 1)).thenReturn(List.of());
        when(turnRepository.findRecentByConversation(202L, 1)).thenReturn(List.of());
        when(artifactRepository.findByConversation(101L)).thenReturn(List.of());
        when(artifactRepository.findByConversation(202L)).thenReturn(List.of());
        when(toolCallRepository.listVisibleByConversation(101L, 50)).thenReturn(List.of());
        when(toolCallRepository.listVisibleByConversation(202L, 50)).thenReturn(List.of());

        VerlaContextQueryService service = new VerlaContextQueryService(
                conversationRepository,
                turnRepository,
                sessionRepository,
                messageRepository,
                artifactRepository,
                toolCallRepository,
                meterRegistry,
                properties,
                Optional.of(redisContextCache));
        service.init();

        var redisView = service.getConversationContext(101L, 7L, null, null);
        var dbView = service.getConversationContext(202L, 3L, null, null);

        assertThat(redisView.getCacheHitLayer()).isEqualTo("redis");
        assertThat(dbView.getCacheHitLayer()).isEqualTo("db");
        assertThat(meterRegistry.get("verla.context.cache.request.total").counter().count()).isEqualTo(2.0d);
        assertThat(meterRegistry.get("verla.context.cache.hit.total").tag("layer", "redis").counter().count()).isEqualTo(1.0d);
        assertThat(meterRegistry.get("verla.context.cache.hit.total").tag("layer", "db").counter().count()).isEqualTo(1.0d);
        assertThat(meterRegistry.get("verla.context.cache.db.fallback.total").counter().count()).isEqualTo(1.0d);
        assertThat(meterRegistry.get("verla.cache.redis.read.error.total").counter().count()).isEqualTo(1.0d);
        assertThat(meterRegistry.get("verla.cache.lock.contention.total").counter().count()).isEqualTo(1.0d);
        assertThat(meterRegistry.get("verla.cache.lock.wait").timer().count()).isEqualTo(1L);
    }

    @Test
    void cacheLogs_shouldNotContainMessageBody() {
        VerlaConversationRepository conversationRepository = mock(VerlaConversationRepository.class);
        VerlaTurnRepository turnRepository = mock(VerlaTurnRepository.class);
        VerlaSessionRepository sessionRepository = mock(VerlaSessionRepository.class);
        VerlaMessageRepository messageRepository = mock(VerlaMessageRepository.class);
        VerlaArtifactRepository artifactRepository = mock(VerlaArtifactRepository.class);
        VerlaToolCallRepository toolCallRepository = mock(VerlaToolCallRepository.class);
        VerlaRedisContextCache redisContextCache = mock(VerlaRedisContextCache.class);

        VerlaContextCacheProperties properties = new VerlaContextCacheProperties();
        properties.setRedisEnabled(true);
        VerlaCacheKeyFactory keyFactory = new VerlaCacheKeyFactory(properties);

        VerlaConversation conversation = VerlaConversation.builder()
                .id(101L)
                .userId("user_1")
                .title("题目讲解")
                .version(7L)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        VerlaMessage recentMessage = VerlaMessage.builder()
                .id(9001L)
                .conversationId(101L)
                .role("assistant")
                .textContent("VERY_SECRET_MESSAGE_BODY")
                .createdAt(LocalDateTime.now())
                .build();

        when(redisContextCache.getConversationSummary(keyFactory.convSummaryKey(101L, 7L, 20))).thenReturn(Optional.of(
                VerlaCacheJsonCodec.CacheEnvelope.<ConversationSummaryCacheValue>builder()
                        .schemaVersion(1)
                        .version(7L)
                        .cachedAt(java.time.OffsetDateTime.now())
                        .data(new ConversationSummaryCacheValue(conversation, List.of(recentMessage)))
                        .build()));
        when(turnRepository.findRecentByConversation(101L, 1)).thenReturn(List.of());
        when(artifactRepository.findByConversation(101L)).thenReturn(List.of());
        when(toolCallRepository.listVisibleByConversation(101L, 50)).thenReturn(List.of());

        Logger logger = (Logger) LoggerFactory.getLogger(VerlaContextQueryService.class);
        Level originalLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);

        try {
            VerlaContextQueryService service = new VerlaContextQueryService(
                    conversationRepository,
                    turnRepository,
                    sessionRepository,
                    messageRepository,
                    artifactRepository,
                    toolCallRepository,
                    new SimpleMeterRegistry(),
                    properties,
                    Optional.of(redisContextCache));
            service.init();

            var view = service.getConversationContext(101L, 7L, null, null);

            assertThat(view.getCacheHitLayer()).isEqualTo("redis");
            String logs = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + "\n" + right);
            assertThat(logs).contains("scope=conversationContext");
            assertThat(logs).contains("version=7");
            assertThat(logs).contains("hitLayer=redis");
            assertThat(logs).doesNotContain("VERY_SECRET_MESSAGE_BODY");
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(originalLevel);
        }
    }

    @Test
    void redisCircuitOpen_shouldSkipSlowRedisCall() {
        VerlaConversationRepository conversationRepository = mock(VerlaConversationRepository.class);
        VerlaTurnRepository turnRepository = mock(VerlaTurnRepository.class);
        VerlaSessionRepository sessionRepository = mock(VerlaSessionRepository.class);
        VerlaMessageRepository messageRepository = mock(VerlaMessageRepository.class);
        VerlaArtifactRepository artifactRepository = mock(VerlaArtifactRepository.class);
        VerlaToolCallRepository toolCallRepository = mock(VerlaToolCallRepository.class);
        VerlaRedisContextCache redisContextCache = mock(VerlaRedisContextCache.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        VerlaContextCacheProperties properties = new VerlaContextCacheProperties();
        properties.setRedisEnabled(true);
        properties.setRedisCircuitFailureThreshold(1);
        properties.setRedisCircuitOpenDuration(java.time.Duration.ofMinutes(1));
        VerlaCacheKeyFactory keyFactory = new VerlaCacheKeyFactory(properties);

        VerlaConversation firstConversation = VerlaConversation.builder()
                .id(101L)
                .userId("user_1")
                .title("第一次回源")
                .version(7L)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        VerlaMessage firstMessage = VerlaMessage.builder()
                .id(9001L)
                .conversationId(101L)
                .role("assistant")
                .textContent("first")
                .createdAt(LocalDateTime.now())
                .build();
        VerlaConversation secondConversation = VerlaConversation.builder()
                .id(202L)
                .userId("user_2")
                .title("第二次快速失败")
                .version(3L)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        VerlaMessage secondMessage = VerlaMessage.builder()
                .id(9002L)
                .conversationId(202L)
                .role("assistant")
                .textContent("second")
                .createdAt(LocalDateTime.now())
                .build();

        when(redisContextCache.getConversationSummary(keyFactory.convSummaryKey(101L, 7L, 20)))
                .thenThrow(new RuntimeException("redis timeout"));
        when(redisContextCache.tryLock(eq(keyFactory.convSummaryLockKey(101L, 7L, 20)), anyString()))
                .thenReturn(true);
        when(conversationRepository.findById(101L)).thenReturn(firstConversation);
        when(messageRepository.findByCursor(101L, null, 20)).thenReturn(List.of(firstMessage));

        when(conversationRepository.findById(202L)).thenReturn(secondConversation);
        when(messageRepository.findByCursor(202L, null, 20)).thenReturn(List.of(secondMessage));
        when(turnRepository.findRecentByConversation(101L, 1)).thenReturn(List.of());
        when(turnRepository.findRecentByConversation(202L, 1)).thenReturn(List.of());
        when(artifactRepository.findByConversation(101L)).thenReturn(List.of());
        when(artifactRepository.findByConversation(202L)).thenReturn(List.of());
        when(toolCallRepository.listVisibleByConversation(101L, 50)).thenReturn(List.of());
        when(toolCallRepository.listVisibleByConversation(202L, 50)).thenReturn(List.of());

        VerlaContextQueryService service = new VerlaContextQueryService(
                conversationRepository,
                turnRepository,
                sessionRepository,
                messageRepository,
                artifactRepository,
                toolCallRepository,
                meterRegistry,
                properties,
                Optional.of(redisContextCache));
        service.init();

        var first = service.getConversationContext(101L, 7L, null, null);
        var second = service.getConversationContext(202L, 3L, null, null);

        assertThat(first.getCacheHitLayer()).isEqualTo("db");
        assertThat(second.getCacheHitLayer()).isEqualTo("db");
        verify(redisContextCache, never()).getConversationSummary(keyFactory.convSummaryKey(202L, 3L, 20));
        verify(redisContextCache, never()).tryLock(eq(keyFactory.convSummaryLockKey(202L, 3L, 20)), anyString());
        assertThat(meterRegistry.get("verla.cache.redis.circuit.skip.total").counter().count()).isGreaterThanOrEqualTo(1.0d);
    }
}
