package com.studyagent.api.service;

import com.studyagent.common.event.AgentEventType;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
@RequiredArgsConstructor
public class LegacyAgentEventMetrics {

    private final MeterRegistry meterRegistry;

    public void record(Result result, String rawEventType) {
        meterRegistry.counter(
                "legacy.agent.event.processing",
                "result", result.label(),
                "event_type", normalizeEventType(rawEventType))
                .increment();
    }

    static String normalizeEventType(String rawEventType) {
        if (rawEventType == null) {
            return "unknown";
        }
        try {
            return AgentEventType.valueOf(rawEventType)
                    .name()
                    .toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException ex) {
            return "unknown";
        }
    }

    public enum Result {
        SUCCESS("success"),
        DUPLICATE("duplicate"),
        IGNORED("ignored"),
        ERROR("error");

        private final String label;

        Result(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }
}
