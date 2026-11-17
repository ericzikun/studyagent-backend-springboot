package com.studyagent.infra.service.billing;

import java.time.Instant;
import java.util.*;

/**
 * Test-only attestation of an owned fixture's controlled operation, never production evidence.
 * The fixture must populate these facts from complete native API reads, not webhook headers,
 * metadata, recipes, or inferred Event/Charge business timestamps. This validates consistency;
 * it cannot authenticate arbitrary caller-supplied facts. No public constructor for Validated
 * exists and no supplied occurredAt value is accepted: only the unchanged ready clock is used.
 */
final class RecoveryOwnedClockEvidence {
    private RecoveryOwnedClockEvidence() {}

    record Scope(String runId, String clockId, String customerId, String subscriptionId) {}
    record ClockState(String clockId, String status, Instant frozenTime) {}
    record SubscriptionState(String subscriptionId, String customerId, String clockId,
                             String itemId, String priceId, Boolean livemode, Long quantity) {
        SubscriptionState(String subscriptionId, String customerId, String clockId,
                          String itemId, String priceId, Boolean livemode) {
            this(subscriptionId, customerId, clockId, itemId, priceId, livemode, 1L);
        }
    }
    record InvoiceFact(String invoiceId, String customerId, String subscriptionId,
                       String paymentIntentId, String priceId, Boolean livemode, Long created) {}
    record PaymentIntentFact(String paymentIntentId, String customerId, String latestChargeId, Boolean livemode) {}
    record ChargeFact(String chargeId, String customerId, String invoiceId, String paymentIntentId,
                      String status, Boolean livemode, Long created) {}
    record FailureEventFact(String eventId, String type, String invoiceId, String customerId,
                            String subscriptionId, String paymentIntentId, Boolean livemode, Long created,
                            String apiVersion, String associationSchema, String requestId) {
        FailureEventFact(String eventId, String type, String invoiceId, String customerId,
                         String subscriptionId, String paymentIntentId, Boolean livemode, Long created,
                         String apiVersion, String associationSchema) {
            this(eventId, type, invoiceId, customerId, subscriptionId, paymentIntentId, livemode,
                    created, apiVersion, associationSchema, null);
        }
    }

    record PrePaymentIntent(String paymentIntentId, String customerId, Boolean livemode,
                            String status, Long amountReceived, String latestChargeId,
                            String lastPaymentError, String nextAction) {}

    /** Native reads immediately before the single explicit off-session Invoice.pay call. */
    record PreAttempt(InvoiceFact invoice, String invoiceStatus, Boolean autoAdvance,
                      Long amountPaid, Long amountRemaining, Boolean attempted, Long attemptCount,
                      Long finalizedAt, PrePaymentIntent paymentIntent,
                      Set<String> chargeIds, Set<String> failedEventIds, Set<String> actionRequiredEventIds,
                      Boolean invoiceReadComplete, Boolean paymentIntentReadComplete,
                      Boolean chargeListComplete, Boolean eventListComplete, Boolean offSession) {
        PreAttempt {
            chargeIds = chargeIds == null ? null : Set.copyOf(chargeIds);
            failedEventIds = failedEventIds == null ? null : Set.copyOf(failedEventIds);
            actionRequiredEventIds = actionRequiredEventIds == null ? null : Set.copyOf(actionRequiredEventIds);
        }
    }

    record Frame(Scope scope, ClockState beforeClock, ClockState afterClock,
                 SubscriptionState beforeSubscription, SubscriptionState afterSubscription,
                 Set<String> beforeInvoiceIds, Set<String> beforeFormalInvoiceIds, Set<String> usedPriceIds,
                 String operationRequestId, InvoiceFact invoice, PaymentIntentFact paymentIntent,
                 List<ChargeFact> charges, List<FailureEventFact> failureEvents,
                 String operationMode, PreAttempt preAttempt) {
        Frame(Scope scope, ClockState beforeClock, ClockState afterClock,
              SubscriptionState beforeSubscription, SubscriptionState afterSubscription,
              Set<String> beforeInvoiceIds, Set<String> beforeFormalInvoiceIds, Set<String> usedPriceIds,
              String operationRequestId, InvoiceFact invoice, PaymentIntentFact paymentIntent,
              List<ChargeFact> charges, List<FailureEventFact> failureEvents) {
            this(scope, beforeClock, afterClock, beforeSubscription, afterSubscription,
                    beforeInvoiceIds, beforeFormalInvoiceIds, usedPriceIds, operationRequestId,
                    invoice, paymentIntent, charges, failureEvents, "PRICE_UPDATE", null);
        }
        Frame {
            // A null remains missing evidence, not an empty successful API result.
            beforeInvoiceIds = beforeInvoiceIds == null ? null : Set.copyOf(beforeInvoiceIds);
            beforeFormalInvoiceIds = beforeFormalInvoiceIds == null ? null : Set.copyOf(beforeFormalInvoiceIds);
            usedPriceIds = usedPriceIds == null ? null : Set.copyOf(usedPriceIds);
            charges = charges == null ? null : List.copyOf(charges);
            failureEvents = failureEvents == null ? null : List.copyOf(failureEvents);
        }
    }

    static final class Validated {
        private final Frame frame;
        private Validated(Frame frame) { this.frame = frame; }
        Frame frame() { return frame; }
        Instant occurredAt() { return frame.beforeClock().frozenTime(); }
    }

    static final class Registry {
        private final Map<String, Validated> byInvoice = new HashMap<>();

        synchronized Validated register(Frame frame) {
            validate(frame);
            var previous = byInvoice.get(frame.invoice().invoiceId());
            if (previous != null) {
                if (!previous.frame().equals(frame))
                    throw new IllegalStateException("OWNED_CLOCK_EVIDENCE_CONFLICT:" + frame.invoice().invoiceId());
                return previous;
            }
            var validated = new Validated(frame);
            byInvoice.put(frame.invoice().invoiceId(), validated);
            return validated;
        }

        synchronized Optional<Validated> find(String runId, String invoiceId) {
            var value = byInvoice.get(invoiceId);
            return value != null && value.frame().scope().runId().equals(runId) ? Optional.of(value) : Optional.empty();
        }
    }

    private static void validate(Frame f) {
        require(f != null, "frame");
        require("PRICE_UPDATE".equals(f.operationMode()) || "EXPLICIT_FIRST_PAY".equals(f.operationMode()), "known operation mode");
        boolean firstPay = "EXPLICIT_FIRST_PAY".equals(f.operationMode());
        var s = f.scope(); require(s != null, "scope");
        text(s.runId()); text(s.clockId()); text(s.customerId()); text(s.subscriptionId());
        var before = f.beforeClock(); var after = f.afterClock();
        require(before != null && after != null, "clock reads");
        require(s.clockId().equals(before.clockId()) && s.clockId().equals(after.clockId()), "clock scope");
        require("ready".equals(before.status()) && "ready".equals(after.status()), "ready clocks");
        require(before.frozenTime() != null && before.frozenTime().getEpochSecond() > 0
                && before.frozenTime().getNano() == 0 && before.frozenTime().equals(after.frozenTime()), "unchanged clock");
        sub(s, f.beforeSubscription()); sub(s, f.afterSubscription());
        require(f.beforeSubscription().itemId().equals(f.afterSubscription().itemId()), "same subscription item");
        if (firstPay) {
            require(f.beforeSubscription().equals(f.afterSubscription()), "unchanged formal subscription");
        } else {
            require(!f.beforeSubscription().priceId().equals(f.afterSubscription().priceId()), "new formal price");
            require(f.preAttempt() == null, "price update has no first-pay proof");
        }
        require(f.beforeInvoiceIds() != null && f.beforeFormalInvoiceIds() != null && f.usedPriceIds() != null, "complete preflight lists");
        f.beforeInvoiceIds().forEach(RecoveryOwnedClockEvidence::text);
        f.usedPriceIds().forEach(RecoveryOwnedClockEvidence::text);
        f.beforeFormalInvoiceIds().forEach(RecoveryOwnedClockEvidence::text);
        if (firstPay) {
            require(f.usedPriceIds().contains(f.afterSubscription().priceId()), "existing formal price");
            require(f.beforeInvoiceIds().containsAll(f.beforeFormalInvoiceIds()), "complete formal invoice inventory");
        } else {
            require(f.beforeFormalInvoiceIds().isEmpty(), "no previous formal invoice");
            require(!f.usedPriceIds().contains(f.afterSubscription().priceId()), "formal price never used");
        }
        text(f.operationRequestId());
        var in = f.invoice(); require(in != null, "invoice");
        text(in.invoiceId()); text(in.paymentIntentId()); created(in.created());
        require(Boolean.FALSE.equals(in.livemode()), "test invoice");
        require(s.customerId().equals(in.customerId()) && s.subscriptionId().equals(in.subscriptionId()), "invoice scope");
        require(f.afterSubscription().priceId().equals(in.priceId()), "formal invoice price");
        if (firstPay) {
            require(f.beforeInvoiceIds().contains(in.invoiceId()) && f.beforeFormalInvoiceIds().contains(in.invoiceId()), "existing formal invoice");
            validatePreAttempt(f);
        } else {
            require(!f.beforeInvoiceIds().contains(in.invoiceId()), "new invoice");
        }
        var pi = f.paymentIntent(); require(pi != null, "payment intent");
        text(pi.latestChargeId());
        require(Boolean.FALSE.equals(pi.livemode()) && in.paymentIntentId().equals(pi.paymentIntentId())
                && s.customerId().equals(pi.customerId()), "payment intent scope");
        require(f.charges() != null && f.charges().size() == 1, "exactly one charge");
        var ch = f.charges().get(0); created(ch.created());
        require(Boolean.FALSE.equals(ch.livemode()) && "failed".equals(ch.status()), "failed test charge");
        require(pi.latestChargeId().equals(ch.chargeId()) && s.customerId().equals(ch.customerId())
                && in.invoiceId().equals(ch.invoiceId()) && pi.paymentIntentId().equals(ch.paymentIntentId()), "charge scope");
        require(f.failureEvents() != null && f.failureEvents().size() == 1, "exactly one failure event");
        var event = f.failureEvents().get(0); text(event.eventId()); created(event.created());
        require(Boolean.FALSE.equals(event.livemode()) && "invoice.payment_failed".equals(event.type()), "failed test event");
        if (firstPay) {
            text(event.requestId());
            require(f.operationRequestId().equals(event.requestId()), "explicit pay native request");
        }
        require(in.invoiceId().equals(event.invoiceId()) && s.customerId().equals(event.customerId())
                && s.subscriptionId().equals(event.subscriptionId()), "event scope");
        // Historical events keep their native API schema even when retrieved using 2023-10-16.
        // The supported parent schema has no native root PI; preserve that absence instead of
        // synthesizing it. The already validated Invoice -> PI -> unique failed Charge is its bridge.
        if ("ROOT_INVOICE".equals(event.associationSchema())) {
            require("2023-10-16".equals(event.apiVersion()), "root event API version");
            require(pi.paymentIntentId().equals(event.paymentIntentId()), "native root event PI");
        } else if ("PARENT_SUBSCRIPTION_DETAILS".equals(event.associationSchema())) {
            require("2025-12-15.clover".equals(event.apiVersion()), "parent event API version");
            require(event.paymentIntentId() == null || pi.paymentIntentId().equals(event.paymentIntentId()), "native parent event PI conflict");
        } else {
            require(false, "known native event schema");
        }
        // Native timestamps remain native. They are intentionally never compared with frozenTime.
    }

    private static void validatePreAttempt(Frame f) {
        var p = f.preAttempt(); require(p != null, "pre-attempt native evidence");
        // invoiceReadComplete includes the complete invoice/formal-invoice/used-price inventory.
        require(Boolean.TRUE.equals(p.invoiceReadComplete()) && Boolean.TRUE.equals(p.paymentIntentReadComplete())
                && Boolean.TRUE.equals(p.chargeListComplete()) && Boolean.TRUE.equals(p.eventListComplete()), "complete pre-attempt native reads");
        require(f.invoice().equals(p.invoice()), "same pre-attempt invoice associations");
        require("open".equals(p.invoiceStatus()) && Boolean.FALSE.equals(p.autoAdvance()), "manually finalized open invoice");
        created(p.finalizedAt());
        require(Long.valueOf(0).equals(p.amountPaid()) && p.amountRemaining() != null && p.amountRemaining() > 0, "unpaid invoice");
        require(Boolean.FALSE.equals(p.attempted()) && Long.valueOf(0).equals(p.attemptCount()), "never attempted invoice");
        var pi = p.paymentIntent(); require(pi != null, "pre-attempt payment intent");
        require(f.invoice().paymentIntentId().equals(pi.paymentIntentId())
                && f.scope().customerId().equals(pi.customerId()) && Boolean.FALSE.equals(pi.livemode()), "pre-attempt payment intent scope");
        require("requires_confirmation".equals(pi.status()) && Long.valueOf(0).equals(pi.amountReceived()), "unconfirmed unpaid payment intent");
        require(pi.latestChargeId() == null && pi.lastPaymentError() == null && pi.nextAction() == null, "untouched payment intent");
        require(p.chargeIds() != null && p.chargeIds().isEmpty(), "zero pre-attempt native charges");
        require(p.failedEventIds() != null && p.failedEventIds().isEmpty()
                && p.actionRequiredEventIds() != null && p.actionRequiredEventIds().isEmpty(), "zero pre-attempt failure or action events");
        require(Boolean.TRUE.equals(p.offSession()), "explicit off-session pay");
    }

    private static void sub(Scope scope, SubscriptionState sub) {
        require(sub != null, "subscription read"); text(sub.itemId()); text(sub.priceId());
        require(sub.quantity() != null && sub.quantity() > 0, "positive subscription quantity");
        require(Boolean.FALSE.equals(sub.livemode()) && scope.subscriptionId().equals(sub.subscriptionId())
                && scope.customerId().equals(sub.customerId()) && scope.clockId().equals(sub.clockId()), "subscription scope");
    }
    private static void created(Long value) { require(value != null && value > 0, "native created"); }
    private static void text(String value) { require(value != null && !value.isBlank(), "required identifier"); }
    private static void require(boolean valid, String field) {
        if (!valid) throw new IllegalArgumentException("INVALID_OWNED_CLOCK_EVIDENCE:" + field);
    }
}
