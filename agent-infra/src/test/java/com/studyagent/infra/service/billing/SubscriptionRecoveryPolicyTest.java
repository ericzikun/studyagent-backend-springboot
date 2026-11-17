package com.studyagent.infra.service.billing;

import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class SubscriptionRecoveryPolicyTest {
    private final Instant first = Instant.parse("2026-11-04T02:00:00Z");

    @Test void keepsFourFixedSlotsAndThirtyDays() {
        for (int slot = 1; slot <= 4; slot++) assertEquals(first.plus(Duration.ofDays(7L * slot)), SubscriptionRecoveryPolicy.retryAt(first, slot));
        assertEquals(Instant.parse("2026-12-04T02:00:00Z"), SubscriptionRecoveryPolicy.deadline(first));
        assertThrows(IllegalArgumentException.class, () -> SubscriptionRecoveryPolicy.retryAt(first, 0));
        assertThrows(IllegalArgumentException.class, () -> SubscriptionRecoveryPolicy.retryAt(first, 5));
    }
    @Test void onlyInsufficientFundsWithoutStopAdviceAllowsRetry() {
        assertTrue(SubscriptionRecoveryPolicy.allowsAutomaticRetry("insufficient_funds", null));
        assertTrue(SubscriptionRecoveryPolicy.allowsAutomaticRetry("insufficient_funds", "try_again_later"));
        for (String advice : new String[]{"do_not_try_again", "update_card", "unrecognized_advice"})
            assertFalse(SubscriptionRecoveryPolicy.allowsAutomaticRetry("insufficient_funds", advice));
        assertFalse(SubscriptionRecoveryPolicy.allowsAutomaticRetry("do_not_honor", "try_again_later"));
        assertFalse(SubscriptionRecoveryPolicy.allowsAutomaticRetry(null, null));
    }
    @Test void fullUtcCalendarPeriodsAndAnnualMonthlyQuota() {
        Instant paid = Instant.parse("2027-01-31T20:00:00Z");
        assertEquals(Instant.parse("2027-02-28T20:00:00Z"), SubscriptionRecoveryPolicy.paidPeriodEnd(paid, "month", 1));
        Instant leap = Instant.parse("2028-02-29T02:00:00Z");
        assertEquals(Instant.parse("2029-02-28T02:00:00Z"), SubscriptionRecoveryPolicy.paidPeriodEnd(leap, "year", 1));
        assertEquals(Instant.parse("2028-03-29T02:00:00Z"), SubscriptionRecoveryPolicy.quotaPeriodEnd(leap, "year", 1));
        assertThrows(IllegalArgumentException.class, () -> SubscriptionRecoveryPolicy.paidPeriodEnd(paid, "week", 1));
    }
    @Test void dispatchWindowInclusiveButDeadlineExclusive() {
        Instant scheduled = SubscriptionRecoveryPolicy.retryAt(first, 1);
        assertTrue(SubscriptionRecoveryPolicy.withinDispatchWindow(scheduled, scheduled.plusSeconds(360), SubscriptionRecoveryPolicy.deadline(first), 3600));
        assertTrue(SubscriptionRecoveryPolicy.withinDispatchWindow(scheduled, scheduled.plusSeconds(3600), SubscriptionRecoveryPolicy.deadline(first), 3600));
        assertFalse(SubscriptionRecoveryPolicy.withinDispatchWindow(scheduled, scheduled.plusSeconds(3601), SubscriptionRecoveryPolicy.deadline(first), 3600));
        assertFalse(SubscriptionRecoveryPolicy.withinDispatchWindow(scheduled, SubscriptionRecoveryPolicy.deadline(first), SubscriptionRecoveryPolicy.deadline(first), Integer.MAX_VALUE));
    }
    @Test void pauseCoveringSlotRemainsSkippedAfterExit() {
        Instant scheduled = SubscriptionRecoveryPolicy.retryAt(first, 1);
        assertTrue(SubscriptionRecoveryPolicy.pauseCovers(scheduled, scheduled.minusSeconds(30), scheduled.plusSeconds(60)));
        assertTrue(SubscriptionRecoveryPolicy.pauseCovers(scheduled, scheduled, null));
        assertFalse(SubscriptionRecoveryPolicy.pauseCovers(scheduled, scheduled.minusSeconds(60), scheduled.minusSeconds(1)));
    }
    @Test void providerIdempotencyUsesRealElapsedTimeNotBusinessClock() {
        assertTrue(SubscriptionRecoveryPolicy.canReplayUnknown(first, first.plusSeconds(86399)));
        assertFalse(SubscriptionRecoveryPolicy.canReplayUnknown(first, first.plusSeconds(86400)));
        assertFalse(SubscriptionRecoveryPolicy.canReplayUnknown(null, first));
    }
    @Test void unknownNetworkAdviceCannotBecomeMissingAdvice() {
        assertEquals("unrecognized_advice", SubscriptionRecoveryPolicy.normalizeAdvice(null, "visa", "99"));
        assertEquals("do_not_try_again", SubscriptionRecoveryPolicy.normalizeAdvice("do_not_try_again", "visa", "99"));
        assertNull(SubscriptionRecoveryPolicy.normalizeAdvice(null, "visa", null));
    }
}
