package com.studyagent.infra.service.billing;

import com.studyagent.infra.entity.SubscriptionRecoveryActionEntity;
import com.stripe.model.Subscription;
import java.util.Optional;

/** Test-only acknowledgement loss after the real provider write; never a Spring bean. */
final class RecoverySandboxShiftResponseLoss {
    record Evidence(Long actionId, String actionKey, String providerKey, String frozenPayload,
                    String nativeRequestId, String subscriptionId, int nativeExecuteCount) {}

    private final String runId, subscriptionId;
    private Long actionId, recoveryId;
    private String actionKey, providerKey, frozenPayload;
    private Evidence receipt;

    RecoverySandboxShiftResponseLoss(String runId, String subscriptionId) {
        check(text(runId) && text(subscriptionId), "owned scope");
        this.runId=runId;this.subscriptionId=subscriptionId;
    }

    synchronized void arm(SubscriptionRecoveryActionEntity a) {
        check(actionId==null && a!=null, "arm once");
        check("SHIFT_RENEWAL".equals(a.getActionType()) && "READY".equals(a.getStatus())
                && a.getId()!=null && a.getRecoveryId()!=null && subscriptionId.equals(a.getTargetId())
                && text(a.getActionKey()) && text(a.getProviderIdempotencyKey()) && text(a.getPayloadJson()), "persisted ready shift");
        actionId=a.getId();recoveryId=a.getRecoveryId();actionKey=a.getActionKey();
        providerKey=a.getProviderIdempotencyKey();frozenPayload=a.getPayloadJson();
    }

    synchronized SubscriptionRecoveryStripeGateway.WriteResult afterNativeExecute(String runId,
            SubscriptionRecoveryActionEntity claimedAction,
            SubscriptionRecoveryStripeGateway.WriteResult nativeResult) {
        check(claimedAction!=null, "claimed action");
        if(!"SHIFT_RENEWAL".equals(claimedAction.getActionType()))return nativeResult;
        check(this.runId.equals(runId) && actionId!=null, "armed run");
        var a=claimedAction;
        check(actionId.equals(a.getId()) && recoveryId.equals(a.getRecoveryId()) && subscriptionId.equals(a.getTargetId())
                && "DISPATCHING".equals(a.getStatus()) && actionKey.equals(a.getActionKey())
                && providerKey.equals(a.getProviderIdempotencyKey()) && frozenPayload.equals(a.getPayloadJson()), "frozen claimed scope");
        check(receipt==null, "second native execute");
        check(nativeResult!=null, "native result");
        if(!"SUCCEEDED".equals(nativeResult.state()))return nativeResult;
        check(text(nativeResult.requestId()) && nativeResult.object() instanceof Subscription, "native success receipt");
        var sub=(Subscription)nativeResult.object();
        check(subscriptionId.equals(sub.getId()) && Boolean.FALSE.equals(sub.getLivemode()), "native test subscription");
        receipt=new Evidence(actionId,actionKey,providerKey,frozenPayload,nativeResult.requestId(),subscriptionId,1);
        return new SubscriptionRecoveryStripeGateway.WriteResult("UNKNOWN",null,"TEST_INJECTED_SHIFT_ACK_LOSS",null);
    }

    synchronized Optional<Evidence> evidence() { return Optional.ofNullable(receipt); }
    private static boolean text(String value){return value!=null&&!value.isBlank();}
    private static void check(boolean ok,String field){if(!ok)throw new IllegalStateException("SHIFT_ACK_LOSS_SCOPE:"+field);}
}
