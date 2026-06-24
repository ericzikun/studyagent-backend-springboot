package com.studyagent.infra.service.billing;

import com.studyagent.infra.entity.SubscriptionPlanEntity;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

public final class UpgradeChargeCalculator {
    private UpgradeChargeCalculator() {
    }

    public static UpgradeChargeQuote quote(
            SubscriptionPlanEntity currentPlan,
            SubscriptionPlanEntity targetPlan,
            LocalDateTime quotaPeriodStart,
            LocalDateTime currentPeriodEnd,
            LocalDateTime now) {
        String currentInterval = currentPlan.getBillingInterval();
        String targetInterval = targetPlan.getBillingInterval();
        if ("month".equals(currentInterval) && "month".equals(targetInterval)) {
            return UpgradeChargeQuote.builder()
                    .amountCents(targetPlan.getPriceCents())
                    .chargeType("monthly_full")
                    .remainingAnnualMonthsExcludingCurrent(0)
                    .pricingFormula("target_monthly_full")
                    .build();
        }
        if ("month".equals(currentInterval) && "year".equals(targetInterval)) {
            return UpgradeChargeQuote.builder()
                    .amountCents(targetPlan.getPriceCents())
                    .chargeType("annual_full")
                    .remainingAnnualMonthsExcludingCurrent(0)
                    .pricingFormula("target_annual_full")
                    .build();
        }

        int remainingMonths = calculateRemainingAnnualMonthsExcludingCurrent(quotaPeriodStart, currentPeriodEnd, now);
        int credit = (currentPlan.getPriceCents() * remainingMonths) / 12;
        int amountCents = Math.max(targetPlan.getPriceCents() - credit, 0);
        boolean fullAnnualCharge = remainingMonths == 0 && amountCents == targetPlan.getPriceCents();
        return UpgradeChargeQuote.builder()
                .amountCents(amountCents)
                .chargeType(fullAnnualCharge ? "annual_full" : "annual_diff")
                .remainingAnnualMonthsExcludingCurrent(remainingMonths)
                .pricingFormula(fullAnnualCharge
                        ? "target_annual_full"
                        : "target_annual_full_minus_current_annual_credit")
                .build();
    }

    static int calculateRemainingAnnualMonthsExcludingCurrent(
            LocalDateTime quotaPeriodStart,
            LocalDateTime currentPeriodEnd,
            LocalDateTime now) {
        if (quotaPeriodStart == null || currentPeriodEnd == null || !quotaPeriodStart.isBefore(currentPeriodEnd)) {
            return 0;
        }
        LocalDateTime currentWindowStart = quotaPeriodStart;
        while (!currentWindowStart.plusMonths(1).isAfter(now)) {
            currentWindowStart = currentWindowStart.plusMonths(1);
        }
        LocalDateTime nextWindowStart = currentWindowStart.plusMonths(1);
        if (!nextWindowStart.isBefore(currentPeriodEnd)) {
            return 0;
        }
        long remaining = ChronoUnit.MONTHS.between(nextWindowStart, currentPeriodEnd);
        return (int) Math.max(remaining, 0);
    }
}
