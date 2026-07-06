package com.studyagent.service.domain.billing;

import lombok.Builder;
import lombok.Value;

/**
 * V2 商业化 Checkout 相关机器人通知请求（subscription / addon / manual upgrade）。
 */
@Value
@Builder
public class BillingCheckoutNotifyRequest {
    String stripeEventId;
    String stripeEventType;
    String sessionId;
    String clerkUserId;
    /** subscription / addon / subscription_upgrade_manual */
    String purchaseType;
    String planCode;
    String targetPlanCode;
    String addonCode;
    String featureCode;
    long quotaAmount;
    int priceCents;
    String currency;
    String customerEmail;
    String paymentIntentId;
}
