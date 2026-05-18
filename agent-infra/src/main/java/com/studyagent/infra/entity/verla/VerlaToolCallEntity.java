package com.studyagent.infra.entity.verla;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * verla_tool_calls 表实体（V2）。
 * <p>
 * 详见 docs/V2/5.1 Java后端 + 数据库 V2 升级技术方案.md §3。
 */
@Data
@Accessors(chain = true)
@TableName("verla_tool_calls")
public class VerlaToolCallEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String toolCallId;
    private Long conversationId;
    private Long turnId;
    private Long sessionId;
    private String stepId;
    private String parentCallId;
    private String agentName;
    private String toolName;
    private String status;
    private String visibility;
    private String toolInputJson;
    private String toolOutputJson;
    private String summary;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Integer durationMs;
    private String metaJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
