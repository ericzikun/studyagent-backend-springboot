package com.studyagent.service.domain.verla;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Verla 事件 cursor 领域对象（每个 session 一行）
 * <p>
 * 对应 verla_event_cursor 表，详见 docs/verla-Java侧MVP技术方案.md §4.4 / §8.3。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerlaEventCursor {

    private Long sessionId;
    private Long conversationId;
    private Long turnId;
    /** 下一条期望的 eventSeq；初始值 1 */
    private Long nextExpectedSeq;
    /** 已成功处理的最大 eventSeq；初始值 0 */
    private Long lastProcessedSeq;
    private LocalDateTime updatedAt;
}
