package com.studyagent.infra.entity.demo.learning;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * demo_learning_agent_state 表实体（theme_id 为业务主键）
 */
@Data
@TableName("demo_learning_agent_state")
public class DemoLearningAgentStateEntity {

    @TableId(type = IdType.INPUT)
    private Long themeId;
    private Long currentFocusNodeId;
    private String pendingOutline;
    private String currentLearningStage;
    private LocalDateTime updatedAt;
}
