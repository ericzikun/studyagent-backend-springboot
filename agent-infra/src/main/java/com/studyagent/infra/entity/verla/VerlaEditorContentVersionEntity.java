package com.studyagent.infra.entity.verla;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * Conversation 维度编辑器历史版本。
 */
@Data
@Accessors(chain = true)
@TableName("verla_editor_content_versions")
public class VerlaEditorContentVersionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("editor_content_id")
    private Long editorContentId;

    @TableField("version_no")
    private Integer versionNo;

    @TableField("content_json")
    private String contentJson;

    @TableField("meta_json")
    private String metaJson;

    @TableField("save_source")
    private String saveSource;

    @TableField("created_by")
    private String createdBy;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
