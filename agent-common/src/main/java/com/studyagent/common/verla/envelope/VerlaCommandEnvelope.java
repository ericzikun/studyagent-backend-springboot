package com.studyagent.common.verla.envelope;

import com.studyagent.common.verla.enums.VerlaCommandAction;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * Verla Java→Py 命令信封
 * <p>
 * 对应文档 docs/verla-Java侧MVP技术方案.md §6.1
 * <pre>
 * {
 *   "schemaVersion": 1,
 *   "messageId": "cmd_01JR00X",
 *   "correlationId": "conv:1001:turn:55:sess:9001",
 *   "orderingKey": "session:9001",
 *   "action": "cmd.agent.run",
 *   "timestamp": "...",
 *   "producer": {...},
 *   "conversation": {...},
 *   "turn": {...},
 *   "session": {...},
 *   "payload": {...}
 * }
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerlaCommandEnvelope {

    /** 信封 schema 版本，当前 = 1 */
    @Builder.Default
    private Integer schemaVersion = 1;

    /** 全局唯一命令 ID（Java 生成，ULID） */
    private String messageId;

    /** conv:{c}:turn:{t}:sess:{s} */
    private String correlationId;

    /** session:{sessionId}，MQ 内部 hash 分片用 */
    private String orderingKey;

    /** 命令动作；与 enum {@link VerlaCommandAction} 的 code 一致 */
    private String action;

    /** 命令产生时间 */
    private Instant timestamp;

    /** 发起方信息 */
    private VerlaProducerInfo producer;

    private VerlaConversationRef conversation;
    private VerlaTurnRef turn;
    private VerlaSessionRef session;

    /**
     * 业务 payload（按 action 不同结构不同）
     * <p>
     * 例如 cmd.agent.run 时包含 agentType / contextRef / options 等
     */
    private Map<String, Object> payload;
}
