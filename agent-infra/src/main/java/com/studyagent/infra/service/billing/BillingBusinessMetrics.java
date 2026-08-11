package com.studyagent.infra.service.billing;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class BillingBusinessMetrics {

    private final MeterRegistry meterRegistry;

    public BillingBusinessMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public Observation start() {
        return new Observation(Timer.start(meterRegistry));
    }

    public void recordCheckout(
            Observation observation,
            CheckoutPurchaseType purchaseType,
            Result result,
            ErrorType errorType) {
        String[] tags = {
                "purchase_type", purchaseType.tag(),
                "result", result.tag(),
                "error_type", errorType.tag()
        };
        Counter.builder("billing.checkout").tags(tags).register(meterRegistry).increment();
        observation.sample().stop(Timer.builder("billing.checkout.duration").tags(tags).register(meterRegistry));
    }

    public void recordWebhook(
            Observation observation,
            WebhookEventType eventType,
            Result result,
            ErrorType errorType) {
        String[] tags = {
                "event_type", eventType.tag(),
                "result", result.tag(),
                "error_type", errorType.tag()
        };
        Counter.builder("billing.stripe.webhook").tags(tags).register(meterRegistry).increment();
        observation.sample().stop(Timer.builder("billing.stripe.webhook.duration")
                .tags(tags)
                .register(meterRegistry));
    }

    public void recordSignatureFailure() {
        Counter.builder("billing.stripe.signature.failure").register(meterRegistry).increment();
    }

    public void recordUnknownPrice(UnknownPricePurchaseType purchaseType) {
        Counter.builder("billing.stripe.unknown.price")
                .tag("purchase_type", purchaseType.tag())
                .register(meterRegistry)
                .increment();
    }

    public record Observation(Timer.Sample sample) {
    }

    public enum CheckoutPurchaseType {
        SUBSCRIPTION,
        ADDON,
        SUBSCRIPTION_UPGRADE,
        LEGACY;

        public String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public enum Result {
        SUCCESS,
        ERROR,
        IGNORED,
        REVIEW_REQUIRED;

        public String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public enum ErrorType {
        NONE,
        VALIDATION,
        INSUFFICIENT,
        CATALOG,
        SIGNATURE,
        STRIPE_4XX,
        STRIPE_429,
        STRIPE_5XX,
        TIMEOUT,
        DATABASE,
        REDIS,
        OSS,
        MQ,
        INTERNAL,
        UNKNOWN;

        public String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public enum WebhookEventType {
        CHECKOUT,
        INVOICE_PAID,
        INVOICE_FAILED,
        SUBSCRIPTION,
        REFUND,
        DISPUTE,
        OTHER;

        public String tag() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static WebhookEventType from(String eventType) {
            if (eventType == null) {
                return OTHER;
            }
            if (eventType.startsWith("checkout.")) {
                return CHECKOUT;
            }
            if ("invoice.paid".equals(eventType)) {
                return INVOICE_PAID;
            }
            if ("invoice.payment_failed".equals(eventType)) {
                return INVOICE_FAILED;
            }
            if (eventType.startsWith("customer.subscription.")) {
                return SUBSCRIPTION;
            }
            if (eventType.contains("refund") || eventType.contains("refunded")) {
                return REFUND;
            }
            if (eventType.contains("dispute")) {
                return DISPUTE;
            }
            return OTHER;
        }
    }

    public enum UnknownPricePurchaseType {
        SUBSCRIPTION,
        ADDON,
        UNKNOWN;

        public String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
