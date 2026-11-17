package com.studyagent.infra.service.billing;

import com.stripe.model.Subscription;
import com.studyagent.infra.entity.SubscriptionRecoveryActionEntity;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecoverySandboxShiftResponseLossTest {
    private static final String RUN = "owned_run_ack_loss";
    private static final String SUB = "sub_owned_ack_loss";
    private static final String PAYLOAD = "{\"trial_end\":1893456000,\"proration_behavior\":\"none\",\"pause_collection\":\"\",\"metadata\":{\"payment_recovery_invoice_id\":\"in_owned\"}}";

    @Test void dropsOnlyAcknowledgementAfterNativeSuccessfulOwnedShift() {
        var policy = armed();
        var result = policy.afterNativeExecute(RUN, claimed(), success());
        assertEquals("UNKNOWN", result.state());
        assertNull(result.requestId(), "Lost acknowledgement must not deliver the native request ID to service");
        assertNull(result.object(), "Service must settle by native read-back");
        assertEquals("TEST_INJECTED_SHIFT_ACK_LOSS", result.error());
        var evidence = policy.evidence().orElseThrow();
        assertEquals(41L, evidence.actionId());
        assertEquals("shift:owned", evidence.actionKey());
        assertEquals("provider_owned_shift", evidence.providerKey());
        assertEquals(PAYLOAD, evidence.frozenPayload());
        assertEquals("req_native_shift", evidence.nativeRequestId());
        assertEquals(SUB, evidence.subscriptionId());
        assertEquals(1, evidence.nativeExecuteCount());
    }

    @Test void rejectsSecondNativeExecuteForSameShiftInsteadOfHidingDuplicateWrite() {
        var policy = armed();
        policy.afterNativeExecute(RUN, claimed(), success());
        assertThrows(IllegalStateException.class, () -> policy.afterNativeExecute(RUN, claimed(), success()));
        assertEquals(1, policy.evidence().orElseThrow().nativeExecuteCount());
    }

    @Test void leavesUnrelatedNormalActionsAndNativeFailuresUntouchedWithoutConsumingLoss() {
        var policy = armed();
        var unrelated = claimed(); unrelated.setActionType("RELEASE_PAUSE");
        var normal = success();
        assertSame(normal, policy.afterNativeExecute(RUN, unrelated, normal));
        for (String state : List.of("FAILED", "UNKNOWN", "DECLINED")) {
            var failure = new SubscriptionRecoveryStripeGateway.WriteResult(state, "req_failed", "native_reason", null);
            assertSame(failure, policy.afterNativeExecute(RUN, claimed(), failure));
            assertTrue(policy.evidence().isEmpty());
        }
        assertEquals("UNKNOWN", policy.afterNativeExecute(RUN, claimed(), normal).state());
        assertTrue(policy.evidence().isPresent());
    }

    @Test void rejectsMissingOrForeignClaimAndFrozenPayloadDrift() {
        var mutations = List.<Consumer<SubscriptionRecoveryActionEntity>>of(
                a -> a.setId(null), a -> a.setId(42L), a -> a.setRecoveryId(null), a -> a.setRecoveryId(72L),
                a -> a.setActionKey(null), a -> a.setActionKey("foreign-action"),
                a -> a.setTargetId("sub_foreign"), a -> a.setStatus("READY"),
                a -> a.setProviderIdempotencyKey(null), a -> a.setProviderIdempotencyKey("foreign-key"),
                a -> a.setPayloadJson(null), a -> a.setPayloadJson(PAYLOAD + " "));
        for (var mutate : mutations) {
            var policy = armed(); var action = claimed(); mutate.accept(action);
            assertThrows(IllegalStateException.class, () -> policy.afterNativeExecute(RUN, action, success()));
            assertTrue(policy.evidence().isEmpty());
        }
        assertThrows(IllegalStateException.class, () -> armed().afterNativeExecute("foreign_run", claimed(), success()));
        assertThrows(IllegalStateException.class, () -> armed().afterNativeExecute(RUN, null, success()));
    }

    @Test void refusesUnarmedOrInadequateNativeSuccessEvidence() {
        assertThrows(IllegalStateException.class, () -> new RecoverySandboxShiftResponseLoss(RUN, SUB)
                .afterNativeExecute(RUN, claimed(), success()));
        var missingRequest = new SubscriptionRecoveryStripeGateway.WriteResult("SUCCEEDED", null, null, success().object());
        assertThrows(IllegalStateException.class, () -> armed().afterNativeExecute(RUN, claimed(), missingRequest));
        var missingObject = new SubscriptionRecoveryStripeGateway.WriteResult("SUCCEEDED", "req_native", null, null);
        assertThrows(IllegalStateException.class, () -> armed().afterNativeExecute(RUN, claimed(), missingObject));
        var foreign = new Subscription(); foreign.setId("sub_foreign"); foreign.setLivemode(false);
        assertThrows(IllegalStateException.class, () -> armed().afterNativeExecute(RUN, claimed(),
                new SubscriptionRecoveryStripeGateway.WriteResult("SUCCEEDED", "req_native", null, foreign)));
        var live = new Subscription(); live.setId(SUB); live.setLivemode(true);
        assertThrows(IllegalStateException.class, () -> armed().afterNativeExecute(RUN, claimed(),
                new SubscriptionRecoveryStripeGateway.WriteResult("SUCCEEDED", "req_native", null, live)));
    }

    @Test void armFreezesValuesAndCannotBeReplaced() {
        var policy = new RecoverySandboxShiftResponseLoss(RUN, SUB);
        var ready = claimed(); ready.setStatus("READY"); policy.arm(ready);
        ready.setPayloadJson("mutated caller object");
        assertEquals("UNKNOWN", policy.afterNativeExecute(RUN, claimed(), success()).state());
        assertEquals(PAYLOAD, policy.evidence().orElseThrow().frozenPayload());
        assertThrows(IllegalStateException.class, () -> policy.arm(ready));
    }

    private static RecoverySandboxShiftResponseLoss armed() {
        var policy = new RecoverySandboxShiftResponseLoss(RUN, SUB);
        var ready = claimed(); ready.setStatus("READY"); policy.arm(ready); return policy;
    }
    private static SubscriptionRecoveryActionEntity claimed() {
        var action = new SubscriptionRecoveryActionEntity();
        action.setId(41L); action.setRecoveryId(71L); action.setActionType("SHIFT_RENEWAL");
        action.setActionKey("shift:owned"); action.setTargetId(SUB); action.setStatus("DISPATCHING");
        action.setProviderIdempotencyKey("provider_owned_shift"); action.setPayloadJson(PAYLOAD);
        action.setTransportAttempts(1); action.setLeaseToken("owned_lease"); return action;
    }
    private static SubscriptionRecoveryStripeGateway.WriteResult success() {
        var subscription = new Subscription(); subscription.setId(SUB); subscription.setLivemode(false);
        return new SubscriptionRecoveryStripeGateway.WriteResult("SUCCEEDED", "req_native_shift", null, subscription);
    }
}
