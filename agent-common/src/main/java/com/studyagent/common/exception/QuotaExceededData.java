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
    /** 额度重置时间（服务器时区） */
    private String quotaResetAt;
    /** 额度重置时间（UTC），供海外用户自行换算 */
    private String quotaResetAtUtc;
}
