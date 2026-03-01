package com.studyagent.common.exception;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AI 额度不足时携带的数据，供转换为 API 响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InsufficientQuotaData {

    private String featureCode;
    private String featureName;
    private String quotaUnit;
    private Long freeBalance;
    private Long freePeriodTotal;
    private Long paidBalance;
    private Long totalAvailable;
}
