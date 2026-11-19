package com.studyagent.infra.entity.demo.note;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** demo_note_feedback */
@Data
@TableName("demo_note_feedback")
public class DemoNoteFeedbackEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("note_conversation_id")
    private Long noteConversationId;
    @TableField("clerk_user_id")
    private String clerkUserId;
    /** up / down / report */
    private String kind;
    private String comment;
    @TableField("created_at")
    private LocalDateTime createdAt;
}
