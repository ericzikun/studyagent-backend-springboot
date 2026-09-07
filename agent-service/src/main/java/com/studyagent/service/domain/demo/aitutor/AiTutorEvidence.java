package com.studyagent.service.domain.demo.aitutor;

import lombok.Data;

import java.time.LocalDateTime;

/** AI Tutor 引用证据（demo_ai_tutor_evidence），user=用户材料 / search=检索确认 */
@Data
public class AiTutorEvidence {
    private Long id;
    private Long conversationId;
    private String sourceType;
    private String title;
    private String url;
    private String snippet;
    private String metaJson;
    private Long seqNo;
    private Boolean confirmed;
    private LocalDateTime createdAt;
}
