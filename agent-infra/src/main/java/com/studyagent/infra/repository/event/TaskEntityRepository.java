package com.studyagent.infra.repository.event;

import com.studyagent.infra.entity.TaskEntity;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 任务实体仓储接口（用于 Agent 事件处理）
 */
public interface TaskEntityRepository {
    
    /**
     * 根据ID查找任务
     */
    Optional<TaskEntity> findById(Long id);
    
    /**
     * 保存任务
     */
    void save(TaskEntity task);

    /**
     * 仅在任务仍处于「待执行 / 执行中」时标记为失败。
     * 与 {@code stopTask} 将任务置为草稿并发时，避免用 TASK_FAILED 覆盖已写入的 DRAFT。
     *
     * @return 是否成功更新（影响行数 &gt; 0）
     */
    boolean markFailedIfExecuting(Long taskId, LocalDateTime finishTime, Integer costTime, String errorMessage);
}
