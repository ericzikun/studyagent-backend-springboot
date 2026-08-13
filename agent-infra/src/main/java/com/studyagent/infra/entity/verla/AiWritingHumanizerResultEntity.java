package com.studyagent.infra.entity.verla;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@TableName("ai_writing_humanizer_results")
public class AiWritingHumanizerResultEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String clerkUserId;
    private Long conversationId;
    private Long sessionId;
    private String artifactUid;
    private String resultHash;
    private String resultText;
    private LocalDateTime createdAt;
}
