package com.studyagent.service.application.verla.handler;

import com.studyagent.common.verla.enums.VerlaAgentEventType;
import com.studyagent.common.verla.envelope.VerlaEventEnvelope;
import com.studyagent.service.domain.verla.VerlaEventInbox;

import java.util.Set;

/**
 * Verla 事件 handler 抽象接口
 * <p>
 * 详见 docs/verla-Java侧MVP技术方案.md §8.5 / §11.5。
 * <p>
 * Handler 与 {@link com.studyagent.service.application.verla.VerlaInboxService} 共享同一事务；
 * 抛 {@link RuntimeException} 会回滚整个 ingest 事务并由 listener nack-no-requeue。
 */
public interface VerlaEventHandler {

    /**
     * 该 handler 关心的事件类型集合
     */
    Set<VerlaAgentEventType> supportedTypes();

    /**
     * 处理一条 inbox 行（已校验 seq == expected，且 status=READY）。
     *
     * @param row inbox 中持久化的事件行
     * @param env 原始信封（解析自 row.payloadJson 之外的字段）
     */
    void handle(VerlaEventInbox row, VerlaEventEnvelope env);
}
