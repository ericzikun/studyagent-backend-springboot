package com.studyagent.api.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Verla SSE 网关配置（见 docs/V2/SSE多Tab连接瓶颈分析与修复方案.md）。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "verla.sse")
public class VerlaSseProperties {

    /**
     * SseEmitter 绝对超时（毫秒）。0 = 永不超时（依赖 idle 清扫或客户端断开）。
     */
    private long emitterTimeoutMs = 0L;

    /** 心跳间隔（毫秒），用于 {@code event:ping} 保活与探活。 */
    private long heartbeatIntervalMs = 15_000L;

    /**
     * 空闲超时（毫秒）：超过该时长未推送业务 {@code verla} 事件则主动 complete。
     * 0 = 关闭 idle 清扫。
     */
    private long idleTimeoutMs = 600_000L;

    /** idle 清扫任务间隔（毫秒）。 */
    private long idleSweepIntervalMs = 60_000L;

    /** 单个 conversation 最大并发 SSE 连接数。 */
    private int maxEmittersPerConversation = 8;

    /**
     * 单个用户（clerkUserId）全局最大 SSE 连接数。0 = 不限制。
     */
    private int maxEmittersPerUser = 16;
}
