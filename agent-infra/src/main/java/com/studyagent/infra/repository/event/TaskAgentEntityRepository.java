package com.studyagent.infra.repository.event;

import com.studyagent.infra.entity.TaskAgentEntity;

import java.time.LocalDateTime;

/**
 * Agent 实体仓储接口（用于 Agent 事件处理）
 */
public interface TaskAgentEntityRepository {
    
    /**
     * 保存 Agent
     */
    void save(TaskAgentEntity agent);
    
    /**
     * 根据任务ID和Agent名称查找
     */
    TaskAgentEntity findByTaskIdAndAgentName(Long taskId, String agentName);

    /**
     * 仅当同名 Agent 记录唯一时返回。
     * 用于缺少 subtaskId 时的安全兜底，避免同名 Agent 串状态。
     */
    TaskAgentEntity findUniqueByTaskIdAndAgentName(Long taskId, String agentName);
    
    /**
     * 根据任务ID、Agent名称和子任务ID查找
     * 用于区分同一Agent类型处理不同子任务的输出
     */
    TaskAgentEntity findByTaskIdAndAgentNameAndSubtaskId(Long taskId, String agentName, String subtaskId);
    
    /**
     * 根据任务ID查找最后一个Agent（按开始时间倒序）
     */
    TaskAgentEntity findTopByTaskIdOrderByAgentStartTimeDesc(Long taskId);
    
    /**
     * 批量完成任务下所有Agent
     */
    void completeAllByTaskId(Long taskId, LocalDateTime finishTime);

    /**
     * 批量完成任务下所有未进入终态的 Agent。
     * 终态定义：已完成、已失败。
     */
    void completePendingByTaskId(Long taskId, LocalDateTime finishTime);
    
    /**
     * 批量将未完成的Agent状态设为失败
     */
    void failPendingByTaskId(Long taskId);
}
