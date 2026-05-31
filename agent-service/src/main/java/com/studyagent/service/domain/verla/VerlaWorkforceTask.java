package com.studyagent.service.domain.verla;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Verla Workforce 子任务状态快照领域对象。
 * <p>
 * 对应 {@code verla_workforce_tasks} 表，由
 * {@code ASSIGNMENT_AGENT_NODE_UPDATED} 事件驱动 upsert。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerlaWorkforceTask {

    private Long id;
    private Long conversationId;
    private Long turnId;
    private Long sessionId;

    /** "assignment-plan"、"task-{camel_task_id}" 或 "compose-progress"，session 内唯一 */
    private String nodeId;
    /** CAMEL 原始 task_id，plan / compose 节点为 null */
    private String camelTaskId;
    /** plan / task / compose */
    private String nodeKind;

    private String taskName;
    private String taskType;
    private String description;
    private String taskAgent;

    /** queued / running / completed / failed */
    private String status;
    private String content;

    /** plan 节点的 steps 数组（JSON 字符串） */
    private String planStepsJson;
    private Integer planTaskCount;

    /** compose 节点：当前已完成的 compose 轮次 */
    private Integer composeCurrentRound;
    /** compose / plan 节点：compose 总轮次 */
    private Integer composeTotalRounds;

    private Integer sortOrder;

    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private Integer processingTimeMs;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
