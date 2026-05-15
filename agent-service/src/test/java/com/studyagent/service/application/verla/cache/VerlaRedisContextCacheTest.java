package com.studyagent.service.application.verla.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.service.config.VerlaContextCacheProperties;
import com.studyagent.service.config.VerlaRedisCacheConfig;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
