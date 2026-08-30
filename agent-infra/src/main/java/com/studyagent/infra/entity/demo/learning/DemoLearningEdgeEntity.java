package com.studyagent.infra.entity.demo.learning;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * demo_learning_edge 表实体
 */
@Data
@TableName("demo_learning_edge")
public class DemoLearningEdgeEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long themeId;
    private Long sourceId;
    private Long targetId;
    private String label;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
