package com.studyagent.service.application.verla.metrics;

import com.studyagent.service.domain.verla.dispatch.AssignmentRunDispatchGate;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@Slf4j
public class AssignmentBusinessMetrics {

    private static final String SUBMIT_METER = "verla.assignment.submit";

    private final MeterRegistry meterRegistry;
    private final AssignmentRunDispatchGate dispatchGate;

    public AssignmentBusinessMetrics(MeterRegistry meterRegistry, AssignmentRunDispatchGate dispatchGate) {
        this.meterRegistry = meterRegistry;
        this.dispatchGate = dispatchGate;
        for (Result result : Result.values()) {
            submitCounter(result);
        }
        for (AssignmentTerminalTransitionedEvent.Status status
                : AssignmentTerminalTransitionedEvent.Status.values()) {
            terminalCounter(status);
        }
    }

    public void recordAcceptedAfterCommit() {
        Result result = classifyAcceptedResult();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    increment(result);
                }
            });
            return;
        }
        increment(result);
    }

    private Result classifyAcceptedResult() {
        try {
            return dispatchGate.canDispatchNow() ? Result.SUCCESS : Result.QUEUED;
        } catch (RuntimeException ex) {
            log.warn("Unable to classify assignment submission queue state; defaulting to success", ex);
            return Result.SUCCESS;
        }
    }

    public void recordFailure(Result result) {
        if (result != Result.INSUFFICIENT && result != Result.ERROR) {
            throw new IllegalArgumentException("Only failure results can be recorded immediately");
        }
        increment(result);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void recordTerminal(AssignmentTerminalTransitionedEvent event) {
        terminalCounter(event.status()).increment();
    }

    private void increment(Result result) {
        submitCounter(result).increment();
    }

    private io.micrometer.core.instrument.Counter submitCounter(Result result) {
        return meterRegistry.counter(SUBMIT_METER, "result", result.label());
    }

    private io.micrometer.core.instrument.Counter terminalCounter(
            AssignmentTerminalTransitionedEvent.Status status) {
        return meterRegistry.counter("verla.assignment.terminal", "status", status.label());
    }

    public enum Result {
        SUCCESS("success"),
        QUEUED("queued"),
        INSUFFICIENT("insufficient"),
        ERROR("error");

        private final String label;

        Result(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
