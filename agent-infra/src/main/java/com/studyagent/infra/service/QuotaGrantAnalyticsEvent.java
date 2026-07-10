package com.studyagent.infra.service;

import java.time.LocalDateTime;

public record QuotaGrantAnalyticsEvent(
        String clerkUserId,
        String grantType,
        String featureCode,
        long quotaAmount,
        String planCode,
        String addonCode,
        String sourceType,
        String sourceId,
        String idempotencyKey,
        LocalDateTime quotaPeriodStart,
        LocalDateTime quotaPeriodEnd) {
}
