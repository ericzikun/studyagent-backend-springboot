package com.studyagent.infra.service.billing;

import com.stripe.param.checkout.SessionCreateParams;
import com.studyagent.infra.entity.SubscriptionRecoveryActionEntity;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Instant;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SubscriptionRecoveryStripeGatewayTest {
    @Test void realCollectionOmitsTheTwoInvalidFalseFlags() {
        assertEquals(Map.of("off_session",true),SubscriptionRecoveryStripeGateway.retryParams());
    }
    @Test void cancellationAndDraftFinalizationAreExplicitlySilent() {
        assertEquals(Map.of("invoice_now",false,"prorate",false),SubscriptionRecoveryStripeGateway.cancelParams());
        assertEquals(Map.of("auto_advance",false),SubscriptionRecoveryStripeGateway.finalizeParams());
    }
    @Test void shiftUsesTheExactPaidEndAndOneAtomicRelease() {
        var params=SubscriptionRecoveryStripeGateway.shiftParams("in_paid",Instant.ofEpochSecond(1800000000));
        assertEquals(1800000000L,params.get("trial_end"));assertEquals("none",params.get("proration_behavior"));
        assertEquals("",params.get("pause_collection"));assertFalse(params.containsKey("billing_cycle_anchor"));
        assertEquals(Map.of("payment_recovery_invoice_id","in_paid"),params.get("metadata"));
    }
    @Test void freezingUsesSdkMapAndRestoresIntegerAndNestedBooleanSemantics() {
        var params=SessionCreateParams.builder().setMode(SessionCreateParams.Mode.SUBSCRIPTION).setExpiresAt(1800000000L)
                .putMetadata("order_no","order1").addLineItem(SessionCreateParams.LineItem.builder().setPrice("price_1").setQuantity(1L).build()).build();
        var thawed=SubscriptionRecoveryStripeGateway.thaw(SubscriptionRecoveryStripeGateway.freeze(params.toMap()));
        assertEquals(params.toMap(),thawed);assertInstanceOf(Long.class,thawed.get("expires_at"));
    }
    @Test void cashProofRemainsReadableWhenOnlyTheSubscriptionMetadataRequestFails() {
        var invoice=com.stripe.net.ApiResource.GSON.fromJson("{\"id\":\"in_paid\",\"subscription\":\"sub_old\",\"customer\":\"cus_old\",\"status\":\"paid\",\"amount_paid\":100,\"payment_intent\":\"pi_paid\",\"lines\":{\"data\":[{\"id\":\"il_paid\"}],\"has_more\":false}}",com.stripe.model.Invoice.class);var pi=new com.stripe.model.PaymentIntent();pi.setId("pi_paid");pi.setLatestCharge("ch_paid");var charge=new com.stripe.model.Charge();charge.setId("ch_paid");
        try(var invoices=org.mockito.Mockito.mockStatic(com.stripe.model.Invoice.class);var subs=org.mockito.Mockito.mockStatic(com.stripe.model.Subscription.class);var intents=org.mockito.Mockito.mockStatic(com.stripe.model.PaymentIntent.class);var charges=org.mockito.Mockito.mockStatic(com.stripe.model.Charge.class)){
            invoices.when(()->com.stripe.model.Invoice.retrieve("in_paid")).thenReturn(invoice);subs.when(()->com.stripe.model.Subscription.retrieve("sub_old")).thenThrow(org.mockito.Mockito.mock(com.stripe.exception.ApiConnectionException.class));intents.when(()->com.stripe.model.PaymentIntent.retrieve("pi_paid")).thenReturn(pi);charges.when(()->com.stripe.model.Charge.retrieve("ch_paid")).thenReturn(charge);
            var snapshot=new SubscriptionRecoveryStripeGateway().readInvoice("in_paid");assertSame(pi,snapshot.paymentIntent());assertSame(charge,snapshot.charge());assertEquals(1,snapshot.lines().size());assertNull(snapshot.subscription());
        }
    }
    @Test void historicalEventsCanIdentifyAnEarlierAuthenticationFailureWithoutACharge() {
        Instant end=Instant.parse("2026-11-18T02:00:00Z"),first=end.minusSeconds(7*86400L);var gateway=new SubscriptionRecoveryStripeGateway(java.time.Clock.fixed(end,java.time.ZoneOffset.UTC));var invoice=new com.stripe.model.Invoice();invoice.setId("in_history");invoice.setCustomer("cus_own");invoice.setCreated(first.getEpochSecond());invoice.setPaymentIntent("pi_own");var sub=new com.stripe.model.Subscription();sub.setId("sub_own");var snapshot=new SubscriptionRecoveryStripeGateway.Snapshot(invoice,sub,null,java.util.List.of(),null,null,end);var current=historyEvent("evt_later","invoice.payment_failed",end.getEpochSecond(),"in_history");var authentication=historyEvent("evt_first","invoice.payment_action_required",first.getEpochSecond(),"in_history");var unrelated=historyEvent("evt_other","invoice.payment_failed",first.minusSeconds(1).getEpochSecond(),"in_other");var events=org.mockito.Mockito.mock(com.stripe.model.EventCollection.class);org.mockito.Mockito.when(events.autoPagingIterable()).thenReturn(java.util.List.of(current,unrelated,authentication));var charges=org.mockito.Mockito.mock(com.stripe.model.ChargeCollection.class);org.mockito.Mockito.when(charges.autoPagingIterable()).thenReturn(java.util.List.of());
        try(var eventApi=org.mockito.Mockito.mockStatic(com.stripe.model.Event.class);var chargeApi=org.mockito.Mockito.mockStatic(com.stripe.model.Charge.class)){
            eventApi.when(()->com.stripe.model.Event.list(org.mockito.ArgumentMatchers.anyMap())).thenReturn(events);chargeApi.when(()->com.stripe.model.Charge.list(org.mockito.ArgumentMatchers.anyMap())).thenReturn(charges);var proof=gateway.readFirstFailure(snapshot,current);assertEquals(first,proof.occurredAt());assertEquals("evt_first",proof.eventId());assertEquals(end,proof.businessObservedAt());
        }
    }
    @Test void chargeCreationDoesNotPredateTheActualFailureEventWhenProcessingWasDelayed() {
        Instant now=Instant.parse("2026-11-18T02:00:00Z"),created=now.minusSeconds(7*86400L),failed=created.plusSeconds(2*86400L);var gateway=new SubscriptionRecoveryStripeGateway(java.time.Clock.fixed(now,java.time.ZoneOffset.UTC));var invoice=new com.stripe.model.Invoice();invoice.setId("in_delayed");invoice.setCustomer("cus_own");invoice.setCreated(created.getEpochSecond());invoice.setPaymentIntent("pi_own");var snapshot=new SubscriptionRecoveryStripeGateway.Snapshot(invoice,null,null,java.util.List.of(),null,null,now);var charge=new com.stripe.model.Charge();charge.setId("ch_delayed");charge.setCustomer("cus_own");charge.setInvoice("in_delayed");charge.setPaymentIntent("pi_own");charge.setStatus("failed");charge.setCreated(created.getEpochSecond());var events=org.mockito.Mockito.mock(com.stripe.model.EventCollection.class);org.mockito.Mockito.when(events.autoPagingIterable()).thenReturn(java.util.List.of(historyEvent("evt_failure","invoice.payment_failed",failed.getEpochSecond(),"in_delayed")));var charges=org.mockito.Mockito.mock(com.stripe.model.ChargeCollection.class);org.mockito.Mockito.when(charges.autoPagingIterable()).thenReturn(java.util.List.of(charge));
        try(var eventApi=org.mockito.Mockito.mockStatic(com.stripe.model.Event.class);var chargeApi=org.mockito.Mockito.mockStatic(com.stripe.model.Charge.class)){
            eventApi.when(()->com.stripe.model.Event.list(org.mockito.ArgumentMatchers.anyMap())).thenReturn(events);chargeApi.when(()->com.stripe.model.Charge.list(org.mockito.ArgumentMatchers.anyMap())).thenReturn(charges);assertEquals(failed,gateway.readFirstFailure(snapshot,historyEvent("evt_late","invoice.payment_failed",now.getEpochSecond(),"in_delayed")).occurredAt());
        }
    }
    @Test void physicalChargeTimeIsNeverInventedAsATestClockBusinessFailureTime() {
        Instant now=Instant.parse("2026-11-18T02:00:00Z");var gateway=new SubscriptionRecoveryStripeGateway(java.time.Clock.fixed(now,java.time.ZoneOffset.UTC));var invoice=new com.stripe.model.Invoice();invoice.setId("in_clock");invoice.setCreated(now.plusSeconds(7*86400L).getEpochSecond());var sub=new com.stripe.model.Subscription();sub.setLivemode(false);sub.setTestClock("clock_own");var snapshot=new SubscriptionRecoveryStripeGateway.Snapshot(invoice,sub,null,java.util.List.of(),null,null,now.plusSeconds(7*86400L));var event=historyEvent("evt_clock","invoice.payment_failed",now.getEpochSecond(),"in_clock");var error=assertThrows(IllegalStateException.class,()->gateway.readFirstFailure(snapshot,event));assertTrue(error.getMessage().contains("BUSINESS_EVIDENCE_REQUIRED"));
    }
    @Test void eventRetentionExpiryCannotStartANewRecoveryWindow() {
        Instant now=Instant.parse("2026-11-18T02:00:00Z");var gateway=new SubscriptionRecoveryStripeGateway(java.time.Clock.fixed(now,java.time.ZoneOffset.UTC));var invoice=new com.stripe.model.Invoice();invoice.setId("in_old");invoice.setCreated(now.minusSeconds(31*86400L).getEpochSecond());var snapshot=new SubscriptionRecoveryStripeGateway.Snapshot(invoice,null,null,java.util.List.of(),null,null,now);var event=historyEvent("evt_late","invoice.payment_failed",now.getEpochSecond(),"in_old");assertThrows(IllegalStateException.class,()->gateway.readFirstFailure(snapshot,event));
    }
    private com.stripe.model.Event historyEvent(String id,String type,long created,String invoice){
        return com.stripe.net.ApiResource.GSON.fromJson("{\"id\":\""+id+"\",\"type\":\""+type+"\",\"created\":"+created+",\"data\":{\"object\":{\"id\":\""+invoice+"\",\"object\":\"invoice\"}}}",com.stripe.model.Event.class);
    }
    @Test void aDeclinedDispatchRetainsProviderAttemptProofAndDisablesHiddenTransportRetries() throws Exception {
        var gateway=new SubscriptionRecoveryStripeGateway();var action=new SubscriptionRecoveryActionEntity();action.setStatus("DISPATCHING");action.setActionType("RETRY_PAY");action.setTargetId("in_old");action.setProviderIdempotencyKey("recovery:in_old:retry:1");action.setPayloadJson("{\"off_session\":true}");
        var invoice=org.mockito.Mockito.mock(com.stripe.model.Invoice.class);var intent=new com.stripe.model.PaymentIntent();intent.setId("pi_old");intent.setLatestCharge("ch_failed");var error=new com.stripe.model.StripeError();error.setPaymentIntent(intent);
        var decline=new com.stripe.exception.CardException("declined","req_declined","card_declined",null,"insufficient_funds","ch_failed",402,null);decline.setStripeError(error);
        org.mockito.Mockito.when(invoice.pay(org.mockito.ArgumentMatchers.anyMap(),org.mockito.ArgumentMatchers.any(com.stripe.net.RequestOptions.class))).thenThrow(decline);
        try(var invoices=org.mockito.Mockito.mockStatic(com.stripe.model.Invoice.class)){
            invoices.when(()->com.stripe.model.Invoice.retrieve("in_old")).thenReturn(invoice);
            var result=gateway.execute(action);assertEquals("DECLINED",result.state());assertSame(intent,result.object());
            var options=org.mockito.ArgumentCaptor.forClass(com.stripe.net.RequestOptions.class);org.mockito.Mockito.verify(invoice).pay(org.mockito.ArgumentMatchers.anyMap(),options.capture());
            assertEquals(0,options.getValue().getMaxNetworkRetries());assertEquals(action.getProviderIdempotencyKey(),options.getValue().getIdempotencyKey());
        }
    }
    @Test void frozenDecimalValuesDoNotLosePrecisionWhenTheyAreReadBack() {
        var value=new java.math.BigDecimal("12.34567890123456789");
        assertEquals(value,SubscriptionRecoveryStripeGateway.thaw(SubscriptionRecoveryStripeGateway.freeze(Map.of("application_fee_percent",value))).get("application_fee_percent"));
    }
    @Test void unclaimedWritesAndWritesInsideTransactionsAreRejectedBeforeNetwork() {
        var gateway=new SubscriptionRecoveryStripeGateway();
        var action=new SubscriptionRecoveryActionEntity();action.setStatus("READY");
        assertThrows(IllegalStateException.class,()->gateway.execute(action));
        action.setStatus("DISPATCHING");action.setProviderIdempotencyKey("saved-key");action.setPayloadJson("{}");
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try{assertThrows(IllegalStateException.class,()->gateway.execute(action));}
        finally{TransactionSynchronizationManager.setActualTransactionActive(false);}
    }
}
