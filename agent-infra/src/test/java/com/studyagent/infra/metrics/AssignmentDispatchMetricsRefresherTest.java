package com.studyagent.infra.metrics;

import com.studyagent.infra.mapper.verla.AssignmentRunDispatchMonitorMapper;
import com.studyagent.service.domain.verla.dispatch.AssignmentRunDispatchGate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AssignmentDispatchMetricsRefresherTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @AfterEach
    void closeRegistry() {
        registry.close();
    }

    @Test
    void successfulRefreshReplacesAllGaugeValues() {
        AssignmentRunDispatchMonitorMapper mapper = mock(AssignmentRunDispatchMonitorMapper.class);
        AssignmentRunDispatchGate gate = mock(AssignmentRunDispatchGate.class);
        when(mapper.selectAssignmentDispatchMetrics()).thenReturn(
                new AssignmentDispatchMetricsSnapshot(3, 75L, 2, 1, 4));
        when(gate.activeCount()).thenReturn(5);
        when(gate.maxConcurrency()).thenReturn(8);
        AssignmentDispatchMetricsRefresher refresher =
                new AssignmentDispatchMetricsRefresher(mapper, gate, registry);

        refresher.refresh();

        assertGauge("verla_assignment_dispatch_pending", 3);
        assertGauge("verla_assignment_dispatch_oldest_age_seconds", 75);
        assertGauge("verla_assignment_dispatch_active", 5);
        assertGauge("verla_assignment_dispatch_capacity", 8);
        assertLifecycle("queued", 2);
        assertLifecycle("dispatching", 1);
        assertLifecycle("running", 4);
        assertRefreshCounter("success", 1);
        assertRefreshCounter("error", 0);
    }

    @Test
    void failedRefreshKeepsLastSuccessfulValuesAndRecordsError() {
        AssignmentRunDispatchMonitorMapper mapper = mock(AssignmentRunDispatchMonitorMapper.class);
        AssignmentRunDispatchGate gate = mock(AssignmentRunDispatchGate.class);
        when(mapper.selectAssignmentDispatchMetrics())
                .thenReturn(new AssignmentDispatchMetricsSnapshot(3, 75L, 2, 1, 4))
                .thenThrow(new IllegalStateException("db unavailable"));
        when(gate.activeCount()).thenReturn(5);
        when(gate.maxConcurrency()).thenReturn(8);
        AssignmentDispatchMetricsRefresher refresher =
                new AssignmentDispatchMetricsRefresher(mapper, gate, registry);

        refresher.refresh();
        refresher.refresh();

        assertGauge("verla_assignment_dispatch_pending", 3);
        assertGauge("verla_assignment_dispatch_oldest_age_seconds", 75);
        assertLifecycle("queued", 2);
        assertRefreshCounter("success", 1);
        assertRefreshCounter("error", 1);
    }

    private void assertGauge(String name, double expected) {
        assertEquals(expected, registry.get(name).gauge().value());
    }

    private void assertLifecycle(String state, double expected) {
        assertEquals(expected, registry.get("verla_assignment_lifecycle")
                .tag("state", state).gauge().value());
    }

    private void assertRefreshCounter(String result, double expected) {
        assertEquals(expected, registry.counter(
                "studyagent.metrics.refresh",
                "scope", "assignment_dispatch",
                "result", result).count());
    }
}
