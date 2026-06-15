package com.studyagent.service.domain.quota;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

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
        long planBalance,
        LocalDateTime planPeriodEnd,
        long addonBalance,
        List<Map<String, Object>> addonItems,
        long legacyBalance,
        long totalAvailable
) {
    public long paidBalance() {
        return planBalance + addonBalance + legacyBalance;
    }
}
