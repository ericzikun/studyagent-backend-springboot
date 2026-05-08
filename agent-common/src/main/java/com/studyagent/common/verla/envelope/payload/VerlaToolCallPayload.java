package com.studyagent.common.verla.envelope.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * {@code AGENT_TOOL_CALL_RECORDED} 事件 payload。
 * <p>
 * Py 在工具调用的关键节点（开始 / 结束 / 失败）发送一次该事件，Java 侧 upsert
 * {@code verla_tool_calls}。详见 docs/V2/5.1 §4.1。
 * <p>
 * 反序列化容错：未知字段忽略，便于 Py 协议演进。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class VerlaToolCallPayload {

    /** 业务唯一 ID（call_*），整次调用生命周期内不变 */
    private String toolCallId;

    /** 嵌套调用父引用，可空 */
    private String parentCallId;

    /** planner / homework_agent / file_summary ... */
    private String agentName;

    /** web_search / pdf_extract ... */
    private String toolName;

    /** PENDING / RUNNING / SUCCEEDED / FAILED / CANCELLED */
    private String status;

    /** INTERNAL / USER_VISIBLE，缺省 INTERNAL */
    private String visibility;

    /** 工具入参（脱敏后） */
    private Map<String, Object> toolInput;

    /** 工具出参摘要（脱敏后） */
    private Map<String, Object> toolOutput;

    /** 1 句话总结，给 trace 列表展示 */
    private String summary;

    private String errorCode;
    private String errorMessage;

    private Instant startedAt;
    private Instant finishedAt;
    private Integer durationMs;

    /** token / model / cost / 是否裁剪等 */
    private Map<String, Object> meta;
}
