package com.studyagent.infra.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 子任务表实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sub_tasks")
public class SubTaskEntity extends BaseEntity {
    @TableField("task_id")
    private Long taskId;
    
    /**
     * Python端生成的子任务ID，如 "0.0", "0.1", "0.2.1" 等
     * 用于与 task_agents.subtask_id 进行关联
     */
    @TableField("subtask_code")
    private String subtaskCode;
    
    private String title;
    private String description;
    
    @TableField("process_desc")
    private String processDesc;
    
    @TableField("agent_name")
    private String agentName;
    
    @TableField("result_content")
    private String resultContent;
    
    private Integer status; // 0-待执行, 1-执行中, 2-已完成, 3-失败
    
    @TableField("order_index")
    private Integer orderIndex;
}

