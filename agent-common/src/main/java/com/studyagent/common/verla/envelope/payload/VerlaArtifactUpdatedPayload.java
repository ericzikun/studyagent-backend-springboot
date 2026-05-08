package com.studyagent.common.verla.envelope.payload;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * {@code AGENT_ARTIFACT_UPDATED} 事件 payload（V2 增强版）。
 * <p>
 * 兼容 V1 简化字段（kind/mime/bodyOrRef/version）；V2 新增 artifactUid / source* /
 * status / summary / contentRef / sizeBytes / meta，使 Java 能直接 upsert
 * verla_artifacts 完整记录。详见 docs/V2/5.1 §4.4。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class VerlaArtifactUpdatedPayload {

    /** V2 业务唯一 ID（artifact_*） */
    private String artifactUid;

    /** V2: 触发产物的 messageId（可空） */
    private Long sourceMessageId;

    /** V2: 上游附件 objectId（可空） */
    private String sourceObjectId;

    /** assignment_card / flashcards / outline / document_markdown / document_summary ... */
    private String kind;

    private String mime;

    /** V2: 短摘要，hydrate 注入上下文用 */
    private String summary;

    /** V2: internal:// 或 oss:// 引用，正文小且落 bodyOrRef 时为空 */
    private String contentRef;

    /** 正文（小，<= 32KB）；超过走 contentRef；Py 作业流常用字段名为 {@code body} */
    @JsonAlias("body")
    private String bodyOrRef;

    /** PENDING / READY / FAILED；缺省 READY */
    private String status;

    /** V2: 正文/对象大小 */
    private Long sizeBytes;

    /** 增量更新版本，初始 1 */
    private Integer version;

    /** V2: schemaVersion / agent / model / tokens */
    private Map<String, Object> meta;
}
