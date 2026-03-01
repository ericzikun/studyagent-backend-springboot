package com.studyagent.service.domain.quota;

import java.util.List;

/**
 * 额度流水分页结果
 */
public record QuotaLedgerPageResult(List<QuotaLedgerItem> items, long total) {}
