package com.studyagent.service.application.verla.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.service.config.VerlaContextCacheProperties;
import com.studyagent.service.config.VerlaRedisCacheConfig;
import com.studyagent.service.domain.verla.VerlaConversation;
import com.studyagent.service.domain.verla.VerlaMessage;
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

    private record SamplePayload(String name, int limit) {
    }
}
