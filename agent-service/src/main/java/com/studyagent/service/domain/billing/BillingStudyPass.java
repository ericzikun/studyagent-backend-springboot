package com.studyagent.service.domain.billing;

import lombok.Builder;
import lombok.Data;

/** Study Pass 商品目录（订阅及历史一次性商品），随 {@code /v1/billing/config} 下发。 */
@Data
@Builder
public class BillingStudyPass {
    private String passCode;
    private String billingType;
    private String stripeProductId;
    private String stripePriceId;
    private Integer priceCents;
    private String currency;
    private Integer validityDays;
}
