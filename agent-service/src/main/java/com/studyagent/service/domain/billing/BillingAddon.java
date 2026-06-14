package com.studyagent.service.domain.billing;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class BillingAddon {
    private String addonCode;
    private String featureCode;
    private String stripeProductId;
    private String stripePriceId;
    private Long quotaAmount;
    private Integer validityMonths;
    private Integer priceCents;
    private String currency;
}
