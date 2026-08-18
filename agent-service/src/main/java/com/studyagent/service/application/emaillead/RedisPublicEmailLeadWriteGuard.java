package com.studyagent.service.application.emaillead;

import com.studyagent.common.exception.PublicWriteProtectionUnavailableException;
import com.studyagent.common.exception.RateLimitExceededException;
import com.studyagent.service.config.PublicEmailLeadProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HexFormat;

/**
 * 用 Redis 原子脚本限制匿名邮箱写入。
 *
 * <p>IP 仅以 SHA-256 摘要进入短期 Redis key，不写数据库；每日额度先预占，数据库确认重复后再归还，
 * 从而保证并发下的新写入不会越过硬上限。Redis 异常时失败关闭，不允许绕过保护直接写 MySQL。</p>
 */
@Component
public class RedisPublicEmailLeadWriteGuard implements PublicEmailLeadWriteGuard {

    private static final String ENDPOINT = "public-email-leads";
    private static final long DAILY_KEY_TTL_SECONDS = 172800L;

    private static final DefaultRedisScript<Long> INCREMENT_WITH_TTL_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
              redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return current
            """, Long.class);

    private static final DefaultRedisScript<Long> RESERVE_DAILY_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
              redis.call('EXPIRE', KEYS[1], ARGV[2])
            end
            if current > tonumber(ARGV[1]) then
              redis.call('DECR', KEYS[1])
              return -1
            end
            return current
            """, Long.class);

    private static final DefaultRedisScript<Long> RELEASE_DAILY_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1])
            if current and tonumber(current) > 0 then
              return redis.call('DECR', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final PublicEmailLeadProperties properties;
    private final Clock clock;

    @Autowired
    public RedisPublicEmailLeadWriteGuard(StringRedisTemplate redisTemplate,
                                          PublicEmailLeadProperties properties) {
        this(redisTemplate, properties, Clock.systemUTC());
    }

    RedisPublicEmailLeadWriteGuard(StringRedisTemplate redisTemplate,
                                   PublicEmailLeadProperties properties,
                                   Clock clock) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public void checkIpRateLimit(String clientIp) {
        validateProperties();
        try {
            Long ipCount = redisTemplate.execute(
                    INCREMENT_WITH_TTL_SCRIPT,
                    Collections.singletonList(ipKey(clientIp)),
                    Long.toString(properties.getIpWindow().toSeconds()));
            if (ipCount == null) {
                throw new PublicWriteProtectionUnavailableException();
            }
            if (ipCount > properties.getIpMaxRequests()) {
                throw new RateLimitExceededException(ENDPOINT);
            }
        } catch (RateLimitExceededException | PublicWriteProtectionUnavailableException ex) {
            throw ex;
        } catch (DataAccessException ex) {
            throw new PublicWriteProtectionUnavailableException(ex);
        } catch (RuntimeException ex) {
            throw new PublicWriteProtectionUnavailableException(ex);
        }
    }

    @Override
    public void reserveDailyNew() {
        validateProperties();
        try {
            Long reservation = redisTemplate.execute(
                    RESERVE_DAILY_SCRIPT,
                    Collections.singletonList(dailyKey()),
                    Integer.toString(properties.getDailyNewMax()),
                    Long.toString(DAILY_KEY_TTL_SECONDS));
            if (reservation == null) {
                throw new PublicWriteProtectionUnavailableException();
            }
            if (reservation < 0) {
                throw new RateLimitExceededException(ENDPOINT);
            }
        } catch (RateLimitExceededException | PublicWriteProtectionUnavailableException ex) {
            throw ex;
        } catch (DataAccessException ex) {
            throw new PublicWriteProtectionUnavailableException(ex);
        } catch (RuntimeException ex) {
            throw new PublicWriteProtectionUnavailableException(ex);
        }
    }

    @Override
    public void releaseDailyReservation() {
        try {
            Long result = redisTemplate.execute(
                    RELEASE_DAILY_SCRIPT,
                    Collections.singletonList(dailyKey()));
            if (result == null) {
                throw new PublicWriteProtectionUnavailableException();
            }
        } catch (PublicWriteProtectionUnavailableException ex) {
            throw ex;
        } catch (DataAccessException ex) {
            throw new PublicWriteProtectionUnavailableException(ex);
        } catch (RuntimeException ex) {
            throw new PublicWriteProtectionUnavailableException(ex);
        }
    }

    private String ipKey(String clientIp) {
        String safeIp = clientIp == null || clientIp.isBlank() ? "unknown" : clientIp.trim();
        return properties.getRedisKeyPrefix() + ":ip:" + sha256(safeIp);
    }

    private String dailyKey() {
        return properties.getRedisKeyPrefix() + ":daily-new:" + LocalDate.now(clock);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private void validateProperties() {
        if (properties.getRedisKeyPrefix() == null || properties.getRedisKeyPrefix().isBlank()
                || properties.getIpWindow() == null || properties.getIpWindow().isZero()
                || properties.getIpWindow().isNegative()
                || properties.getIpMaxRequests() <= 0
                || properties.getDailyNewMax() <= 0) {
            throw new PublicWriteProtectionUnavailableException();
        }
    }
}
