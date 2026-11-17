package com.studyagent.infra.service.billing;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

/** Pure recovery policy; business dates and provider idempotency clocks are supplied separately. */
final class SubscriptionRecoveryPolicy {
    private SubscriptionRecoveryPolicy() {}

    static Instant deadline(Instant first) { return first.plus(Duration.ofDays(30)); }
    static Instant retryAt(Instant first, int slot) {
        if (slot < 1 || slot > 4) throw new IllegalArgumentException("Retry slot must be between 1 and 4");
        return first.plus(Duration.ofDays(7L * slot));
    }
    static boolean allowsAutomaticRetry(String decline, String advice) {
        return "insufficient_funds".equals(decline)
                && (advice == null || advice.isBlank() || "try_again_later".equals(advice));
    }
    static Instant paidPeriodEnd(Instant paid, String interval, int count) {
        if (count <= 0) throw new IllegalArgumentException("Interval count must be positive");
        return switch (interval) {
            case "month" -> paid.atZone(ZoneOffset.UTC).plusMonths(count).toInstant();
            case "year" -> paid.atZone(ZoneOffset.UTC).plusYears(count).toInstant();
            default -> throw new IllegalArgumentException("Unsupported membership interval");
        };
    }
    static Instant quotaPeriodEnd(Instant paid, String interval, int count) {
        Instant end = paidPeriodEnd(paid, interval, count);
        Instant monthly = paid.atZone(ZoneOffset.UTC).plusMonths(1).toInstant();
        return "year".equals(interval) && monthly.isBefore(end) ? monthly : end;
    }
    static boolean withinDispatchWindow(Instant scheduled, Instant now, Instant deadline, long tolerance) {
        return tolerance >= 0 && !now.isBefore(scheduled) && now.isBefore(deadline)
                && !now.isAfter(scheduled.plusSeconds(tolerance));
    }
    static boolean pauseCovers(Instant scheduled, Instant paused, Instant closed) {
        return paused != null && !scheduled.isBefore(paused) && (closed == null || !scheduled.isAfter(closed));
    }
    static boolean canReplayUnknown(Instant firstDispatched, Instant realNow) {
        return firstDispatched != null && !realNow.isBefore(firstDispatched)
                && realNow.isBefore(firstDispatched.plus(Duration.ofHours(24)));
    }
    static String normalizeAdvice(String advice, String network, String networkCode) {
        if (advice != null && !advice.isBlank()) return advice;
        // No raw network mapping has P0 evidence yet. Fail closed until an exact fixture verifies it.
        return networkCode == null || networkCode.isBlank() ? null : "unrecognized_advice";
    }
}
