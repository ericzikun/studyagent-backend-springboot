package com.studyagent.common.exception;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 额度超限时携带的数据，供转换为 API 响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuotaExceededData {

    private Integer dailyLimit;
    private Integer usedToday;
    private Integer remainingQuota;
    private String quotaResetAt;
}
