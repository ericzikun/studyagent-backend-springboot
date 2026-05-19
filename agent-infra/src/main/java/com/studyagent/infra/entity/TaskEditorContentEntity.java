package com.studyagent.infra.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 任务编辑器当前内容表实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("task_editor_contents")
public class TaskEditorContentEntity extends BaseEntity {

    @TableField("task_id")
    private Long taskId;

    @TableField("editor_kind")
    private String editorKind;

    private String title;

    @TableField("content_json")
    private String contentJson;

    @TableField("meta_json")
    private String metaJson;

    @TableField("source_artifact_uid")
    private String sourceArtifactUid;

    @TableField("source_object_id")
    private String sourceObjectId;

    @TableField("content_schema_version")
    private Integer contentSchemaVersion;

    @TableField("created_by")
    private String createdBy;

    @TableField("updated_by")
    private String updatedBy;
}
