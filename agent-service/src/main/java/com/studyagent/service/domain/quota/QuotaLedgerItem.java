package com.studyagent.service.domain.quota;

import java.time.LocalDateTime;

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
        Long paidBalanceAfter,
        LocalDateTime createdAt,
        String featureCode,
        String quotaUnit
) {}
