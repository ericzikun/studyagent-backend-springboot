package com.studyagent.infra.entity.demo.note;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** demo_note_message */
@Data
@TableName("demo_note_message")
public class DemoNoteMessageEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("conversation_id")
    private Long conversationId;
    private String role;
    @TableField("msg_type")
    private String msgType;
    @TableField("content_md")
    private String contentMd;
    private Integer seq;
    @TableField("created_at")
    private LocalDateTime createdAt;
}
