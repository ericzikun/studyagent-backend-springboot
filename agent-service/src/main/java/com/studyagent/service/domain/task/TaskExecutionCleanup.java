package com.studyagent.service.domain.task;

/**
 * 清理任务 workflow 执行态（子任务、Agent、活动、输出等），并将主任务行重置为草稿态所需字段。
 * 不删除任务主表、不删除任务与文件的关联。
 */
public interface TaskExecutionCleanup {

    /**
     * 删除执行期附属数据，并将 tasks 行更新为 DRAFT，清空进度与结果类字段。
     *
     * @param taskId 内部任务主键
     */
    void resetTaskToDraftAndClearExecution(long taskId);
}
