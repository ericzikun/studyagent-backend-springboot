package com.studyagent.infra.metrics;

import com.studyagent.infra.mapper.BillingEntitlementFulfillmentMapper;
import com.studyagent.infra.mapper.BillingFulfillmentOpenAggregate;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class BillingFulfillmentMetricsRefresher {
    private final BillingEntitlementFulfillmentMapper mapper;
    private final MeterRegistry meterRegistry;
    private final Map<SeriesKey, SeriesValues> series = new HashMap<>();

    public BillingFulfillmentMetricsRefresher(
            BillingEntitlementFulfillmentMapper mapper,
            MeterRegistry meterRegistry) {
        this.mapper = mapper;
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(fixedDelayString = "${studyagent.metrics.billing-fulfillment.refresh-ms:15000}")
    public synchronized void refresh() {
        final List<BillingFulfillmentOpenAggregate> current;
        try {
            current = mapper.selectOpenAggregates();
        } catch (RuntimeException error) {
            refreshCounter("error").increment();
            log.warn("Billing fulfillment metrics refresh failed; keeping previous snapshot: {}",
                    error.getMessage());
            return;
        }

        series.values().forEach(values -> {
            values.count().set(0L);
            values.oldestAgeSeconds().set(0L);
        });
        LocalDateTime now = LocalDateTime.now();
        for (BillingFulfillmentOpenAggregate aggregate : current) {
            SeriesKey key = new SeriesKey(
                    aggregate.getPurchaseType(),
                    aggregate.getProductCode(),
                    aggregate.getState());
            SeriesValues values = series.computeIfAbsent(key, this::registerSeries);
            values.count().set(aggregate.getOpenCount() == null ? 0L : aggregate.getOpenCount());
            values.oldestAgeSeconds().set(ageSeconds(aggregate.getOldestAcceptedAt(), now));
        }
        refreshCounter("success").increment();
    }

    private SeriesValues registerSeries(SeriesKey key) {
        AtomicLong count = new AtomicLong();
        AtomicLong oldestAgeSeconds = new AtomicLong();
        String[] tags = {
                "purchase_type", key.purchaseType(),
                "product_code", key.productCode(),
                "state", key.state()
        };
        Gauge.builder("billing.entitlement.unfulfilled", count, AtomicLong::doubleValue)
                .tags(tags)
                .register(meterRegistry);
        Gauge.builder("billing.entitlement.unfulfilled.oldest.age.seconds",
                        oldestAgeSeconds,
                        AtomicLong::doubleValue)
                .tags(tags)
                .register(meterRegistry);
        return new SeriesValues(count, oldestAgeSeconds);
    }

    private long ageSeconds(LocalDateTime acceptedAt, LocalDateTime now) {
        if (acceptedAt == null || acceptedAt.isAfter(now)) {
            return 0L;
        }
        return Duration.between(acceptedAt, now).getSeconds();
    }

    private Counter refreshCounter(String result) {
        return Counter.builder("studyagent.metrics.refresh")
                .tags("scope", "billing_fulfillment", "result", result)
                .register(meterRegistry);
    }

    private record SeriesKey(String purchaseType, String productCode, String state) {
    }

    private record SeriesValues(AtomicLong count, AtomicLong oldestAgeSeconds) {
    }
}
