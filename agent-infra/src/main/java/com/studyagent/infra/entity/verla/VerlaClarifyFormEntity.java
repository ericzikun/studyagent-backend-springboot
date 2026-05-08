package com.studyagent.infra.entity.verla;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * verla_clarify_forms 表实体（V2）。
 */
@Data
@Accessors(chain = true)
@TableName("verla_clarify_forms")
public class VerlaClarifyFormEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String formId;
    private Long conversationId;
    private Long turnId;
    private Long sessionId;
    private Long messageId;
    private String title;
    private String description;
    private String schemaJson;
    private String status;
    private LocalDateTime expiresAt;
    private LocalDateTime submittedAt;
    private Long submittedResponseId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
