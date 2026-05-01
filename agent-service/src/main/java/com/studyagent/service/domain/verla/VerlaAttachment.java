package com.studyagent.service.domain.verla;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Verla 用户上传附件领域对象（V2）。
 * <p>
 * 对应 {@code verla_attachments} 表，承载文件元数据 + 解析状态。
 * 详见 docs/V2/5.1 Java后端 + 数据库 V2 升级技术方案.md §3 / §6。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerlaAttachment {

    private Long id;
    /** 业务唯一 ID（att_*），前端引用用 */
    private String objectId;
    private Long conversationId;
    /** 上传后未提交时为 NULL，finalize/绑定 turn 后回填 */
    private Long turnId;
    /** 预留：上传时所处 session（可为空） */
    private Long sessionId;
    private String userId;
    private String filename;
    private String mime;
    private Long sizeBytes;
    /** oss://{bucket}/{key}（V2 仅 OSS，不再写本地 file://） */
    private String storageUri;
    /** OSS 对象 Key，消费侧下载优先使用 */
    private String ossKey;
    private String checksumSha256;
    /** {@link com.studyagent.common.verla.enums.VerlaAttachmentStatus} 字符串 */
    private String status;
    /** 0~100，仅 PARSING 阶段 */
    private Integer parseProgress;
    private String parseError;
    /** 解析后的短摘要，hydrate 用 */
    private String summary;
    /** 主产物（如 markdown 全文）的 verla_artifacts.artifact_uid */
    private String primaryArtifactUid;
    /** JSON 字符串：page_count / image_size / ocr 等 */
    private String metaJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
