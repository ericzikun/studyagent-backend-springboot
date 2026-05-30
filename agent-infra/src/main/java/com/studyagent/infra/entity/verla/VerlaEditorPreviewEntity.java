package com.studyagent.infra.entity.verla;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@TableName("verla_editor_previews")
public class VerlaEditorPreviewEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("conversation_id")
    private Long conversationId;

    @TableField("source_artifact_uid")
    private String sourceArtifactUid;

    @TableField("editor_kind")
    private String editorKind;

    @TableField("attachment_object_id")
    private String attachmentObjectId;

    @TableField("preview_url")
    private String previewUrl;

    @TableField("content_hash")
    private String contentHash;

    @TableField("capture_source")
    private String captureSource;

    private Integer width;

    private Integer height;

    @TableField("created_by")
    private String createdBy;

    @TableField("updated_by")
    private String updatedBy;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
