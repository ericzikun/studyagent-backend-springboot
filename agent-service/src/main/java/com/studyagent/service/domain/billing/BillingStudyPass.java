package com.studyagent.service.domain.billing;

import lombok.Builder;
import lombok.Data;

/** Study Pass 一次性购买项的目录定义，随 {@code /v1/billing/config} 下发。 */
@Data
@Builder
public class BillingStudyPass {
    private String passCode;
    private String stripeProductId;
    private String stripePriceId;
    private Integer priceCents;
    private String currency;
    private Integer validityDays;
}
