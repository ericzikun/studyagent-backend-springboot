package com.studyagent.common.verla.envelope.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * {@code ASSIGNMENT_AGENT_NODE_UPDATED} 事件 payload。
 * <p>
 * 由 Python {@code VerlaWorkforceCallback} 在任务生命周期各节点（created / started /
 * completed / failed / decomposed）发出。Java 侧 upsert {@code verla_workforce_tasks}。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class VerlaWorkforceNodeUpdatedPayload {

    private Node node;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Node {
        /** "assignment-plan"、"task-{camel_task_id}" 或 "compose-progress" */
        private String id;
        /**
         * 节点类型（Python 显式携带，优先于 id 推断）：
         * "plan" — 规划节点；"task" — 子任务节点；"compose" — Compose 进度节点
         */
        private String nodeType;
        private String taskName;
        private String taskAgent;
        /** queued / running / completed / failed */
        private String status;
        /** 任务描述或错误信息 */
        private String content;
        /** plan 节点专用：所有已分解子任务列表 */
        private List<Map<String, Object>> steps;
        /** CAMEL processing_time_seconds，task 节点完成时携带 */
        private Double processingTimeSeconds;
        /** compose 节点：当前已完成的 compose 轮次 */
        private Integer composeCurrentRound;
        /** plan / compose 节点：compose 总轮次 */
        private Integer composeTotalRounds;
    }
}
