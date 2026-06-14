package com.studyagent.service.application.verla;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.service.application.verla.cache.SessionMetaCacheValue;
import com.studyagent.service.application.verla.cache.VerlaCacheJsonCodec;
import com.studyagent.service.application.verla.cache.VerlaCacheKeyFactory;
import com.studyagent.service.application.verla.cache.VerlaRedisContextCache;
import com.studyagent.service.config.VerlaContextCacheProperties;
import com.studyagent.service.domain.verla.VerlaConversation;
import com.studyagent.service.domain.verla.VerlaArtifact;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListenerFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VerlaContextCacheSyncIntegrationTest {

    private static final int DEFAULT_REDIS_PORT = 6380;
    private static final int DEFAULT_REDIS_DB = 15;
    private static final long STARTUP_TIMEOUT_MILLIS = 5000L;

    private Process redisProcess;
    private boolean startedByTest;

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
        File logFile = new File("/tmp/verla-context-sync-live-test.log");
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

    @AfterEach
    void cleanupTestKeys() {
        withRedis((properties, keyFactory, redisTemplate, redisContextCache) -> {
            Set<String> keys = redisTemplate.keys(properties.getKeyPrefix() + "*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        });
    }

    @Test
    void sessionChange_shouldRefreshSessionMetaAfterCommit() {
        withRedis((properties, keyFactory, redisTemplate, redisContextCache) -> {
            InMemorySessionRepository delegate = new InMemorySessionRepository();
            AnnotationConfigApplicationContext context = buildContext(properties, redisContextCache, delegate,
                    new InMemoryTurnRepository(), new InMemoryConversationRepository());
            try {
                TransactionTemplate tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
                VerlaSessionRepository sessionRepository = context.getBean(VerlaSessionRepository.class);
                VerlaSession session = VerlaSession.builder()
                        .id(301L)
                        .conversationId(101L)
                        .turnId(201L)
                        .kind("PLAN")
                        .status("RUNNING")
                        .createdAt(LocalDateTime.now())
                        .updatedAt(LocalDateTime.now())
                        .build();

                tx.executeWithoutResult(status -> sessionRepository.save(session));

                var envelope = redisContextCache.getSessionMeta(keyFactory.sessMetaKey(301L)).orElse(null);
                assertThat(envelope).isNotNull();
                assertThat(envelope.getData().session().getStatus()).isEqualTo("RUNNING");
            } finally {
                context.close();
            }
        });
    }

    @Test
    void turnChange_shouldRefreshTurnMetaAfterCommit() {
        withRedis((properties, keyFactory, redisTemplate, redisContextCache) -> {
            InMemoryTurnRepository delegate = new InMemoryTurnRepository();
            AnnotationConfigApplicationContext context = buildContext(properties, redisContextCache,
                    new InMemorySessionRepository(), delegate, new InMemoryConversationRepository());
            try {
                TransactionTemplate tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
                VerlaTurnRepository turnRepository = context.getBean(VerlaTurnRepository.class);

                VerlaTurn planning = VerlaTurn.builder()
                        .id(201L)
                        .conversationId(101L)
                        .status("PLANNING")
                        .createdAt(LocalDateTime.now())
                        .updatedAt(LocalDateTime.now())
                        .build();
                VerlaTurn running = VerlaTurn.builder()
                        .id(201L)
                        .conversationId(101L)
                        .status("RUNNING_AGENT")
                        .resolvedIntent("assignment.run")
                        .resolvedSlotsJson("{\"course\":\"math\"}")
                        .activeSessionId(301L)
                        .createdAt(planning.getCreatedAt())
                        .updatedAt(LocalDateTime.now())
                        .build();

                tx.executeWithoutResult(status -> turnRepository.save(planning));
                tx.executeWithoutResult(status -> turnRepository.save(running));

                var envelope = redisContextCache.getTurnMeta(keyFactory.turnMetaKey(201L)).orElse(null);
                assertThat(envelope).isNotNull();
                assertThat(envelope.getData().turn().getStatus()).isEqualTo("RUNNING_AGENT");
                assertThat(envelope.getData().turn().getResolvedIntent()).isEqualTo("assignment.run");
                assertThat(envelope.getData().turn().getActiveSessionId()).isEqualTo(301L);
            } finally {
                context.close();
            }
        });
    }

    @Test
    void conversationChange_shouldRefreshLatestVersionAfterCommit() {
        withRedis((properties, keyFactory, redisTemplate, redisContextCache) -> {
            InMemoryConversationRepository delegate = new InMemoryConversationRepository();
            delegate.save(VerlaConversation.builder()
                    .id(101L)
                    .userId("user_1")
                    .title("题目讲解")
                    .version(7L)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build());
            AnnotationConfigApplicationContext context = buildContext(properties, redisContextCache,
                    new InMemorySessionRepository(), new InMemoryTurnRepository(), delegate);
            try {
                TransactionTemplate tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
                VerlaConversationRepository conversationRepository = context.getBean(VerlaConversationRepository.class);

                tx.executeWithoutResult(status -> conversationRepository.incrementVersion(101L));

                assertThat(redisContextCache.getConversationLatestVersion(keyFactory.convLatestVersionKey(101L)))
                        .contains(8L);
            } finally {
                context.close();
            }
        });
    }

    @Test
    void rollback_shouldNotWriteRedisPrematurely() {
        withRedis((properties, keyFactory, redisTemplate, redisContextCache) -> {
            InMemorySessionRepository delegate = new InMemorySessionRepository();
            AnnotationConfigApplicationContext context = buildContext(properties, redisContextCache, delegate,
                    new InMemoryTurnRepository(), new InMemoryConversationRepository());
            try {
                TransactionTemplate tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
                VerlaSessionRepository sessionRepository = context.getBean(VerlaSessionRepository.class);
                VerlaSession session = VerlaSession.builder()
                        .id(401L)
                        .conversationId(101L)
                        .turnId(201L)
                        .kind("AGENT")
                        .status("RUNNING")
                        .createdAt(LocalDateTime.now())
                        .updatedAt(LocalDateTime.now())
                        .build();

                tx.executeWithoutResult(status -> {
                    sessionRepository.save(session);
                    status.setRollbackOnly();
                });

                assertThat(delegate.findById(401L)).isNotNull();
                assertThat(redisContextCache.getSessionMeta(keyFactory.sessMetaKey(401L))).isEmpty();
            } finally {
                context.close();
            }
        });
    }

    private AnnotationConfigApplicationContext buildContext(VerlaContextCacheProperties properties,
                                                            VerlaRedisContextCache redisContextCache,
                                                            InMemorySessionRepository sessionRepository,
                                                            InMemoryTurnRepository turnRepository,
                                                            InMemoryConversationRepository conversationRepository) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(VerlaContextCacheProperties.class, () -> properties);
        context.registerBean(VerlaRedisContextCache.class, () -> redisContextCache);
        context.registerBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new);
        context.registerBean(PlatformTransactionManager.class, TestTransactionManager::new);
        context.registerBean(TransactionalEventListenerFactory.class, TransactionalEventListenerFactory::new);
        context.registerBean("verlaSessionRepositoryImpl", VerlaSessionRepository.class, () -> sessionRepository);
        context.registerBean("verlaTurnRepositoryImpl", VerlaTurnRepository.class, () -> turnRepository);
        context.registerBean("verlaConversationRepositoryImpl", VerlaConversationRepository.class, () -> conversationRepository);
        context.registerBean(VerlaMessageRepository.class, NoopMessageRepository::new);
        context.registerBean(VerlaArtifactRepository.class, NoopArtifactRepository::new);
        context.registerBean(VerlaToolCallRepository.class, NoopToolCallRepository::new);
        context.register(VerlaContextQueryService.class, VerlaContextCacheRepositoryConfig.class);
        context.refresh();
        return context;
    }

    private void withRedis(RedisScenario scenario) {
        VerlaContextCacheProperties properties = new VerlaContextCacheProperties();
        properties.setRedisEnabled(true);
        properties.setJitterRatio(0.0d);
        properties.setKeyPrefix("verla:test:sync:" + System.nanoTime());

        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration("127.0.0.1", redisPort());
        configuration.setDatabase(redisDatabase());
        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();
        StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        VerlaRedisContextCache redisContextCache = new VerlaRedisContextCache(
                redisTemplate,
                new VerlaCacheJsonCodec(new ObjectMapper()),
                properties);
        VerlaCacheKeyFactory keyFactory = new VerlaCacheKeyFactory(properties);
        try {
            scenario.accept(properties, keyFactory, redisTemplate, redisContextCache);
        } finally {
            Set<String> keys = redisTemplate.keys(properties.getKeyPrefix() + "*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
            connectionFactory.destroy();
        }
    }

    private void waitUntilRedisReady() throws Exception {
        long deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (!redisProcess.isAlive()) {
                throw new IllegalStateException("redis-server exited early; check /tmp/verla-context-sync-live-test.log");
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

    @FunctionalInterface
    private interface RedisScenario {
        void accept(VerlaContextCacheProperties properties,
                    VerlaCacheKeyFactory keyFactory,
                    StringRedisTemplate redisTemplate,
                    VerlaRedisContextCache redisContextCache);
    }

    private static class TestTransactionManager extends AbstractPlatformTransactionManager {
        private TestTransactionManager() {
            setTransactionSynchronization(SYNCHRONIZATION_ALWAYS);
        }

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }

    private static class InMemorySessionRepository implements VerlaSessionRepository {
        private final Map<Long, VerlaSession> store = new ConcurrentHashMap<>();

        @Override
        public VerlaSession save(VerlaSession session) {
            store.put(session.getId(), session);
            return session;
        }

        @Override
        public VerlaSession findById(Long id) {
            return store.get(id);
        }

        @Override
        public VerlaSession findByIdForUpdate(Long id) {
            return store.get(id);
        }

        @Override
        public List<VerlaSession> findByTurn(Long turnId) {
            return List.of();
        }

        @Override
        public List<VerlaSession> findCompletedSiblings(Long turnId, Long excludeSessionId) {
            return List.of();
        }

        @Override
        public VerlaSession findByCorrelationId(String correlationId) {
            return null;
        }

        @Override
        public boolean bindQuotaLedger(Long sessionId, Long ledgerId, Long amount) {
            return true;
        }

        @Override
        public int countActiveAssignmentRuns() {
            return 0;
        }

        @Override
        public int countActiveCapabilityRuns(String action) {
            return 0;
        }
    }

    private static class InMemoryTurnRepository implements VerlaTurnRepository {
        private final Map<Long, VerlaTurn> store = new ConcurrentHashMap<>();

        @Override
        public VerlaTurn save(VerlaTurn turn) {
            store.put(turn.getId(), turn);
            return turn;
        }

        @Override
        public VerlaTurn findById(Long id) {
            return store.get(id);
        }

        @Override
        public VerlaTurn findByIdForUpdate(Long id) {
            return store.get(id);
        }

        @Override
        public List<VerlaTurn> findRecentByConversation(Long conversationId, int limit) {
            return List.of();
        }
    }

    private static class InMemoryConversationRepository implements VerlaConversationRepository {
        private final Map<Long, VerlaConversation> store = new ConcurrentHashMap<>();

        @Override
        public VerlaConversation save(VerlaConversation conversation) {
            store.put(conversation.getId(), conversation);
            return conversation;
        }

        @Override
        public VerlaConversation findById(Long id) {
            return store.get(id);
        }

        @Override
        public List<VerlaConversation> findByUserFilteredPaged(String userId, String segmentQueryKey, String conversationStatusDb, int page, int size) {
            return List.of();
        }

        @Override
        public long countByUserFiltered(String userId, String segmentQueryKey, String conversationStatusDb) {
            return 0;
        }

        @Override
        public int touchOnNewTurn(Long id, Long turnId) {
            return incrementVersion(id);
        }

        @Override
        public int incrementVersion(Long id) {
            VerlaConversation current = store.get(id);
            if (current == null) {
                return 0;
            }
            current.setVersion((current.getVersion() == null ? 0L : current.getVersion()) + 1);
            current.setLastTurnId(current.getLastTurnId() == null ? turnIdFallback() : current.getLastTurnId());
            current.setUpdatedAt(LocalDateTime.now());
            store.put(id, current);
            return 1;
        }

        @Override
        public int updateTitle(Long id, String title) {
            return 0;
        }

        private Long turnIdFallback() {
            return 0L;
        }
    }

    private static class NoopMessageRepository implements VerlaMessageRepository {
        @Override
        public VerlaMessage save(VerlaMessage message) {
            return message;
        }

        @Override
        public VerlaMessage findById(Long id) {
            return null;
        }

        @Override
        public List<VerlaMessage> findByCursor(Long conversationId, Long beforeMessageId, int limit) {
            return List.of();
        }

        @Override
        public List<VerlaMessage> findFileChatByCursor(Long conversationId, String objectId, Long cursor, int limit) {
            return List.of();
        }
    }

    private static class NoopArtifactRepository implements VerlaArtifactRepository {
        @Override
        public VerlaArtifact findById(Long id) {
            return null;
        }

        @Override
        public VerlaArtifact findByUid(String artifactUid) {
            return null;
        }

        @Override
        public List<VerlaArtifact> findByConversation(Long conversationId) {
            return List.of();
        }

        @Override
        public List<VerlaArtifact> findBySession(Long sessionId) {
            return List.of();
        }

        @Override
        public List<VerlaArtifact> findByUids(List<String> artifactUids) {
            return List.of();
        }

        @Override
        public VerlaArtifact upsertByUid(VerlaArtifact artifact) {
            return artifact;
        }
    }

    private static class NoopToolCallRepository implements VerlaToolCallRepository {
        @Override
        public VerlaToolCall findByCallId(String toolCallId) {
            return null;
        }

        @Override
        public List<VerlaToolCall> listByTurn(Long turnId, int limit) {
            return List.of();
        }

        @Override
        public List<VerlaToolCall> listBySession(Long sessionId, int limit) {
            return List.of();
        }

        @Override
        public List<VerlaToolCall> listVisibleByConversation(Long conversationId, int limit) {
            return List.of();
        }

        @Override
        public VerlaToolCall upsertByCallId(VerlaToolCall toolCall) {
            return toolCall;
        }
    }
}
