package com.studyagent.api.dto.demo.aitutor;

import com.studyagent.api.dto.verla.support.VerlaPublicIdVoSupport;
import com.studyagent.service.domain.demo.aitutor.AiTutorConversation;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * AI Tutor 会话响应体。
 * <p>{@code id} 是 demo 自己的主键（前端各 REST 路径继续用它），
 * {@code verlaConversationId} 是主线 public id（{@code vc_xxx}），仅用于建立 SSE 事件通道。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiTutorConversationVO {

    private Long id;
    private String verlaConversationId;
    private String clerkUserId;
    private String title;
    private String initialQuery;
    private String paperMeta;
    private String status;
    private Long baseVersion;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static AiTutorConversationVO from(AiTutorConversation c) {
        if (c == null) {
            return null;
        }
        return AiTutorConversationVO.builder()
                .id(c.getId())
                .verlaConversationId(VerlaPublicIdVoSupport.conversation(c.getVerlaConversationId(), true))
                .clerkUserId(c.getClerkUserId())
                .title(c.getTitle())
                .initialQuery(c.getInitialQuery())
                .paperMeta(c.getPaperMeta())
                .status(c.getStatus())
                .baseVersion(c.getBaseVersion())
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .build();
    }
}
