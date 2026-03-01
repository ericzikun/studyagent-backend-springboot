package com.studyagent.api.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AI 额度不足时的响应体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InsufficientQuotaResponse {

    private String featureCode;
    private String featureName;
    private String quotaUnit;
    private Long freeBalance;
    private Long freePeriodTotal;
    private Long paidBalance;
    private Long totalAvailable;
}
