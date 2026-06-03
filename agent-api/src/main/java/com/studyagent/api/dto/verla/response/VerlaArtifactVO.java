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
    /** V2：业务唯一 ID（artifact_*） */
    private String artifactUid;
    private Long conversationId;
    private Long turnId;
    private Long sessionId;
    private Long sourceMessageId;
    private String sourceObjectId;
    private String kind;
    private String mime;
    private String summary;
    private String contentRef;
    private String bodyOrRef;
    private String status;
    private Long sizeBytes;
    private String metaJson;
    private Integer version;
    private LocalDateTime updatedAt;

    /**
     * {@code assignment_code_file} 是后端内部索引行（支撑单文件懒加载 / 整包 zip），
     * 不作为前端可渲染 artifact 暴露（见技术方案 §2.2 / §4.2）。
     */
    public static final String KIND_CODE_FILE = "assignment_code_file";

    /** conversation 列表是否应排除该 artifact（内部文件行不暴露给前端）。 */
    public static boolean isListVisible(VerlaArtifact a) {
        return a != null && !KIND_CODE_FILE.equals(a.getKind());
    }

    public static VerlaArtifactVO from(VerlaArtifact a) {
        if (a == null) {
            return null;
        }
        return VerlaArtifactVO.builder()
                .artifactId(a.getId())
                .artifactUid(a.getArtifactUid())
                .conversationId(a.getConversationId())
                .turnId(a.getTurnId())
                .sessionId(a.getSessionId())
                .sourceMessageId(a.getSourceMessageId())
                .sourceObjectId(a.getSourceObjectId())
                .kind(a.getKind())
                .mime(a.getMime())
                .summary(a.getSummary())
                .contentRef(a.getContentRef())
                .bodyOrRef(a.getBodyOrRef())
                .status(a.getStatus())
                .sizeBytes(a.getSizeBytes())
                .metaJson(a.getMetaJson())
                .version(a.getVersion())
                .updatedAt(a.getUpdatedAt())
                .build();
    }
}
