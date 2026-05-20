package com.studyagent.service.application.verla.cache;

import com.studyagent.service.domain.verla.VerlaMessage;

import java.util.List;

/**
 * conversation 历史消息页缓存值。
 * <p>
 * 仅缓存消息窗口本身，不混入 artifacts / tool trace 等额外聚合字段。
 */
public record ConversationMessagesPageCacheValue(
        List<VerlaMessage> messages
) {
}
