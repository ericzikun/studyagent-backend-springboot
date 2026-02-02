package com.studyagent.infra.repository.event.impl;

import com.studyagent.infra.entity.TaskActivityEntity;
import com.studyagent.infra.mapper.TaskActivityMapper;
import com.studyagent.infra.repository.event.TaskActivityEntityRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 活动日志实体仓储实现
 */
@Repository
@RequiredArgsConstructor
public class TaskActivityEntityRepositoryImpl implements TaskActivityEntityRepository {

    private final TaskActivityMapper taskActivityMapper;

    @Override
    public void save(TaskActivityEntity activity) {
        taskActivityMapper.insert(activity);
    }
}
