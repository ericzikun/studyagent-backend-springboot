package com.studyagent.service.domain.demo.learning;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Learning Canvas 跨主题用户偏好领域对象（L0 Memory）
 * <p>
 * 对应 demo_learning_user_profile 表。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DemoLearningUserProfile {

    private Long id;
    private String clerkUserId;
    /** 偏好 JSON 数组 */
    private String preferences;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
