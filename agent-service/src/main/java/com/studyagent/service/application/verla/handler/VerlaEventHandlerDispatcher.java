package com.studyagent.service.application.verla.handler;

import com.studyagent.common.verla.enums.VerlaAgentEventType;
import com.studyagent.common.verla.envelope.VerlaEventEnvelope;
import com.studyagent.service.domain.verla.VerlaEventInbox;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Verla 事件 handler 路由器
 * <p>
 * 扫描所有 {@link VerlaEventHandler} bean，按 {@link VerlaEventHandler#supportedTypes()} 注册到内部表。
 * <p>
 * 未注册的事件类型将被静默 dispatch 成功（表示"有人收到了，只是当前 PR 还没接"），
 * 这样 inbox 行仍能 markProcessed，cursor 才能正常推进，避免阻塞后续严格保序的事件。
 */
@Slf4j
@Component
public class VerlaEventHandlerDispatcher {

    private final Map<VerlaAgentEventType, VerlaEventHandler> registry = new EnumMap<>(VerlaAgentEventType.class);

    public VerlaEventHandlerDispatcher(List<VerlaEventHandler> handlers) {
        for (VerlaEventHandler h : handlers) {
            for (VerlaAgentEventType t : h.supportedTypes()) {
                VerlaEventHandler exists = registry.put(t, h);
                if (exists != null) {
                    log.warn("[Verla/dispatch] duplicated handler for {}: {} overrides {}",
                            t, h.getClass().getSimpleName(), exists.getClass().getSimpleName());
                }
            }
        }
        log.info("[Verla/dispatch] registered handlers: {}", registry.keySet());
    }

    /**
     * 按 row.eventType dispatch 到 handler；
     * 解析失败 / 未注册 都不抛异常，仅打日志（让 cursor 能继续推进）。
     */
    public void dispatch(VerlaEventInbox row, VerlaEventEnvelope env) {
        if (row == null || row.getEventType() == null) {
            return;
        }
        VerlaAgentEventType type;
        try {
            type = VerlaAgentEventType.valueOf(row.getEventType());
        } catch (IllegalArgumentException e) {
            log.warn("[Verla/dispatch] unknown eventType={}, sessionId={}, seq={} → silent processed",
                    row.getEventType(), row.getSessionId(), row.getEventSeq());
            return;
        }
        VerlaEventHandler h = registry.get(type);
        if (h == null) {
            log.debug("[Verla/dispatch] no handler for {} (sessionId={}, seq={}) → silent processed",
                    type, row.getSessionId(), row.getEventSeq());
            return;
        }
        h.handle(row, env);
    }
}
