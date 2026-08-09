package com.studyagent.service.domain.billing;

import lombok.Builder;
import lombok.Data;

/**
 * Nested Basic paid-trial account state for frontend {@code basicTrial} field.
 */
@Data
@Builder
public class BasicTrialAccount {
    /** eligible | used | active_trial | active_subscription | unknown */
    private String eligibility;
    private Boolean active;
    private Boolean used;
    private Boolean eligible;
    private java.time.LocalDateTime endsAt;
    private String convertsToPlanCode;
    /** month | year */
    private String convertsToBillingInterval;
}
