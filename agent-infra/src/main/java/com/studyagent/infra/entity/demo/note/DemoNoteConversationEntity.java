package com.studyagent.infra.entity.demo.note;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** demo_note_conversation */
@Data
@TableName("demo_note_conversation")
public class DemoNoteConversationEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("clerk_user_id")
    private String clerkUserId;
    @TableField("verla_conversation_id")
    private Long verlaConversationId;
    private String title;
    @TableField("source_type")
    private String sourceType;
    @TableField("attachment_object_id")
    private String attachmentObjectId;
    @TableField("input_text")
    private String inputText;
    @TableField("note_md")
    private String noteMd;
    private String status;
    @TableField("created_at")
    private LocalDateTime createdAt;
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
