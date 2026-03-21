package com.studyagent.service.application.dto;

/**
 * Stop 任务应用层结果（供 API 层组装响应）
 *
 * @param internalTaskId        内部数字 ID
 * @param taskStatusCode        {@link com.studyagent.service.domain.task.TaskStatus#getCode()}
 * @param workflowAvailable     是否仍应按「可执行 workflow」处理（停止为草稿后为 false）
 * @param draftResetJustApplied 本次请求是否执行了「清执行态 + 转 DRAFT」（用于区分幂等「已是草稿」）
 */
public record StopTaskApplicationResult(
        long internalTaskId,
        int taskStatusCode,
        boolean workflowAvailable,
        boolean draftResetJustApplied) {
}
