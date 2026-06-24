package com.studyagent.service.domain.billing;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Lightweight billing row for the V2 Bill tab.
 *
 * The record is sourced from local recharge_orders snapshots and intentionally
 * excludes Stripe object ids; invoice details and downloads remain owned by
 * Stripe Customer Portal.
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
}
