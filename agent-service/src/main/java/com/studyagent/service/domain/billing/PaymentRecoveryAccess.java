package com.studyagent.service.domain.billing;

import java.time.Instant;

public record PaymentRecoveryAccess(boolean locked, boolean paidCycleValid,
        Instant paidPeriodStart, Instant paidPeriodEnd, PaymentRecoverySummary summary) {
    public static PaymentRecoveryAccess none() {
        return new PaymentRecoveryAccess(false, false, null, null, null);
    }
}
