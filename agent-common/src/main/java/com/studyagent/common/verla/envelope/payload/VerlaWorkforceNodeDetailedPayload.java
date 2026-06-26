package com.studyagent.common.verla.envelope.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * {@code ASSIGNMENT_AGENT_NODE_DETAILED} 事件 payload。
 * <p>
 * 由 Python {@code VerlaWorkforceCallback.log_task_started / log_task_completed} 发出，
 * 携带任务节点的流式 detail 内容和产出文本。Java 侧 upsert
 * {@code verla_workforce_task_outputs}（result_text 追加，detail_items_json 合并）。
 * 当任务重试时，Python 会先发 {@code reset=true}，Java 侧先清空该节点上一轮失败
 * 的 result_text/detail_items_json，再写入同一事件里的增量。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class VerlaWorkforceNodeDetailedPayload {

    /** 对应 verla_workforce_tasks.node_id，格式："task-{camel_task_id}" */
    private String id;

    /** queued / running / retrying / completed / failed */
    private String status;

    private String taskName;
    private String taskAgent;

    /** 流式 detail 增量：[{type, name}]，每次事件携带当次 chunk，Java 侧累积合并 */
    private List<Map<String, Object>> detailChunk;

    /** 任务产出文本增量（result_summary），Java 侧追加写入 result_text */
    private String contentChunk;

    /** true 表示重试开始，先清空旧 Output 和 Detailed process 再应用本事件增量 */
    private Boolean reset;

    private String startStamp;

    /** 任务完成后的实际耗时毫秒；Java 侧同步到任务表，供 snapshot 恢复详情用时。 */
    private Integer durationMs;
}
