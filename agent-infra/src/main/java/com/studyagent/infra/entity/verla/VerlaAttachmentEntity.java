package com.studyagent.infra.entity.verla;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * verla_attachments 表实体（V2）。
 */
@Data
@Accessors(chain = true)
@TableName("verla_attachments")
public class VerlaAttachmentEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String objectId;
    private Long conversationId;
    private Long turnId;
    private Long sessionId;
    private String userId;
    private String filename;
    private String mime;
    private Long sizeBytes;
    private String storageUri;
    private String ossKey;
    private String checksumSha256;
    private String status;
    private Integer parseProgress;
    private String parseError;
    private String summary;
    private String primaryArtifactUid;
    private String metaJson;
    private String attachmentOrigin;
    /** Py 解析全文缓存（SQL 042） */
    private String markdownContent;
    /** 抽取图片元数据 JSON（SQL 042） */
    private String imagesJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    /**
     * DATETIME 软删除：活跃行为 NULL，删除时写入时间戳（见 SQL 063）。
     * 用 value="null"/delval="now()" 覆盖全局整型逻辑删除配置（logic-delete-value=1/0），
     * 让 MP 自动 CRUD 生成 {@code deleted_at IS NULL} 而非 {@code deleted_at=0}，
     * 与本表手写 SQL 的软删除语义保持一致。
     */
    @TableLogic(value = "null", delval = "now()")
    private LocalDateTime deletedAt;
}
