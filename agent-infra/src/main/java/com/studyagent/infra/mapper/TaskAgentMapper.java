package com.studyagent.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.infra.entity.TaskAgentEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * Agent执行表 Mapper
 */
@Mapper
public interface TaskAgentMapper extends BaseMapper<TaskAgentEntity> {
}

