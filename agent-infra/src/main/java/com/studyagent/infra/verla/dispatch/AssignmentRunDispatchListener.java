package com.studyagent.infra.verla.dispatch;

import com.studyagent.infra.mq.OutboxDispatchScheduler;
import com.studyagent.service.domain.verla.dispatch.AssignmentRunSlotReleasedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Assignment run slot 释放后，尽快尝试派发仍在 outbox 中等待的命令。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssignmentRunDispatchListener {

    private final OutboxDispatchScheduler outboxDispatchScheduler;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAssignmentRunSlotReleased(AssignmentRunSlotReleasedEvent event) {
        log.debug("[Verla/assignment-run-dispatch] slot released sessionId={}, flushing outbox",
                event.getSessionId());
        try {
            outboxDispatchScheduler.dispatchPendingMessages();
        } catch (Exception e) {
            log.warn("[Verla/assignment-run-dispatch] flush after slot release failed sessionId={}",
                    event.getSessionId(), e);
        }
    }
}
