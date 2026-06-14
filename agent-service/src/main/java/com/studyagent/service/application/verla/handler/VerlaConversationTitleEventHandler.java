package com.studyagent.service.application.verla.handler;

import com.studyagent.common.verla.enums.VerlaAgentEventType;
import com.studyagent.common.verla.envelope.VerlaEventEnvelope;
import com.studyagent.service.application.verla.HumanizerTaskNameDispatcher;
import com.studyagent.service.domain.humanizer.HumanizerTaskTitleWriter;
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
 * 将 Python ConversationTitleService 生成的标题落库。
 *
 * <p>该事件有两个来源，靠 payload 标记区分（dispatcher 每种事件类型只路由到唯一
 * handler，故两路逻辑同处一个 handler）：
 * <ul>
 *   <li>常规会话：session kind {@code TASK_NAME}，由 {@code spawnTaskNameSession()}
 *       触发，写入 {@code verla_conversations.title}；</li>
 *   <li>独立 Humanizer/检测任务：payload 带 {@code scope=HUMANIZER} 与
 *       {@code humanizerTaskId}（见 {@link HumanizerTaskNameDispatcher}），
 *       写入 {@code humanizer_tasks.task_name}。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VerlaConversationTitleEventHandler implements VerlaEventHandler {

    private static final Set<VerlaAgentEventType> SUPPORTED =
            EnumSet.of(
                    VerlaAgentEventType.PLAN_TASK_NAME_RESOLVED,
                    VerlaAgentEventType.PLAN_TASK_NAME_FAILED);

    private final VerlaConversationRepository conversationRepository;
    private final HumanizerTaskTitleWriter humanizerTaskTitleWriter;

    @Override
    public Set<VerlaAgentEventType> supportedTypes() {
        return SUPPORTED;
    }

    @Override
    public void handle(VerlaEventInbox row, VerlaEventEnvelope env) {
        Map<String, Object> payload = env == null ? Map.of()
                : env.getPayload() == null ? Map.of() : env.getPayload();

        Long humanizerTaskId = parseHumanizerTaskId(payload);

        // 标题生成是 best-effort：失败时保留既有/兜底标题，仅记录，不阻断会话。
        if (VerlaAgentEventType.PLAN_TASK_NAME_FAILED.name().equals(row.getEventType())) {
            log.warn("[Verla/conversationTitle] PLAN_TASK_NAME_FAILED conversationId={} sessionId={} humanizerTaskId={} reason={}",
                    row.getConversationId(), row.getSessionId(), humanizerTaskId, payload.get("errorMessage"));
            return;
        }

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

        // 独立 Humanizer/检测任务：回写 humanizer_tasks.task_name，不碰会话标题。
        if (humanizerTaskId != null) {
            int updated = humanizerTaskTitleWriter.updateTaskName(humanizerTaskId, title);
            log.info("[Verla/conversationTitle] PLAN_TASK_NAME_RESOLVED humanizerTaskId={} title={} rows={}",
                    humanizerTaskId, title, updated);
            return;
        }

        Long conversationId = row.getConversationId();
        int updated = conversationRepository.updateTitle(conversationId, title);
        log.info("[Verla/conversationTitle] PLAN_TASK_NAME_RESOLVED conversationId={} title={} rows={}",
                conversationId, title, updated);
    }

    /**
     * 从 payload 解析 Humanizer 任务标记。仅当 {@code scope=HUMANIZER} 且携带合法
     * {@code humanizerTaskId} 时返回任务 id，否则返回 null（按常规会话标题处理）。
     */
    private static Long parseHumanizerTaskId(Map<String, Object> payload) {
        Object scope = payload.get("scope");
        if (!HumanizerTaskNameDispatcher.SCOPE_HUMANIZER.equals(scope == null ? null : scope.toString())) {
            return null;
        }
        Object raw = payload.get("humanizerTaskId");
        if (raw instanceof Number number) {
            return number.longValue();
        }
        if (raw != null) {
            try {
                return Long.parseLong(raw.toString().trim());
            } catch (NumberFormatException ignored) {
                // fallthrough
            }
        }
        return null;
    }
}
