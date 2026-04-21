package com.studyagent.infra.repository.event.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.studyagent.infra.entity.TaskEntity;
import com.studyagent.infra.mapper.TaskMapper;
import com.studyagent.infra.repository.event.TaskEntityRepository;
import com.studyagent.service.domain.task.TaskStatus;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 任务实体仓储实现
 */
@Repository
@RequiredArgsConstructor
public class TaskEntityRepositoryImpl implements TaskEntityRepository {

    private final TaskMapper taskMapper;

    @Override
    public Optional<TaskEntity> findById(Long id) {
        return Optional.ofNullable(taskMapper.selectById(id));
    }

    @Override
    public void save(TaskEntity task) {
        if (task.getId() == null) {
            taskMapper.insert(task);
        } else {
            taskMapper.updateById(task);
        }
    }

    @Override
    public boolean markFailedIfExecuting(Long taskId, LocalDateTime finishTime, Integer costTime, String errorMessage) {
        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<TaskEntity> uw = new LambdaUpdateWrapper<TaskEntity>()
                .eq(TaskEntity::getId, taskId)
                .in(TaskEntity::getStatus, TaskStatus.PENDING.getCode(), TaskStatus.IN_PROGRESS.getCode())
                .set(TaskEntity::getStatus, TaskStatus.FAILED.getCode())
                .set(TaskEntity::getFinishTime, finishTime)
                .set(TaskEntity::getErrorMessage, errorMessage)
                .set(TaskEntity::getUpdatedAt, now);
        if (costTime != null) {
            uw.set(TaskEntity::getCostTime, costTime);
        }
        return taskMapper.update(null, uw) > 0;
    }
}
