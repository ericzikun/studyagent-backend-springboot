package com.studyagent.infra.repository.event.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.studyagent.infra.entity.SubTaskEntity;
import com.studyagent.infra.mapper.SubTaskMapper;
import com.studyagent.infra.repository.event.SubTaskEntityRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

/**
 * 子任务实体仓储实现
 */
@Repository
@RequiredArgsConstructor
public class SubTaskEntityRepositoryImpl implements SubTaskEntityRepository {

    private final SubTaskMapper subTaskMapper;

    @Override
    public void save(SubTaskEntity subtask) {
        if (subtask.getId() == null) {
            subTaskMapper.insert(subtask);
        } else {
            subTaskMapper.updateById(subtask);
        }
    }

    @Override
    public boolean existsByTaskIdAndTitleLike(Long taskId, String titlePrefix) {
        LambdaQueryWrapper<SubTaskEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SubTaskEntity::getTaskId, taskId)
               .likeRight(SubTaskEntity::getTitle, titlePrefix);
        return subTaskMapper.selectCount(wrapper) > 0;
    }

    @Override
    public void updateStatusByTaskId(Long taskId, int status, String processDesc) {
        LambdaUpdateWrapper<SubTaskEntity> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(SubTaskEntity::getTaskId, taskId)
               .ne(SubTaskEntity::getStatus, status) // 排除已经是目标状态的
               .set(SubTaskEntity::getStatus, status)
               .set(SubTaskEntity::getProcessDesc, processDesc)
               .set(SubTaskEntity::getUpdatedAt, LocalDateTime.now());
        subTaskMapper.update(null, wrapper);
    }

    @Override
    public void updateUnfinishedStatusByTaskId(Long taskId, int status, String processDesc) {
        LambdaUpdateWrapper<SubTaskEntity> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(SubTaskEntity::getTaskId, taskId)
               .notIn(SubTaskEntity::getStatus, 2, 3) // 排除已完成和已失败的终态
               .set(SubTaskEntity::getStatus, status)
               .set(SubTaskEntity::getProcessDesc, processDesc)
               .set(SubTaskEntity::getUpdatedAt, LocalDateTime.now());
        subTaskMapper.update(null, wrapper);
    }

    @Override
    public void updatePendingStatusByTaskId(Long taskId, int status, String processDesc) {
        LambdaUpdateWrapper<SubTaskEntity> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(SubTaskEntity::getTaskId, taskId)
               .notIn(SubTaskEntity::getStatus, 2, 3) // 排除已完成和已失败的
               .set(SubTaskEntity::getStatus, status)
               .set(SubTaskEntity::getProcessDesc, processDesc)
               .set(SubTaskEntity::getUpdatedAt, LocalDateTime.now());
        subTaskMapper.update(null, wrapper);
    }

    @Override
    public void updateByTaskIdAndSubtaskId(Long taskId, String subtaskId, int status, 
                                            String processDesc, String agentName) {
        // 🆕 优先使用 subtask_code 精确匹配
        LambdaUpdateWrapper<SubTaskEntity> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(SubTaskEntity::getTaskId, taskId);
        
        if (subtaskId != null && !subtaskId.isEmpty()) {
            // 直接使用 subtaskCode 进行精确匹配
            wrapper.eq(SubTaskEntity::getSubtaskCode, subtaskId);
        }
        
        wrapper.set(SubTaskEntity::getStatus, status)
               .set(SubTaskEntity::getUpdatedAt, LocalDateTime.now());
        
        if (processDesc != null) {
            wrapper.set(SubTaskEntity::getProcessDesc, processDesc);
        }
        if (agentName != null && !agentName.isEmpty()) {
            wrapper.set(SubTaskEntity::getAgentName, agentName);
        }
        
        int updated = subTaskMapper.update(null, wrapper);
        
        // 如果 subtaskCode 精确匹配失败，尝试旧的逻辑作为兜底（向后兼容）
        if (updated == 0 && subtaskId != null && !subtaskId.isEmpty()) {
            LambdaUpdateWrapper<SubTaskEntity> fallbackWrapper = new LambdaUpdateWrapper<>();
            fallbackWrapper.eq(SubTaskEntity::getTaskId, taskId);
            
            // 尝试通过 orderIndex 匹配
            if (subtaskId.matches("\\d+")) {
                int orderIndex = Integer.parseInt(subtaskId) - 1; // 转为 0-based
                fallbackWrapper.eq(SubTaskEntity::getOrderIndex, orderIndex);
            } else if (subtaskId.contains(".")) {
                // 格式如 "0.0"，提取最后的数字作为 orderIndex
                String[] parts = subtaskId.split("\\.");
                String lastPart = parts[parts.length - 1];
                if (lastPart.matches("\\d+")) {
                    int orderIndex = Integer.parseInt(lastPart);
                    fallbackWrapper.eq(SubTaskEntity::getOrderIndex, orderIndex);
                }
            }
            
            fallbackWrapper.set(SubTaskEntity::getStatus, status)
                          .set(SubTaskEntity::getUpdatedAt, LocalDateTime.now());
            if (processDesc != null) {
                fallbackWrapper.set(SubTaskEntity::getProcessDesc, processDesc);
            }
            if (agentName != null && !agentName.isEmpty()) {
                fallbackWrapper.set(SubTaskEntity::getAgentName, agentName);
            }
            
            subTaskMapper.update(null, fallbackWrapper);
        }
    }
}
