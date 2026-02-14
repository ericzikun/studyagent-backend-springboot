package com.studyagent.api.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 任务提交额度信息
 * <p>
 * 用于提交成功时返回剩余额度，或超限时返回当前额度状态供前端展示
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubmitQuotaInfo {

    /**
     * 每日限额
     */
    private Integer dailyLimit;

    /**
     * 今日已使用次数（含本次提交）
     */
    private Integer usedToday;

    /**
     * 剩余可用次数
     */
    private Integer remainingQuota;

    /**
     * 额度重置时间（次日零点），格式：yyyy-MM-dd HH:mm:ss
     */
    private String quotaResetAt;
}
