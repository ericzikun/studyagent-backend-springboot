package com.studyagent.service.domain.task;

/**
 * 任务文件关联Repository接口
 */
public interface TaskFileRepository {
    /**
     * 关联文件到任务
     * @param taskId 任务ID
     * @param fileId 文件ID
     * @param fileOrder 文件顺序
     */
    void associateFileToTask(Long taskId, Long fileId, Integer fileOrder);

    /**
     * 移除任务已有的文件关联
     * @param taskId 任务ID
     */
    void removeByTaskId(Long taskId);
}

