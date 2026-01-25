package com.studyagent.infra.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 任务文件关联表实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("task_files")
public class TaskFileEntity extends BaseEntity {
    @TableField("task_id")
    private Long taskId;
    
    @TableField("file_id")
    private Long fileId;
    
    @TableField("file_order")
    private Integer fileOrder;
    
    /**
     * task_files 表没有 updated_at 字段，需要忽略
     * 覆盖父类的 updatedAt 字段，标记为不存在
     */
    @TableField(exist = false)
    private LocalDateTime updatedAt;
}

