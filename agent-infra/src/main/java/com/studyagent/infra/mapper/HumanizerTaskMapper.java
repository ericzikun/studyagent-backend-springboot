package com.studyagent.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.infra.entity.HumanizerTaskEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * Humanizer/AI检测 异步任务 Mapper
 */
public interface HumanizerTaskMapper extends BaseMapper<HumanizerTaskEntity> {

    /**
     * 原子抢占：将 PENDING 任务改为 PROCESSING
     * 返回受影响行数，>0 表示抢占成功
     */
    @Update("UPDATE humanizer_tasks SET status = 'PROCESSING', started_at = NOW(), updated_at = NOW() " +
            "WHERE id = #{id} AND status = 'PENDING'")
    int claimTask(@Param("id") Long id);
}
