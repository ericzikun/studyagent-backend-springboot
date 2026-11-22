package com.studyagent.infra.service.simulation;

import com.studyagent.common.datetime.DateTimeFormats;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Counts successful SIMULATION sessions against a quota window. */
@Service
@RequiredArgsConstructor
public class SimulationUsageCounter {

    private final JdbcTemplate jdbcTemplate;

    /** Both bounds are UTC wall clock, matching {@code verla_sessions.created_at}. */
    public long countSucceededUtc(String clerkUserId, LocalDateTime startUtc, LocalDateTime endUtc) {
        if (clerkUserId == null || clerkUserId.isBlank()) {
            return 0L;
        }
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM verla_sessions s "
                        + "INNER JOIN verla_conversations c ON c.id = s.conversation_id "
                        + "WHERE c.user_id = ? AND c.status <> 'deleted' "
                        + "AND s.kind = 'SIMULATION' AND s.status = 'SUCCEEDED'");
        if (startUtc != null) {
            sql.append(" AND s.created_at >= ?");
        }
        if (endUtc != null) {
            sql.append(" AND s.created_at < ?");
        }
        Long count;
        if (startUtc != null && endUtc != null) {
            count = jdbcTemplate.queryForObject(sql.toString(), Long.class, clerkUserId, startUtc, endUtc);
        } else if (startUtc != null) {
            count = jdbcTemplate.queryForObject(sql.toString(), Long.class, clerkUserId, startUtc);
        } else {
            count = jdbcTemplate.queryForObject(sql.toString(), Long.class, clerkUserId);
        }
        return count == null ? 0L : count;
    }

    /** Shanghai wall clock (billing API time) -> UTC wall clock (session storage). */
    public static LocalDateTime toUtcWallClock(LocalDateTime appWallClock) {
        if (appWallClock == null) {
            return null;
        }
        return LocalDateTime.ofInstant(
                appWallClock.atZone(DateTimeFormats.APP_ZONE).toInstant(), ZoneOffset.UTC);
    }

    /** DATETIME value stored in {@code sourceZone} -> UTC wall clock. */
    public static LocalDateTime toUtcWallClock(LocalDateTime value, String sourceZone) {
        if (value == null) {
            return null;
        }
        if (sourceZone == null || sourceZone.isBlank()) {
            return value;
        }
        try {
            return LocalDateTime.ofInstant(
                    value.atZone(ZoneId.of(sourceZone)).toInstant(), ZoneOffset.UTC);
        } catch (java.time.DateTimeException invalidZone) {
            return value;
        }
    }
}
