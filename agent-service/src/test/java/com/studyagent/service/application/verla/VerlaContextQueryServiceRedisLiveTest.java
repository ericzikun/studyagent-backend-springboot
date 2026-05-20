package com.studyagent.service.application.verla;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.service.application.verla.cache.ConversationMessagesPageCacheValue;
import com.studyagent.service.application.verla.cache.ConversationSummaryCacheValue;
import com.studyagent.service.application.verla.cache.VerlaCacheJsonCodec;
import com.studyagent.service.application.verla.cache.VerlaCacheKeyFactory;
import com.studyagent.service.application.verla.cache.VerlaRedisContextCache;
import com.studyagent.service.application.verla.dto.VerlaSessionContextQueryOptions;
import com.studyagent.service.config.VerlaContextCacheProperties;
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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VerlaContextQueryServiceRedisLiveTest {

    private static final int DEFAULT_REDIS_PORT = 6380;
    private static final int DEFAULT_REDIS_DB = 15;
    private static final long STARTUP_TIMEOUT_MILLIS = 5000L;

    private Process redisProcess;
    private boolean startedByTest;

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private VerlaRedisContextCache redisContextCache;
    private VerlaContextCacheProperties properties;
    private VerlaCacheKeyFactory keyFactory;
    private String testKeyPrefix;

    @BeforeAll
    void startRedisIfEnabled() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("verla.redis.live.enabled"),
                "requires -Dverla.redis.live.enabled=true");
        if (!shouldSpawnRedis()) {
            Assumptions.assumeTrue(isRedisListening(),
                    () -> "requires reachable redis at 127.0.0.1:" + redisPort());
            return;
        }
        if (isRedisListening()) {
            return;
        }
        File logFile = new File("/tmp/verla-redis-live-test.log");
        ProcessBuilder builder = new ProcessBuilder(
                "/usr/local/bin/redis-server",
                "--port", String.valueOf(redisPort()),
                "--save", "",
                "--appendonly", "no");
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));
        redisProcess = builder.start();
        startedByTest = true;
        waitUntilRedisReady();
    }

    @AfterAll
    void stopRedisIfStarted() {
        if (redisProcess != null && startedByTest) {
            redisProcess.destroy();
            try {
                redisProcess.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @BeforeEach
    void setUp() {
        properties = new VerlaContextCacheProperties();
        properties.setRedisEnabled(true);
        properties.setJitterRatio(0.0d);
        testKeyPrefix = "verla:test:live:" + System.nanoTime();
        properties.setKeyPrefix(testKeyPrefix);

        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration("127.0.0.1", redisPort());
        configuration.setDatabase(redisDatabase());
        connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();

        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        cleanupTestKeys();

        redisContextCache = new VerlaRedisContextCache(
                redisTemplate,
                new VerlaCacheJsonCodec(new ObjectMapper()),
                properties);
        keyFactory = new VerlaCacheKeyFactory(properties);
    }

    @AfterEach
    void tearDown() {
        if (redisTemplate != null) {
            cleanupTestKeys();
        }
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void liveRedis_shouldServeConversationContextByConvVersionWithoutDbPeek() {
        VerlaConversationRepository conversationRepository = mock(VerlaConversationRepository.class);
        VerlaTurnRepository turnRepository = mock(VerlaTurnRepository.class);
        VerlaSessionRepository sessionRepository = mock(VerlaSessionRepository.class);
        VerlaMessageRepository messageRepository = mock(VerlaMessageRepository.class);
        VerlaArtifactRepository artifactRepository = mock(VerlaArtifactRepository.class);
        VerlaToolCallRepository toolCallRepository = mock(VerlaToolCallRepository.class);

        VerlaConversation conversation = conversation(7L);
        VerlaTurn latestTurn = latestTurn();
        VerlaMessage recentMessage = message(9001L, "帮我解释一下");

        String summaryKey = keyFactory.convSummaryKey(101L, 7L, 20);
        redisContextCache.putConversationSummary(
                summaryKey,
                7L,
                new ConversationSummaryCacheValue(conversation, List.of(recentMessage)),
                Duration.ofSeconds(60));

        when(conversationRepository.findById(101L))
                .thenThrow(new AssertionError("should not peek conversation db when redis summary hits"));
        when(turnRepository.findRecentByConversation(101L, 1)).thenReturn(List.of(latestTurn));
        when(artifactRepository.findByConversation(101L)).thenReturn(List.of());
        when(toolCallRepository.listVisibleByConversation(101L, 50)).thenReturn(List.of());

        VerlaContextQueryService service = service(
                conversationRepository,
                turnRepository,
                sessionRepository,
                messageRepository,
                artifactRepository,
                toolCallRepository);

        var view = service.getConversationContext(101L, 7L, null, null);

        assertThat(view.getConversation().getVersion()).isEqualTo(7L);
        assertThat(view.getRecentMessages()).extracting(VerlaMessage::getId).containsExactly(9001L);
        assertThat(view.getCacheHitLayer()).isEqualTo("redis");
        assertThat(redisTemplate.opsForValue().get(summaryKey)).isNotBlank();
        verify(conversationRepository, never()).findById(101L);
        verify(messageRepository, never()).findByCursor(101L, null, 20);
    }

    @Test
    void liveRedis_shouldWriteDifferentSummaryKeysForDifferentMessageLimits() {
        VerlaConversationRepository conversationRepository = mock(VerlaConversationRepository.class);
        VerlaTurnRepository turnRepository = mock(VerlaTurnRepository.class);
        VerlaSessionRepository sessionRepository = mock(VerlaSessionRepository.class);
        VerlaMessageRepository messageRepository = mock(VerlaMessageRepository.class);
        VerlaArtifactRepository artifactRepository = mock(VerlaArtifactRepository.class);
        VerlaToolCallRepository toolCallRepository = mock(VerlaToolCallRepository.class);

        VerlaConversation conversation = conversation(7L);
        VerlaTurn latestTurn = latestTurn();
        VerlaMessage latest20 = message(9020L, "limit 20");
        VerlaMessage latest50 = message(9050L, "limit 50");

        when(conversationRepository.findById(101L)).thenReturn(conversation);
        when(turnRepository.findRecentByConversation(101L, 1)).thenReturn(List.of(latestTurn));
        when(artifactRepository.findByConversation(101L)).thenReturn(List.of());
        when(toolCallRepository.listVisibleByConversation(101L, 50)).thenReturn(List.of());
        when(messageRepository.findByCursor(101L, null, 20)).thenReturn(List.of(latest20));
        when(messageRepository.findByCursor(101L, null, 50)).thenReturn(List.of(latest50));

        VerlaContextQueryService service = service(
                conversationRepository,
                turnRepository,
                sessionRepository,
                messageRepository,
                artifactRepository,
                toolCallRepository);

        var view20 = service.getConversationContext(
                101L, 7L, null, VerlaSessionContextQueryOptions.builder().messageLimit(20).build());
        var view50 = service.getConversationContext(
                101L, 7L, null, VerlaSessionContextQueryOptions.builder().messageLimit(50).build());

        String summaryKey20 = keyFactory.convSummaryKey(101L, 7L, 20);
        String summaryKey50 = keyFactory.convSummaryKey(101L, 7L, 50);

        assertThat(view20.getRecentMessages()).extracting(VerlaMessage::getId).containsExactly(9020L);
        assertThat(view50.getRecentMessages()).extracting(VerlaMessage::getId).containsExactly(9050L);
        assertThat(redisTemplate.opsForValue().get(summaryKey20)).isNotBlank();
        assertThat(redisTemplate.opsForValue().get(summaryKey50)).isNotBlank();
        Set<String> keys = redisTemplate.keys(testKeyPrefix + ":conv:{101}:summary:v7:ml:*");
        assertThat(keys).containsExactlyInAnyOrder(summaryKey20, summaryKey50);

        var summary20 = redisContextCache.getConversationSummary(summaryKey20).orElseThrow();
        var summary50 = redisContextCache.getConversationSummary(summaryKey50).orElseThrow();
        assertThat(summary20.getData().recentMessages()).extracting(VerlaMessage::getId).containsExactly(9020L);
        assertThat(summary50.getData().recentMessages()).extracting(VerlaMessage::getId).containsExactly(9050L);
    }

    @Test
    void liveRedis_shouldKeepBeforePageSeparatedFromLatestPage() {
        VerlaConversationRepository warmupConversationRepository = mock(VerlaConversationRepository.class);
        VerlaTurnRepository warmupTurnRepository = mock(VerlaTurnRepository.class);
        VerlaSessionRepository warmupSessionRepository = mock(VerlaSessionRepository.class);
        VerlaMessageRepository warmupMessageRepository = mock(VerlaMessageRepository.class);
        VerlaArtifactRepository warmupArtifactRepository = mock(VerlaArtifactRepository.class);
        VerlaToolCallRepository warmupToolCallRepository = mock(VerlaToolCallRepository.class);

        VerlaConversation conversation = conversation(7L);
        VerlaTurn latestTurn = latestTurn();
        VerlaMessage latestMessage = message(9100L, "最近页");
        VerlaMessage historyMessage = message(8999L, "历史页");

        when(warmupConversationRepository.findById(101L)).thenReturn(conversation);
        when(warmupTurnRepository.findRecentByConversation(101L, 1)).thenReturn(List.of(latestTurn));
        when(warmupArtifactRepository.findByConversation(101L)).thenReturn(List.of());
        when(warmupToolCallRepository.listVisibleByConversation(101L, 50)).thenReturn(List.of());
        when(warmupMessageRepository.findByCursor(101L, null, 20)).thenReturn(List.of(latestMessage));
        when(warmupMessageRepository.findByCursor(101L, 9001L, 20)).thenReturn(List.of(historyMessage));

        VerlaContextQueryService warmupService = service(
                warmupConversationRepository,
                warmupTurnRepository,
                warmupSessionRepository,
                warmupMessageRepository,
                warmupArtifactRepository,
                warmupToolCallRepository);

        warmupService.getConversationContext(101L, 7L, null, null);
        warmupService.getConversationContext(101L, 7L, 9001L, null);

        String latestKey = keyFactory.convSummaryKey(101L, 7L, 20);
        String historyKey = keyFactory.convMessagesKey(101L, 7L, 9001L, 20);
        assertThat(redisTemplate.opsForValue().get(latestKey)).isNotBlank();
        assertThat(redisTemplate.opsForValue().get(historyKey)).isNotBlank();

        VerlaConversationRepository readConversationRepository = mock(VerlaConversationRepository.class);
        VerlaTurnRepository readTurnRepository = mock(VerlaTurnRepository.class);
        VerlaSessionRepository readSessionRepository = mock(VerlaSessionRepository.class);
        VerlaMessageRepository readMessageRepository = mock(VerlaMessageRepository.class);
        VerlaArtifactRepository readArtifactRepository = mock(VerlaArtifactRepository.class);
        VerlaToolCallRepository readToolCallRepository = mock(VerlaToolCallRepository.class);

        when(readConversationRepository.findById(101L)).thenReturn(conversation);
        when(readTurnRepository.findRecentByConversation(101L, 1)).thenReturn(List.of(latestTurn));
        when(readArtifactRepository.findByConversation(101L)).thenReturn(List.of());
        when(readToolCallRepository.listVisibleByConversation(101L, 50)).thenReturn(List.of());
        when(readMessageRepository.findByCursor(101L, null, 20))
                .thenThrow(new AssertionError("latest page should hit redis summary"));
        when(readMessageRepository.findByCursor(101L, 9001L, 20))
                .thenThrow(new AssertionError("history page should hit redis messages page"));

        VerlaContextQueryService readService = service(
                readConversationRepository,
                readTurnRepository,
                readSessionRepository,
                readMessageRepository,
                readArtifactRepository,
                readToolCallRepository);

        var latestView = readService.getConversationContext(101L, 7L, null, null);
        var historyView = readService.getConversationContext(101L, 7L, 9001L, null);

        assertThat(latestView.getRecentMessages()).extracting(VerlaMessage::getId).containsExactly(9100L);
        assertThat(historyView.getRecentMessages()).extracting(VerlaMessage::getId).containsExactly(8999L);
        assertThat(redisContextCache.getConversationMessagesPage(historyKey)
                .map(envelope -> envelope.getData().messages())
                .orElseThrow())
                .extracting(VerlaMessage::getId)
                .containsExactly(8999L);
        verify(readMessageRepository, never()).findByCursor(101L, null, 20);
        verify(readMessageRepository, never()).findByCursor(101L, 9001L, 20);
    }

    @Test
    void liveRedis_cleanupShouldDeleteOnlyTestPrefixedKeys() {
        String foreignKey = "foreign:data:" + System.nanoTime();
        String ownedKey = keyFactory.convSummaryKey(101L, 7L, 20);

        redisTemplate.opsForValue().set(foreignKey, "keep");
        redisTemplate.opsForValue().set(ownedKey, "delete");

        cleanupTestKeys();

        assertThat(redisTemplate.opsForValue().get(foreignKey)).isEqualTo("keep");
        assertThat(redisTemplate.opsForValue().get(ownedKey)).isNull();
    }

    private VerlaContextQueryService service(VerlaConversationRepository conversationRepository,
                                             VerlaTurnRepository turnRepository,
                                             VerlaSessionRepository sessionRepository,
                                             VerlaMessageRepository messageRepository,
                                             VerlaArtifactRepository artifactRepository,
                                             VerlaToolCallRepository toolCallRepository) {
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
        return service;
    }

    private void cleanupTestKeys() {
        if (redisTemplate == null || testKeyPrefix == null || testKeyPrefix.isBlank()) {
            return;
        }
        Set<String> keys = redisTemplate.keys(testKeyPrefix + "*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    private void waitUntilRedisReady() throws Exception {
        long deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (!redisProcess.isAlive()) {
                throw new IllegalStateException("redis-server exited early; check /tmp/verla-redis-live-test.log");
            }
            if (isRedisListening()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("redis-server did not become ready within " + STARTUP_TIMEOUT_MILLIS + "ms");
    }

    private boolean isRedisListening() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", redisPort()), 200);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private int redisPort() {
        return Integer.getInteger("verla.redis.live.port", DEFAULT_REDIS_PORT);
    }

    private int redisDatabase() {
        return Integer.getInteger("verla.redis.live.db", DEFAULT_REDIS_DB);
    }

    private boolean shouldSpawnRedis() {
        return Boolean.parseBoolean(System.getProperty("verla.redis.live.spawn", "true"));
    }

    private VerlaConversation conversation(Long version) {
        return VerlaConversation.builder()
                .id(101L)
                .userId("user_1")
                .title("题目讲解")
                .version(version)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private VerlaTurn latestTurn() {
        return VerlaTurn.builder()
                .id(201L)
                .conversationId(101L)
                .status("SUCCEEDED")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private VerlaMessage message(Long id, String text) {
        return VerlaMessage.builder()
                .id(id)
                .conversationId(101L)
                .role("assistant")
                .textContent(text)
                .createdAt(LocalDateTime.now())
                .build();
    }
}
