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
    @Update("UPDATE humanizer_tasks SET status = 'PROCESSING', started_at = UTC_TIMESTAMP(), updated_at = UTC_TIMESTAMP() " +
            "WHERE id = #{id} AND status = 'PENDING'")
    int claimTask(@Param("id") Long id);

    /**
     * 原子取消：只有 PENDING/PROCESSING 状态才能取消
     * 返回受影响行数，>0 表示取消成功
     */
    @Update("UPDATE humanizer_tasks SET status = 'CANCELLED', finished_at = UTC_TIMESTAMP(), updated_at = UTC_TIMESTAMP(), " +
            "error_message = 'Cancelled by user' " +
            "WHERE id = #{id} AND status IN ('PENDING', 'PROCESSING')")
    int cancelTask(@Param("id") Long id);

    /**
     * 超时回收：PROCESSING 超过 timeoutMinutes 分钟的任务改回 PENDING（重试）或 FAILED（超限）
     * 返回受影响行数
     */
    @Update("UPDATE humanizer_tasks SET status = CASE WHEN retry_count < #{maxRetry} THEN 'PENDING' ELSE 'FAILED' END, " +
            "retry_count = retry_count + 1, " +
            "error_message = 'Processing timeout, auto recovered', " +
            "updated_at = UTC_TIMESTAMP() " +
            "WHERE status = 'PROCESSING' AND started_at < DATE_SUB(UTC_TIMESTAMP(), INTERVAL #{timeoutMinutes} MINUTE)")
    int recoverTimeoutTasks(@Param("timeoutMinutes") int timeoutMinutes, @Param("maxRetry") int maxRetry);
}
