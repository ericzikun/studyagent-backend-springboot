package com.studyagent.service.domain.billing;

import lombok.Builder;
import lombok.Data;

/**
 * Short-lived Stripe-hosted invoice page link for a single owned billing record.
 */
@Data
@Builder
public class BillingHostedInvoiceResult {
    private String url;
}
