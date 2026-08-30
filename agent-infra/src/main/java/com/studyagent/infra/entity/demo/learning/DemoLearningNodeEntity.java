package com.studyagent.infra.entity.demo.learning;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * demo_learning_node 表实体
 */
@Data
@TableName("demo_learning_node")
public class DemoLearningNodeEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long themeId;
    private Long parentId;
    private String nodeType;
    private String title;
    private String summary;
    private String masteryLevel;
    private String learningType;
    private String certaintyStatus;
    private String startMsgId;
    private String trajectory;
    private String preTestResults;
    private String metaJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
