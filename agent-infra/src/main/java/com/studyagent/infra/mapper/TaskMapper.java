package com.studyagent.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.infra.entity.TaskEntity;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 任务Mapper接口
 * 注意: 不需要 @Mapper 注解，@MapperScan 会扫描所有接口
 */
public interface TaskMapper extends BaseMapper<TaskEntity> {
    List<TaskEntity> findByClerkUserId(@Param("clerkUserId") String clerkUserId);
    List<TaskEntity> findByStatus(@Param("status") Integer status);
    List<TaskEntity> findByKeyword(@Param("keyword") String keyword);
}

