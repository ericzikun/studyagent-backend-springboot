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

    /** DETECT 分句信息：第一个 chunk 需要的 word 数 */
    private Integer firstChunkWords;
    /** DETECT 分句信息：总 chunk 数 */
    private Integer totalChunks;
    /** DETECT 分句信息：总 word 数 */
    private Integer totalWords;
}
