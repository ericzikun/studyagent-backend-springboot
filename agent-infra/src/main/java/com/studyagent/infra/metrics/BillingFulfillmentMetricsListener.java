package com.studyagent.infra.metrics;

import com.studyagent.infra.service.billing.BillingEntitlementFulfilledEvent;
import com.studyagent.infra.service.billing.BillingPaymentAcceptedEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class BillingFulfillmentMetricsListener {
    private final MeterRegistry meterRegistry;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentAccepted(BillingPaymentAcceptedEvent event) {
        Counter.builder("billing.payment")
                .tags(
                        "purchase_type", event.purchaseType(),
                        "product_code", event.productCode(),
                        "result", event.result())
                .register(meterRegistry)
                .increment();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onFulfillment(BillingEntitlementFulfilledEvent event) {
        Counter.builder("billing.entitlement.fulfillment")
                .tags(
                        "purchase_type", event.purchaseType(),
                        "product_code", event.productCode(),
                        "result", event.result())
                .register(meterRegistry)
                .increment();
    }
}
