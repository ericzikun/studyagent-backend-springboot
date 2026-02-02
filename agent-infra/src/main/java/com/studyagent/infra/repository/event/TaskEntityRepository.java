package com.studyagent.infra.repository.event;

import com.studyagent.infra.entity.TaskEntity;

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
}
