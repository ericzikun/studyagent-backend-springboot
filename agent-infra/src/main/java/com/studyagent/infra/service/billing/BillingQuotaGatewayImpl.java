package com.studyagent.infra.service.billing;

import com.studyagent.service.domain.billing.BillingQuotaGateway;
import com.studyagent.service.domain.quota.AddonGrantService;
import com.studyagent.service.domain.quota.PlanQuotaService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class BillingQuotaGatewayImpl implements BillingQuotaGateway {
    private final PlanQuotaService planQuotaService;
    private final AddonGrantService addonGrantService;

    @Override
    public void resetFromPaidInvoice(
            String clerkUserId,
            String subscriptionId,
            String planCode,
            Instant periodStart,
            Instant periodEnd,
            String invoiceId) {
        planQuotaService.resetFromPaidInvoice(
                clerkUserId, subscriptionId, planCode, periodStart, periodEnd, invoiceId);
    }

    @Override
    public void resetFromPaidInvoice(
            String clerkUserId,
            String subscriptionId,
            String planCode,
            Instant periodStart,
            Instant periodEnd,
            String invoiceId,
            String grantType) {
        planQuotaService.resetFromPaidInvoice(
                clerkUserId, subscriptionId, planCode, periodStart, periodEnd, invoiceId, grantType);
    }

    @Override
    public void addFullPlanForUpgrade(
            String clerkUserId,
            String subscriptionId,
            String planCode,
            Instant periodStart,
            Instant periodEnd,
            String invoiceId) {
        planQuotaService.addFullPlanForUpgrade(
                clerkUserId, subscriptionId, planCode, periodStart, periodEnd, invoiceId);
    }

    @Override
    public void grantUpgradeFromCheckout(
            String clerkUserId,
            String subscriptionId,
            String planCode,
            Instant periodStart,
            Instant periodEnd,
            String upgradeOrderNo) {
        planQuotaService.grantUpgradeFromCheckout(
                clerkUserId, subscriptionId, planCode, periodStart, periodEnd, upgradeOrderNo);
    }

    @Override
    public void clearPlanQuota(String clerkUserId, String subscriptionId, String planCode, String idempotencyKey) {
        planQuotaService.clearPlanQuota(clerkUserId, subscriptionId, planCode, idempotencyKey);
    }

    @Override
    public void grantAddonFromCheckout(
            String clerkUserId,
            String addonCode,
            String stripeSessionId,
            String paymentIntentId,
            Instant paidAt) {
        addonGrantService.grantFromPaidCheckout(
                clerkUserId, addonCode, stripeSessionId, paymentIntentId, paidAt);
    }

    @Override
    public void pauseAddons(String clerkUserId, String subscriptionId, String idempotencyKey) {
        addonGrantService.pauseAll(clerkUserId, subscriptionId, idempotencyKey);
    }

    @Override
    public void resumeEligibleAddons(String clerkUserId, String subscriptionId, String idempotencyKey) {
        addonGrantService.resumeEligible(clerkUserId, subscriptionId, idempotencyKey);
    }
}
