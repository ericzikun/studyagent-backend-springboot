package com.studyagent.api.dto.verla.response;

import com.studyagent.service.domain.verla.VerlaArtifact;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Verla artifact 对外 VO（卡片 / 材料）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerlaArtifactVO {

    private Long artifactId;
    private Long conversationId;
    private Long turnId;
    private Long sessionId;
    private String kind;
    private String mime;
    private String bodyOrRef;
    private Integer version;
    private LocalDateTime updatedAt;

    public static VerlaArtifactVO from(VerlaArtifact a) {
        if (a == null) {
            return null;
        }
        return VerlaArtifactVO.builder()
                .artifactId(a.getId())
                .conversationId(a.getConversationId())
                .turnId(a.getTurnId())
                .sessionId(a.getSessionId())
                .kind(a.getKind())
                .mime(a.getMime())
                .bodyOrRef(a.getBodyOrRef())
                .version(a.getVersion())
                .updatedAt(a.getUpdatedAt())
                .build();
    }
}
