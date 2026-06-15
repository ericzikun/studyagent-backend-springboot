package com.studyagent.service.domain.quota;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 额度流水条目（含展示文案、功能编码、额度单位）
 */
public record QuotaLedgerItem(
        Long id,
        String ledgerNo,
        String ledgerType,
        Long amount,
        String sourceType,
        String sourceId,
        String displayText,
        Long freeBalanceAfter,
        Long planBalanceAfter,
        Long addonBalanceAfter,
        Long legacyBalanceAfter,
        LocalDateTime createdAt,
        String featureCode,
        String quotaUnit,
        List<Map<String, Object>> allocations
) {
    public Long paidBalanceAfter() {
        return legacyBalanceAfter;
    }
}
