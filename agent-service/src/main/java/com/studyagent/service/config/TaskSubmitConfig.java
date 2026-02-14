package com.studyagent.service.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 任务提交配置
 * <p>
 * 用于控制普通用户每日任务提交额度，管理员不受限制
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "task.submit")
public class TaskSubmitConfig {

    /**
     * 普通用户每日任务提交上限
     * <p>
     * - 大于 0：启用限额，如 3 表示每天最多提交 3 个任务
     * - 0 或负数：不限制
     */
    private int dailyLimitPerUser = 3;

    /**
     * 是否启用限额
     */
    public boolean isLimitEnabled() {
        return dailyLimitPerUser > 0;
    }
}
