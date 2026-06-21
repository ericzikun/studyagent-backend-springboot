package com.studyagent.api.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.studyagent.common.exception.CurrentPlanData;
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
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InsufficientQuotaResponse {

    private CurrentPlanData currentPlan;
    private String reasonCode;
    private String purchaseProductId;
    private String blockedAction;
    private String featureCode;
    private String featureName;
    private String quotaUnit;
    private Long freeBalance;
    private Long freePeriodTotal;
    private Long paidBalance;
    private Long totalAvailable;

    /** DETECT 分句信息：第一个 chunk 需要的 word 数 */
    private Integer firstChunkWords;
    /** DETECT 分句信息：总 chunk 数 */
    private Integer totalChunks;
    /** DETECT 分句信息：总 word 数 */
    private Integer totalWords;
}
