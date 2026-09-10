package com.studyagent.service.application.demo.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AI Tutor 一轮对话派发结果（内部 Long 主键，由 agent-api 层转 public id）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiTutorChatDispatchResult {

    private Long verlaConversationId;
    private Long turnId;
    private Long sessionId;
    private String correlationId;
}
