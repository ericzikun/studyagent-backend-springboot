package com.studyagent.infra.service.simulation;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * One-time post-launch Simulation Lab trial.
 *
 * <p>The grant timestamp is created lazily on the first entitlement check after
 * this release. Historical successful labs before that timestamp do not consume
 * the regranted trial; any successful lab afterwards marks it used. The used
 * marker is persisted so deleting the conversation cannot restore the trial.
 */
@Service
@RequiredArgsConstructor
public class SimulationTrialGrantService {

    private final JdbcTemplate jdbcTemplate;

    public void ensureGrant(String clerkUserId) {
        jdbcTemplate.update(
                "INSERT IGNORE INTO simulation_lab_trial_grants (clerk_user_id, granted_at) "
                        + "VALUES (?, ?)",
                clerkUserId, LocalDateTime.now());
    }

    public boolean isUsed(String clerkUserId) {
        ensureGrant(clerkUserId);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT granted_at, used_at FROM simulation_lab_trial_grants "
                        + "WHERE clerk_user_id = ?",
                clerkUserId);
        if (rows == null || rows.isEmpty()) {
            return false;
        }
        Map<String, Object> row = rows.get(0);
        if (row.get("used_at") != null) {
            return true;
        }
        LocalDateTime grantedAt = toLocalDateTime(row.get("granted_at"));
        if (grantedAt == null) {
            return false;
        }
        List<Long> succeeded = jdbcTemplate.queryForList(
                "SELECT c.id FROM verla_sessions s "
                        + "INNER JOIN verla_conversations c ON c.id = s.conversation_id "
                        + "WHERE c.user_id = ? AND c.status <> 'deleted' "
                        + "AND s.kind = 'SIMULATION' AND s.status = 'SUCCEEDED' "
                        + "AND s.created_at >= ? ORDER BY s.created_at ASC LIMIT 1",
                Long.class, clerkUserId, grantedAt);
        if (succeeded == null || succeeded.isEmpty()) {
            return false;
        }
        jdbcTemplate.update(
                "UPDATE simulation_lab_trial_grants SET used_at = ?, used_conversation_id = ? "
                        + "WHERE clerk_user_id = ? AND used_at IS NULL",
                LocalDateTime.now(), succeeded.get(0), clerkUserId);
        return true;
    }

    private static LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        return null;
    }
}
