package com.studyagent.infra.repository.event;

import com.studyagent.infra.entity.TaskOutputEntity;

/**
 * 输出实体仓储接口（用于 Agent 事件处理）
 */
public interface TaskOutputEntityRepository {
    
    /**
     * 保存输出
     */
    void save(TaskOutputEntity output);
}
