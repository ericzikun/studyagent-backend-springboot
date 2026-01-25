package com.studyagent.infra.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.math.BigDecimal;

/**
 * Agent执行表实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("task_agents")
public class TaskAgentEntity extends BaseEntity {
    @TableField("task_id")
    private Long taskId;
    
    @TableField("agent_name")
    private String agentName;
    
    @TableField("agent_desc")
    private String agentDesc;
    
    @TableField("agent_status")
    private Integer agentStatus; // 0-待执行, 1-等待中, 2-运行中, 3-已完成, 4-失败
    
    @TableField("complete_percent")
    private BigDecimal completePercent;
    
    @TableField("agent_priority")
    private Integer agentPriority;
    
    @TableField("agent_start_time")
    private LocalDateTime agentStartTime;
    
    @TableField("agent_finish_time")
    private LocalDateTime agentFinishTime;
    
    @TableField("agent_output")
    private String agentOutput;
}

