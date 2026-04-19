package com.studyagent.infra.repository.event;

import com.studyagent.infra.entity.SubTaskEntity;

/**
 * 子任务实体仓储接口（用于 Agent 事件处理）
 */
public interface SubTaskEntityRepository {
    
    /**
     * 保存子任务
     */
    void save(SubTaskEntity subtask);
    
    /**
     * 检查是否存在相同标题的子任务
     */
    boolean existsByTaskIdAndTitleLike(Long taskId, String titlePrefix);
    
    /**
     * 批量更新任务下所有子任务状态
     */
    void updateStatusByTaskId(Long taskId, int status, String processDesc);

    /**
     * 批量更新任务下所有未进入终态的子任务状态。
     * 终态定义：已完成、已失败。
     */
    void updateUnfinishedStatusByTaskId(Long taskId, int status, String processDesc);
    
    /**
     * 批量更新任务下所有待处理子任务状态
     */
    void updatePendingStatusByTaskId(Long taskId, int status, String processDesc);
    
    /**
     * 通过子任务ID更新状态
     */
    void updateByTaskIdAndSubtaskId(Long taskId, String subtaskId, int status, 
                                     String processDesc, String agentName);
}
