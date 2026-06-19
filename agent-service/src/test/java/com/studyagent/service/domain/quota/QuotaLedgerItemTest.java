package com.studyagent.service.domain.quota;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QuotaLedgerItemTest {

    @Test
    void paidBalanceAfterSumsPlanAddonAndLegacyBuckets() {
        QuotaLedgerItem item = new QuotaLedgerItem(
                1L,
                "ledger_1",
                "consume",
                -3L,
                "verla_session",
                "100",
                "Assignment consumed 3 times",
                2L,
                4L,
                5L,
                6L,
                LocalDateTime.now(),
                "task_create",
                "time",
                List.of());

        assertEquals(15L, item.paidBalanceAfter());
    }

    @Test
    void paidBalanceAfterTreatsMissingBucketsAsZero() {
        QuotaLedgerItem item = new QuotaLedgerItem(
                1L,
                "ledger_1",
                "consume",
                -3L,
                "verla_session",
                "100",
                "Assignment consumed 3 times",
                null,
                null,
                null,
                6L,
                LocalDateTime.now(),
                "task_create",
                "time",
                List.of());

        assertEquals(6L, item.paidBalanceAfter());
    }
}
