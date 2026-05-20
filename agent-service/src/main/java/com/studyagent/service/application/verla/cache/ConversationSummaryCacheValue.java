package com.studyagent.service.application.verla.cache;

import com.studyagent.service.domain.verla.VerlaConversation;
import com.studyagent.service.domain.verla.VerlaMessage;

import java.util.List;

/**
 * conversation 摘要缓存值。
 * <p>
 * 只缓存高复用的 conversation 本体与 recentMessages；
 * artifacts / toolSummaries / recentToolCalls 继续由 Java 在 /context 聚合时直查 DB。
 */
public record ConversationSummaryCacheValue(
        VerlaConversation conversation,
        List<VerlaMessage> recentMessages
) {
}
