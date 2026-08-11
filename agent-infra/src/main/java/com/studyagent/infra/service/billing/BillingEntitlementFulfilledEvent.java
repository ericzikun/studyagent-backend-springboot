package com.studyagent.infra.service.billing;

public record BillingEntitlementFulfilledEvent(
        String purchaseType,
        String productCode,
        String result) {
}
