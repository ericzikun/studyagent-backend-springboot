package com.studyagent.service.domain.quota;

import java.time.Instant;

public interface PlanQuotaService {
    void resetFromPaidInvoice(
            String clerkUserId,
            String subscriptionId,
            String planCode,
            Instant quotaPeriodStart,
            Instant quotaPeriodEnd,
            String invoiceId
    );

    void addFullPlanForUpgrade(
            String clerkUserId,
            String subscriptionId,
            String planCode,
            Instant quotaPeriodStart,
            Instant quotaPeriodEnd,
            String invoiceId
    );

    void clearPlanQuota(String clerkUserId, String subscriptionId, String idempotencyKey);
}
