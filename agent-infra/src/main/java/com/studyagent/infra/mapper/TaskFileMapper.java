package com.studyagent.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import com.studyagent.infra.entity.TaskFileEntity;

/**
 * 任务文件关联Mapper接口
 */
public interface TaskFileMapper extends BaseMapper<TaskFileEntity> {
    @Insert("""
        INSERT INTO task_files (task_id, file_id, file_order, created_at)
        VALUES (#{taskId}, #{fileId}, #{fileOrder}, #{createdAt})
        """)
    int insertTaskFile(
        @Param("taskId") Long taskId,
        @Param("fileId") Long fileId,
        @Param("fileOrder") Integer fileOrder,
        @Param("createdAt") java.time.LocalDateTime createdAt
    );
}

