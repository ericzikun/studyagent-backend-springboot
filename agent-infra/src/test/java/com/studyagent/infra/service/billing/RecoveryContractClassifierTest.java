package com.studyagent.infra.service.billing;

import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionSchedule;
import com.stripe.net.ApiResource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryContractClassifierTest {
    @Test void explicitCompleteNoScheduleIsSupportedButMissingReadIsUnknown() {
        assertEquals("NO_SCHEDULE", classify(subscription(false),null,true).scheduleClass());
        assertEquals("UNKNOWN", classify(null,null,false).scheduleClass());
        assertEquals("UNKNOWN", classify(subscription(true),null,true).scheduleClass());
    }
    @Test void completedIntroAndEquivalentFormalPhasesCanBeClassified() {
        var result=classify(subscription(true),schedule("price_formal","release"),true);
        assertEquals("EQUIVALENT_SCHEDULE",result.scheduleClass());assertEquals("RENEW",result.cancelMode());
        assertNotNull(result.contractHash());assertTrue(result.contractJson().contains("price_intro"));
    }
    @Test void futurePriceChangeOrScheduleCancellationCannotBeReleased() {
        assertEquals("FUTURE_CONTRACT_CHANGE",classify(subscription(true),schedule("price_new","release"),true).scheduleClass());
        assertEquals("FIXED_CANCELLATION",classify(subscription(true),schedule("price_formal","cancel"),true).scheduleClass());
    }
    @Test void changedCurrentSubscriptionPriceMustNotLookLikeAnUnchangedNoScheduleContract() {
        var sub=subscription(false);
        var items=ApiResource.GSON.fromJson("{\"data\":[{\"id\":\"si_old\",\"price\":{\"id\":\"price_new\"},\"quantity\":1}]}",com.stripe.model.SubscriptionItemCollection.class);
        sub.setItems(items);
        assertEquals("FUTURE_CONTRACT_CHANGE",classify(sub,null,true).scheduleClass());
    }
    @Test void missingSchedulePhasesOrOwnershipIsRetryReadNotNoSchedule() {
        var missing=schedule("price_formal","release"); missing.setPhases(null);
        assertEquals("UNKNOWN",classify(subscription(true),missing,true).scheduleClass());
        var foreign=schedule("price_formal","release");foreign.setSubscription("sub_other");
        assertEquals("UNKNOWN",classify(subscription(true),foreign,true).scheduleClass());
    }
    @Test void periodEndIntentIsDistinctFromFixedCancelDate() {
        var period=subscription(false);period.setCancelAtPeriodEnd(true);period.setCancelAt(300L);
        var result=classify(period,null,true);assertEquals("NO_SCHEDULE",result.scheduleClass());assertEquals("PERIOD_END",result.cancelMode());
        period.setCancelAtPeriodEnd(false);
        assertEquals("FIXED_CANCELLATION",classify(period,null,true).scheduleClass());
    }
    @Test void noScheduleDiscountOrTaxChangeMustChangeAdmissionHash() {
        var original=subscription(false);
        var json=com.google.gson.JsonParser.parseString(original.toJson()).getAsJsonObject();
        json.add("automatic_tax",com.google.gson.JsonParser.parseString("{\"enabled\":true}"));
        var changed=ApiResource.GSON.fromJson(json,Subscription.class);
        assertNotEquals(classify(original,null,true).contractHash(),classify(changed,null,true).contractHash());
    }
    @Test void controlledPaymentMethodChangeDoesNotInvalidateScheduleContract() {
        var sub=subscription(true);var schedule=schedule("price_formal","release");
        schedule.getDefaultSettings().setDefaultPaymentMethod("pm_old");
        String original=classify(sub,schedule,true).contractHash();
        schedule.getDefaultSettings().setDefaultPaymentMethod("pm_new");
        assertEquals(original,classify(sub,schedule,true).contractHash());
    }
    @Test void operationalPaymentStateDoesNotChangeContractHash() {
        var sub=subscription(true);var schedule=schedule("price_formal","release");
        String before=classify(sub,schedule,true).contractHash();
        sub.setStatus("past_due");sub.setLatestInvoice("in_new");
        assertEquals(before,classify(sub,schedule,true).contractHash());
        schedule.getPhases().get(2).getItems().get(0).setQuantity(2L);
        assertNotEquals(before,classify(sub,schedule,true).contractHash());
    }
    private RecoveryContractClassifier.Result classify(Subscription sub,SubscriptionSchedule schedule,boolean complete) {
        return RecoveryContractClassifier.classify(sub,schedule,complete,"price_formal",1L);
    }
    private Subscription subscription(boolean scheduled) {
        return ApiResource.GSON.fromJson("{\"id\":\"sub_old\",\"customer\":\"cus_test\",\"cancel_at_period_end\":false,\"items\":{\"data\":[{\"price\":{\"id\":\"price_formal\"},\"quantity\":1}]}"+(scheduled?",\"schedule\":\"sched_old\"":"")+"}",Subscription.class);
    }
    private SubscriptionSchedule schedule(String futurePrice,String end) {
        return ApiResource.GSON.fromJson("""
            {"id":"sched_old","subscription":"sub_old","status":"active","end_behavior":"%s",
             "current_phase":{"start_date":100,"end_date":200},"default_settings":{"collection_method":"charge_automatically"},
             "phases":[{"start_date":0,"end_date":100,"items":[{"price":"price_intro","quantity":1}]},
             {"start_date":100,"end_date":200,"items":[{"price":"price_formal","quantity":1}]},
             {"start_date":200,"end_date":300,"items":[{"price":"%s","quantity":1}]}]}
            """.formatted(end,futurePrice),SubscriptionSchedule.class);
    }
}
