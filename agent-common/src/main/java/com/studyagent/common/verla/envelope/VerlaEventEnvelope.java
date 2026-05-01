package com.studyagent.common.verla.envelope;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * Verla Py→Java 事件信封
 * <p>
 * 对应文档 docs/verla-Java侧MVP技术方案.md §6.2
 * <pre>
 * {
 *   "schemaVersion": 1,
 *   "messageId": "evt_01JR00Y",
 *   "eventId": "evt_01JR00Y",
 *   "correlationId": "conv:1001:turn:55:sess:9001",
 *   "orderingKey": "session:9001",
 *   "eventType": "AGENT_STEP_STREAM_CHUNK",
 *   "routingKey": "verla.event.s01.agent.step.stream_chunk",
 *   "eventSeq": 42,
 *   ...
 * }
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerlaEventEnvelope {

    @Builder.Default
    private Integer schemaVersion = 1;

    /** 全局唯一事件 ID（Py 生成，ULID） */
    private String messageId;

    /** 与 messageId 等价（兼容 Py 同学命名习惯） */
    private String eventId;

    /** conv:{c}:turn:{t}:sess:{s} */
    private String correlationId;

    /** session:{sessionId} */
    private String orderingKey;

    /**
     * 事件类型（必须可由 {@link com.studyagent.common.verla.enums.VerlaAgentEventType#valueOf} 解析）
     */
    private String eventType;

    /**
     * 路由键：verla.event.s{shard}.{xxx}
     */
    private String routingKey;

    /**
     * session 内单调递增（保序的唯一权威依据）
     */
    private Long eventSeq;

    @JsonDeserialize(using = LenientInstantDeserializer.class)
    private Instant timestamp;

    private VerlaProducerInfo producer;

    private VerlaConversationRef conversation;
    private VerlaTurnRef turn;
    private VerlaSessionRef session;

    /**
     * 可选：仅多 sub-agent 流式事件携带
     */
    private VerlaStepRef step;

    /**
     * 业务 payload（按 eventType 不同结构不同）
     */
    private Map<String, Object> payload;
}
