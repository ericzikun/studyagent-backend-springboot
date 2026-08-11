package com.studyagent.service.application.verla.metrics;

import com.studyagent.service.domain.verla.dispatch.AssignmentRunDispatchGate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AssignmentBusinessMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        registry.close();
    }

    @Test
    void acceptedSubmissionBelowCapacityRecordsSuccessOnlyAfterCommit() {
        AssignmentRunDispatchGate gate = mock(AssignmentRunDispatchGate.class);
        when(gate.canDispatchNow()).thenReturn(true);
        AssignmentBusinessMetrics metrics = new AssignmentBusinessMetrics(registry, gate);
        TransactionSynchronizationManager.initSynchronization();

        metrics.recordAcceptedAfterCommit();

        assertCounter(AssignmentBusinessMetrics.Result.SUCCESS, 0.0);
        TransactionSynchronizationUtils.triggerAfterCommit();
        assertCounter(AssignmentBusinessMetrics.Result.SUCCESS, 1.0);
        assertTotalAcrossResults(1.0);
    }

    @Test
    void acceptedSubmissionAtCapacityRecordsQueuedOnlyAfterCommit() {
        AssignmentRunDispatchGate gate = mock(AssignmentRunDispatchGate.class);
        when(gate.canDispatchNow()).thenReturn(false);
        AssignmentBusinessMetrics metrics = new AssignmentBusinessMetrics(registry, gate);
        TransactionSynchronizationManager.initSynchronization();

        metrics.recordAcceptedAfterCommit();

        assertCounter(AssignmentBusinessMetrics.Result.QUEUED, 0.0);
        TransactionSynchronizationUtils.triggerAfterCommit();
        assertCounter(AssignmentBusinessMetrics.Result.QUEUED, 1.0);
        assertTotalAcrossResults(1.0);
    }

    @Test
    void insufficientSubmissionRecordsOnlyInsufficient() {
        AssignmentBusinessMetrics metrics = new AssignmentBusinessMetrics(
                registry, mock(AssignmentRunDispatchGate.class));

        metrics.recordFailure(AssignmentBusinessMetrics.Result.INSUFFICIENT);

        assertCounter(AssignmentBusinessMetrics.Result.INSUFFICIENT, 1.0);
        assertTotalAcrossResults(1.0);
    }

    @Test
    void systemFailureRecordsOnlyError() {
        AssignmentBusinessMetrics metrics = new AssignmentBusinessMetrics(
                registry, mock(AssignmentRunDispatchGate.class));

        metrics.recordFailure(AssignmentBusinessMetrics.Result.ERROR);

        assertCounter(AssignmentBusinessMetrics.Result.ERROR, 1.0);
        assertTotalAcrossResults(1.0);
    }

    @Test
    void assignmentTerminalStatusUsesFrozenLabels() {
        AssignmentBusinessMetrics metrics = new AssignmentBusinessMetrics(
                registry, mock(AssignmentRunDispatchGate.class));

        metrics.recordTerminal(new AssignmentTerminalTransitionedEvent(
                1L, AssignmentTerminalTransitionedEvent.Status.COMPLETED));
        metrics.recordTerminal(new AssignmentTerminalTransitionedEvent(
                2L, AssignmentTerminalTransitionedEvent.Status.FAILED));
        metrics.recordTerminal(new AssignmentTerminalTransitionedEvent(
                3L, AssignmentTerminalTransitionedEvent.Status.CANCELLED));

        assertEquals(1.0, registry.counter(
                "verla.assignment.terminal", "status", "completed").count());
        assertEquals(1.0, registry.counter(
                "verla.assignment.terminal", "status", "failed").count());
        assertEquals(1.0, registry.counter(
                "verla.assignment.terminal", "status", "cancelled").count());
    }

    private void assertCounter(AssignmentBusinessMetrics.Result result, double expected) {
        assertEquals(expected, registry.counter(
                "verla.assignment.submit", "result", result.label()).count());
    }

    private void assertTotalAcrossResults(double expected) {
        double total = registry.find("verla.assignment.submit").counters().stream()
                .mapToDouble(counter -> counter.count())
                .sum();
        assertEquals(expected, total);
    }
}
