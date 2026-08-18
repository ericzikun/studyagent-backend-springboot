package com.studyagent.service.application.emaillead;

import com.studyagent.common.exception.PublicWriteProtectionUnavailableException;
import com.studyagent.common.exception.RateLimitExceededException;
import com.studyagent.service.config.PublicEmailLeadProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisPublicEmailLeadWriteGuardTest {

    private StringRedisTemplate redisTemplate;
    private PublicEmailLeadProperties properties;
    private RedisPublicEmailLeadWriteGuard guard;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        properties = new PublicEmailLeadProperties();
        properties.setRedisKeyPrefix("test:email-lead");
        properties.setIpWindow(Duration.ofMinutes(10));
        properties.setIpMaxRequests(5);
        properties.setDailyNewMax(1000);
        guard = new RedisPublicEmailLeadWriteGuard(
                redisTemplate,
                properties,
                Clock.fixed(Instant.parse("2026-08-18T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void acquiresIpAndDailyReservationWithoutPuttingRawIpInRedisKey() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(1L, 1L);

        guard.checkIpRateLimit("203.0.113.8");
        guard.reserveDailyNew();

        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(redisTemplate, times(2)).execute(any(RedisScript.class), keys.capture(), any(Object[].class));
        assertThat(keys.getAllValues().get(0).get(0))
                .startsWith("test:email-lead:ip:")
                .doesNotContain("203.0.113.8");
        assertThat(keys.getAllValues().get(1)).containsExactly("test:email-lead:daily-new:2026-08-18");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void rejectsWhenIpWindowIsExceededBeforeDailyReservation() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(6L);

        assertThatThrownBy(() -> guard.checkIpRateLimit("203.0.113.8"))
                .isInstanceOf(RateLimitExceededException.class);

        verify(redisTemplate, times(1)).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void rejectsWhenDailyNewBudgetIsExhausted() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(-1L);

        assertThatThrownBy(guard::reserveDailyNew)
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void failsClosedWhenRedisIsUnavailable() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("offline"));

        assertThatThrownBy(() -> guard.checkIpRateLimit("203.0.113.8"))
                .isInstanceOf(PublicWriteProtectionUnavailableException.class)
                .hasMessage("Public write protection is temporarily unavailable");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void releasesOnlyTheDailyReservation() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(0L);

        guard.releaseDailyReservation();

        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(List.of("test:email-lead:daily-new:2026-08-18")),
                any(Object[].class));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void invalidConfigurationFailsBeforeRedis() {
        properties.setDailyNewMax(0);

        assertThatThrownBy(() -> guard.checkIpRateLimit("203.0.113.8"))
                .isInstanceOf(PublicWriteProtectionUnavailableException.class);

        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }
}
