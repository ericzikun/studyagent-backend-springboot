package com.studyagent.service.domain.verla;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Verla Workforce 子任务产出内容领域对象。
 * <p>
 * 对应 {@code verla_workforce_task_outputs} 表，与 {@code verla_workforce_tasks} 1:1，
 * 由 {@code ASSIGNMENT_AGENT_NODE_DETAILED} 事件驱动 upsert。
 * <p>
 * {@code resultText} 追加写入（contentChunk 累积），
 * {@code detailItemsJson} 合并写入（detailChunk 数组追加）。重试开始时事件会携带
 * {@code reset=true}，仓储先清空上一轮失败的 Output 和 Detailed process，再应用本次增量。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerlaWorkforceTaskOutput {

    private Long id;
    private Long conversationId;
    private Long turnId;
    private Long sessionId;

    /** 对应 verla_workforce_tasks.node_id */
    private String nodeId;

    /** 任务最终产出文本（result_summary 累积） */
    private String resultText;

    /** detailChunk 数组累积（JSON 字符串）：[{type, name}] */
    private String detailItemsJson;

    /** 仅写路径使用：true 表示 upsert 前先清空该节点已有 resultText/detailItemsJson */
    private Boolean reset;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
