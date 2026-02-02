package com.studyagent.infra.repository.event.impl;

import com.studyagent.infra.entity.TaskEntity;
import com.studyagent.infra.mapper.TaskMapper;
import com.studyagent.infra.repository.event.TaskEntityRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

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
}
