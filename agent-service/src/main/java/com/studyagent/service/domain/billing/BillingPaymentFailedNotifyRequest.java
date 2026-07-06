package com.studyagent.service.domain.billing;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class BillingPaymentFailedNotifyRequest {
    String notifyEventId;
    String clerkUserId;
    String purchaseType;
    String planCode;
    String addonCode;
    int priceCents;
    String currency;
    String paymentIntentId;
    String invoiceId;
    String failureReason;
    String stripeEventType;
}
