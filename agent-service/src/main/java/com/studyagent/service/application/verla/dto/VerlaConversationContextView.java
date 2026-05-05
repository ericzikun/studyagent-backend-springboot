package com.studyagent.service.application.verla.dto;

import com.studyagent.service.domain.verla.VerlaArtifact;
import com.studyagent.service.domain.verla.VerlaConversation;
import com.studyagent.service.domain.verla.VerlaMessage;
import com.studyagent.service.domain.verla.VerlaToolCall;
import com.studyagent.service.domain.verla.VerlaTurn;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Conversation 维度的 hydrate 视图（供 Py 在处理用户 query 时拉全量历史与附属上下文）。
 * <p>
 * 与 {@link VerlaSessionContextView} 的区别：不绑定单一 session，消息支持 {@code before} 游标分页，
 * 工具 trace 按 conversation 聚合（USER_VISIBLE）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerlaConversationContextView {

    private VerlaConversation conversation;

    /** 当前会话最新一轮 turn（按 id desc）；尚无 turn 时为 null */
    private VerlaTurn latestTurn;

    /** 消息页（id desc，与 {@link com.studyagent.service.domain.verla.repo.VerlaMessageRepository#findByCursor} 一致） */
    private List<VerlaMessage> recentMessages;

    /**
     * 下一页游标：本页满 {@code limit} 时为当前页最旧一条消息的 id，否则 null。
     * Py 将下一次请求的 {@code before} 设为该值可继续向前翻页直至 null。
     */
    private Long nextCursor;

    private List<VerlaArtifact> artifacts;

    private List<VerlaSessionContextView.ToolCallSummaryView> toolSummaries;

    private List<VerlaToolCall> recentToolCalls;

    private Boolean traceIncluded;

    private String cacheHitLayer;
}
