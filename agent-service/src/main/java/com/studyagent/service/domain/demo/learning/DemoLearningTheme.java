package com.studyagent.service.domain.demo.learning;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Learning Canvas 学习主题领域对象
 * <p>
 * 对应 demo_learning_theme 表。仅新增，不触碰任何旧表/旧领域对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DemoLearningTheme {

    private Long id;
    /** Clerk user id */
    private String clerkUserId;
    /** 用户开场 query */
    private String initialQuery;
    /** 主题标题（建图后回填） */
    private String title;
    /** 人格：sheldon / lasso */
    private String persona;
    /** in_progress / completed */
    private String status;
    /** 最近自动保存时间（历史页排序） */
    private LocalDateTime lastSavedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
