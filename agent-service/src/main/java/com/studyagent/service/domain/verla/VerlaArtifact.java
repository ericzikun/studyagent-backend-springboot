package com.studyagent.service.domain.verla;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Verla 卡片 / 材料终稿领域对象（V2 扩展）。
 * <p>
 * 对应 verla_artifacts 表，详见
 * docs/verla-Java侧MVP技术方案.md §4.6 / §13.4 与
 * docs/V2/5.1 Java后端 + 数据库 V2 升级技术方案.md §3。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerlaArtifact {

    private Long id;
    /** V2: 业务唯一 ID（artifact_*），Py / 前端引用用 */
    private String artifactUid;
    private Long conversationId;
    private Long turnId;
    private Long sessionId;
    /** V2: 触发产物的 messageId（可空） */
    private Long sourceMessageId;
    /** V2: 上游附件 objectId（可空，关联 verla_attachments.object_id） */
    private String sourceObjectId;
    /** assignment_card / flashcards / outline / document_markdown / document_summary ... */
    private String kind;
    private String mime;
    /** V2: 短摘要（hydrate 注入上下文用） */
    private String summary;
    /** V2: internal:// 或 oss:// URI；正文小且落 bodyOrRef 时为空 */
    private String contentRef;
    /** 正文（小，<= 32KB）；超过走 contentRef */
    private String bodyOrRef;
    /** V2: PENDING / READY / FAILED */
    private String status;
    /** V2: 正文 / 对象大小 */
    private Long sizeBytes;
    /** 增量更新版本，初始 1 */
    private Integer version;
    /** V2: schemaVersion / agent / model / tokens（JSON 字符串） */
    private String metaJson;
    private LocalDateTime updatedAt;
}
