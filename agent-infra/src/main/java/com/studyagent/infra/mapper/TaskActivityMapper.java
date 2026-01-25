package com.studyagent.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.infra.entity.TaskActivityEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 任务活动日志表 Mapper
 */
@Mapper
public interface TaskActivityMapper extends BaseMapper<TaskActivityEntity> {
}

