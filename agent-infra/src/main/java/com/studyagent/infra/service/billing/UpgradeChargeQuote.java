package com.studyagent.infra.service.billing;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class UpgradeChargeQuote {
    int amountCents;
    String chargeType;
    int remainingAnnualMonthsExcludingCurrent;
    String pricingFormula;
    int currentNetPaidCents;
    String sourceInvoiceId;
}
