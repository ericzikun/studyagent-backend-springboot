package com.studyagent.service.domain.billing;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Lightweight billing row for the V2 Bill tab.
 *
 * The record is sourced from local recharge_orders snapshots and intentionally
 * excludes Stripe object ids; hosted invoice details are resolved on demand.
 * hostedInvoiceAvailable only says the row already has a stored Stripe invoice,
 * or belongs to a billing flow whose Stripe reference can resolve to one; it
 * does not expose that reference.
 */
@Data
@Builder
public class BillingRecordResult {
    private String id;
    private LocalDateTime paidAt;
    private Integer amountCents;
    private String currency;
    private String status;
    private String orderType;
    private boolean hostedInvoiceAvailable;
}
