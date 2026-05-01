package com.studyagent.service.application.verla.handler;

import com.studyagent.common.verla.enums.VerlaAgentEventType;
import com.studyagent.common.verla.envelope.VerlaEventEnvelope;
import com.studyagent.service.application.verla.VerlaTurnOrchestrator;
import com.studyagent.service.domain.verla.VerlaEventInbox;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Verla Plan 阶段事件 handler（PR-13）
 * <p>
 * 详见 docs/verla-Java侧MVP技术方案.md §11.5。
 * <ul>
 *     <li>{@link VerlaAgentEventType#PLAN_INTENT_RESOLVED} → orchestrator.onPlanResolved → spawn agent</li>
 *     <li>{@link VerlaAgentEventType#PLAN_NEEDS_CLARIFY} → orchestrator.onPlanNeedsClarify → 写 assistant 消息</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VerlaPlanEventHandler implements VerlaEventHandler {

    private static final Set<VerlaAgentEventType> SUPPORTED = EnumSet.of(
            VerlaAgentEventType.PLAN_INTENT_RESOLVED,
            VerlaAgentEventType.PLAN_NEEDS_CLARIFY);

    private final VerlaTurnOrchestrator orchestrator;

    @Override
    public Set<VerlaAgentEventType> supportedTypes() {
        return SUPPORTED;
    }

    @Override
    public void handle(VerlaEventInbox row, VerlaEventEnvelope env) {
        VerlaAgentEventType type = VerlaAgentEventType.valueOf(row.getEventType());
        Long planSessionId = row.getSessionId();
        Map<String, Object> payload = env == null ? Map.of()
                : env.getPayload() == null ? Map.of() : env.getPayload();

        switch (type) {
            case PLAN_INTENT_RESOLVED -> {
                String intent = stringField(payload, "intent");
                Object slotsObj = payload.get("slots");
                @SuppressWarnings("unchecked")
                Map<String, Object> slots = slotsObj instanceof Map<?, ?> m
                        ? (Map<String, Object>) m : Map.of();
                if (intent == null || intent.isBlank()) {
                    log.warn("[Verla/plan] PLAN_INTENT_RESOLVED missing intent, sessionId={} seq={}",
                            planSessionId, row.getEventSeq());
                    return;
                }
                String content = stringField(payload, "content");
                log.info("[Verla/plan] PLAN_INTENT_RESOLVED sessionId={} intent={} slots={}",
                        planSessionId, intent, slots.keySet());
                orchestrator.onPlanResolved(planSessionId, intent, slots, content);
            }
            case PLAN_NEEDS_CLARIFY -> {
                Object clarifyObj = payload.get("clarify");
                @SuppressWarnings("unchecked")
                Map<String, Object> clarifyBlock = clarifyObj instanceof Map<?, ?> m
                        ? (Map<String, Object>) m : payload;
                log.info("[Verla/plan] PLAN_NEEDS_CLARIFY sessionId={} keys={}",
                        planSessionId, clarifyBlock.keySet());
                orchestrator.onPlanNeedsClarify(planSessionId, clarifyBlock);
            }
            default -> log.warn("[Verla/plan] unexpected event {}", type);
        }
    }

    private static String stringField(Map<String, Object> payload, String key) {
        Object v = payload.get(key);
        return v == null ? null : v.toString();
    }
}
