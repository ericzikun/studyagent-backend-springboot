package com.studyagent.infra.service.simulation;

import com.studyagent.infra.entity.QuotaLedgerEntity;
import com.studyagent.infra.mapper.QuotaLedgerMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SimulationQuotaLedgerServiceTest {

    private QuotaLedgerMapper quotaLedgerMapper;
    private SimulationUsageCounter usageCounter;
    private SimulationQuotaLedgerService service;

    @BeforeEach
    void setUp() {
        quotaLedgerMapper = mock(QuotaLedgerMapper.class);
        usageCounter = mock(SimulationUsageCounter.class);
        service = new SimulationQuotaLedgerService(quotaLedgerMapper, usageCounter);
    }

    @Test
    void planGrantWritesDisplayOnlySimulationRow() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 22, 17, 17);
        LocalDateTime end = start.plusMonths(1);
        LocalDateTime now = start.plusMinutes(1);
        when(quotaLedgerMapper.selectOne(any())).thenReturn(null);

        service.recordPlanGrant("user_1", "sub_1", "basic_monthly", "plan_reset",
                "invoice", "in_1", "invoice", false, 3L, start, end, now);

        ArgumentCaptor<QuotaLedgerEntity> ledger = ArgumentCaptor.forClass(QuotaLedgerEntity.class);
        verify(quotaLedgerMapper).insert(ledger.capture());
        assertEquals("simulation", ledger.getValue().getFeatureCode());
        assertEquals("plan_reset", ledger.getValue().getLedgerType());
        assertEquals(3L, ledger.getValue().getAmount());
        assertEquals("invoice:in_1:plan:simulation", ledger.getValue().getIdempotencyKey());
        assertTrue(ledger.getValue().getBizContext().contains("\"display_only\":true"));
    }

    @Test
    void planClearWritesRemainingAndAlsoRecordsExhaustedWindow() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 22, 9, 17);
        LocalDateTime end = start.plusMonths(1);
        LocalDateTime now = start.plusHours(2);
        when(quotaLedgerMapper.selectOne(any())).thenReturn(null);
        when(usageCounter.countSucceededUtc("user_1", start, end)).thenReturn(1L);

        service.recordPlanClear("user_1", "sub_1", "basic_monthly", 3L,
                start, "UTC", end, "subscription:sub_1:deleted", now);

        ArgumentCaptor<QuotaLedgerEntity> ledger = ArgumentCaptor.forClass(QuotaLedgerEntity.class);
        verify(quotaLedgerMapper).insert(ledger.capture());
        assertEquals("plan_clear", ledger.getValue().getLedgerType());
        assertEquals(-2L, ledger.getValue().getAmount());
        assertEquals("subscription:sub_1:deleted:simulation",
                ledger.getValue().getIdempotencyKey());

        when(usageCounter.countSucceededUtc("user_1", start, end)).thenReturn(3L);
        service.recordPlanClear("user_1", "sub_1", "basic_monthly", 3L,
                start, "UTC", end, "subscription:sub_2:deleted", now);
        verify(quotaLedgerMapper, org.mockito.Mockito.times(2)).insert(ledger.capture());
        assertEquals(0L, ledger.getValue().getAmount());
    }

    @Test
    void existingLedgerIsIdempotent() {
        when(quotaLedgerMapper.selectOne(any())).thenReturn(new QuotaLedgerEntity());

        service.recordPlanGrant("user_1", "sub_1", "basic_monthly", "plan_reset",
                "invoice", "in_1", "invoice", false, 3L,
                LocalDateTime.now(), LocalDateTime.now().plusMonths(1), LocalDateTime.now());

        verify(quotaLedgerMapper, never()).insert(any(QuotaLedgerEntity.class));
    }
}
