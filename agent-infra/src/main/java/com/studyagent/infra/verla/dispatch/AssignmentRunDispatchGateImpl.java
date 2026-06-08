package com.studyagent.infra.verla.dispatch;

import com.studyagent.service.domain.verla.dispatch.AssignmentRunDispatchGate;
import com.studyagent.service.domain.verla.repo.VerlaSessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 基于 {@code verla_sessions} + {@code mq_outbox} 统计 in-flight assignment run 数量。
 */
@Slf4j
@Component
public class AssignmentRunDispatchGateImpl implements AssignmentRunDispatchGate {

    private final VerlaSessionRepository sessionRepository;
    private final int maxConcurrency;

    public AssignmentRunDispatchGateImpl(
            VerlaSessionRepository sessionRepository,
            @Value("${verla.assignment-run.max-concurrency:4}") int maxConcurrency) {
        if (maxConcurrency < 0) {
            throw new IllegalArgumentException("verla.assignment-run.max-concurrency must be >= 0");
        }
        this.sessionRepository = sessionRepository;
        this.maxConcurrency = maxConcurrency;
        log.info("[Verla/assignment-run-dispatch] gate enabled={} maxConcurrency={}",
                maxConcurrency > 0, maxConcurrency);
    }

    @Override
    public boolean isEnabled() {
        return maxConcurrency > 0;
    }

    @Override
    public int maxConcurrency() {
        return maxConcurrency;
    }

    @Override
    public int activeCount() {
        return sessionRepository.countActiveAssignmentRuns();
    }

    @Override
    public boolean canDispatchNow() {
        if (!isEnabled()) {
            return true;
        }
        return activeCount() < maxConcurrency;
    }
}
