package com.studyagent.service.application.verla.handler;

import com.studyagent.common.verla.enums.VerlaAgentEventType;
import com.studyagent.common.verla.envelope.VerlaEventEnvelope;
import com.studyagent.service.domain.verla.VerlaEventInbox;
import com.studyagent.service.domain.verla.repo.VerlaConversationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 处理 {@link VerlaAgentEventType#PLAN_TASK_NAME_RESOLVED}：
 * 将 Python ConversationTitleService 生成的对话标题写入 verla_conversations.title。
 *
 * <p>该 handler 对应的 session kind 为 {@code TASK_NAME}，
 * 与 PLAN session 并行由 Java {@code spawnTaskNameSession()} 分流触发。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VerlaConversationTitleEventHandler implements VerlaEventHandler {

    private static final Set<VerlaAgentEventType> SUPPORTED =
            EnumSet.of(VerlaAgentEventType.PLAN_TASK_NAME_RESOLVED);

    private final VerlaConversationRepository conversationRepository;

    @Override
    public Set<VerlaAgentEventType> supportedTypes() {
        return SUPPORTED;
    }

    @Override
    public void handle(VerlaEventInbox row, VerlaEventEnvelope env) {
        Map<String, Object> payload = env == null ? Map.of()
                : env.getPayload() == null ? Map.of() : env.getPayload();

        Object raw = payload.get("taskName");
        if (raw == null) {
            log.warn("[Verla/conversationTitle] PLAN_TASK_NAME_RESOLVED missing taskName, sessionId={} seq={}",
                    row.getSessionId(), row.getEventSeq());
            return;
        }
        String title = raw.toString().trim();
        if (title.isBlank()) {
            log.warn("[Verla/conversationTitle] PLAN_TASK_NAME_RESOLVED empty title, sessionId={}", row.getSessionId());
            return;
        }

        Long conversationId = row.getConversationId();
        int updated = conversationRepository.updateTitle(conversationId, title);
        log.info("[Verla/conversationTitle] PLAN_TASK_NAME_RESOLVED conversationId={} title={} rows={}",
                conversationId, title, updated);
    }
}
