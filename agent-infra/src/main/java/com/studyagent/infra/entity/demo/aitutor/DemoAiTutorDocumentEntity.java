package com.studyagent.infra.entity.demo.aitutor;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** demo_ai_tutor_document */
@Data
@TableName("demo_ai_tutor_document")
public class DemoAiTutorDocumentEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("conversation_id")
    private Long conversationId;
    private String title;
    @TableField("content_md")
    private String contentMd;
    @TableField("base_version")
    private Long baseVersion;
    @TableField("updated_by")
    private String updatedBy;
    @TableField("created_at")
    private LocalDateTime createdAt;
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
