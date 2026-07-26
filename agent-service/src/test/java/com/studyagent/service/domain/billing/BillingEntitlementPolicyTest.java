package com.studyagent.service.domain.billing;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class BillingEntitlementPolicyTest {

    @Test
    void activeAndTrialingAllowRefreshAndPaidConsumption() {
        assertThat(BillingEntitlementPolicy.allowsPlanRefresh("active")).isTrue();
        assertThat(BillingEntitlementPolicy.allowsPlanRefresh("trialing")).isTrue();
        assertThat(BillingEntitlementPolicy.allowsPaidEntitlementConsumption(
                "active", null, LocalDateTime.parse("2026-07-15T10:00:00"))).isTrue();
    }

    @Test
    void pastDueGraceAllowsExistingConsumptionButNeverRefresh() {
        LocalDateTime now = LocalDateTime.parse("2026-07-15T10:00:00");

        assertThat(BillingEntitlementPolicy.allowsPlanRefresh("past_due")).isFalse();
        assertThat(BillingEntitlementPolicy.allowsPaidEntitlementConsumption(
                "past_due", now.plusDays(1), now)).isTrue();
        assertThat(BillingEntitlementPolicy.allowsPaidEntitlementConsumption(
                "past_due", now.minusSeconds(1), now)).isFalse();
    }

    @Test
    void terminalAndSuspendedStatusesRejectPaidEntitlements() {
        LocalDateTime now = LocalDateTime.parse("2026-07-15T10:00:00");

        for (String status : new String[]{"free", "incomplete", "incomplete_expired", "unpaid", "paused", "canceled"}) {
            assertThat(BillingEntitlementPolicy.allowsPlanRefresh(status)).isFalse();
            assertThat(BillingEntitlementPolicy.allowsPaidEntitlementConsumption(status, null, now)).isFalse();
        }
    }

    @Test
    void recoverablePaymentStatusesRequireResolutionBeforePlanChanges() {
        for (String status : new String[]{"past_due", "unpaid", "incomplete"}) {
            assertThat(BillingEntitlementPolicy.requiresPaymentResolution(status)).isTrue();
        }
        for (String status : new String[]{
                "active", "trialing", "incomplete_expired", "paused", "canceled", "free", null
        }) {
            assertThat(BillingEntitlementPolicy.requiresPaymentResolution(status)).isFalse();
        }
    }
}
