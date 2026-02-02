package com.studyagent.infra.repository.event.impl;

import com.studyagent.infra.entity.TaskOutputEntity;
import com.studyagent.infra.mapper.TaskOutputMapper;
import com.studyagent.infra.repository.event.TaskOutputEntityRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 输出实体仓储实现
 */
@Repository
@RequiredArgsConstructor
public class TaskOutputEntityRepositoryImpl implements TaskOutputEntityRepository {

    private final TaskOutputMapper taskOutputMapper;

    @Override
    public void save(TaskOutputEntity output) {
        taskOutputMapper.insert(output);
    }
}
