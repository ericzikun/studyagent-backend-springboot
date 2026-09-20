package com.studyagent.infra.job;

import com.studyagent.service.domain.billing.BillingDomainService;
import com.studyagent.service.domain.billing.ConvertedYearlyTrialMigrationOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConvertedYearlyTrialMonthlyMigrationSchedulerTest {

    @Mock
    private BillingDomainService billingDomainService;

    @Test
    void migratesEveryConfiguredUser() {
        when(billingDomainService.migrateConvertedYearlyTrialToMonthly("user_a"))
                .thenReturn(ConvertedYearlyTrialMigrationOutcome.VOIDED_AND_SWITCHED_TO_MONTHLY);
        when(billingDomainService.migrateConvertedYearlyTrialToMonthly("user_b"))
                .thenReturn(ConvertedYearlyTrialMigrationOutcome.VOIDED_ONLY_CANCEL_AT_PERIOD_END);

        scheduler(true, "user_a, user_b").migrateConfiguredUsers();

        verify(billingDomainService).migrateConvertedYearlyTrialToMonthly("user_a");
        verify(billingDomainService).migrateConvertedYearlyTrialToMonthly("user_b");
    }

    @Test
    void doesNothingWhenDisabledOrUnconfigured() {
        scheduler(false, "user_a").migrateConfiguredUsers();
        scheduler(true, "").migrateConfiguredUsers();

        verify(billingDomainService, never()).migrateConvertedYearlyTrialToMonthly(anyString());
    }

    @Test
    void keepsMigratingRemainingUsersWhenOneFails() {
        when(billingDomainService.migrateConvertedYearlyTrialToMonthly("user_bad"))
                .thenThrow(new IllegalStateException("stripe unavailable"));
        when(billingDomainService.migrateConvertedYearlyTrialToMonthly("user_ok"))
                .thenReturn(ConvertedYearlyTrialMigrationOutcome.SKIPPED);

        scheduler(true, "user_bad,user_ok").migrateConfiguredUsers();

        verify(billingDomainService).migrateConvertedYearlyTrialToMonthly("user_ok");
    }

    private ConvertedYearlyTrialMonthlyMigrationScheduler scheduler(boolean enabled, String userIds) {
        ConvertedYearlyTrialMonthlyMigrationScheduler scheduler =
                new ConvertedYearlyTrialMonthlyMigrationScheduler(billingDomainService);
        ReflectionTestUtils.setField(scheduler, "migrationEnabled", enabled);
        ReflectionTestUtils.setField(scheduler, "migrationUserIds", userIds);
        return scheduler;
    }
}
