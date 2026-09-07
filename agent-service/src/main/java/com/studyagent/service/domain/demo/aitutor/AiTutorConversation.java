package com.studyagent.service.domain.demo.aitutor;

import lombok.Data;

import java.time.LocalDateTime;

/** AI Tutor 会话（demo_ai_tutor_conversation） */
@Data
public class AiTutorConversation {
    private Long id;
    private String clerkUserId;
    private String title;
    private String initialQuery;
    /** paperMeta JSON 字符串（类型/字数/语言/要求） */
    private String paperMeta;
    private String status;
    private Long baseVersion;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
