package com.studyagent.infra.entity.demo.learning;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * demo_learning_message 表实体
 */
@Data
@TableName("demo_learning_message")
public class DemoLearningMessageEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long themeId;
    private String role;
    private String content;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
