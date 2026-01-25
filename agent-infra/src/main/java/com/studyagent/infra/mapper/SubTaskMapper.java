package com.studyagent.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.infra.entity.SubTaskEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 子任务表 Mapper
 */
@Mapper
public interface SubTaskMapper extends BaseMapper<SubTaskEntity> {
}

