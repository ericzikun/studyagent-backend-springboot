package com.studyagent.service.application.verla.dto;

import com.studyagent.service.domain.verla.VerlaMessage;

import java.util.List;
import java.util.Map;

/**
 * 白板会话的刷新恢复快照（只读，不含任何前端概念）。
 * <p>
 * {@code resumeAfterEventId} 是 <b>SSE 续传游标</b>，语义与 {@code AssignmentRuntimeSnapshotView} 一致：
 * {@code VerlaSseGateway} 按 {@code inbox.id > lastEventId} 补发，所以这里给的是该 conversation
 * 最新一条已 PROCESSED 事件的行 id（没有则为 0）。
 * <p>
 * 前端恢复顺序（design.md §4.2）：先拿本快照出板子与消息（{@code reveal=instant}，不重播书写动画），
 * 再用 {@code resumeAfterEventId} 校准 lastEventId 后开 EventSource；重放帧由
 * {@code (sessionId, stepIndex)} 幂等键吞掉。{@code refSeq} 也要从这里取，别让前端自己数。
 */
public record VideoTutorSnapshotView(
        Long conversationId,
        long resumeAfterEventId,
        List<Map<String, Object>> board,
        int refSeq,
        int boardVersion,
        List<VerlaMessage> messages) {
}
