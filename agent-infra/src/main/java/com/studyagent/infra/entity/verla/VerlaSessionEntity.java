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
 * verla_sessions 表实体
 * <p>
 * 详见 docs/verla-Java侧MVP技术方案.md §4.5 / §11.3。
 */
@Data
@Accessors(chain = true)
@TableName("verla_sessions")
public class VerlaSessionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long conversationId;

    private Long turnId;

    /** PLAN / AGENT / MATERIALS */
    private String kind;

    /** 功能码：assignment / flashcards / outline ... */
    private String featureCode;

    /** CREATED / DISPATCHING / RUNNING / SUCCEEDED / FAILED / CANCELLING / CANCELLED */
    private String status;

    /** conv:{c}:turn:{t}:sess:{s} */
    private String correlationId;

    private String contextRefJson;

    private String resultJson;

    private String errorJson;

    private Long expectedSeq;

    private Long lastEventSeq;

    private LocalDateTime lastProgressAt;

    private LocalDateTime startedAt;

    private LocalDateTime endedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
