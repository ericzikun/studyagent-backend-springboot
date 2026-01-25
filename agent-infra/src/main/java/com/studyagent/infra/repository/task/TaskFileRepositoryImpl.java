package com.studyagent.infra.repository.task;

import com.studyagent.infra.entity.TaskFileEntity;
import com.studyagent.infra.mapper.TaskFileMapper;
import com.studyagent.service.domain.task.TaskFileRepository;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

/**
 * 任务文件关联Repository实现
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class TaskFileRepositoryImpl implements TaskFileRepository {
    
    private final TaskFileMapper taskFileMapper;
    
    @Override
    public void associateFileToTask(Long taskId, Long fileId, Integer fileOrder) {
        LocalDateTime now = LocalDateTime.now();
        taskFileMapper.insertTaskFile(taskId, fileId, fileOrder, now);
        log.debug("任务文件关联成功: taskId={}, fileId={}, order={}", taskId, fileId, fileOrder);
    }

    @Override
    public void removeByTaskId(Long taskId) {
        if (taskId == null) {
            return;
        }
        int deleted = taskFileMapper.delete(
            new LambdaQueryWrapper<TaskFileEntity>()
                .eq(TaskFileEntity::getTaskId, taskId)
        );
        log.debug("已移除任务文件关联: taskId={}, count={}", taskId, deleted);
    }
}

