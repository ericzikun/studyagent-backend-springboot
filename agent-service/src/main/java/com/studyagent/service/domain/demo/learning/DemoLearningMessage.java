package com.studyagent.service.domain.demo.learning;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Learning Canvas 对话消息领域对象
 * <p>
 * 对应 demo_learning_message 表。role：user / assistant / tool / system。
 * content 可为纯文本，或 tool_calls / tool 结果的 JSON。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DemoLearningMessage {

    private Long id;
    private Long themeId;
    private String role;
    private String content;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
