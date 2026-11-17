package com.studyagent.infra.service.billing;

import com.stripe.model.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import static com.studyagent.infra.service.billing.RecoveryOwnedClockEvidence.*;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryOwnedClockEvidenceTest {
    private static final Instant T = Instant.parse("2026-12-01T00:00:00Z");
    // Native created timestamps deliberately differ from the frozen business clock.
    private static final long REAL_CREATED = Instant.parse("2026-11-15T00:00:00Z").getEpochSecond();

    @Test void controlledOperationPinsBusinessFailureToUnchangedReadyClock() {
        var registry = new Registry();
        var proof = registry.register(new Input().frame());
        assertEquals(T, proof.occurredAt());
        assertEquals(REAL_CREATED, proof.frame().charges().get(0).created());
        assertEquals(REAL_CREATED + 1, proof.frame().failureEvents().get(0).created());
        assertSame(proof, registry.register(new Input().frame()));
    }

    @Test void invalidOrIncompleteEvidenceCannotBeRegistered() {
        List<Consumer<Input>> invalid = List.of(
                i -> i.afterClock = new ClockState("clock_own", "ready", T.plusSeconds(1)),
                i -> i.beforeClock = new ClockState("clock_own", "advancing", T),
                i -> i.afterClock = new ClockState("clock_other", "ready", T),
                i -> i.afterClock = new ClockState("clock_own", "internal_failure", T),
                i -> i.requestId = null,
                i -> i.beforeInvoiceIds = Set.of("in_new"),
                i -> i.beforeFormalInvoiceIds = Set.of("in_old_formal"),
                i -> i.usedPriceIds = Set.of("price_new"),
                i -> i.afterSub = new SubscriptionState("sub_other", "cus_own", "clock_own", "si_own", "price_new", false),
                i -> i.afterSub = new SubscriptionState("sub_own", "cus_other", "clock_own", "si_own", "price_new", false),
                i -> i.afterSub = new SubscriptionState("sub_own", "cus_own", "clock_own", "si_other", "price_new", false),
                i -> i.afterSub = new SubscriptionState("sub_own", "cus_own", "clock_own", "si_own", "price_intro", false),
                i -> i.afterSub = new SubscriptionState("sub_own", "cus_own", "clock_own", "si_own", "price_new", true),
                i -> i.invoice = new InvoiceFact("in_new", "cus_own", "sub_own", "pi_other", "price_new", false, REAL_CREATED),
                i -> i.intent = new PaymentIntentFact("pi_new", "cus_other", "ch_new", false),
                i -> i.charges = List.of(),
                i -> i.charges = List.of(i.charges.get(0), i.charges.get(0)),
                i -> i.charges = List.of(new ChargeFact("ch_new", "cus_own", "in_other", "pi_new", "failed", false, REAL_CREATED)),
                i -> i.charges = List.of(new ChargeFact("ch_new", "cus_own", "in_new", "pi_new", "succeeded", false, REAL_CREATED)),
                i -> i.events = List.of(),
                i -> i.events = List.of(i.events.get(0), i.events.get(0)),
                i -> i.events = List.of(new FailureEventFact("evt_new", "invoice.paid", "in_new", "cus_own", "sub_own", "pi_new", false, REAL_CREATED, "2023-10-16", "ROOT_INVOICE")),
                i -> i.events = List.of(new FailureEventFact("evt_new", "invoice.payment_failed", "in_other", "cus_own", "sub_own", "pi_new", false, REAL_CREATED, "2023-10-16", "ROOT_INVOICE")),
                i -> i.events = List.of(new FailureEventFact("evt_new", "invoice.payment_failed", "in_new", "cus_own", "sub_own", "pi_new", false, null, "2023-10-16", "ROOT_INVOICE")),
                i -> i.beforeClock = null,
                i -> i.beforeInvoiceIds = null);
        for (var mutation : invalid) {
            var input = new Input(); mutation.accept(input);
            assertThrows(IllegalArgumentException.class, () -> new Registry().register(input.frame()));
        }
    }

    @Test void registeredInvoiceCannotBeRebasedToAnotherTimeOrRun() {
        var registry = new Registry();
        registry.register(new Input().frame());
        var rebased = new Input();
        rebased.beforeClock = rebased.afterClock = new ClockState("clock_own", "ready", T.plusSeconds(3600));
        assertThrows(IllegalStateException.class, () -> registry.register(rebased.frame()));
        var anotherRun = new Input(); anotherRun.scope = new Scope("run_other", "clock_own", "cus_own", "sub_own");
        assertThrows(IllegalStateException.class, () -> registry.register(anotherRun.frame()));
    }

    @Test void inputCollectionsAreCopiedBeforeTheyCanBeChanged() {
        var input = new Input(); input.beforeInvoiceIds = new HashSet<>(Set.of("in_intro"));
        input.charges = new ArrayList<>(input.charges);
        var frame = input.frame();
        input.beforeInvoiceIds.add("in_new"); input.charges.clear();
        assertEquals(1, new Registry().register(frame).frame().charges().size());
        assertThrows(UnsupportedOperationException.class, () -> frame.beforeInvoiceIds().add("in_new"));
    }

    @Test void gatewayUsesRegisteredScopeAndPreservesFirstFailureOnLaterReplay() {
        var registry = new Registry(); registry.register(new Input().frame());
        var gateway = new RecoveryOwnedClockGateway("run_own", registry);
        var first = gateway.readFirstFailure(snapshot(T), event("evt_new", "in_new"));
        var replay = gateway.readFirstFailure(snapshot(T.plusSeconds(86400)), event("evt_new", "in_new"));
        assertEquals(T, first.occurredAt()); assertEquals(T, replay.occurredAt());
        assertEquals("owned_test_clock_controlled_operation", first.source());
        assertEquals("evt_new", replay.eventId()); assertEquals("ch_new", replay.chargeId());
        assertEquals(T.plusSeconds(86400), replay.businessObservedAt());
        assertThrows(IllegalStateException.class, () -> gateway.readFirstFailure(snapshot(T), event("evt_replaced", "in_new")));
    }

    @Test void nativeParentSchemaKeepsMissingPiNullAndUsesRegisteredInvoicePiChargeBridge() {
        var input = new Input(); input.events = List.of(parentFact("sub_own", null, "2025-12-15.clover", "PARENT_SUBSCRIPTION_DETAILS"));
        var registry = new Registry(); var proof = registry.register(input.frame());
        assertNull(proof.frame().failureEvents().get(0).paymentIntentId());
        assertEquals("2025-12-15.clover", proof.frame().failureEvents().get(0).apiVersion());
        var gateway = new RecoveryOwnedClockGateway("run_own", registry);
        var failure = gateway.readFirstFailure(snapshot(T), parentEvent("\"parent\":{\"type\":\"subscription_details\",\"subscription_details\":{\"subscription\":\"sub_own\"}}"));
        assertEquals(T, failure.occurredAt()); assertEquals("ch_new", failure.chargeId());
    }

    @Test void parentSchemaRejectsMissingOrConflictingNativeAssociations() {
        for (var fact : List.of(
                parentFact(null, null, "2025-12-15.clover", "PARENT_SUBSCRIPTION_DETAILS"),
                parentFact("sub_other", null, "2025-12-15.clover", "PARENT_SUBSCRIPTION_DETAILS"),
                parentFact("sub_own", "pi_other", "2025-12-15.clover", "PARENT_SUBSCRIPTION_DETAILS"),
                parentFact("sub_own", null, "unknown", "PARENT_SUBSCRIPTION_DETAILS"),
                parentFact("sub_own", null, "2025-12-15.clover", "UNKNOWN"),
                parentFact("sub_own", null, "2023-10-16", "ROOT_INVOICE"))) {
            var input = new Input(); input.events = List.of(fact);
            assertThrows(IllegalArgumentException.class, () -> new Registry().register(input.frame()));
        }
        var input = new Input(); input.events = List.of(parentFact("sub_own", null, "2025-12-15.clover", "PARENT_SUBSCRIPTION_DETAILS"));
        var registry = new Registry(); registry.register(input.frame());
        var gateway = new RecoveryOwnedClockGateway("run_own", registry);
        String parent = "\"parent\":{\"type\":\"subscription_details\",\"subscription_details\":{\"subscription\":\"sub_own\"}}";
        for (String raw : List.of(
                "\"payment_intent\":\"pi_new\"", // No native parent; a PI alone cannot substitute for it.
                "\"parent\":{\"type\":\"subscription_details\",\"subscription_details\":{}}",
                "\"parent\":{\"type\":\"unknown\",\"subscription_details\":{\"subscription\":\"sub_own\"}}",
                parent.replace("sub_own", "sub_other"),
                parent + ",\"payment_intent\":\"pi_other\"",
                parent + ",\"subscription\":\"sub_other\"")) {
            assertThrows(IllegalStateException.class, () -> gateway.readFirstFailure(snapshot(T), parentEvent(raw)));
        }
        var unknown = new RecoveryOwnedClockGateway("run_own", new Registry());
        var rejected = assertThrows(IllegalStateException.class, () -> unknown.readFirstFailure(snapshot(T), parentEvent(parent)));
        assertTrue(rejected.getMessage().contains("TEST_CLOCK_BUSINESS_EVIDENCE_REQUIRED"));
    }

    @Test void parentSchemaCannotBypassTheNativeInvoicePiUniqueChargeBridge() {
        var input = new Input(); input.events = List.of(parentFact("sub_own", null, "2025-12-15.clover", "PARENT_SUBSCRIPTION_DETAILS"));
        input.intent = new PaymentIntentFact("pi_other", "cus_own", "ch_new", false);
        var conflictingIntent = input;
        assertThrows(IllegalArgumentException.class, () -> new Registry().register(conflictingIntent.frame()));
        input = new Input(); input.events = List.of(parentFact("sub_own", null, "2025-12-15.clover", "PARENT_SUBSCRIPTION_DETAILS"));
        input.charges = List.of(input.charges.get(0), input.charges.get(0));
        var duplicateCharge = input;
        assertThrows(IllegalArgumentException.class, () -> new Registry().register(duplicateCharge.frame()));
    }

    @Test void explicitFirstPayUsesExistingFormalRenewalAndUnchangedClock() {
        var input = firstPay();
        var proof = new Registry().register(input.frame());
        assertEquals(T, proof.occurredAt());
        assertEquals("EXPLICIT_FIRST_PAY", proof.frame().operationMode());
        assertEquals(input.beforeSub, proof.frame().afterSubscription());
        assertTrue(proof.frame().beforeFormalInvoiceIds().contains("in_new"));
        var gateway = new RecoveryOwnedClockGateway("run_own", registered(input));
        assertEquals(T, gateway.readFirstFailure(snapshot(T.plusSeconds(86400)), event("evt_new", "in_new")).occurredAt());
    }

    @Test void explicitFirstPaySupportsNativeParentEventWithoutInventingPi() {
        var input = firstPay();
        input.events = List.of(new FailureEventFact("evt_new", "invoice.payment_failed", "in_new", "cus_own",
                "sub_own", null, false, REAL_CREATED + 1, "2025-12-15.clover", "PARENT_SUBSCRIPTION_DETAILS", "req_pay"));
        assertEquals(T, new Registry().register(input.frame()).occurredAt());
    }

    @Test void explicitFirstPayRejectsChangedQuantity() {
        var input = firstPay();
        input.afterSub = new SubscriptionState("sub_own", "cus_own", "clock_own", "si_own", "price_new", false, 2L);
        assertThrows(IllegalArgumentException.class, () -> new Registry().register(input.frame()));
    }

    @Test void explicitFirstPayRejectsMissingQuantityEvenWhenSubscriptionReadsAgree() {
        assertInvalidFirstPayQuantity(null);
    }

    @Test void explicitFirstPayRejectsZeroQuantityEvenWhenSubscriptionReadsAgree() {
        assertInvalidFirstPayQuantity(0L);
    }

    @Test void explicitFirstPayRejectsNegativeQuantityEvenWhenSubscriptionReadsAgree() {
        assertInvalidFirstPayQuantity(-1L);
    }

    private static void assertInvalidFirstPayQuantity(Long quantity) {
        var input = firstPay();
        input.beforeSub = input.afterSub = new SubscriptionState("sub_own", "cus_own", "clock_own", "si_own", "price_new", false, quantity);
        assertThrows(IllegalArgumentException.class, () -> new Registry().register(input.frame()));
    }

    @Test void explicitFirstPayRejectsIncompleteOrAlreadyAttemptedNativeEvidence() {
        List<Consumer<Input>> invalid = List.of(
                i -> i.operationMode = "UNKNOWN",
                i -> i.requestId = null,
                i -> i.requestId = " ",
                i -> i.events = List.of(failureWithRequest(null)),
                i -> i.events = List.of(failureWithRequest(" ")),
                i -> i.preAttempt = null,
                i -> i.beforeInvoiceIds = Set.of("in_intro"),
                i -> i.beforeFormalInvoiceIds = Set.of(),
                i -> i.beforeFormalInvoiceIds = Set.of("in_other"),
                i -> i.usedPriceIds = Set.of("price_intro"),
                i -> i.beforeSub = new SubscriptionState("sub_own", "cus_own", "clock_own", "si_own", "price_intro", false),
                i -> i.afterSub = new SubscriptionState("sub_own", "cus_own", "clock_own", "si_other", "price_new", false),
                i -> i.scope = new Scope("run_own", "clock_other", "cus_own", "sub_own"),
                i -> i.afterClock = new ClockState("clock_own", "ready", T.plusSeconds(1)),
                i -> i.events = List.of(failureWithRequest("req_other")),
                i -> i.charges = List.of(),
                i -> i.events = List.of());
        for (var mutation : invalid) {
            var input = firstPay(); mutation.accept(input);
            assertThrows(IllegalArgumentException.class, () -> new Registry().register(input.frame()));
        }
        List<Consumer<PreInput>> badPre = List.of(
                p -> p.invoice = new InvoiceFact("in_other", "cus_own", "sub_own", "pi_new", "price_new", false, REAL_CREATED),
                p -> p.invoice = new InvoiceFact("in_new", "cus_other", "sub_own", "pi_new", "price_new", false, REAL_CREATED),
                p -> p.status = "draft", p -> p.autoAdvance = true, p -> p.autoAdvance = null,
                p -> p.amountPaid = 1L, p -> p.amountPaid = null,
                p -> p.amountRemaining = 0L, p -> p.amountRemaining = null,
                p -> p.attempted = true, p -> p.attempted = null,
                p -> p.attemptCount = 1L, p -> p.attemptCount = null,
                p -> p.finalizedAt = null,
                p -> p.piId = "pi_other", p -> p.piCustomer = "cus_other", p -> p.piLivemode = true,
                p -> p.piStatus = "requires_payment_method", p -> p.piStatus = null,
                p -> p.amountReceived = 1L, p -> p.amountReceived = null,
                p -> p.latestCharge = "ch_old", p -> p.lastError = "card_declined", p -> p.nextAction = "redirect",
                p -> p.chargeIds = Set.of("ch_old"), p -> p.chargeIds = null,
                p -> p.failedIds = Set.of("evt_old"), p -> p.failedIds = null,
                p -> p.actionIds = Set.of("evt_action"), p -> p.actionIds = null,
                p -> p.invoiceComplete = false, p -> p.piComplete = null,
                p -> p.chargesComplete = false, p -> p.eventsComplete = false, p -> p.offSession = false);
        for (var mutation : badPre) {
            var input = firstPay(); var pre = new PreInput(); mutation.accept(pre); input.preAttempt = pre.fact();
            assertThrows(IllegalArgumentException.class, () -> new Registry().register(input.frame()));
        }
    }

    @Test void unknownModeCannotBorrowPriceUpdateProof() {
        var input = new Input(); input.operationMode = "UNKNOWN";
        assertThrows(IllegalArgumentException.class, () -> new Registry().register(input.frame()));
    }

    @Test void preAttemptNativeCollectionsAreImmutableCopies() {
        var pre = new PreInput(); pre.chargeIds = new HashSet<>();
        var fact = pre.fact(); pre.chargeIds.add("ch_old");
        assertTrue(fact.chargeIds().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> fact.chargeIds().add("ch_old"));
    }

    private static Registry registered(Input input) {
        var registry = new Registry(); registry.register(input.frame()); return registry;
    }

    private static FailureEventFact failureWithRequest(String request) {
        return new FailureEventFact("evt_new", "invoice.payment_failed", "in_new", "cus_own", "sub_own", "pi_new",
                false, REAL_CREATED + 1, "2023-10-16", "ROOT_INVOICE", request);
    }

    private static Input firstPay() {
        var input = new Input(); input.operationMode = "EXPLICIT_FIRST_PAY";
        input.beforeSub = input.afterSub;
        input.beforeInvoiceIds = Set.of("in_intro", "in_new");
        input.beforeFormalInvoiceIds = Set.of("in_new"); input.usedPriceIds = Set.of("price_intro", "price_new");
        input.requestId = "req_pay"; input.events = List.of(failureWithRequest("req_pay"));
        input.preAttempt = new PreInput().fact(); return input;
    }

    private static class PreInput {
        InvoiceFact invoice = new InvoiceFact("in_new", "cus_own", "sub_own", "pi_new", "price_new", false, REAL_CREATED);
        String status = "open"; Boolean autoAdvance = false, attempted = false;
        Long amountPaid = 0L, amountRemaining = 100L, attemptCount = 0L, finalizedAt = REAL_CREATED;
        String piId = "pi_new", piCustomer = "cus_own", piStatus = "requires_confirmation";
        Boolean piLivemode = false; Long amountReceived = 0L;
        String latestCharge = null, lastError = null, nextAction = null;
        Set<String> chargeIds = Set.of(), failedIds = Set.of(), actionIds = Set.of();
        Boolean invoiceComplete = true, piComplete = true, chargesComplete = true, eventsComplete = true, offSession = true;
        PreAttempt fact() {
            return new PreAttempt(invoice, status, autoAdvance, amountPaid, amountRemaining, attempted,
                    attemptCount, finalizedAt, new PrePaymentIntent(piId, piCustomer, piLivemode, piStatus,
                    amountReceived, latestCharge, lastError, nextAction), chargeIds, failedIds, actionIds,
                    invoiceComplete, piComplete, chargesComplete, eventsComplete, offSession);
        }
    }

    private static FailureEventFact parentFact(String subscription, String pi, String version, String schema) {
        return new FailureEventFact("evt_new", "invoice.payment_failed", "in_new", "cus_own", subscription, pi,
                false, REAL_CREATED + 1, version, schema);
    }

    private static Event parentEvent(String rawAssociations) {
        return com.stripe.net.ApiResource.GSON.fromJson("{\"id\":\"evt_new\",\"type\":\"invoice.payment_failed\",\"api_version\":\"2025-12-15.clover\",\"livemode\":false,\"created\":" + (REAL_CREATED + 1) + ",\"data\":{\"object\":{\"object\":\"invoice\",\"id\":\"in_new\",\"customer\":\"cus_own\"," + rawAssociations + "}}}", Event.class);
    }

    @Test void gatewayDoesNotAcceptHeadersOrMetadataAsBusinessTimeEvidence() {
        var snapshot = snapshot(T);
        snapshot.invoice().setMetadata(Map.of("first_failure_at", T.toString(), "run_id", "run_own"));
        var empty = new RecoveryOwnedClockGateway("run_own", new Registry());
        assertThrows(IllegalStateException.class, () -> empty.readFirstFailure(snapshot, event("evt_new", "in_new")));
        var registry = new Registry(); registry.register(new Input().frame());
        assertThrows(IllegalStateException.class, () -> new RecoveryOwnedClockGateway("run_other", registry).readFirstFailure(snapshot, event("evt_new", "in_new")));
        var gateway = new RecoveryOwnedClockGateway("run_own", registry);
        assertThrows(IllegalStateException.class, () -> gateway.readFirstFailure(snapshot(T.minusSeconds(1)), event("evt_new", "in_new")));
        assertThrows(IllegalStateException.class, () -> gateway.readFirstFailure(snapshot, event("evt_new", "in_other")));
        snapshot.subscription().setTestClock("clock_other");
        assertThrows(IllegalStateException.class, () -> gateway.readFirstFailure(snapshot, event("evt_new", "in_new")));
    }

    private static SubscriptionRecoveryStripeGateway.Snapshot snapshot(Instant now) {
        var in = new Invoice(); in.setId("in_new"); in.setCustomer("cus_own"); in.setSubscription("sub_own");
        in.setPaymentIntent("pi_new"); in.setLivemode(false); in.setCreated(REAL_CREATED);
        var sub = new Subscription(); sub.setId("sub_own"); sub.setCustomer("cus_own"); sub.setTestClock("clock_own"); sub.setLivemode(false);
        var pi = new PaymentIntent(); pi.setId("pi_new"); pi.setCustomer("cus_own"); pi.setLatestCharge("ch_new"); pi.setLivemode(false);
        var ch = new Charge(); ch.setId("ch_new"); ch.setCustomer("cus_own"); ch.setInvoice("in_new"); ch.setPaymentIntent("pi_new"); ch.setLivemode(false);
        return new SubscriptionRecoveryStripeGateway.Snapshot(in, sub, null, List.of(), pi, ch, now);
    }

    private static Event event(String id, String invoice) {
        return com.stripe.net.ApiResource.GSON.fromJson("{\"id\":\"" + id + "\",\"type\":\"invoice.payment_failed\",\"api_version\":\"2023-10-16\",\"livemode\":false,\"created\":" + (REAL_CREATED + 1) + ",\"data\":{\"object\":{\"object\":\"invoice\",\"id\":\"" + invoice + "\",\"customer\":\"cus_own\",\"subscription\":\"sub_own\",\"payment_intent\":\"pi_new\"}}}", Event.class);
    }

    private static class Input {
        Scope scope = new Scope("run_own", "clock_own", "cus_own", "sub_own");
        ClockState beforeClock = new ClockState("clock_own", "ready", T), afterClock = beforeClock;
        SubscriptionState beforeSub = new SubscriptionState("sub_own", "cus_own", "clock_own", "si_own", "price_intro", false);
        SubscriptionState afterSub = new SubscriptionState("sub_own", "cus_own", "clock_own", "si_own", "price_new", false);
        Set<String> beforeInvoiceIds = Set.of("in_intro"), beforeFormalInvoiceIds = Set.of(), usedPriceIds = Set.of("price_intro");
        String requestId = "req_update";
        String operationMode = "PRICE_UPDATE"; PreAttempt preAttempt;
        InvoiceFact invoice = new InvoiceFact("in_new", "cus_own", "sub_own", "pi_new", "price_new", false, REAL_CREATED);
        PaymentIntentFact intent = new PaymentIntentFact("pi_new", "cus_own", "ch_new", false);
        List<ChargeFact> charges = List.of(new ChargeFact("ch_new", "cus_own", "in_new", "pi_new", "failed", false, REAL_CREATED));
        List<FailureEventFact> events = List.of(new FailureEventFact("evt_new", "invoice.payment_failed", "in_new", "cus_own", "sub_own", "pi_new", false, REAL_CREATED + 1, "2023-10-16", "ROOT_INVOICE"));
        Frame frame() { return new Frame(scope, beforeClock, afterClock, beforeSub, afterSub, beforeInvoiceIds,
                beforeFormalInvoiceIds, usedPriceIds, requestId, invoice, intent, charges, events, operationMode, preAttempt); }
    }
}
