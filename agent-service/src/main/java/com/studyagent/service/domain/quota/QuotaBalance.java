package com.studyagent.service.domain.quota;

import java.time.LocalDateTime;

/**
 * 用户额度余额（某功能点）
 */
public record QuotaBalance(
        String featureCode,
        String featureName,
        String quotaUnit,
        long freeBalance,
        long freePeriodTotal,
        LocalDateTime freePeriodEnd,
        long paidBalance,
        long totalAvailable
) {}
