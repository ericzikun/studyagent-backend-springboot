package com.studyagent.infra.entity.demo.learning;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * demo_learning_user_profile 表实体
 */
@Data
@TableName("demo_learning_user_profile")
public class DemoLearningUserProfileEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String clerkUserId;
    private String preferences;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
