package com.studyagent.infra.entity.verla;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * verla_event_inbox 表实体
 * <p>
 * 详见 docs/verla-Java侧MVP技术方案.md §4.4 / §11.4。
 */
@Data
@Accessors(chain = true)
@TableName("verla_event_inbox")
public class VerlaEventInboxEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String messageId;
    private String correlationId;
    private Long conversationId;
    private Long turnId;
    private Long sessionId;
    private Long eventSeq;
    private String eventType;
    private String stepId;
    private Integer stepSeq;
    private String payloadJson;
    /** READY / PROCESSED / SKIPPED / FAILED */
    private String status;
    private String errorMessage;
    private LocalDateTime receivedAt;
    private LocalDateTime processedAt;
}
