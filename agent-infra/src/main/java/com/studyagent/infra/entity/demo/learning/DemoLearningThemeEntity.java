package com.studyagent.infra.entity.demo.learning;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * demo_learning_theme 表实体
 */
@Data
@TableName("demo_learning_theme")
public class DemoLearningThemeEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String clerkUserId;
    private String initialQuery;
    private String title;
    private String persona;
    private String status;
    private LocalDateTime lastSavedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
