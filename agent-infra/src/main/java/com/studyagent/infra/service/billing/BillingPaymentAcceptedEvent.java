package com.studyagent.infra.service.billing;

public record BillingPaymentAcceptedEvent(
        String purchaseType,
        String productCode,
        String result) {
}
