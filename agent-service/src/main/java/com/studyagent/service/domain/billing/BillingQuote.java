package com.studyagent.service.domain.billing;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

/** 服务端购买报价；确认值只检测变化，不赋予选币或改价权限。 */
@Data
@Builder
public class BillingQuote {
    private String kind;
    private String skuCode;
    private String action;
    private String currency;
    private Integer amountCents;
    private String quoteVersion;
    private String billingInterval;
    private Integer trialDays;
    private Integer renewalAmountCents;
    private String renewalCurrency;
    private LocalDateTime effectiveAt;
}
