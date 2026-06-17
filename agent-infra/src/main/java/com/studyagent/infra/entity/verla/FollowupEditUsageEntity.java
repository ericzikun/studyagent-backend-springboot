package com.studyagent.infra.entity.verla;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@TableName("verla_followup_edit_usages")
public class FollowupEditUsageEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long conversationId;
    private Long assignmentSessionId;
    private String clerkUserId;
    private Long userMessageId;
    private Long assignmentChatSessionId;
    private String state;
    private String releaseReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
