package com.studyagent.service.domain.billing;

/**
 * Outcome of the one-off remediation for subscriptions that converted out of the retired Pro Trial
 * yearly SKU and therefore keep renewing as an annual plan.
 */
public enum ConvertedYearlyTrialMigrationOutcome {
    /** The unpaid annual invoice was voided and the subscription switched to monthly, charged now. */
    VOIDED_AND_SWITCHED_TO_MONTHLY,
    /** The unpaid annual invoice was voided; the scheduled cancellation is respected as-is. */
    VOIDED_ONLY_CANCEL_AT_PERIOD_END,
    /** Nothing was unpaid, so the monthly switch was scheduled for the end of the paid annual term. */
    SCHEDULED_MONTHLY_AT_PERIOD_END,
    /** Not a converted yearly Pro Trial subscription, or there was nothing to correct. */
    SKIPPED
}
