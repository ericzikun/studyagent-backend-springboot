package com.studyagent.infra.service.billing;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.stripe.model.Event;
import java.util.Objects;

/** Explicitly wired test-only gateway; deliberately not a Spring bean. */
final class RecoveryOwnedClockGateway extends SubscriptionRecoveryStripeGateway {
    private final String runId;
    private final RecoveryOwnedClockEvidence.Registry registry;
    private final RecoverySandboxShiftResponseLoss shiftResponseLoss;
    private int shiftNativeExecuteCount;
    private java.util.function.Consumer<com.studyagent.infra.entity.SubscriptionRecoveryActionEntity> checkoutBeforeWrite;
    private int checkoutNativeExecuteCount;
    private java.util.function.Consumer<com.studyagent.infra.entity.SubscriptionRecoveryActionEntity> beforeNativeWrite;
    void observeBeforeNativeWrite(java.util.function.Consumer<com.studyagent.infra.entity.SubscriptionRecoveryActionEntity> observer){beforeNativeWrite=observer;}
    private java.util.function.Consumer<Snapshot> afterNativeRead;
    void observeNativeReads(java.util.function.Consumer<Snapshot> observer){afterNativeRead=observer;}
    @Override public Snapshot readInvoice(String id){
        var snapshot=super.readInvoice(id);
        if(afterNativeRead!=null)afterNativeRead.accept(snapshot);
        return snapshot;
    }
    void observeCheckoutBeforeWrite(java.util.function.Consumer<com.studyagent.infra.entity.SubscriptionRecoveryActionEntity> observer){checkoutBeforeWrite=observer;}
    int checkoutNativeExecuteCount(){return checkoutNativeExecuteCount;}

    RecoveryOwnedClockGateway(String runId, RecoveryOwnedClockEvidence.Registry registry) {
        this(runId,registry,null);
    }
    RecoveryOwnedClockGateway(String runId, RecoveryOwnedClockEvidence.Registry registry, RecoverySandboxShiftResponseLoss shiftResponseLoss) {
        if (runId == null || runId.isBlank()) throw new IllegalArgumentException("Owned run required");
        this.runId = runId; this.registry = Objects.requireNonNull(registry);this.shiftResponseLoss=shiftResponseLoss;
    }

    @Override public WriteResult execute(com.studyagent.infra.entity.SubscriptionRecoveryActionEntity action) {
        if(beforeNativeWrite!=null)beforeNativeWrite.accept(action);
        if("CREATE_CHECKOUT".equals(action.getActionType())){
            if(checkoutBeforeWrite!=null)checkoutBeforeWrite.accept(action);
            checkoutNativeExecuteCount++;
        }
        if(shiftResponseLoss!=null && "SHIFT_RENEWAL".equals(action.getActionType()))shiftNativeExecuteCount++;
        var nativeResult=super.execute(action);
        return shiftResponseLoss==null?nativeResult:shiftResponseLoss.afterNativeExecute(runId,action,nativeResult);
    }
    int shiftNativeExecuteCount(){return shiftNativeExecuteCount;}

    @Override public FirstFailure readFirstFailure(Snapshot snapshot, Event incoming) {
        if (snapshot == null || snapshot.invoice() == null) throw new IllegalStateException("OWNED_CLOCK_SNAPSHOT_REQUIRED");
        var known = registry.find(runId, snapshot.invoice().getId());
        if (known.isEmpty()) return super.readFirstFailure(snapshot, incoming);
        var proof = known.get(); var f = proof.frame(); var scope = f.scope();
        var in = snapshot.invoice(); var sub = snapshot.subscription(); var pi = snapshot.paymentIntent(); var ch = snapshot.charge();
        check(sub != null && pi != null && ch != null && incoming != null, "objects");
        check(Boolean.FALSE.equals(in.getLivemode()) && Boolean.FALSE.equals(sub.getLivemode())
                && Boolean.FALSE.equals(pi.getLivemode()) && Boolean.FALSE.equals(ch.getLivemode())
                && Boolean.FALSE.equals(incoming.getLivemode()), "test objects");
        check(scope.customerId().equals(in.getCustomer()) && scope.subscriptionId().equals(in.getSubscription())
                && f.paymentIntent().paymentIntentId().equals(in.getPaymentIntent()), "invoice scope");
        check(scope.subscriptionId().equals(sub.getId()) && scope.customerId().equals(sub.getCustomer())
                && scope.clockId().equals(sub.getTestClock()), "subscription scope");
        check(f.paymentIntent().paymentIntentId().equals(pi.getId()) && scope.customerId().equals(pi.getCustomer()), "intent scope");
        // A later retry may replace latest_charge. Scope must still match this owned invoice/PI.
        check(ch.getId() != null && ch.getId().equals(pi.getLatestCharge()) && scope.customerId().equals(ch.getCustomer())
                && in.getId().equals(ch.getInvoice()) && pi.getId().equals(ch.getPaymentIntent()), "charge scope");
        check(snapshot.businessNow() != null && !snapshot.businessNow().isBefore(proof.occurredAt()), "business observation");
        var registeredEvent = f.failureEvents().get(0);
        check(registeredEvent.eventId().equals(incoming.getId()) && registeredEvent.type().equals(incoming.getType())
                && registeredEvent.apiVersion().equals(incoming.getApiVersion())
                && registeredEvent.created().equals(incoming.getCreated()), "registered native first event");
        try {
            JsonObject raw = JsonParser.parseString(incoming.getDataObjectDeserializer().getRawJson()).getAsJsonObject();
            check("invoice".equals(id(raw, "object")) && in.getId().equals(id(raw, "id"))
                    && scope.customerId().equals(id(raw, "customer")), "event invoice scope");
            String nativePi = id(raw, "payment_intent");
            check(Objects.equals(registeredEvent.paymentIntentId(), nativePi), "unchanged native event PI");
            if ("ROOT_INVOICE".equals(registeredEvent.associationSchema())) {
                check("2023-10-16".equals(incoming.getApiVersion()) && !present(raw, "parent")
                        && scope.subscriptionId().equals(id(raw, "subscription")) && pi.getId().equals(nativePi), "root event scope");
            } else if ("PARENT_SUBSCRIPTION_DETAILS".equals(registeredEvent.associationSchema())) {
                check("2025-12-15.clover".equals(incoming.getApiVersion()) && !present(raw, "subscription"), "parent event schema");
                var parent = raw.getAsJsonObject("parent");
                check(parent != null && "subscription_details".equals(id(parent, "type")), "native parent type");
                var details = parent.getAsJsonObject("subscription_details");
                check(details != null && scope.subscriptionId().equals(id(details, "subscription")), "native parent subscription");
                check(nativePi == null || pi.getId().equals(nativePi), "parent event PI scope");
            } else {
                check(false, "known native event schema");
            }
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("OWNED_CLOCK_EVENT_SCOPE_REQUIRED", invalid);
        }
        return new FirstFailure(proof.occurredAt(), "owned_test_clock_controlled_operation",
                f.failureEvents().get(0).eventId(), f.charges().get(0).chargeId(), snapshot.businessNow());
    }

    private static String id(JsonObject raw, String key) {
        var value = raw.get(key);
        if (value == null || value.isJsonNull()) return null;
        return value.isJsonObject() ? value.getAsJsonObject().get("id").getAsString() : value.getAsString();
    }
    private static boolean present(JsonObject raw, String key) {
        return raw.has(key) && !raw.get(key).isJsonNull();
    }
    private static void check(boolean valid, String field) {
        if (!valid) throw new IllegalStateException("OWNED_CLOCK_EVIDENCE_SCOPE_MISMATCH:" + field);
    }
}
