package com.studyagent.infra.entity.verla;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * verla_turns 表实体
 * <p>
 * 详见 docs/verla-Java侧MVP技术方案.md §4.4 / §11.2。
 */
@Data
@Accessors(chain = true)
@TableName("verla_turns")
public class VerlaTurnEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long conversationId;

    private Long userMessageId;

    /** CREATED / PLANNING / AWAITING_CLARIFY / DISPATCHING / RUNNING_AGENT / COMPLETED / FAILED / CANCELLING / CANCELLED */
    private String status;

    private String resolvedIntent;

    private String resolvedSlotsJson;

    private Long activeSessionId;

    private Long planSessionId;

    private Long agentSessionId;

    private Integer totalSteps;

    private Integer completedSteps;

    private LocalDateTime lastProgressAt;

    private LocalDateTime startedAt;

    private LocalDateTime endedAt;

    private String errorJson;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
