package com.studyagent.service.domain.demo.aitutor;

import lombok.Data;

import java.time.LocalDateTime;

/** AI Tutor 论文活文档（demo_ai_tutor_document） */
@Data
public class AiTutorDocument {
    private Long id;
    private Long conversationId;
    private String title;
    private String contentMd;
    private Long baseVersion;
    private String updatedBy;  // ai/user
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
