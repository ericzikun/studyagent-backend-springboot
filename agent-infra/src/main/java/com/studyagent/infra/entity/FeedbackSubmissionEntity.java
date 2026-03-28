package com.studyagent.infra.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 反馈提交实体
 */
@Data
@TableName("feedback_submissions")
public class FeedbackSubmissionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("submission_id")
    private String submissionId;

    @TableField("prompt_session_id")
    private String promptSessionId;

    private Integer score;

    private String vote;

    @TableField("selected_tag_codes_json")
    private String selectedTagCodesJson;

    private String comment;

    private String contact;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
