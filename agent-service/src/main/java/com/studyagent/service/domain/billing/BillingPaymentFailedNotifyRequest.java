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
    /** 合同/订单上的购买国家（ISO alpha-2），用于付费播报展示。 */
    String pricingCountry;
}
