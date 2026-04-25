package com.studyagent.service.application.verla.dto;

import com.studyagent.service.domain.verla.VerlaConversation;
import com.studyagent.service.domain.verla.VerlaMessage;
import com.studyagent.service.domain.verla.VerlaSession;
import com.studyagent.service.domain.verla.VerlaTurn;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Verla session 启动时一把拿全的上下文聚合视图
 * <p>
 * 对应 docs/verla-Java侧MVP技术方案.md §10.1 / §10.2
 * <ul>
 *     <li>conversation 摘要 + 最近 N 条消息</li>
 *     <li>当前 session 自身实体</li>
 *     <li>当前 turn 实体</li>
 *     <li>同 turn 内已 SUCCEEDED 的兄弟 session（一般是 plan→agent 的接力）</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerlaSessionContextView {

    private VerlaConversation conversation;
    private VerlaTurn turn;
    private VerlaSession session;

    /** 同 turn 内已完成的兄弟 session（按时间升序） */
    private List<VerlaSession> upstreamSessions;

    /** conversation 最近 N 条消息（按 created_at desc） */
    private List<VerlaMessage> recentMessages;

    /** 命中缓存层标记（none / sess / turn / conv），便于 metrics 与调试 */
    private String cacheHitLayer;
}
