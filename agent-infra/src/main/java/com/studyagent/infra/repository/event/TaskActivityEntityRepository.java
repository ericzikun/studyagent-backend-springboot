package com.studyagent.infra.repository.event;

import com.studyagent.infra.entity.TaskActivityEntity;

/**
 * 活动日志实体仓储接口（用于 Agent 事件处理）
 */
public interface TaskActivityEntityRepository {
    
    /**
     * 保存活动日志
     */
    void save(TaskActivityEntity activity);
}
