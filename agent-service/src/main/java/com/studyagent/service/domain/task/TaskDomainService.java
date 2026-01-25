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
            throw new IllegalArgumentException("任务不能为空");
        }
        
        if (task.getTaskTitle() == null || task.getTaskTitle().trim().isEmpty()) {
            throw new IllegalArgumentException("任务标题不能为空");
        }
        
        if (task.getTaskDesc() == null || task.getTaskDesc().trim().isEmpty()) {
            throw new IllegalArgumentException("任务描述不能为空");
        }
        
        // dueDate 为可选字段，如果提供了则验证不能早于当前时间
        if (task.getDueDate() != null && task.getDueDate().isBefore(java.time.LocalDateTime.now())) {
            throw new IllegalArgumentException("截止时间不能早于当前时间");
        }
        
        if (task.getPageLength() == null || task.getPageLength() <= 0) {
            throw new IllegalArgumentException("页数要求必须大于0");
        }
        
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
            throw new IllegalArgumentException("任务不存在");
        }
        
        if (task.getStatus() != TaskStatus.COMPLETED) {
            throw new IllegalStateException("只能评价已完成的任务");
        }
    }
}

