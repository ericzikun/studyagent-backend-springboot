package com.studyagent.service.domain.billing;

/** Read-only contract shared by billing, quota and feature admission. */
public interface PaymentRecoveryAccessService {
    PaymentRecoveryAccess resolve(String clerkUserId, String currentSubscriptionId);

    /** Current reads in the caller's transaction, acquiring user then recovery locks. */
    default PaymentRecoveryAccess resolveForUpdate(String clerkUserId, String currentSubscriptionId) {
        return resolve(clerkUserId, currentSubscriptionId);
    }
}
