package com.studyagent.infra.job;

import com.studyagent.service.domain.billing.BillingDomainService;
import com.studyagent.service.domain.billing.ConvertedYearlyTrialMigrationOutcome;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * One-off remediation for users whose Pro Trial yearly SKU ({@code pro_trial_to_yearly}) had already
 * converted into a formal annual plan before that SKU was retired, so they keep renewing yearly
 * instead of the intended monthly plan.
 *
 * <p>Candidates are an explicit uid allowlist taken from configuration: the blast radius is exactly
 * what the operator types, and no rule can accidentally catch an ordinary annual subscriber.
 * Everything else happens in {@link BillingDomainService#migrateConvertedYearlyTrialToMonthly},
 * which voids unpaid annual invoices, respects an already scheduled cancellation and either switches
 * the subscription to monthly right away or schedules that switch for the end of the paid annual
 * term. Local plan state is synced by the existing Stripe webhooks, so this job writes no billing
 * state of its own.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConvertedYearlyTrialMonthlyMigrationScheduler {
    private final BillingDomainService billingDomainService;

    @Value("${billing.converted-yearly-trial-monthly-migration.enabled:false}")
    private boolean migrationEnabled;

    @Value("${billing.converted-yearly-trial-monthly-migration.user-ids:}")
    private String migrationUserIds;

    @Scheduled(cron = "${billing.converted-yearly-trial-monthly-migration.cron:0 55 * * * ?}")
    public void migrateConfiguredUsers() {
        if (!migrationEnabled || migrationUserIds == null || migrationUserIds.isBlank()) {
            return;
        }
        for (String rawUserId : migrationUserIds.split(",")) {
            String clerkUserId = rawUserId.trim();
            if (clerkUserId.isEmpty()) {
                continue;
            }
            try {
                ConvertedYearlyTrialMigrationOutcome outcome =
                        billingDomainService.migrateConvertedYearlyTrialToMonthly(clerkUserId);
                log.info("[Billing/convertedAnnualTrialMigration] user={} outcome={}", clerkUserId, outcome);
            } catch (RuntimeException e) {
                log.warn("[Billing/convertedAnnualTrialMigration] user={} failed: {}", clerkUserId, e.getMessage());
            }
        }
    }
}
