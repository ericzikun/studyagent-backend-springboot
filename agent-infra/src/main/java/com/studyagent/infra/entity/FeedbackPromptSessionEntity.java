package com.studyagent.infra.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 反馈会话实体
 */
@Data
@TableName("feedback_prompt_sessions")
public class FeedbackPromptSessionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("prompt_session_id")
    private String promptSessionId;

    @TableField("clerk_user_id")
    private String clerkUserId;

    @TableField("subject_type")
    private String subjectType;

    @TableField("subject_id")
    private String subjectId;

    @TableField("trigger_code")
    private String triggerCode;

    private String variant;

    @TableField("config_key")
    private String configKey;

    @TableField("config_version")
    private Integer configVersion;

    private String status;

    @TableField("source_page")
    private String sourcePage;

    @TableField("shown_at")
    private LocalDateTime shownAt;

    @TableField("submitted_at")
    private LocalDateTime submittedAt;
}
