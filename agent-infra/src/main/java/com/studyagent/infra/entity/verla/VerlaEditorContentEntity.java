package com.studyagent.infra.entity.verla;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * Conversation 维度编辑器当前工作态。
 */
@Data
@Accessors(chain = true)
@TableName("verla_editor_contents")
public class VerlaEditorContentEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("conversation_id")
    private Long conversationId;

    @TableField("source_artifact_uid")
    private String sourceArtifactUid;

    @TableField("seed_artifact_uid")
    private String seedArtifactUid;

    @TableField("editor_kind")
    private String editorKind;

    private String title;

    @TableField("content_json")
    private String contentJson;

    @TableField("meta_json")
    private String metaJson;

    @TableField("content_schema_version")
    private Integer contentSchemaVersion;

    @TableField("created_by")
    private String createdBy;

    @TableField("updated_by")
    private String updatedBy;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
