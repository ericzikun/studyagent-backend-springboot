package com.studyagent.infra.repository.event.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.studyagent.infra.entity.TaskAgentEntity;
import com.studyagent.infra.mapper.TaskAgentMapper;
import com.studyagent.infra.repository.event.TaskAgentEntityRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Agent 实体仓储实现
 */
@Repository
@RequiredArgsConstructor
public class TaskAgentEntityRepositoryImpl implements TaskAgentEntityRepository {

    private final TaskAgentMapper taskAgentMapper;

    @Override
    public void save(TaskAgentEntity agent) {
        if (agent.getId() == null) {
            taskAgentMapper.insert(agent);
        } else {
            taskAgentMapper.updateById(agent);
        }
    }

    @Override
    public TaskAgentEntity findByTaskIdAndAgentName(Long taskId, String agentName) {
        LambdaQueryWrapper<TaskAgentEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TaskAgentEntity::getTaskId, taskId)
               .eq(TaskAgentEntity::getAgentName, agentName)
               .last("LIMIT 1");
        return taskAgentMapper.selectOne(wrapper);
    }

    @Override
    public TaskAgentEntity findByTaskIdAndAgentNameAndSubtaskId(Long taskId, String agentName, String subtaskId) {
        LambdaQueryWrapper<TaskAgentEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TaskAgentEntity::getTaskId, taskId)
               .eq(TaskAgentEntity::getAgentName, agentName);
        
        // 处理 subtaskId 可能为 null 的情况
        if (subtaskId != null) {
            wrapper.eq(TaskAgentEntity::getSubtaskId, subtaskId);
        } else {
            wrapper.isNull(TaskAgentEntity::getSubtaskId);
        }
        
        wrapper.last("LIMIT 1");
        return taskAgentMapper.selectOne(wrapper);
    }

    @Override
    public TaskAgentEntity findTopByTaskIdOrderByAgentStartTimeDesc(Long taskId) {
        LambdaQueryWrapper<TaskAgentEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TaskAgentEntity::getTaskId, taskId)
               .orderByDesc(TaskAgentEntity::getAgentStartTime)
               .last("LIMIT 1");
        return taskAgentMapper.selectOne(wrapper);
    }

    @Override
    public void completeAllByTaskId(Long taskId, LocalDateTime finishTime) {
        LambdaUpdateWrapper<TaskAgentEntity> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(TaskAgentEntity::getTaskId, taskId)
               .ne(TaskAgentEntity::getAgentStatus, 3) // 排除已完成的
               .set(TaskAgentEntity::getAgentStatus, 3) // Completed
               .set(TaskAgentEntity::getCompletePercent, new BigDecimal("100.00"))
               .set(TaskAgentEntity::getAgentFinishTime, finishTime)
               .set(TaskAgentEntity::getUpdatedAt, LocalDateTime.now());
        taskAgentMapper.update(null, wrapper);
    }

    @Override
    public void failPendingByTaskId(Long taskId) {
        LambdaUpdateWrapper<TaskAgentEntity> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(TaskAgentEntity::getTaskId, taskId)
               .notIn(TaskAgentEntity::getAgentStatus, 3, 4) // 排除已完成和已失败的
               .set(TaskAgentEntity::getAgentStatus, 4) // Failed
               .set(TaskAgentEntity::getUpdatedAt, LocalDateTime.now());
        taskAgentMapper.update(null, wrapper);
    }
}
