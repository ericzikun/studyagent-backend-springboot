package com.studyagent.api.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 任务提交额度超限时的响应体
 * <p>
 * 前端可通过 meta.statusCode === 1010 判断为额度超限，使用 data 中的额度信息展示提示
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubmitQuotaExceededResponse {

    /**
     * 每日限额
     */
    private Integer dailyLimit;

    /**
     * 今日已使用次数（已达上限）
     */
    private Integer usedToday;

    /**
     * 剩余可用次数（超限时为 0）
     */
    private Integer remainingQuota;

    /**
     * 额度重置时间（服务器时区），格式：yyyy-MM-dd HH:mm:ss
     */
    private String quotaResetAt;

    /**
     * 额度重置时间（UTC），格式：yyyy-MM-dd HH:mm:ss UTC，供海外用户自行换算
     */
    private String quotaResetAtUtc;
}
