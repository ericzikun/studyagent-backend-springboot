package com.studyagent.infra.entity.demo.aitutor;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** demo_ai_tutor_message */
@Data
@TableName("demo_ai_tutor_message")
public class DemoAiTutorMessageEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("conversation_id")
    private Long conversationId;
    private String role;
    @TableField("msg_type")
    private String msgType;
    /** 产出该段的 Agent（mentor/outline/writer/main…）；NULL 为历史消息 / 用户消息。 */
    private String agent;
    @TableField("content_md")
    private String contentMd;
    private Long seq;
    @TableField("created_at")
    private LocalDateTime createdAt;
}
