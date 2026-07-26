package com.studyagent.service.domain.billing;

/**
 * Local entitlement state derived from Stripe subscription lifecycle fields.
 */
public enum BillingAccessState {
    ACTIVE,
    ACTIVE_ENDING,
    GRACE,
    PAYMENT_PENDING,
    SUSPENDED,
    TERMINATED
}
