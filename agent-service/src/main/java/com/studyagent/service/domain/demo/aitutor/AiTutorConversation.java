package com.studyagent.service.domain.demo.aitutor;

import lombok.Data;

import java.time.LocalDateTime;

/** AI Tutor 会话（demo_ai_tutor_conversation） */
@Data
public class AiTutorConversation {
    private Long id;
    private String clerkUserId;
    /**
     * 主线 {@code verla_conversations.id}。
     * <p>demo 会话建表时同事务创建，作为 SSE 事件通道（{@code /v1/verla/conversations/{cid}/events}）
     * 与 turn / session 归属校验的键。
     */
    private Long verlaConversationId;
    private String title;
    private String initialQuery;
    /** paperMeta JSON 字符串（类型/字数/语言/要求） */
    private String paperMeta;
    private String status;
    private Long baseVersion;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
