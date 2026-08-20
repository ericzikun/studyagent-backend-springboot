package com.studyagent.service.application.verla.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.studyagent.service.config.VerlaContextCacheProperties;
import com.studyagent.service.domain.verla.state.SessionStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

public class VerlaRedisContextCache {

    private static final String REDIS_COMMAND_DURATION = "verla.cache.redis.command.duration";

    private final StringRedisTemplate redisTemplate;
    private final VerlaCacheJsonCodec codec;
    private final VerlaContextCacheProperties properties;
    private final MeterRegistry meterRegistry;

    public VerlaRedisContextCache(StringRedisTemplate redisTemplate,
                                  VerlaCacheJsonCodec codec,
                                  VerlaContextCacheProperties properties) {
        this(redisTemplate, codec, properties, Metrics.globalRegistry);
    }

    public VerlaRedisContextCache(StringRedisTemplate redisTemplate,
                                  VerlaCacheJsonCodec codec,
                                  VerlaContextCacheProperties properties,
                                  MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.codec = codec;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    public <T> Optional<VerlaCacheJsonCodec.CacheEnvelope<T>> get(String key,
                                                                  TypeReference<VerlaCacheJsonCodec.CacheEnvelope<T>> typeReference) {
        String json = observe("get", () -> ops().get(key));
        if (json == null) {
            return Optional.empty();
        }
        return Optional.of(codec.decode(json, typeReference));
    }

    public Optional<Long> getConversationLatestVersion(String key) {
        String value = observe("get", () -> ops().get(key));
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(Long.parseLong(value));
    }

    public Optional<String> getRaw(String key) {
        return Optional.ofNullable(observe("get", () -> ops().get(key)));
    }

    public Optional<VerlaCacheJsonCodec.CacheEnvelope<ConversationSummaryCacheValue>> getConversationSummary(String key) {
        return get(key, new TypeReference<>() {
        });
    }

    public Optional<VerlaCacheJsonCodec.CacheEnvelope<ConversationMessagesPageCacheValue>> getConversationMessagesPage(String key) {
        return get(key, new TypeReference<>() {
        });
    }

    public Optional<VerlaCacheJsonCodec.CacheEnvelope<SessionMetaCacheValue>> getSessionMeta(String key) {
        return get(key, new TypeReference<>() {
        });
    }

    public Optional<VerlaCacheJsonCodec.CacheEnvelope<TurnMetaCacheValue>> getTurnMeta(String key) {
        return get(key, new TypeReference<>() {
        });
    }

    public void put(String key, Duration ttl, Long version, Object data) {
        observe("set", () -> {
            ops().set(key, codec.encode(version, data), applyJitter(ttl));
            return null;
        });
    }

    public void putConversationLatestVersion(String key, Long version) {
        if (version == null) {
            delete(key);
            return;
        }
        observe("set", () -> {
            ops().set(key, String.valueOf(version));
            return null;
        });
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

    public void putSessionMeta(String key,
                               Long version,
                               SessionMetaCacheValue value) {
        put(key, resolveSessionMetaTtl(value), version, value);
    }

    public void putTurnMeta(String key,
                            Long version,
                            TurnMetaCacheValue value) {
        put(key, properties.getTurnMetaTtl(), version, value);
    }

    public void putRaw(String key, String value, Duration ttl) {
        observe("set", () -> {
            ops().set(key, value, applyJitter(ttl));
            return null;
        });
    }

    public void publish(String channel, String payload) {
        observe("publish", () -> redisTemplate.convertAndSend(channel, payload));
    }

    public void delete(String key) {
        observe("delete", () -> redisTemplate.delete(key));
    }

    public boolean tryLock(String key, String token) {
        return tryLock(key, token, properties.getRedisLockTimeout());
    }

    public boolean tryLock(String key, String token, Duration ttl) {
        return Boolean.TRUE.equals(observe("set_if_absent", () -> ops().setIfAbsent(key, token, ttl)));
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

    private <T> T observe(String operation, Supplier<T> command) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            return command.get();
        } finally {
            sample.stop(Timer.builder(REDIS_COMMAND_DURATION)
                    .tag("operation", operation)
                    .register(meterRegistry));
        }
    }

    private Duration resolveSessionMetaTtl(SessionMetaCacheValue value) {
        if (value == null || value.session() == null || value.session().getStatus() == null) {
            return properties.getSessMetaTtl();
        }
        try {
            SessionStatus status = SessionStatus.valueOf(value.session().getStatus());
            return status.isTerminal() ? properties.getSessionTerminalTtl() : properties.getSessionRunningTtl();
        } catch (IllegalArgumentException ex) {
            return properties.getSessMetaTtl();
        }
    }
}
