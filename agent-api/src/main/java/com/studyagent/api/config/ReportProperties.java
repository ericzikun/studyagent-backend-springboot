package com.studyagent.api.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 数据日报/周报推送与手动触发配置
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "report")
public class ReportProperties {

    /** 是否启用定时推送（日报 12:00 / 周报周日 12:00，均为北京时间） */
    private boolean schedulingEnabled = true;

    /**
     * 手动触发接口鉴权（请求头 X-Report-Token）。
     * 未配置时禁止手动触发（仅定时任务仍可按 schedulingEnabled 执行）。
     */
    private String manualTriggerToken = "";

    /** 日报/周报标题前缀 */
    private String titlePrefix = "Verla 数据";
}
