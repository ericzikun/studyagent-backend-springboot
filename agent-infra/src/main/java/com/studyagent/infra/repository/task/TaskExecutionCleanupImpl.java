package com.studyagent.infra.repository.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.studyagent.infra.entity.SubTaskEntity;
import com.studyagent.infra.entity.TaskActivityEntity;
import com.studyagent.infra.entity.TaskAgentEntity;
import com.studyagent.infra.entity.TaskEntity;
import com.studyagent.infra.entity.TaskOutputEntity;
import com.studyagent.infra.mapper.SubTaskMapper;
import com.studyagent.infra.mapper.TaskActivityMapper;
import com.studyagent.infra.mapper.TaskAgentMapper;
import com.studyagent.infra.mapper.TaskMapper;
import com.studyagent.infra.mapper.TaskOutputMapper;
import com.studyagent.service.domain.task.TaskExecutionCleanup;
import com.studyagent.service.domain.task.TaskStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 停止任务时清理执行态数据并将主任务置为草稿
 */
@Component
@RequiredArgsConstructor
public class TaskExecutionCleanupImpl implements TaskExecutionCleanup {

    private final SubTaskMapper subTaskMapper;
    private final TaskAgentMapper taskAgentMapper;
    private final TaskOutputMapper taskOutputMapper;
    private final TaskActivityMapper taskActivityMapper;
    private final TaskMapper taskMapper;

    @Override
    @Transactional(timeout = 30)
    public void resetTaskToDraftAndClearExecution(long taskId) {
        subTaskMapper.delete(new LambdaQueryWrapper<SubTaskEntity>().eq(SubTaskEntity::getTaskId, taskId));
        taskAgentMapper.delete(new LambdaQueryWrapper<TaskAgentEntity>().eq(TaskAgentEntity::getTaskId, taskId));
        taskOutputMapper.delete(new LambdaQueryWrapper<TaskOutputEntity>().eq(TaskOutputEntity::getTaskId, taskId));
        taskActivityMapper.delete(new LambdaQueryWrapper<TaskActivityEntity>().eq(TaskActivityEntity::getTaskId, taskId));

        LocalDateTime now = LocalDateTime.now();
        taskMapper.update(
                null,
                new LambdaUpdateWrapper<TaskEntity>()
                        .eq(TaskEntity::getId, taskId)
                        .set(TaskEntity::getStatus, TaskStatus.DRAFT.getCode())
                        .set(TaskEntity::getStartTime, null)
                        .set(TaskEntity::getFinishTime, null)
                        .set(TaskEntity::getCostTime, null)
                        .set(TaskEntity::getCompletePercent, null)
                        .set(TaskEntity::getTaskCompletedSize, null)
                        .set(TaskEntity::getActiveAgentSize, null)
                        .set(TaskEntity::getComposeTotalRounds, null)
                        .set(TaskEntity::getEstRemainingTime, null)
                        .set(TaskEntity::getFinalResult, null)
                        .set(TaskEntity::getErrorMessage, null)
                        .set(TaskEntity::getTraceId, null)
                        .set(TaskEntity::getUpdatedAt, now));
    }
}
