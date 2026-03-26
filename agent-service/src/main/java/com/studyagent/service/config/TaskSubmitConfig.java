package com.studyagent.service.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 任务提交配置
 * <p>
 * 支持两套额度体系：
 * 1. AI 额度（user_ai_quotas）：免费+付费，quota-enabled=true 时使用
 * 2. 每日次数限制：dailyLimitPerUser，quota-enabled=false 时使用
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "task.submit")
public class TaskSubmitConfig {

    /**
     * 是否启用 AI 额度体系（user_ai_quotas，免费+付费）
     * true: 使用 QuotaDomainService 扣减额度
     * false: 使用下方的每日次数限制
     */
    private boolean quotaEnabled = true;

    /**
     * 普通用户每日任务提交上限（仅当 quotaEnabled=false 时生效）
     * - 大于 0：启用限额，如 3 表示每天最多提交 3 个任务
     * - 0 或负数：不限制
     */
    private int dailyLimitPerUser = 3;

    /**
     * 是否启用限额（每日次数模式）
     */
    public boolean isLimitEnabled() {
        return !quotaEnabled && dailyLimitPerUser > 0;
    }

    /**
     * 新建草稿（请求未带 draftId）时，相同用户在窗口内、相同内容指纹只落库一次；0 表示关闭幂等。
     */
    private int saveDraftIdempotencyTtlSeconds = 60;
}
