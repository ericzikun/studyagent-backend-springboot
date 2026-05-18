package com.studyagent.service.domain.verla;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Verla Agent Tool 调用 trace 领域对象（V2）。
 * <p>
 * 对应 {@code verla_tool_calls} 表，由 {@code AGENT_TOOL_CALL_RECORDED} 事件驱动 upsert。
 * 详见 docs/V2/5.1 Java后端 + 数据库 V2 升级技术方案.md §3 / §4。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerlaToolCall {

    private Long id;
    /** 业务唯一 ID（call_*），Py 生成，整次调用生命周期不变 */
    private String toolCallId;
    private Long conversationId;
    private Long turnId;
    private Long sessionId;
    /** Py 层 step UUID（与 verla_event_inbox.step_id 对齐，可空） */
    private String stepId;
    /** 嵌套调用父引用，可空 */
    private String parentCallId;
    private String agentName;
    private String toolName;
    /** {@link com.studyagent.common.verla.enums.VerlaToolStatus} 字符串 */
    private String status;
    /** {@link com.studyagent.common.verla.enums.VerlaToolVisibility} 字符串 */
    private String visibility;
    /** JSON 字符串：脱敏入参 */
    private String toolInputJson;
    /** JSON 字符串：脱敏出参 */
    private String toolOutputJson;
    /** 1 句话总结 */
    private String summary;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Integer durationMs;
    /** JSON 字符串：token / model / cost / 是否裁剪等 */
    private String metaJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
