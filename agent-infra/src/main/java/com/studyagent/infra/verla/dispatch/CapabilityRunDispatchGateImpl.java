package com.studyagent.infra.verla.dispatch;

import com.studyagent.common.verla.dispatch.CapabilityRunDispatchActions;
import com.studyagent.common.verla.enums.VerlaCommandAction;
import com.studyagent.service.domain.verla.dispatch.CapabilityRunDispatchGate;
import com.studyagent.service.domain.verla.repo.VerlaSessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 基于 {@code verla_sessions} + {@code mq_outbox} 统计 in-flight capability run 数量。
 */
@Slf4j
@Component
public class CapabilityRunDispatchGateImpl implements CapabilityRunDispatchGate {

    private final VerlaSessionRepository sessionRepository;
    private final int detectionMaxConcurrency;
    private final int humanizerMaxConcurrency;

    public CapabilityRunDispatchGateImpl(
            VerlaSessionRepository sessionRepository,
            @Value("${verla.ai-detection-run.max-concurrency:10}") int detectionMaxConcurrency,
            @Value("${verla.ai-humanizer-run.max-concurrency:20}") int humanizerMaxConcurrency) {
        if (detectionMaxConcurrency < 0 || humanizerMaxConcurrency < 0) {
            throw new IllegalArgumentException(
                    "verla.ai-*-run.max-concurrency must be >= 0");
        }
        this.sessionRepository = sessionRepository;
        this.detectionMaxConcurrency = detectionMaxConcurrency;
        this.humanizerMaxConcurrency = humanizerMaxConcurrency;
        log.info(
                "[Verla/capability-run-dispatch] gate detectionEnabled={} detectionMax={} humanizerEnabled={} humanizerMax={}",
                detectionMaxConcurrency > 0,
                detectionMaxConcurrency,
                humanizerMaxConcurrency > 0,
                humanizerMaxConcurrency);
    }

    @Override
    public boolean isEnabled(String action) {
        return maxConcurrency(action) > 0;
    }

    @Override
    public int maxConcurrency(String action) {
        if (VerlaCommandAction.CMD_DETECTION_RUN.getCode().equals(action)) {
            return detectionMaxConcurrency;
        }
        if (VerlaCommandAction.CMD_HUMANIZER_RUN.getCode().equals(action)) {
            return humanizerMaxConcurrency;
        }
        return 0;
    }

    @Override
    public int activeCount(String action) {
        if (!CapabilityRunDispatchActions.isGated(action)) {
            return 0;
        }
        return sessionRepository.countActiveCapabilityRuns(action);
    }

    @Override
    public boolean canDispatchNow(String action) {
        if (!isEnabled(action)) {
            return true;
        }
        return activeCount(action) < maxConcurrency(action);
    }
}
