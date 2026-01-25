package com.studyagent.infra.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 任务活动日志表实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("task_activities")
public class TaskActivityEntity extends BaseEntity {
    @TableField("task_id")
    private Long taskId;
    
    @TableField("activity_time")
    private LocalDateTime activityTime;
    
    @TableField("agent_name")
    private String agentName;
    
    @TableField("activity_desc")
    private String activityDesc;
    
    @TableField("activity_detail")
    private String activityDetail;
    
    /**
     * task_activities 表没有 updated_at 字段，需要忽略
     * 覆盖父类的 updatedAt 字段，标记为不存在
     */
    @TableField(exist = false)
    private LocalDateTime updatedAt;
}

