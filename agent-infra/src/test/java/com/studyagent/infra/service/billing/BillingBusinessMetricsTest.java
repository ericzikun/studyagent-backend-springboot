package com.studyagent.infra.service.billing;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BillingBusinessMetricsTest {

    @Test
    void recordsCheckoutCounterAndTimerWithOnlyFrozenLabels() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BillingBusinessMetrics metrics = new BillingBusinessMetrics(registry);

        BillingBusinessMetrics.Observation observation = metrics.start();
        metrics.recordCheckout(
                observation,
                BillingBusinessMetrics.CheckoutPurchaseType.SUBSCRIPTION,
                BillingBusinessMetrics.Result.SUCCESS,
                BillingBusinessMetrics.ErrorType.NONE);

        assertThat(registry.get("billing.checkout")
                .tags(
                        "purchase_type", "subscription",
                        "result", "success",
                        "error_type", "none")
                .counter()
                .count()).isEqualTo(1.0);
        assertThat(registry.get("billing.checkout.duration")
                .tags(
                        "purchase_type", "subscription",
                        "result", "success",
                        "error_type", "none")
                .timer()
                .count()).isEqualTo(1L);
        assertThat(registry.getMeters())
                .allSatisfy(meter -> assertThat(meter.getId().getTags())
                        .extracting(tag -> tag.getKey())
                        .doesNotContain("session_id", "event_id", "price_id", "user_id"));
    }

    @Test
    void mapsWebhookEventTypesToFrozenCategories() {
        assertThat(BillingBusinessMetrics.WebhookEventType.from("checkout.session.completed").tag())
                .isEqualTo("checkout");
        assertThat(BillingBusinessMetrics.WebhookEventType.from("invoice.paid").tag())
                .isEqualTo("invoice_paid");
        assertThat(BillingBusinessMetrics.WebhookEventType.from("invoice.payment_failed").tag())
                .isEqualTo("invoice_failed");
        assertThat(BillingBusinessMetrics.WebhookEventType.from("customer.subscription.updated").tag())
                .isEqualTo("subscription");
        assertThat(BillingBusinessMetrics.WebhookEventType.from("charge.refunded").tag())
                .isEqualTo("refund");
        assertThat(BillingBusinessMetrics.WebhookEventType.from("charge.dispute.created").tag())
                .isEqualTo("dispute");
        assertThat(BillingBusinessMetrics.WebhookEventType.from("unhandled.event").tag())
                .isEqualTo("other");
    }

    @Test
    void recordsWebhookCounterAndTimerWithFrozenEventCategory() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BillingBusinessMetrics metrics = new BillingBusinessMetrics(registry);

        metrics.recordWebhook(
                metrics.start(),
                BillingBusinessMetrics.WebhookEventType.INVOICE_FAILED,
                BillingBusinessMetrics.Result.SUCCESS,
                BillingBusinessMetrics.ErrorType.NONE);

        assertThat(registry.get("billing.stripe.webhook")
                .tags(
                        "event_type", "invoice_failed",
                        "result", "success",
                        "error_type", "none")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("billing.stripe.webhook.duration")
                .tags(
                        "event_type", "invoice_failed",
                        "result", "success",
                        "error_type", "none")
                .timer().count()).isEqualTo(1L);
    }

    @Test
    void checkoutPurchaseTypesUseOnlyTheFrozenValues() {
        assertThat(BillingBusinessMetrics.CheckoutPurchaseType.values())
                .extracting(BillingBusinessMetrics.CheckoutPurchaseType::tag)
                .containsExactly("subscription", "addon", "subscription_upgrade", "legacy");
    }

    @Test
    void recordsSignatureAndUnknownPriceWithoutRawIdentifiers() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BillingBusinessMetrics metrics = new BillingBusinessMetrics(registry);

        metrics.recordSignatureFailure();
        metrics.recordUnknownPrice(BillingBusinessMetrics.UnknownPricePurchaseType.SUBSCRIPTION);

        assertThat(registry.get("billing.stripe.signature.failure").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("billing.stripe.unknown.price")
                .tag("purchase_type", "subscription")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.getMeters())
                .allSatisfy(meter -> assertThat(meter.getId().getTags())
                        .extracting(tag -> tag.getKey())
                        .doesNotContain("event_id", "price_id", "ip"));
    }
}
