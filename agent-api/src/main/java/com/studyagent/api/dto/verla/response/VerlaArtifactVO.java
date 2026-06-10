package com.studyagent.api.dto.verla.response;

import com.studyagent.api.dto.verla.support.VerlaPublicIdVoSupport;
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

    /** 内部主键 public id（va_*）；业务引用请优先使用 artifactUid */
    private String artifactId;
    /** V2：业务唯一 ID（artifact_*） */
    private String artifactUid;
    private String conversationId;
    private String turnId;
    private String sessionId;
    private String sourceMessageId;
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
        return from(a, true);
    }

    public static VerlaArtifactVO fromInternal(VerlaArtifact a) {
        return from(a, false);
    }

    private static VerlaArtifactVO from(VerlaArtifact a, boolean encodePublicIds) {
        if (a == null) {
            return null;
        }
        return VerlaArtifactVO.builder()
                .artifactId(VerlaPublicIdVoSupport.artifact(a.getId(), encodePublicIds))
                .artifactUid(a.getArtifactUid())
                .conversationId(VerlaPublicIdVoSupport.conversation(a.getConversationId(), encodePublicIds))
                .turnId(VerlaPublicIdVoSupport.turn(a.getTurnId(), encodePublicIds))
                .sessionId(VerlaPublicIdVoSupport.session(a.getSessionId(), encodePublicIds))
                .sourceMessageId(VerlaPublicIdVoSupport.message(a.getSourceMessageId(), encodePublicIds))
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
