package com.studyagent.infra.service.simulation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SimulationTrialGrantServiceTest {

    private JdbcTemplate jdbcTemplate;
    private SimulationTrialGrantService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        service = new SimulationTrialGrantService(jdbcTemplate);
    }

    @Test
    void historicalSuccessBeforeGrantDoesNotConsumeTrial() {
        LocalDateTime grantedAt = LocalDateTime.now();
        when(jdbcTemplate.queryForList(anyString(), anyString()))
                .thenReturn(List.of(grantRow(grantedAt, null)));
        when(jdbcTemplate.queryForList(
                anyString(), eq(Long.class), anyString(), any(LocalDateTime.class)))
                .thenReturn(List.of());

        assertFalse(service.isUsed("user_1"));
    }

    @Test
    void successAfterGrantMarksTrialUsed() {
        LocalDateTime grantedAt = LocalDateTime.now().minusMinutes(1);
        when(jdbcTemplate.queryForList(anyString(), anyString()))
                .thenReturn(List.of(grantRow(grantedAt, null)));
        when(jdbcTemplate.queryForList(
                anyString(), eq(Long.class), anyString(), any(LocalDateTime.class)))
                .thenReturn(List.of(42L));

        assertTrue(service.isUsed("user_1"));
        verify(jdbcTemplate).update(
                anyString(), any(LocalDateTime.class), eq(42L), eq("user_1"));
    }

    @Test
    void persistedUsedMarkerWinsEvenWhenConversationIsGone() {
        LocalDateTime usedAt = LocalDateTime.now();
        when(jdbcTemplate.queryForList(anyString(), anyString()))
                .thenReturn(List.of(grantRow(usedAt.minusHours(1), usedAt)));

        assertTrue(service.isUsed("user_1"));
    }

    private static Map<String, Object> grantRow(
            LocalDateTime grantedAt, LocalDateTime usedAt) {
        Map<String, Object> row = new HashMap<>();
        row.put("granted_at", Timestamp.valueOf(grantedAt));
        row.put("used_at", usedAt == null ? null : Timestamp.valueOf(usedAt));
        return row;
    }
}
