package com.studyagent.service.domain.billing;

public record RecoveryReplacementCheckoutCommand(String recoveryId, String targetPlanCode,
        String successUrl, String cancelUrl, String resumeToken, String quoteVersion,
        String expectedCurrency, Integer expectedAmountCents) {}
