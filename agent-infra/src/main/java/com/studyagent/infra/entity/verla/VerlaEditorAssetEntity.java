package com.studyagent.infra.entity.verla;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 编辑器内部素材资源实体（verla_editor_assets）。
 * 与 verla_attachments 独立，不进入作业附件语义。
 */
@Data
@Accessors(chain = true)
@TableName("verla_editor_assets")
public class VerlaEditorAssetEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("asset_id")
    private String assetId;

    @TableField("conversation_id")
    private Long conversationId;

    @TableField("artifact_uid")
    private String artifactUid;

    @TableField("editor_kind")
    private String editorKind;

    @TableField("asset_role")
    private String assetRole;

    @TableField("user_id")
    private String userId;

    private String filename;

    private String mime;

    @TableField("size_bytes")
    private Long sizeBytes;

    @TableField("storage_uri")
    private String storageUri;

    @TableField("oss_key")
    private String ossKey;

    @TableField("checksum_sha256")
    private String checksumSha256;

    private String status;

    @TableField("meta_json")
    private String metaJson;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
