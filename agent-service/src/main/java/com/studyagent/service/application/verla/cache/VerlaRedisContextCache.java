package com.studyagent.service.application.verla.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.studyagent.service.config.VerlaContextCacheProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

@RequiredArgsConstructor
public class VerlaRedisContextCache {

    private final StringRedisTemplate redisTemplate;
    private final VerlaCacheJsonCodec codec;
    private final VerlaContextCacheProperties properties;

    public <T> Optional<VerlaCacheJsonCodec.CacheEnvelope<T>> get(String key,
                                                                  TypeReference<VerlaCacheJsonCodec.CacheEnvelope<T>> typeReference) {
        String json = ops().get(key);
        if (json == null) {
            return Optional.empty();
        }
        return Optional.of(codec.decode(json, typeReference));
    }

    public Optional<Long> getConversationLatestVersion(String key) {
        String value = ops().get(key);
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(Long.parseLong(value));
    }

    public Optional<VerlaCacheJsonCodec.CacheEnvelope<ConversationSummaryCacheValue>> getConversationSummary(String key) {
        return get(key, new TypeReference<>() {
        });
    }

    public Optional<VerlaCacheJsonCodec.CacheEnvelope<ConversationMessagesPageCacheValue>> getConversationMessagesPage(String key) {
        return get(key, new TypeReference<>() {
        });
    }

    public void put(String key, Duration ttl, Long version, Object data) {
        ops().set(key, codec.encode(version, data), applyJitter(ttl));
    }

    public void putConversationLatestVersion(String key, Long version) {
        if (version == null) {
            delete(key);
            return;
        }
        ops().set(key, String.valueOf(version));
    }

    public void putConversationSummary(String key,
                                       Long version,
                                       ConversationSummaryCacheValue value,
                                       Duration ttl) {
        put(key, ttl, version, value);
    }

    public void putConversationMessagesPage(String key,
                                            Long version,
                                            ConversationMessagesPageCacheValue value,
                                            Duration ttl) {
        put(key, ttl, version, value);
    }

    public void putRaw(String key, String value, Duration ttl) {
        ops().set(key, value, applyJitter(ttl));
    }

    public void delete(String key) {
        redisTemplate.delete(key);
    }

    public boolean tryLock(String key, String token) {
        return tryLock(key, token, properties.getRedisLockTimeout());
    }

    public boolean tryLock(String key, String token, Duration ttl) {
        return Boolean.TRUE.equals(ops().setIfAbsent(key, token, ttl));
    }

    Duration applyJitter(Duration ttl) {
        double ratio = properties.getJitterRatio();
        if (ttl == null || ttl.isZero() || ttl.isNegative() || ratio <= 0D) {
            return ttl;
        }
        long millis = ttl.toMillis();
        long delta = Math.max(1L, Math.round(millis * ratio));
        long jitter = ThreadLocalRandom.current().nextLong(-delta, delta + 1);
        return Duration.ofMillis(Math.max(1L, millis + jitter));
    }

    private ValueOperations<String, String> ops() {
        return redisTemplate.opsForValue();
    }
}
