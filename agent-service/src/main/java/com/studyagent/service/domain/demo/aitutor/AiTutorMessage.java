package com.studyagent.service.domain.demo.aitutor;

import lombok.Data;

import java.time.LocalDateTime;

/** AI Tutor 消息（demo_ai_tutor_message） */
@Data
public class AiTutorMessage {
    private Long id;
    private Long conversationId;
    private String role;      // user/assistant/system
    private String msgType;   // text/interactive/material/artifact_event
    /** 产出该段的 Agent（mentor/outline/writer/main…）；null 为历史消息 / 用户消息。 */
    private String agent;
    private String contentMd;
    private Long seq;
    private LocalDateTime createdAt;
}
