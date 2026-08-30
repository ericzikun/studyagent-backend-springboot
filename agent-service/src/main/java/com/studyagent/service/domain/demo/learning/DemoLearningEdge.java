package com.studyagent.service.domain.demo.learning;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Learning Canvas 画布边领域对象
 * <p>
 * 对应 demo_learning_edge 表。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DemoLearningEdge {

    private Long id;
    private Long themeId;
    private Long sourceId;
    private Long targetId;
    /** 边标签（如 前置 / 包含 / 对比） */
    private String label;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
