package com.studyagent.service.domain.task;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 任务领域服务
 * 处理跨实体的业务逻辑和复杂业务规则
 */
@Slf4j
@Service
public class TaskDomainService {
    
    /**
     * 验证任务是否可以提交
     */
    public void validateTask(Task task) {
        if (task == null) {
            throw new IllegalArgumentException("Task cannot be null");
        }
        
        if (task.getTaskTitle() == null || task.getTaskTitle().trim().isEmpty()) {
            throw new IllegalArgumentException("Task title is required");
        }
        
        if (task.getTaskDesc() == null || task.getTaskDesc().trim().isEmpty()) {
            throw new IllegalArgumentException("Task description is required");
        }
        
        // dueDate is optional, if provided validate it's not in the past
        if (task.getDueDate() != null && task.getDueDate().isBefore(java.time.LocalDateTime.now())) {
            throw new IllegalArgumentException("Due date cannot be in the past");
        }
        
        // pageLength 为可选字段，允许为 null 或 "Not Specified"
        
        log.debug("任务验证通过: {}", task.getTaskTitle());
    }
    
    /**
     * 计算任务完成百分比
     */
    public java.math.BigDecimal calculateCompletePercent(Task task) {
        // TODO: 根据子任务完成情况计算
        return task.getCompletePercent() != null 
            ? task.getCompletePercent() 
            : java.math.BigDecimal.ZERO;
    }
    
    /**
     * 检查任务是否可以评价
     */
    public void validateTaskCanBeRated(Task task) {
        if (task == null) {
            throw new IllegalArgumentException("Task not found");
        }

        if (task.getStatus() != TaskStatus.COMPLETED) {
            throw new IllegalStateException("Can only rate completed tasks");
        }
    }
}

