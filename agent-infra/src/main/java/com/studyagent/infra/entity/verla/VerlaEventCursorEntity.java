package com.studyagent.infra.entity.verla;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * verla_event_cursor 表实体
 * <p>
 * PK = sessionId；详见 docs/verla-Java侧MVP技术方案.md §4.4。
 */
@Data
@Accessors(chain = true)
@TableName("verla_event_cursor")
public class VerlaEventCursorEntity {

    @TableId(type = IdType.INPUT)
    private Long sessionId;

    private Long conversationId;
    private Long turnId;
    private Long nextExpectedSeq;
    private Long lastProcessedSeq;
    private LocalDateTime updatedAt;
}
