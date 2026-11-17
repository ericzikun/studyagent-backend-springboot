package com.studyagent.service.domain.billing;

import java.time.LocalDateTime;
import java.util.List;

/** Amounts are Stripe minor units. Invoice facts remain distinct from the window outcome. */
public record PaymentRecoverySummary(String recoveryId, String state, boolean locked,
        String displayTier, String displayPlanCode, Long amountDueMinor, Long amountPaidMinor,
        Long amountRemainingMinor, String currency, LocalDateTime firstFailedAt, LocalDateTime deadlineAt,
        LocalDateTime nextRetryAt, List<String> allowedActions, String renewalSyncState,
        String reviewState, Replacement replacement) {
    public PaymentRecoverySummary {
        allowedActions = List.copyOf(allowedActions);
    }
    public record Replacement(String orderNo, String state, LocalDateTime expiresAt) {}
}
