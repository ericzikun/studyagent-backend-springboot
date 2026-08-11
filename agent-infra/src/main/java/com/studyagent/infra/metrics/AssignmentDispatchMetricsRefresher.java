package com.studyagent.infra.metrics;

import com.studyagent.infra.mapper.verla.AssignmentRunDispatchMonitorMapper;
import com.studyagent.service.domain.verla.dispatch.AssignmentRunDispatchGate;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
@Slf4j
public class AssignmentDispatchMetricsRefresher {

    private static final String REFRESH_SCOPE = "assignment_dispatch";

    private final AssignmentRunDispatchMonitorMapper mapper;
    private final AssignmentRunDispatchGate dispatchGate;
    private final MeterRegistry meterRegistry;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong oldestAgeSeconds = new AtomicLong();
    private final AtomicLong active = new AtomicLong();
    private final AtomicLong capacity = new AtomicLong();
    private final Map<String, AtomicLong> lifecycle = new ConcurrentHashMap<>();

    public AssignmentDispatchMetricsRefresher(
            AssignmentRunDispatchMonitorMapper mapper,
            AssignmentRunDispatchGate dispatchGate,
            MeterRegistry meterRegistry) {
        this.mapper = mapper;
        this.dispatchGate = dispatchGate;
        this.meterRegistry = meterRegistry;
        registerGauge("verla_assignment_dispatch_pending", pending);
        registerGauge("verla_assignment_dispatch_oldest_age_seconds", oldestAgeSeconds);
        registerGauge("verla_assignment_dispatch_active", active);
        registerGauge("verla_assignment_dispatch_capacity", capacity);
        for (String state : new String[]{"queued", "dispatching", "running"}) {
            AtomicLong value = new AtomicLong();
            lifecycle.put(state, value);
            Gauge.builder("verla_assignment_lifecycle", value, AtomicLong::get)
                    .tag("state", state)
                    .register(meterRegistry);
        }
        refreshCounter("success");
        refreshCounter("error");
    }

    @Scheduled(fixedDelayString = "${metrics.assignment-dispatch.refresh-ms:15000}")
    public void refresh() {
        try {
            AssignmentDispatchMetricsSnapshot snapshot = mapper.selectAssignmentDispatchMetrics();
            if (snapshot == null) {
                throw new IllegalStateException("assignment dispatch metrics query returned null");
            }
            int currentActive = dispatchGate.activeCount();
            int currentCapacity = dispatchGate.maxConcurrency();

            pending.set(nonNegative(snapshot.getPending()));
            oldestAgeSeconds.set(nonNegative(snapshot.getOldestAgeSeconds()));
            active.set(Math.max(0, currentActive));
            capacity.set(Math.max(0, currentCapacity));
            lifecycle.get("queued").set(nonNegative(snapshot.getQueued()));
            lifecycle.get("dispatching").set(nonNegative(snapshot.getDispatching()));
            lifecycle.get("running").set(nonNegative(snapshot.getRunning()));
            refreshCounter("success").increment();
        } catch (Exception ex) {
            refreshCounter("error").increment();
            log.warn("Unable to refresh assignment dispatch metrics; keeping last successful values", ex);
        }
    }

    private void registerGauge(String name, AtomicLong value) {
        Gauge.builder(name, value, AtomicLong::get).register(meterRegistry);
    }

    private io.micrometer.core.instrument.Counter refreshCounter(String result) {
        return meterRegistry.counter(
                "studyagent.metrics.refresh",
                "scope", REFRESH_SCOPE,
                "result", result);
    }

    private static long nonNegative(Number value) {
        return value == null ? 0 : Math.max(0, value.longValue());
    }
}
