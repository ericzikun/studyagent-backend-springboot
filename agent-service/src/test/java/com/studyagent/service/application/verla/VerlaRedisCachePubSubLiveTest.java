package com.studyagent.service.application.verla;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.service.application.verla.cache.ConversationSummaryCacheValue;
import com.studyagent.service.application.verla.cache.SessionMetaCacheValue;
import com.studyagent.service.application.verla.cache.TurnMetaCacheValue;
import com.studyagent.service.application.verla.cache.VerlaCacheJsonCodec;
import com.studyagent.service.application.verla.cache.VerlaCacheKeyFactory;
import com.studyagent.service.application.verla.cache.VerlaRedisContextCache;
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
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VerlaRedisCachePubSubLiveTest {

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
        File logFile = new File("/tmp/verla-redis-pubsub-live-test.log");
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
        testKeyPrefix = "verla:test:pubsub:" + System.nanoTime();
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
        cleanupTestKeys();
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void liveRedis_shouldInvalidateRemoteL1CacheViaPubSub() throws Exception {
        VerlaSession runningSession = session("RUNNING");
        VerlaSession succeededSession = session("SUCCEEDED");
        VerlaTurn turn = turn();
        VerlaConversation conversation = conversation();
        VerlaMessage recentMessage = message();

        redisContextCache.putSessionMeta(
                keyFactory.sessMetaKey(301L),
                301L,
                new SessionMetaCacheValue(runningSession));
        redisContextCache.putTurnMeta(
                keyFactory.turnMetaKey(201L),
                201L,
                new TurnMetaCacheValue(turn));
        redisContextCache.putConversationSummary(
                keyFactory.convSummaryKey(101L, 7L, 20),
                7L,
                new ConversationSummaryCacheValue(conversation, List.of(recentMessage)),
                Duration.ofSeconds(60));

        VerlaContextQueryService readerService = serviceForReader();
        RedisMessageListenerContainer container = listenerContainer(readerService);
        container.afterPropertiesSet();
        container.start();
        try {
            var firstView = readerService.getSessionContext(301L, 7L, null);
            assertThat(firstView.getSession().getStatus()).isEqualTo("RUNNING");

            VerlaContextQueryService writerService = serviceForWriter(succeededSession);
            writerService.refreshSessionCache(301L);

            long deadline = System.currentTimeMillis() + 3000L;
            String currentStatus = firstView.getSession().getStatus();
            while (System.currentTimeMillis() < deadline) {
                currentStatus = readerService.getSessionContext(301L, 7L, null).getSession().getStatus();
                if ("SUCCEEDED".equals(currentStatus)) {
                    break;
                }
                Thread.sleep(100L);
            }

            assertThat(currentStatus).isEqualTo("SUCCEEDED");
        } finally {
            container.stop();
            container.destroy();
        }
    }

    private VerlaContextQueryService serviceForReader() {
        VerlaConversationRepository conversationRepository = mock(VerlaConversationRepository.class);
        VerlaTurnRepository turnRepository = mock(VerlaTurnRepository.class);
        VerlaSessionRepository sessionRepository = mock(VerlaSessionRepository.class);
        VerlaMessageRepository messageRepository = mock(VerlaMessageRepository.class);
        VerlaArtifactRepository artifactRepository = mock(VerlaArtifactRepository.class);
        VerlaToolCallRepository toolCallRepository = mock(VerlaToolCallRepository.class);

        when(sessionRepository.findCompletedSiblings(201L, 301L)).thenReturn(List.of());
        when(turnRepository.findRecentByConversation(101L, 1)).thenReturn(List.of());
        when(artifactRepository.findByConversation(101L)).thenReturn(List.of());
        when(toolCallRepository.listVisibleByConversation(101L, 50)).thenReturn(List.of());
        when(toolCallRepository.listBySession(301L, 50)).thenReturn(List.of());

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

    private VerlaContextQueryService serviceForWriter(VerlaSession updatedSession) {
        VerlaConversationRepository conversationRepository = mock(VerlaConversationRepository.class);
        VerlaTurnRepository turnRepository = mock(VerlaTurnRepository.class);
        VerlaSessionRepository sessionRepository = mock(VerlaSessionRepository.class);
        VerlaMessageRepository messageRepository = mock(VerlaMessageRepository.class);
        VerlaArtifactRepository artifactRepository = mock(VerlaArtifactRepository.class);
        VerlaToolCallRepository toolCallRepository = mock(VerlaToolCallRepository.class);

        when(sessionRepository.findById(301L)).thenReturn(updatedSession);

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

    private RedisMessageListenerContainer listenerContainer(VerlaContextQueryService service) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(
                new VerlaRedisCacheInvalidationSubscriber(new ObjectMapper(), service),
                ChannelTopic.of(keyFactory.invalidationChannel()));
        return container;
    }

    private void cleanupTestKeys() {
        if (redisTemplate == null) {
            return;
        }
        Set<String> keys = redisTemplate.keys(testKeyPrefix + "*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    private boolean shouldSpawnRedis() {
        return Boolean.parseBoolean(System.getProperty("verla.redis.live.spawn", "true"));
    }

    private int redisPort() {
        return Integer.getInteger("verla.redis.live.port", DEFAULT_REDIS_PORT);
    }

    private int redisDatabase() {
        return Integer.getInteger("verla.redis.live.db", DEFAULT_REDIS_DB);
    }

    private boolean isRedisListening() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", redisPort()), 500);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private void waitUntilRedisReady() throws Exception {
        long deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (isRedisListening()) {
                return;
            }
            Thread.sleep(100L);
        }
        throw new IllegalStateException("redis did not start within " + STARTUP_TIMEOUT_MILLIS + "ms");
    }

    private static VerlaSession session(String status) {
        return VerlaSession.builder()
                .id(301L)
                .conversationId(101L)
                .turnId(201L)
                .kind("PLAN")
                .status(status)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private static VerlaTurn turn() {
        return VerlaTurn.builder()
                .id(201L)
                .conversationId(101L)
                .status("RUNNING_AGENT")
                .activeSessionId(301L)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private static VerlaConversation conversation() {
        return VerlaConversation.builder()
                .id(101L)
                .userId("user_1")
                .title("题目讲解")
                .version(7L)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private static VerlaMessage message() {
        return VerlaMessage.builder()
                .id(9001L)
                .conversationId(101L)
                .role("assistant")
                .textContent("redis live")
                .createdAt(LocalDateTime.now())
                .build();
    }
}
