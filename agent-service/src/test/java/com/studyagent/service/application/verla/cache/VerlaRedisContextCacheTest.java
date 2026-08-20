package com.studyagent.service.application.verla.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.service.config.VerlaContextCacheProperties;
import com.studyagent.service.config.VerlaRedisCacheConfig;
import com.studyagent.service.domain.verla.VerlaConversation;
import com.studyagent.service.domain.verla.VerlaMessage;
import com.studyagent.service.domain.verla.VerlaSession;
import com.studyagent.service.domain.verla.VerlaTurn;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

class VerlaRedisContextCacheTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withBean(VerlaContextCacheProperties.class)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withUserConfiguration(VerlaRedisCacheConfig.class);

    @Test
    void shouldGenerateDesignedRedisKeys() {
        VerlaContextCacheProperties properties = new VerlaContextCacheProperties();
        properties.setKeyPrefix("verla:v1");

        VerlaCacheKeyFactory keyFactory = new VerlaCacheKeyFactory(properties);

        assertThat(keyFactory.convLatestVersionKey(101L))
                .isEqualTo("verla:v1:conv:{101}:latest-version");
        assertThat(keyFactory.convSummaryKey(101L, 7L, 20))
                .isEqualTo("verla:v1:conv:{101}:summary:v7:ml:20");
        assertThat(keyFactory.convMessagesKey(101L, 7L, null, 20))
                .isEqualTo("verla:v1:conv:{101}:messages:v7:before:latest:limit:20");
        assertThat(keyFactory.convMessagesKey(101L, 7L, 9001L, 50))
                .isEqualTo("verla:v1:conv:{101}:messages:v7:before:9001:limit:50");
        assertThat(keyFactory.turnMetaKey(201L))
                .isEqualTo("verla:v1:turn:{201}:meta");
        assertThat(keyFactory.sessMetaKey(301L))
                .isEqualTo("verla:v1:sess:{301}:meta");
        assertThat(keyFactory.convSummaryLockKey(101L, 7L, 20))
                .isEqualTo("verla:v1:lock:conv:{101}:summary:v7:ml:20");
    }

    @Test
    void shouldEncodeAndDecodeCacheEnvelope() {
        VerlaCacheJsonCodec codec = new VerlaCacheJsonCodec(new ObjectMapper());

        String json = codec.encode(7L, new SamplePayload("conv", 20));
        VerlaCacheJsonCodec.CacheEnvelope<SamplePayload> decoded = codec.decode(
                json,
                new TypeReference<>() {
                });

        assertThat(json).contains("\"schemaVersion\":1");
        assertThat(json).contains("\"version\":7");
        assertThat(json).contains("\"cachedAt\":\"");
        assertThat(decoded.getSchemaVersion()).isEqualTo(1);
        assertThat(decoded.getVersion()).isEqualTo(7L);
        assertThat(decoded.getCachedAt()).isNotNull();
        assertThat(decoded.getData()).isEqualTo(new SamplePayload("conv", 20));
    }

    @Test
    void shouldWriteJsonPayloadWithConfiguredTtlAndAcquireLock() {
        VerlaContextCacheProperties properties = new VerlaContextCacheProperties();
        properties.setJitterRatio(0.0d);
        properties.setRedisLockTimeout(Duration.ofSeconds(3));

        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("lock:key"), eq("token"), eq(Duration.ofSeconds(3))))
                .thenReturn(Boolean.TRUE);

        VerlaRedisContextCache cache = new VerlaRedisContextCache(
                redisTemplate,
                new VerlaCacheJsonCodec(new ObjectMapper()),
                properties);

        cache.put("cache:key", Duration.ofSeconds(30), 7L, new SamplePayload("conv", 20));
        boolean locked = cache.tryLock("lock:key", "token");

        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations).set(eq("cache:key"), valueCaptor.capture(), ttlCaptor.capture());

        assertThat(ttlCaptor.getValue()).isEqualTo(Duration.ofSeconds(30));
        assertThat(valueCaptor.getValue()).contains("\"version\":7");
        assertThat(valueCaptor.getValue()).contains("\"name\":\"conv\"");
        assertThat(locked).isTrue();
    }

    @Test
    void shouldRecordRedisCommandLatencyByOperation() {
        VerlaContextCacheProperties properties = new VerlaContextCacheProperties();
        properties.setJitterRatio(0.0d);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("cache:key")).thenReturn("value");
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        VerlaRedisContextCache cache = new VerlaRedisContextCache(
                redisTemplate,
                new VerlaCacheJsonCodec(new ObjectMapper()),
                properties,
                meterRegistry);

        cache.getRaw("cache:key");
        cache.putRaw("cache:key", "value", Duration.ofSeconds(30));

        assertThat(meterRegistry.get("verla.cache.redis.command.duration")
                .tag("operation", "get").timer().count()).isEqualTo(1L);
        assertThat(meterRegistry.get("verla.cache.redis.command.duration")
                .tag("operation", "set").timer().count()).isEqualTo(1L);
    }

    @Test
    void shouldReadAndWriteConversationLatestVersion() {
        VerlaContextCacheProperties properties = new VerlaContextCacheProperties();
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("verla:v1:conv:{101}:latest-version")).thenReturn("7");

        VerlaRedisContextCache cache = new VerlaRedisContextCache(
                redisTemplate,
                new VerlaCacheJsonCodec(new ObjectMapper()),
                properties);

        cache.putConversationLatestVersion("verla:v1:conv:{101}:latest-version", 7L);
        Long latestVersion = cache.getConversationLatestVersion("verla:v1:conv:{101}:latest-version").orElse(null);

        verify(valueOperations).set("verla:v1:conv:{101}:latest-version", "7");
        verify(valueOperations, times(1)).get("verla:v1:conv:{101}:latest-version");
        assertThat(latestVersion).isEqualTo(7L);
    }

    @Test
    void shouldReadAndWriteConversationSummary() {
        VerlaContextCacheProperties properties = new VerlaContextCacheProperties();
        properties.setJitterRatio(0.0d);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        VerlaConversation conversation = VerlaConversation.builder()
                .id(101L)
                .userId("user_1")
                .title("题目讲解")
                .version(7L)
                .createdAt(LocalDateTime.of(2026, 5, 15, 11, 0))
                .updatedAt(LocalDateTime.of(2026, 5, 15, 11, 1))
                .build();
        VerlaMessage recentMessage = VerlaMessage.builder()
                .id(9001L)
                .conversationId(101L)
                .role("user")
                .textContent("帮我解释一下")
                .createdAt(LocalDateTime.of(2026, 5, 15, 11, 2))
                .build();
        VerlaCacheKeyFactory keyFactory = new VerlaCacheKeyFactory(properties);
        String key = keyFactory.convSummaryKey(101L, 7L, 20);

        VerlaRedisContextCache writeCache = new VerlaRedisContextCache(
                redisTemplate,
                new VerlaCacheJsonCodec(new ObjectMapper()),
                properties);
        writeCache.putConversationSummary(key, 7L,
                new ConversationSummaryCacheValue(conversation, List.of(recentMessage)),
                Duration.ofSeconds(60));

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq(key), jsonCaptor.capture(), eq(Duration.ofSeconds(60)));

        VerlaCacheJsonCodec.CacheEnvelope<ConversationSummaryCacheValue> expectedEnvelope =
                new VerlaCacheJsonCodec(new ObjectMapper()).decode(jsonCaptor.getValue(), new TypeReference<>() {
                });
        when(valueOperations.get(key)).thenReturn(jsonCaptor.getValue());

        VerlaRedisContextCache readCache = new VerlaRedisContextCache(
                redisTemplate,
                new VerlaCacheJsonCodec(new ObjectMapper()),
                properties);
        VerlaCacheJsonCodec.CacheEnvelope<ConversationSummaryCacheValue> actualEnvelope = readCache
                .getConversationSummary(key)
                .orElseThrow();

        assertThat(actualEnvelope.getVersion()).isEqualTo(7L);
        assertThat(actualEnvelope.getData()).isEqualTo(expectedEnvelope.getData());
        assertThat(actualEnvelope.getData().conversation().getVersion()).isEqualTo(7L);
        assertThat(actualEnvelope.getData().recentMessages()).hasSize(1);
    }

    @Test
    void shouldReadAndWriteConversationMessagesPage() {
        VerlaContextCacheProperties properties = new VerlaContextCacheProperties();
        properties.setJitterRatio(0.0d);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        VerlaMessage latest = VerlaMessage.builder()
                .id(9100L)
                .conversationId(101L)
                .role("assistant")
                .textContent("这里是解释")
                .createdAt(LocalDateTime.of(2026, 5, 15, 11, 3))
                .build();
        VerlaCacheKeyFactory keyFactory = new VerlaCacheKeyFactory(properties);
        String key = keyFactory.convMessagesKey(101L, 7L, 9001L, 20);

        VerlaRedisContextCache writeCache = new VerlaRedisContextCache(
                redisTemplate,
                new VerlaCacheJsonCodec(new ObjectMapper()),
                properties);
        writeCache.putConversationMessagesPage(key, 7L,
                new ConversationMessagesPageCacheValue(List.of(latest)),
                Duration.ofMinutes(10));

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq(key), jsonCaptor.capture(), eq(Duration.ofMinutes(10)));
        when(valueOperations.get(key)).thenReturn(jsonCaptor.getValue());

        VerlaRedisContextCache readCache = new VerlaRedisContextCache(
                redisTemplate,
                new VerlaCacheJsonCodec(new ObjectMapper()),
                properties);
        VerlaCacheJsonCodec.CacheEnvelope<ConversationMessagesPageCacheValue> actualEnvelope = readCache
                .getConversationMessagesPage(key)
                .orElseThrow();

        assertThat(actualEnvelope.getVersion()).isEqualTo(7L);
        assertThat(actualEnvelope.getData().messages()).extracting(VerlaMessage::getId)
                .containsExactly(9100L);
    }

    @Test
    void shouldReadAndWriteSessionMeta() {
        VerlaContextCacheProperties properties = new VerlaContextCacheProperties();
        properties.setJitterRatio(0.0d);
        properties.setSessionRunningTtl(Duration.ofSeconds(10));
        properties.setSessionTerminalTtl(Duration.ofMinutes(5));
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        VerlaSession session = VerlaSession.builder()
                .id(301L)
                .conversationId(101L)
                .turnId(201L)
                .kind("PLAN")
                .status("RUNNING")
                .contextRefJson("{\"convVersion\":7}")
                .createdAt(LocalDateTime.of(2026, 5, 18, 15, 0))
                .updatedAt(LocalDateTime.of(2026, 5, 18, 15, 1))
                .build();
        VerlaSession succeededSession = VerlaSession.builder()
                .id(302L)
                .conversationId(101L)
                .turnId(201L)
                .kind("PLAN")
                .status("SUCCEEDED")
                .createdAt(LocalDateTime.of(2026, 5, 18, 15, 2))
                .updatedAt(LocalDateTime.of(2026, 5, 18, 15, 3))
                .build();
        VerlaCacheKeyFactory keyFactory = new VerlaCacheKeyFactory(properties);
        String runningKey = keyFactory.sessMetaKey(301L);
        String terminalKey = keyFactory.sessMetaKey(302L);

        VerlaRedisContextCache writeCache = new VerlaRedisContextCache(
                redisTemplate,
                new VerlaCacheJsonCodec(new ObjectMapper()),
                properties);
        writeCache.putSessionMeta(runningKey, 301L, new SessionMetaCacheValue(session));
        writeCache.putSessionMeta(terminalKey, 302L, new SessionMetaCacheValue(succeededSession));

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations, times(2)).set(anyString(), jsonCaptor.capture(), ttlCaptor.capture());
        assertThat(ttlCaptor.getAllValues()).containsExactly(Duration.ofSeconds(10), Duration.ofMinutes(5));
        when(valueOperations.get(runningKey)).thenReturn(jsonCaptor.getAllValues().get(0));

        VerlaRedisContextCache readCache = new VerlaRedisContextCache(
                redisTemplate,
                new VerlaCacheJsonCodec(new ObjectMapper()),
                properties);
        VerlaCacheJsonCodec.CacheEnvelope<SessionMetaCacheValue> actualEnvelope = readCache
                .getSessionMeta(runningKey)
                .orElseThrow();

        assertThat(actualEnvelope.getVersion()).isEqualTo(301L);
        assertThat(actualEnvelope.getData().session().getStatus()).isEqualTo("RUNNING");
        assertThat(actualEnvelope.getData().session().getConversationId()).isEqualTo(101L);
    }

    @Test
    void shouldReadAndWriteTurnMeta() {
        VerlaContextCacheProperties properties = new VerlaContextCacheProperties();
        properties.setJitterRatio(0.0d);
        properties.setTurnMetaTtl(Duration.ofSeconds(30));
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        VerlaTurn turn = VerlaTurn.builder()
                .id(201L)
                .conversationId(101L)
                .status("RUNNING_AGENT")
                .resolvedIntent("assignment.run")
                .resolvedSlotsJson("{\"course\":\"math\"}")
                .activeSessionId(301L)
                .createdAt(LocalDateTime.of(2026, 5, 18, 16, 0))
                .updatedAt(LocalDateTime.of(2026, 5, 18, 16, 1))
                .build();
        VerlaCacheKeyFactory keyFactory = new VerlaCacheKeyFactory(properties);
        String key = keyFactory.turnMetaKey(201L);

        VerlaRedisContextCache writeCache = new VerlaRedisContextCache(
                redisTemplate,
                new VerlaCacheJsonCodec(new ObjectMapper()),
                properties);
        writeCache.putTurnMeta(key, 201L, new TurnMetaCacheValue(turn));

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq(key), jsonCaptor.capture(), eq(Duration.ofSeconds(30)));
        when(valueOperations.get(key)).thenReturn(jsonCaptor.getValue());

        VerlaRedisContextCache readCache = new VerlaRedisContextCache(
                redisTemplate,
                new VerlaCacheJsonCodec(new ObjectMapper()),
                properties);
        VerlaCacheJsonCodec.CacheEnvelope<TurnMetaCacheValue> actualEnvelope = readCache
                .getTurnMeta(key)
                .orElseThrow();

        assertThat(actualEnvelope.getVersion()).isEqualTo(201L);
        assertThat(actualEnvelope.getData().turn().getResolvedIntent()).isEqualTo("assignment.run");
        assertThat(actualEnvelope.getData().turn().getActiveSessionId()).isEqualTo(301L);
    }

    @Test
    void shouldStartContextWhenRedisDisabled() {
        contextRunner
                .withPropertyValues("verla.context-cache.redis-enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(StringRedisTemplate.class);
                    assertThat(context).doesNotHaveBean(VerlaRedisContextCache.class);
                });
    }

    @Test
    void shouldStartContextWhenRedisEnabled() {
        contextRunner
                .withPropertyValues("verla.context-cache.redis-enabled=true")
                .withBean(RedisConnectionFactory.class, () -> mock(RedisConnectionFactory.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(StringRedisTemplate.class);
                    assertThat(context).hasSingleBean(VerlaRedisContextCache.class);
                });
    }

    @Test
    void shouldCreateVerlaRedisBeansWhenBootRedisAutoConfigProvidesConnectionFactory() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        ConfigurationPropertiesAutoConfiguration.class,
                        RedisAutoConfiguration.class))
                .withBean(VerlaContextCacheProperties.class)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withUserConfiguration(VerlaRedisCacheConfig.class)
                .withPropertyValues(
                        "verla.context-cache.redis-enabled=true",
                        "spring.data.redis.host=127.0.0.1",
                        "spring.data.redis.port=6379")
                .run(context -> {
                    assertThat(context).hasSingleBean(RedisConnectionFactory.class);
                    assertThat(context).hasSingleBean(StringRedisTemplate.class);
                    assertThat(context).hasSingleBean(VerlaRedisContextCache.class);
                });
    }

    private record SamplePayload(String name, int limit) {
    }
}
