package com.studyagent.infra.service.billing;

import com.studyagent.infra.entity.*;
import com.studyagent.infra.mapper.*;
import com.studyagent.service.domain.billing.*;
import com.stripe.model.*;
import com.stripe.net.ApiResource;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SubscriptionPaymentRecoveryServiceTest {
    private final UserSubscriptionMapper users=mock(UserSubscriptionMapper.class);
    private final SubscriptionPaymentRecoveryMapper recoveries=mock(SubscriptionPaymentRecoveryMapper.class);
    private final SubscriptionRecoveryActionMapper actions=mock(SubscriptionRecoveryActionMapper.class);
    private final SubscriptionRecoveryReplacementMapper replacements=mock(SubscriptionRecoveryReplacementMapper.class);
    private final SubscriptionPlanMapper plans=mock(SubscriptionPlanMapper.class);
    private final RechargeOrderMapper orders=mock(RechargeOrderMapper.class);
    private final SubscriptionRecoveryStripeGateway stripe=mock(SubscriptionRecoveryStripeGateway.class);
    private final PaymentRecoveryAccessService access=mock(PaymentRecoveryAccessService.class);
    private final PaymentRecoveryProperties properties=new PaymentRecoveryProperties();
    private final PlatformTransactionManager tx=mock(PlatformTransactionManager.class);
    private final SubscriptionPaymentRecoveryService service=new SubscriptionPaymentRecoveryService(users,recoveries,actions,replacements,plans,orders,stripe,properties,access,tx);
    private final Instant now=Instant.parse("2026-11-11T02:00:00Z");

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void earlierPaidRecoveryReceiptsDoNotBlockProtectionOfANewWindow(boolean historicalChild) {
        var current=snapshot();var pause=new Subscription.PauseCollection();pause.setBehavior("keep_as_draft");current.subscription().setPauseCollection(pause);
        var root=existingRoot();root.setStatus("OPEN_MANUAL");root.setProtectionState("PENDING");root.setGrantState("NONE");root.setReviewState("NONE");ownedFixture(root,current);
        var previous=paidSnapshot();previous.invoice().setId("in_prior");previous.invoice().getStatusTransitions().setPaidAt(now.minusSeconds(86400).getEpochSecond());
        var old=new SubscriptionPaymentRecoveryEntity();old.setId(2L);old.setStripeInvoiceId("in_prior");old.setStatus("PAID");old.setGrantState("GRANTED");old.setReviewState("NONE");if(historicalChild)old.setOwnerRecoveryId(9L);
        when(recoveries.selectByInvoice("in_prior")).thenReturn(old);when(stripe.readInvoice("in_prior")).thenReturn(previous);
        when(stripe.listInvoices("sub_old")).thenReturn(List.of(current.invoice(),previous.invoice()));
        assertDoesNotThrow(()->service.reconcile(1L));
        assertEquals("ACTIVE",root.getProtectionState());assertEquals("OPEN_MANUAL",root.getStatus());assertEquals("NONE",root.getReviewState());
        assertEquals("GRANTED",old.getGrantState());assertEquals("NONE",old.getReviewState());
        verify(stripe,never()).readInvoice("in_prior");verify(recoveries,never()).updateById(same(old));verifyNoInteractions(orders);assertRecoveryHistoryWasNotDeleted();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"unknown","same_time","later"})
    void ambiguousForeignRecoveryReceiptsStillRequireReconciliation(String paidTime) {
        var current=snapshot();var root=existingRoot();ownedFixture(root,current);
        var previous=paidSnapshot();previous.invoice().setId("in_prior");previous.invoice().getStatusTransitions().setPaidAt("unknown".equals(paidTime)?null:now.plusSeconds("later".equals(paidTime)?1:0).getEpochSecond());
        var old=new SubscriptionPaymentRecoveryEntity();old.setId(2L);old.setGrantState("GRANTED");when(recoveries.selectByInvoice("in_prior")).thenReturn(old);when(stripe.readInvoice("in_prior")).thenReturn(previous);
        when(stripe.listInvoices("sub_old")).thenReturn(List.of(current.invoice(),previous.invoice()));
        assertThrows(IllegalStateException.class,()->service.reconcile(1L));assertEquals("GRANTED",old.getGrantState());verifyNoInteractions(orders);
    }

    @Test void ownWindowChildWithAnInvalidEarlyPaidTimeStillOpensReview() {
        var current=snapshot();var root=existingRoot();ownedFixture(root,current);
        var previous=paidSnapshot();previous.invoice().setId("in_prior");previous.invoice().getStatusTransitions().setPaidAt(now.minusSeconds(1).getEpochSecond());
        var child=new SubscriptionPaymentRecoveryEntity();child.setId(2L);child.setOwnerRecoveryId(1L);child.setGrantState("NONE");when(recoveries.selectByInvoice("in_prior")).thenReturn(child);when(stripe.readInvoice("in_prior")).thenReturn(previous);
        when(stripe.listInvoices("sub_old")).thenReturn(List.of(current.invoice(),previous.invoice()));
        service.reconcile(1L);assertEquals("PAYMENT_OUTSIDE_RECOVERY_WINDOW",root.getReviewReason());assertEquals("HELD",child.getGrantState());verifyNoInteractions(orders);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"customer.subscription.updated","customer.subscription.deleted"})
    void releasedPaidRecoveryDoesNotConsumeLaterSubscriptionEvents(String type) {
        var paid=paidSnapshot();var root=confirmedPaidRoot(paid);root.setReviewState("NONE");ownedFixture(root,paid);
        users.selectByUserForUpdate("user").setPaymentRecoveryId(null);
        when(recoveries.selectBySubscription("sub_old",0L,100)).thenReturn(List.of(root));
        var event=ApiResource.GSON.fromJson("{\"id\":\"evt_released_period\",\"type\":\""+type+"\",\"data\":{\"object\":{\"id\":\"sub_old\",\"object\":\"subscription\"}}}",Event.class);
        assertFalse(service.tryProcessEvent(event),"The current normal subscription owns events after the paid recovery pointer was released");
        assertEquals("GRANTED",root.getGrantState());assertEquals("NONE",root.getReviewState());
        verify(stripe,never()).readInvoice(anyString());verify(recoveries,never()).updateById(any(SubscriptionPaymentRecoveryEntity.class));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "customer.subscription.updated,sub_new,", "customer.subscription.deleted,sub_new,",
            "customer.subscription.updated,sub_old,2", "customer.subscription.deleted,sub_old,2"})
    void historicalPaidSubscriptionCannotReachLegacyWhenOwnershipHasMoved(String type,String currentSubscription,Long recoveryId) {
        var paid=paidSnapshot();var root=confirmedPaidRoot(paid);root.setReviewState("NONE");ownedFixture(root,paid);
        var user=users.selectByUserForUpdate("user");user.setStripeSubscriptionId(currentSubscription);user.setPaymentRecoveryId(recoveryId);
        when(recoveries.selectBySubscription("sub_old",0L,100)).thenReturn(List.of(root));
        var event=ApiResource.GSON.fromJson("{\"id\":\"evt_historical_owner\",\"type\":\""+type+"\",\"data\":{\"object\":{\"id\":\"sub_old\",\"object\":\"subscription\"}}}",Event.class);
        assertTrue(service.tryProcessEvent(event),"A historical subscription must not overwrite the new subscription or recovery through legacy handling");
        assertEquals(currentSubscription,user.getStripeSubscriptionId());assertEquals(recoveryId,user.getPaymentRecoveryId());
        assertEquals("GRANTED",root.getGrantState());assertEquals("NONE",root.getReviewState());
        verify(stripe,never()).readInvoice(anyString());verify(users,never()).update(any(UserSubscriptionEntity.class),any());
        verify(recoveries,never()).updateById(any(SubscriptionPaymentRecoveryEntity.class));
    }

    @org.junit.jupiter.api.BeforeAll static void userMapping(){com.studyagent.infra.testutil.MybatisPlusTableInfoTestHelper.initTableInfo(UserSubscriptionEntity.class);com.studyagent.infra.testutil.MybatisPlusTableInfoTestHelper.initTableInfo(SubscriptionPlanEntity.class);com.studyagent.infra.testutil.MybatisPlusTableInfoTestHelper.initTableInfo(SubscriptionPaymentRecoveryEntity.class);}
    @org.junit.jupiter.api.BeforeEach void userWriteResult(){when(users.update(any(UserSubscriptionEntity.class),any())).thenReturn(1);}

    @Test void testOperatorPreflightUsesExactProductionAdmissionAndDoesNotWrite() {
        enable();var snapshot=snapshot();var user=user();user.setStripeCustomerId("cus_old");when(users.selectByUser("user")).thenReturn(user);when(stripe.readInvoice("in_old")).thenReturn(snapshot);
        assertEquals("formal:pro_monthly:month_1:NO_SCHEDULE:RENEW",service.requireTestClockAdmission("user","in_old"));
        assertThrows(IllegalArgumentException.class,()->properties.setVerifiedCohorts(Set.of("formal")));properties.setVerifiedCohorts(Set.of("formal:basic_monthly:month_1:NO_SCHEDULE:RENEW"));assertThrows(IllegalStateException.class,()->service.requireTestClockAdmission("user","in_old"));
        verify(stripe,never()).execute(any());verifyNoInteractions(recoveries,actions,orders);
    }
    @Test void testOperatorPreflightRejectsAnotherCustomer() {
        enable();var user=user();user.setStripeCustomerId("cus_other");when(users.selectByUser("user")).thenReturn(user);when(stripe.readInvoice("in_old")).thenReturn(snapshot());
        assertThrows(IllegalStateException.class,()->service.requireTestClockAdmission("user","in_old"));verify(stripe,never()).execute(any());
    }
    @Test void reviewCalendarSkipsConfiguredHolidayAndCountsAnExplicitWeekendWorkday() {
        var entered=java.time.LocalDateTime.of(2026,11,13,10,20); // Friday
        properties.setReviewHolidays(Set.of(java.time.LocalDate.of(2026,11,16)));
        assertEquals(java.time.LocalDateTime.of(2026,11,18,10,20),org.springframework.test.util.ReflectionTestUtils.invokeMethod(service,"reviewDue",entered));
        properties.setReviewWorkingDates(Set.of(java.time.LocalDate.of(2026,11,14)));
        assertEquals(java.time.LocalDateTime.of(2026,11,17,10,20),org.springframework.test.util.ReflectionTestUtils.invokeMethod(service,"reviewDue",entered));
    }
    @Test void repeatedReviewKeepsOriginalDeadlineOwnerAndOneOverdueNotification() {
        var root=existingRoot();ownedFixture(root,snapshot());properties.setReviewAssignee("alf");
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(service,"review",root,"FIRST_REASON");
        var entered=root.getReviewEnteredAt();var deadline=root.getReviewDueAt();
        var followup=com.studyagent.common.datetime.DateTimeFormats.now().minusHours(1);root.setReviewNextFollowupAt(followup);
        properties.setReviewAssignee("another_owner");properties.setReviewHolidays(Set.of(deadline.toLocalDate()));
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(service,"review",root,"LATER_REASON");
        assertEquals(entered,root.getReviewEnteredAt());assertEquals(deadline,root.getReviewDueAt());assertEquals(followup,root.getReviewNextFollowupAt());assertEquals("alf",root.getReviewAssignee());
        var ledger=failureLedger();when(recoveries.selectOverdueReviewIds(any(),anyInt())).thenReturn(List.of(1L));
        service.registerOverdueReviewAlerts();service.registerOverdueReviewAlerts();
        assertEquals(1,ledger.size());assertEquals("NOTIFY_REVIEW",ledger.get(0).getActionType());
        assertTrue(ledger.get(0).getPayloadJson().contains("alf"));assertTrue(ledger.get(0).getPayloadJson().contains("LATER_REASON"));
        verify(stripe,never()).execute(any());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={5,1441})
    void paidRemoteRetrySurvivesLostLocalCompletionWithoutAnotherCharge(int elapsedMinutes) {
        enable();properties.setAutomaticPayEnabled(true);var initial=snapshot();var pause=new Subscription.PauseCollection();pause.setBehavior("keep_as_draft");initial.subscription().setPauseCollection(pause);
        var root=existingRoot();ownedFixture(root,initial);var quota=injectQuota();
        root.setContractSnapshot("{\"price_id\":\"price_old\",\"quantity\":1,\"recovery_admission\":{\"sourceKey\":\"formal\",\"contractHash\":\""+RecoveryContractClassifier.classify(initial.subscription(),null,true,"price_old",1L).contractHash()+"\"}}");
        var action=readyAction("RETRY_PAY","in_old");action.setActionKey("recovery:in_old:retry:7");action.setPayloadJson("{\"off_session\":true}");action.setScheduledAt(com.studyagent.common.datetime.DateTimeFormats.fromInstant(now));action.setNextRunAt(action.getScheduledAt());action.setRetrySlot(1);
        var ledger=failureLedger();ledger.add(action);var originalKey=action.getActionKey();var originalPayload=action.getPayloadJson();
        when(actions.claim(eq(10L),anyInt(),anyString(),any(),any())).thenReturn(1);when(actions.markDispatched(eq(10L),anyInt(),anyString(),any(),anyString())).thenReturn(1);
        var paid=paidSnapshot();when(stripe.execute(action)).thenAnswer(call->{
            assertEquals(originalKey,action.getActionKey());assertEquals(originalPayload,action.getPayloadJson());
            when(stripe.readInvoice("in_old")).thenReturn(paid);when(stripe.listInvoices("sub_old")).thenReturn(List.of(paid.invoice()));
            return new SubscriptionRecoveryStripeGateway.WriteResult("SUCCEEDED","req_cash_before_crash",null,paid.invoice());
        });
        when(actions.complete(eq(10L),anyInt(),anyString(),anyString(),anyString(),any())).thenThrow(new IllegalStateException("TEST_LOCAL_COMPLETION_INTERRUPTED"));
        assertEquals("TEST_LOCAL_COMPLETION_INTERRUPTED",assertThrows(IllegalStateException.class,()->service.runDueAction(10L)).getMessage());
        assertEquals("DISPATCHING",action.getStatus());assertNull(root.getEntitlementInvoiceId());
        action.setLeaseUntil(com.studyagent.common.datetime.DateTimeFormats.now().minusSeconds(1));action.setFirstDispatchedAt(com.studyagent.common.datetime.DateTimeFormats.now().minusMinutes(elapsedMinutes));
        service.runDueAction(10L);service.runDueAction(10L);assertTrue(service.tryProcessEvent(event()));
        assertEquals("SUCCEEDED",action.getStatus());assertEquals(originalKey,action.getActionKey());assertEquals(originalPayload,action.getPayloadJson());
        assertEquals("PAID",root.getStatus());assertEquals("GRANTED",root.getGrantState());assertEquals("in_old",root.getEntitlementInvoiceId());
        verify(stripe,times(1)).execute(any());verify(quota,times(1)).resetFromPaidInvoiceAt(eq("user"),eq("sub_old"),eq("pro_monthly"),any(),any(),eq("in_old"),eq(paid.businessNow()));
    }
    @Test void anUnknownOpenCheckoutIsReadBackThenItsLatePaymentGrantsOnceWithoutAnotherCreate() {
        var order=replacementPaymentFixture(2900L,"cus_old");var reserved=replacements.selectByOrder("order_new");reserved.setState("CREATING");reserved.setCheckoutSessionId(null);reserved.setActiveSubscriptionKey("sub_old");
        var session=stripe.readCheckout("cs_new");session.setMode("subscription");session.setStatus("open");session.setPaymentStatus("unpaid");session.setSubscription(null);session.setInvoice(null);
        session.setLineItems(ApiResource.GSON.fromJson("{\"data\":[{\"id\":\"li_saved\",\"quantity\":1,\"price\":{\"id\":\"price_old\"}}],\"has_more\":false}",LineItemCollection.class));
        var action=readyAction("CREATE_CHECKOUT","cus_old");action.setStatus("UNKNOWN");action.setFirstDispatchedAt(com.studyagent.common.datetime.DateTimeFormats.now().minusMinutes(5));
        action.setPayloadJson("{\"customer\":\"cus_old\",\"currency\":\"usd\",\"mode\":\"subscription\",\"line_items\":[{\"price\":\"price_old\",\"quantity\":1}],\"metadata\":{\"order_no\":\"order_new\",\"purchase_type\":\"subscription_replacement\",\"recovery_id\":\"1\",\"clerk_user_id\":\"user\",\"old_subscription_id\":\"sub_old\"}}");
        reserved.setQuoteSnapshot(action.getPayloadJson());when(replacements.selectActive("sub_old")).thenReturn(reserved);when(stripe.findCheckout("cus_old","order_new")).thenReturn(session);
        String frozen=action.getPayloadJson(),key=action.getActionKey();service.runDueAction(10L);service.runDueAction(10L);
        assertEquals("SUCCEEDED",action.getStatus());assertEquals("OPEN",reserved.getState());assertEquals("cs_new",reserved.getCheckoutSessionId());assertEquals("sub_old",reserved.getActiveSubscriptionKey());
        assertEquals(frozen,action.getPayloadJson());assertEquals(key,action.getActionKey());assertTrue(action.getResultJson().contains("confirmed_by_read"));
        session.setStatus("complete");session.setPaymentStatus("paid");session.setSubscription("sub_new");session.setInvoice("in_new");
        var quota=injectQuota();assertTrue(service.tryProcessEvent(replacementPaidEvent()));assertTrue(service.tryProcessEvent(replacementPaidEvent()));
        assertEquals("PAID",reserved.getState());assertEquals("completed",order.getStatus());assertNull(reserved.getActiveSubscriptionKey());
        verify(quota,times(1)).resetFromPaidInvoiceAt(eq("user"),eq("sub_new"),eq("pro_monthly"),any(),any(),eq("in_new"),any());verify(stripe,never()).execute(any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={5,1441})
    void unpaidUnknownRetryNeverResendsEitherInsideOrOutsideTheProviderWindow(int elapsedMinutes) {
        var root=existingRoot();ownedFixture(root,snapshot());var action=readyAction("RETRY_PAY","in_old");action.setActionKey("recovery:in_old:retry:7");action.setPayloadJson("{\"off_session\":true}");action.setStatus("UNKNOWN");action.setFirstDispatchedAt(com.studyagent.common.datetime.DateTimeFormats.now().minusMinutes(elapsedMinutes));
        var firstDispatch=action.getFirstDispatchedAt();var ledger=failureLedger();ledger.add(action);
        service.runDueAction(10L);service.runDueAction(10L);
        assertEquals(elapsedMinutes>1440?"REVIEW":"UNKNOWN",action.getStatus());assertEquals(firstDispatch,action.getFirstDispatchedAt());assertEquals("recovery:in_old:retry:7",action.getActionKey());assertEquals("{\"off_session\":true}",action.getPayloadJson());assertNull(root.getEntitlementInvoiceId());
        verify(stripe,never()).execute(any());verify(actions,never()).claim(anyLong(),anyInt(),anyString(),any(),any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"old_cancel_date","mismatched_period_end"})
    void renewalBoundaryDisagreementKeepsThePaidSourceAndNeverCreatesCancellationWrites(String failure) {
        var snap=paidSnapshot();var root=confirmedPaidRoot(snap);root.setContractSnapshot("{\"price_id\":\"price_old\",\"quantity\":1,\"recovery_admission\":{\"sourceKey\":\"formal\",\"cancelMode\":\"PERIOD_END\"}}");
        snap.subscription().setCancelAtPeriodEnd(true);long end=com.studyagent.common.datetime.DateTimeFormats.toEpochSecond(root.getEntitlementEndAt());snap.subscription().setCancelAt(end);
        if("old_cancel_date".equals(failure))snap.subscription().setCancelAt(end-7*86400);else snap.subscription().setCurrentPeriodEnd(end-86400);
        ownedFixture(root,snap);var quota=injectQuota();stubConfirmedShift(root,snap,2900L);
        var first=root.getEntitlementStartAt();var last=root.getEntitlementEndAt();String saved=root.getContractSnapshot();service.reconcile(1L);service.reconcile(1L);
        assertEquals("PAID",root.getStatus());assertEquals("GRANTED",root.getGrantState());assertEquals("in_old",root.getEntitlementInvoiceId());assertEquals(first,root.getEntitlementStartAt());assertEquals(last,root.getEntitlementEndAt());assertEquals(saved,root.getContractSnapshot());
        assertEquals("REVIEW",root.getAnchorSyncState());assertEquals("RENEWAL_BOUNDARY_OR_CANCELLATION_MISMATCH",root.getReviewReason());verifyNoInteractions(quota);verify(stripe,never()).execute(any());
        verify(actions,never()).insert(argThat((SubscriptionRecoveryActionEntity a)->Set.of("SET_CANCEL_PREFERENCE","CANCEL_SUBSCRIPTION","CANCEL_SCHEDULE","CREATE_CHECKOUT","RELEASE_PAUSE").contains(a.getActionType())));
    }
    @Test void aRejectedShiftAndLaterCanceledCallbackCannotClearTheConfirmedPaidPeriodOrReleaseAgain() {
        enable();var snap=paidSnapshot();var root=confirmedPaidRoot(snap);var pause=new Subscription.PauseCollection();pause.setBehavior("keep_as_draft");snap.subscription().setPauseCollection(pause);
        root.setContractSnapshot("{\"price_id\":\"price_old\",\"quantity\":1,\"recovery_admission\":{\"sourceKey\":\"formal\",\"cancelMode\":\"RENEW\",\"contractHash\":\""+RecoveryContractClassifier.classify(snap.subscription(),null,true,"price_old",1L).contractHash()+"\"}}");
        ownedFixture(root,snap);var quota=injectQuota();var action=readyAction("SHIFT_RENEWAL","sub_old");action.setPayloadJson(SubscriptionRecoveryStripeGateway.freeze(SubscriptionRecoveryStripeGateway.shiftParams("in_old",root.getEntitlementEndAt().atZone(com.studyagent.common.datetime.DateTimeFormats.APP_ZONE).toInstant())));
        when(actions.selectByRecovery(1L)).thenReturn(List.of(action));when(actions.claim(eq(10L),anyInt(),anyString(),any(),any())).thenReturn(1);when(actions.markDispatched(eq(10L),anyInt(),anyString(),any(),anyString())).thenReturn(1);
        when(actions.complete(eq(10L),anyInt(),anyString(),anyString(),anyString(),any())).thenAnswer(call->{action.setStatus(call.getArgument(3));action.setResultJson(call.getArgument(4));return 1;});
        when(stripe.execute(any())).thenReturn(new SubscriptionRecoveryStripeGateway.WriteResult("FAILED","req_rejected_shift","api_parameter_rejected",null));
        String frozen=action.getPayloadJson();var first=root.getEntitlementStartAt();var last=root.getEntitlementEndAt();service.runDueAction(10L);
        assertEquals("FAILED",action.getStatus());assertEquals("REVIEW",root.getAnchorSyncState());assertEquals(frozen,action.getPayloadJson());assertEquals("GRANTED",root.getGrantState());
        snap.subscription().setStatus("canceled");when(recoveries.selectBySubscription("sub_old",0L,100)).thenReturn(List.of(root));when(recoveries.selectBySubscription("sub_old",1L,100)).thenReturn(List.of());
        var canceled=ApiResource.GSON.fromJson("{\"id\":\"evt_canceled_after_rejection\",\"type\":\"customer.subscription.deleted\",\"data\":{\"object\":"+snap.subscription().toJson()+"}}",Event.class);assertTrue(service.tryProcessEvent(canceled));
        var pending=readyAction("SHIFT_RENEWAL","sub_old");pending.setPayloadJson(frozen);when(actions.selectByRecovery(1L)).thenReturn(List.of(pending));service.runDueAction(10L);
        assertEquals("SKIPPED",pending.getStatus());assertEquals("PAID",root.getStatus());assertEquals("GRANTED",root.getGrantState());assertEquals("in_old",root.getEntitlementInvoiceId());assertEquals(first,root.getEntitlementStartAt());assertEquals(last,root.getEntitlementEndAt());
        verify(stripe,times(1)).execute(any());verifyNoInteractions(quota);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"invoice.paid","invoice.finalized"})
    void succeededShiftStillDefersItsTechnicalInvoiceUntilRegistration(String type) {
        var paid=paidSnapshot();var root=confirmedPaidRoot(paid);ownedFixture(root,paid);var quota=injectQuota();
        var account=users.selectByUserForUpdate("user");when(users.selectByStripeSubscriptionId("sub_old")).thenReturn(account);
        var technical=ApiResource.GSON.fromJson(paid.invoice().toJson(),Invoice.class);technical.setId("in_technical");
        technical.setStatus("invoice.paid".equals(type)?"paid":"draft");technical.setAmountDue(0L);technical.setAmountPaid(0L);technical.setAmountRemaining(0L);
        technical.setPaymentIntent(null);technical.setCharge(null);technical.setBillingReason("subscription_update");
        when(stripe.readInvoice("in_technical")).thenReturn(new SubscriptionRecoveryStripeGateway.Snapshot(technical,paid.subscription(),null,paid.lines(),null,null,now));
        var shift=readyAction("SHIFT_RENEWAL","sub_old");shift.setStatus("SUCCEEDED");
        shift.setResultJson("{\"latest_invoice\":\"in_technical\",\"before_latest_invoice\":\"in_old\"}");when(actions.selectByRecovery(1L)).thenReturn(List.of(shift));
        var event=invoiceSequenceEvent("evt_succeeded_shift_technical",type,"in_technical");
        assertTrue(assertThrows(IllegalStateException.class,()->service.tryProcessEvent(event)).getMessage().contains("TECHNICAL_INVOICE_PENDING"));
        verify(recoveries,never()).insert(any(SubscriptionPaymentRecoveryEntity.class));verifyNoInteractions(orders,quota);
        assertEquals("PAID",root.getStatus());assertEquals("GRANTED",root.getGrantState());assertNull(root.getTechnicalInvoiceIds());
        root.setTechnicalInvoiceIds("[\"in_technical\"]");assertTrue(service.tryProcessEvent(event));
        verify(recoveries,never()).insert(any(SubscriptionPaymentRecoveryEntity.class));verifyNoInteractions(orders,quota);
    }

    @Test void earlyTechnicalDraftIsDiscoveredWithoutFinalizedWhileAnOrdinaryZeroCycleStaysOrdinary() {
        var paid=paidSnapshot();var root=confirmedPaidRoot(paid);root.setContractSnapshot("{\"price_id\":\"price_old\",\"quantity\":1,\"recovery_admission\":{\"sourceKey\":\"formal\",\"cancelMode\":\"RENEW\",\"silentCleanupVerified\":true}}");ownedFixture(root,paid);var quota=injectQuota();
        var user=users.selectByUserForUpdate("user");when(users.selectByStripeSubscriptionId("sub_old")).thenReturn(user);var first=root.getEntitlementStartAt();var end=root.getEntitlementEndAt();
        var technical=ApiResource.GSON.fromJson(paid.invoice().toJson(),Invoice.class);technical.setId("in_technical");technical.setStatus("draft");technical.setAmountDue(0L);technical.setAmountPaid(0L);technical.setAmountRemaining(0L);technical.setPaymentIntent(null);technical.setCharge(null);technical.setBillingReason("subscription_update");technical.setAutoAdvance(false);
        when(stripe.readInvoice("in_technical")).thenReturn(new SubscriptionRecoveryStripeGateway.Snapshot(technical,paid.subscription(),null,paid.lines(),null,null,now));
        var zero=ApiResource.GSON.fromJson(paid.invoice().toJson(),Invoice.class);zero.setId("in_legitimate_zero");zero.setBillingReason("subscription_cycle");zero.setAmountDue(0L);zero.setAmountPaid(0L);zero.setAmountRemaining(0L);zero.setCreated(end.atZone(com.studyagent.common.datetime.DateTimeFormats.APP_ZONE).toEpochSecond());zero.setPaymentIntent(null);zero.setCharge(null);
        var zeroLine=ApiResource.GSON.fromJson(paid.lines().get(0).toJson(),InvoiceLineItem.class);zeroLine.setAmount(0L);zeroLine.getPeriod().setStart(zero.getCreated());zeroLine.getPeriod().setEnd(zero.getCreated()+30*86400);
        when(stripe.readInvoice("in_legitimate_zero")).thenReturn(new SubscriptionRecoveryStripeGateway.Snapshot(zero,paid.subscription(),null,List.of(zeroLine),null,null,Instant.ofEpochSecond(zero.getCreated())));
        var ledger=failureLedger();var shift=readyAction("SHIFT_RENEWAL","sub_old");shift.setStatus("UNKNOWN");ledger.add(shift);
        var early=invoiceSequenceEvent("evt_early_technical","invoice.paid","in_technical");assertTrue(assertThrows(IllegalStateException.class,()->service.tryProcessEvent(early)).getMessage().contains("TECHNICAL_INVOICE_PENDING"));
        assertNull(root.getTechnicalInvoiceIds());assertEquals("PENDING",root.getAnchorSyncState());assertEquals("GRANTED",root.getGrantState());
        stubConfirmedShift(root,paid,2900L);shift.setStatus("SUCCEEDED");shift.setResultJson("{\"latest_invoice\":\"in_technical\",\"before_latest_invoice\":\"in_old\"}");when(actions.selectByRecovery(1L)).thenAnswer(call->new ArrayList<>(ledger));
        when(stripe.listInvoices("sub_old")).thenReturn(List.of(paid.invoice(),technical,zero));
        service.reconcile(1L);service.reconcile(1L);
        assertTrue(root.getTechnicalInvoiceIds().contains("in_technical"));assertFalse(root.getTechnicalInvoiceIds().contains("in_legitimate_zero"));assertNotEquals("CONFIRMED",root.getAnchorSyncState());
        assertEquals(1,ledger.stream().filter(a->"FINALIZE_INVOICE".equals(a.getActionType())&&"in_technical".equals(a.getTargetId())).count());
        assertTrue(service.tryProcessEvent(early));assertTrue(service.tryProcessEvent(early));assertEquals(first,root.getEntitlementStartAt());assertEquals(end,root.getEntitlementEndAt());
        // Native-read state transitions are simulated; no finalized callback or write is required for discovery.
        technical.setStatus("open");service.reconcile(1L);assertEquals(1,ledger.stream().filter(a->"VOID_INVOICE".equals(a.getActionType())&&"in_technical".equals(a.getTargetId())).count());
        technical.setStatus("void");service.reconcile(1L);assertEquals("CONFIRMED",root.getAnchorSyncState());assertEquals("RELEASED",root.getProtectionState());
        assertFalse(service.tryProcessEvent(invoiceSequenceEvent("evt_normal_zero","invoice.paid","in_legitimate_zero")),"A legitimate next-cycle zero invoice belongs to the normal handler");assertNull(user.getPaymentRecoveryId());
        assertTrue(service.tryProcessEvent(invoiceSequenceEvent("evt_old_late","invoice.payment_failed","in_old")));assertNull(user.getPaymentRecoveryId());assertEquals(first,root.getEntitlementStartAt());assertEquals(end,root.getEntitlementEndAt());
        assertEquals(1,ledger.stream().filter(a->"SHIFT_RENEWAL".equals(a.getActionType())).count());verifyNoInteractions(quota);verify(stripe,never()).execute(any());
    }
    private static Event invoiceSequenceEvent(String id,String type,String invoice){return ApiResource.GSON.fromJson("{\"id\":\""+id+"\",\"type\":\""+type+"\",\"created\":1794362400,\"data\":{\"object\":{\"id\":\""+invoice+"\",\"object\":\"invoice\",\"subscription\":\"sub_old\"}}}",Event.class);}

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"invoice.paid","invoice.payment_succeeded","invoice_payment.paid"})
    void allSuccessEventOrdersThenLateFailuresKeepOneGrantAndTheOriginalRecoveryWindow(String firstType) {
        enable();var snap=paidSnapshot();var root=explicitTimeGrantRoot();
        root.setContractSnapshot("{\"price_id\":\"price_old\",\"quantity\":1,\"recovery_admission\":{\"sourceKey\":\"formal\",\"cancelMode\":\"RENEW\",\"contractHash\":\""+RecoveryContractClassifier.classify(snap.subscription(),null,true,"price_old",1L).contractHash()+"\"}}");
        ownedFixture(root,snap);var quota=injectQuota();var ledger=failureLedger();var first=root.getFirstFailedAt();var deadline=root.getDeadlineAt();
        var types=new ArrayList<>(List.of("invoice.paid","invoice.payment_succeeded","invoice_payment.paid"));types.remove(firstType);types.add(0,firstType);
        for(String type:types){
            var object=new LinkedHashMap<String,Object>();object.put("id","invoice_payment.paid".equals(type)?"inpay_confirmed":"in_old");object.put("invoice","in_old");object.put("subscription","sub_old");object.put("object","invoice_payment.paid".equals(type)?"invoice_payment":"invoice");
            var received=ApiResource.GSON.fromJson(new com.google.gson.Gson().toJson(Map.of("id","evt_"+type,"type",type,"created",now.getEpochSecond(),"data",Map.of("object",object))),Event.class);assertTrue(service.tryProcessEvent(received));
        }
        assertTrue(service.tryProcessEvent(invoiceSequenceEvent("evt_late_failure","invoice.payment_failed","in_old")));assertTrue(service.tryProcessEvent(invoiceSequenceEvent("evt_late_auth","invoice.payment_action_required","in_old")));
        assertEquals("PAID",root.getStatus());assertEquals("GRANTED",root.getGrantState());assertEquals("in_old",root.getEntitlementInvoiceId());assertEquals(first,root.getFirstFailedAt());assertEquals(deadline,root.getDeadlineAt());
        var user=users.selectByUserForUpdate("user");assertEquals(1L,user.getPaymentRecoveryId());assertNull(user.getGraceEndAt());assertEquals(root.getEntitlementEndAt(),user.getCurrentPeriodEnd());
        assertEquals(1,ledger.stream().filter(a->"SHIFT_RENEWAL".equals(a.getActionType())).count());assertEquals(0,ledger.stream().filter(a->"RETRY_PAY".equals(a.getActionType())).count());
        verify(quota,times(1)).resetFromPaidInvoiceAt(eq("user"),eq("sub_old"),eq("pro_monthly"),any(),any(),eq("in_old"),eq(snap.businessNow()));verify(stripe,never()).execute(any());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"future_price","future_quantity","schedule_cancel","fixed_cancel"})
    void unsupportedNativeContractDoesNotCreateRecoveryActionsOrChangeTheLegacyGrace(String conflict) {
        enable();properties.setVerifiedCohorts(Set.of("formal:pro_monthly:month_1:EQUIVALENT_SCHEDULE:RENEW","formal:pro_monthly:month_1:NO_SCHEDULE:RENEW"));var snap=snapshot();var user=user();var grace=com.studyagent.common.datetime.DateTimeFormats.fromInstant(now.plusSeconds(3*86400));user.setGraceEndAt(grace);when(users.selectByStripeSubscriptionId("sub_old")).thenReturn(user);
        SubscriptionSchedule schedule=null;
        if("fixed_cancel".equals(conflict))snap.subscription().setCancelAt(now.plusSeconds(20*86400).getEpochSecond());
        else{
            snap.subscription().setSchedule("sched_conflict");
            String futurePrice="future_price".equals(conflict)?"price_other":"price_old";int quantity="future_quantity".equals(conflict)?2:1;String behavior="schedule_cancel".equals(conflict)?"cancel":"release";
            schedule=ApiResource.GSON.fromJson("{\"id\":\"sched_conflict\",\"customer\":\"cus_old\",\"subscription\":\"sub_old\",\"status\":\"active\",\"end_behavior\":\""+behavior+"\",\"default_settings\":{\"collection_method\":\"charge_automatically\"},\"current_phase\":{\"start_date\":1794362400,\"end_date\":1796954400},\"phases\":[{\"start_date\":1794362400,\"end_date\":1796954400,\"items\":[{\"price\":\"price_old\",\"quantity\":1}]},{\"start_date\":1796954400,\"end_date\":1799546400,\"items\":[{\"price\":\""+futurePrice+"\",\"quantity\":"+quantity+"}]}]}",SubscriptionSchedule.class);
        }
        var nativeSnapshot=new SubscriptionRecoveryStripeGateway.Snapshot(snap.invoice(),snap.subscription(),schedule,snap.lines(),snap.paymentIntent(),snap.charge(),now);when(stripe.readInvoice("in_old")).thenReturn(nativeSnapshot);
        assertFalse(service.tryProcessEvent(event()));assertNull(user.getPaymentRecoveryId());assertEquals(grace,user.getGraceEndAt());
        verify(recoveries,never()).insert(any(SubscriptionPaymentRecoveryEntity.class));verify(actions,never()).insert(any(SubscriptionRecoveryActionEntity.class));verify(replacements,never()).insert(any(SubscriptionRecoveryReplacementEntity.class));verify(users,never()).update(any(UserSubscriptionEntity.class),any());verify(users,never()).selectByUserForUpdate(anyString());verify(stripe,never()).execute(any());verify(stripe,never()).readFirstFailure(any(),any());
    }

    private record RetryGuardFixture(SubscriptionRecoveryStripeGateway.Snapshot snapshot,SubscriptionPaymentRecoveryEntity root,SubscriptionRecoveryActionEntity slot,List<SubscriptionRecoveryActionEntity> ledger,UserSubscriptionEntity user){}
    private RetryGuardFixture retryGuardFixture(){
        enable();properties.setAutomaticPayEnabled(true);var snap=snapshot();var pause=new Subscription.PauseCollection();pause.setBehavior("keep_as_draft");snap.subscription().setPauseCollection(pause);
        var root=existingRoot();root.setFirstFailedAt(com.studyagent.common.datetime.DateTimeFormats.fromInstant(now.minusSeconds(7*86400)));root.setDeadlineAt(com.studyagent.common.datetime.DateTimeFormats.fromInstant(now.plusSeconds(23*86400)));root.setAmountDueMinor(2900L);root.setAmountPaidMinor(0L);root.setAmountRemainingMinor(2900L);root.setStripeInvoiceStatus("open");
        root.setContractSnapshot("{\"price_id\":\"price_old\",\"quantity\":1,\"recovery_admission\":{\"sourceKey\":\"formal\",\"cancelMode\":\"RENEW\",\"contractHash\":\""+RecoveryContractClassifier.classify(snap.subscription(),null,true,"price_old",1L).contractHash()+"\"}}");
        ownedFixture(root,snap);var slot=readyAction("RETRY_PAY","in_old");slot.setActionKey("recovery:in_old:retry:7");slot.setProviderIdempotencyKey(slot.getActionKey());slot.setRetrySlot(1);slot.setScheduledAt(com.studyagent.common.datetime.DateTimeFormats.fromInstant(now));slot.setNextRunAt(slot.getScheduledAt());slot.setPayloadJson("{\"off_session\":true}");
        var ledger=failureLedger();ledger.add(slot);var future=new SubscriptionRecoveryActionEntity();future.setId(11L);future.setRecoveryId(1L);future.setActionType("RETRY_PAY");future.setStatus("READY");future.setRetrySlot(2);future.setScheduledAt(com.studyagent.common.datetime.DateTimeFormats.fromInstant(now.plusSeconds(7*86400)));future.setNextRunAt(future.getScheduledAt());ledger.add(future);
        when(actions.claim(eq(10L),anyInt(),anyString(),any(),any())).thenReturn(1);when(actions.markDispatched(eq(10L),anyInt(),anyString(),any(),anyString())).thenReturn(1);when(actions.complete(eq(10L),anyInt(),anyString(),anyString(),anyString(),any())).thenReturn(1);when(stripe.execute(any())).thenReturn(new SubscriptionRecoveryStripeGateway.WriteResult("UNKNOWN",null,null,null));
        return new RetryGuardFixture(snap,root,slot,ledger,users.selectByUserForUpdate("user"));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "manual,SKIPPED,SKIPPED_TERMINAL","closing,SKIPPED,SKIPPED_TERMINAL","pointer,SKIPPED,SKIPPED_TERMINAL","subscription,SKIPPED,SKIPPED_TERMINAL",
        "paid,SKIPPED,SKIPPED_TERMINAL","partial,SKIPPED,SKIPPED_TERMINAL","amount_missing,SKIPPED,SKIPPED_TERMINAL","void,SKIPPED,SKIPPED_TERMINAL","other_reason,SKIPPED,SKIPPED_TERMINAL","stop_advice,SKIPPED,SKIPPED_TERMINAL",
        "disabled,SKIPPED,SKIPPED_DISABLED","covered,SKIPPED,SKIPPED_REPLACEMENT","late,SKIPPED,SKIPPED_LATE","deadline,SKIPPED,SKIPPED_LATE","contract,SKIPPED,SKIPPED_CONTRACT_CHANGED",
        "protection,READY,none","unpaused,READY,none","advance,READY,none","processing,READY,none","capture,READY,none","replacement,READY,none","previous_unknown,READY,none","method_review,READY,none","early,READY,none",
        "terminal_priority,SKIPPED,SKIPPED_TERMINAL","disabled_priority,SKIPPED,SKIPPED_DISABLED","covered_priority,SKIPPED,SKIPPED_REPLACEMENT"})
    void eachRetryPreconditionRejectsBeforeClaimAndPreservesItsFixedSlot(String condition,String status,String reason){
        var f=retryGuardFixture();Instant observed=now;
        switch(condition){
            case "manual"->f.root().setStatus("OPEN_MANUAL");case "closing"->f.root().setStatus("CLOSING");case "pointer"->f.user().setPaymentRecoveryId(99L);case "subscription"->f.user().setStripeSubscriptionId("sub_new");
            case "paid"->f.snapshot().invoice().setStatus("paid");case "partial"->f.snapshot().invoice().setAmountPaid(100L);case "amount_missing"->f.snapshot().invoice().setAmountPaid(null);case "void"->f.snapshot().invoice().setStatus("void");case "other_reason"->f.snapshot().paymentIntent().getLastPaymentError().setDeclineCode("generic_decline");
            case "stop_advice"->installNativeAdvice(f.snapshot().paymentIntent(),"advice_code","do_not_try_again");
            case "disabled"->properties.setAutomaticPayEnabled(false);case "late"->observed=now.plusSeconds(3601);case "deadline"->f.root().setDeadlineAt(com.studyagent.common.datetime.DateTimeFormats.fromInstant(now));case "contract"->f.root().setContractSnapshot(f.root().getContractSnapshot().replace("\"contractHash\":\"","\"contractHash\":\"changed-"));
            case "protection"->f.root().setProtectionState("PENDING");case "unpaused"->f.snapshot().subscription().setPauseCollection(null);case "advance"->f.snapshot().invoice().setAutoAdvance(true);case "processing"->f.snapshot().paymentIntent().setStatus("processing");case "capture"->f.snapshot().paymentIntent().setStatus("requires_capture");case "early"->f.slot().setNextRunAt(com.studyagent.common.datetime.DateTimeFormats.fromInstant(now.plusSeconds(1)));
            case "replacement"->{var replacement=new SubscriptionRecoveryReplacementEntity();replacement.setState("CREATING");when(replacements.selectActive("sub_old")).thenReturn(replacement);}
            case "previous_unknown","method_review"->{var blocked=new SubscriptionRecoveryActionEntity();blocked.setId(9L);blocked.setActionType("previous_unknown".equals(condition)?"RETRY_PAY":"UPDATE_METHOD");blocked.setStatus("previous_unknown".equals(condition)?"UNKNOWN":"REVIEW");f.ledger().add(blocked);}
            case "covered","covered_priority","disabled_priority","terminal_priority"->{
                var replacement=new SubscriptionRecoveryReplacementEntity();replacement.setPausedAt(com.studyagent.common.datetime.DateTimeFormats.fromInstant(now.minusSeconds(1)));replacement.setClosedAt(com.studyagent.common.datetime.DateTimeFormats.fromInstant(now.plusSeconds(1)));replacement.setState("EXPIRED");when(replacements.selectByRecovery(1L)).thenReturn(List.of(replacement));
                if(!"covered".equals(condition))observed=now.plusSeconds(3601);if("disabled_priority".equals(condition)||"terminal_priority".equals(condition))properties.setAutomaticPayEnabled(false);if("terminal_priority".equals(condition))f.root().setStatus("OPEN_MANUAL");
            }
            default->throw new AssertionError(condition);
        }
        when(stripe.readInvoice("in_old")).thenReturn(new SubscriptionRecoveryStripeGateway.Snapshot(f.snapshot().invoice(),f.snapshot().subscription(),null,f.snapshot().lines(),f.snapshot().paymentIntent(),f.snapshot().charge(),observed));
        var scheduled=f.slot().getScheduledAt();String key=f.slot().getProviderIdempotencyKey(),payload=f.slot().getPayloadJson();service.runDueAction(10L);
        assertEquals(status,f.slot().getStatus(),condition);assertEquals("none".equals(reason)?null:reason,f.slot().getSkipReason(),condition);assertEquals(scheduled,f.slot().getScheduledAt());assertEquals(key,f.slot().getProviderIdempotencyKey());assertEquals(payload,f.slot().getPayloadJson());
        verify(actions,never()).claim(anyLong(),anyInt(),anyString(),any(),any());verify(stripe,never()).execute(any());assertEquals(2,f.ledger().stream().filter(a->"RETRY_PAY".equals(a.getActionType())).count()-("previous_unknown".equals(condition)?1:0));
        if(Set.of("manual","closing","disabled","contract","protection","unpaused","advance","previous_unknown","method_review","replacement","deadline","terminal_priority","disabled_priority").contains(condition)){
            var view=new PaymentRecoveryAccessServiceImpl(users,recoveries,actions,replacements,java.time.Clock.fixed(observed,java.time.ZoneOffset.UTC),properties).resolve("user","sub_old");assertNull(view.summary().nextRetryAt(),condition);
        }else if(Set.of("covered","late","covered_priority").contains(condition)){
            var view=new PaymentRecoveryAccessServiceImpl(users,recoveries,actions,replacements,java.time.Clock.fixed(observed,java.time.ZoneOffset.UTC),properties).resolve("user","sub_old");assertEquals(f.ledger().get(1).getScheduledAt(),view.summary().nextRetryAt(),condition);
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"pi_received,retry","pi_succeeded,retry","charge_captured,retry","pi_received,hosted","pi_succeeded,hosted","charge_captured,hosted","pi_received,replacement","pi_succeeded,replacement","charge_captured,replacement"})
    void aNativeReceiptAheadOfTheInvoiceReadMustWaitBeforeOfferingAnotherPayment(String receipt,String entry){
        var f=retryGuardFixture();f.snapshot().invoice().setHostedInvoiceUrl("https://invoice.stripe.com/owned_test_invoice");
        if(receipt.startsWith("pi_")){f.snapshot().paymentIntent().setStatus("succeeded");if("pi_received".equals(receipt))f.snapshot().paymentIntent().setAmountReceived(2900L);}
        else{f.snapshot().charge().setStatus("succeeded");f.snapshot().charge().setPaid(true);f.snapshot().charge().setCaptured(true);f.snapshot().charge().setAmountCaptured(2900L);}
        if("retry".equals(entry)){service.runDueAction(10L);assertEquals("READY",f.slot().getStatus());verify(actions,never()).claim(anyLong(),anyInt(),anyString(),any(),any());}
        else if("hosted".equals(entry))assertEquals("PAYMENT_PROCESSING",assertThrows(BillingDomainException.class,()->service.createHostedInvoice("user",1L)).getCode());
        else{
            var order=new RechargeOrderEntity();order.setOrderNo("receipt_wait");order.setOrderType("subscription_replacement");order.setClerkUserId("user");
            when(replacements.insert(any(SubscriptionRecoveryReplacementEntity.class))).thenAnswer(call->{((SubscriptionRecoveryReplacementEntity)call.getArgument(0)).setId(2L);return 1;});
            assertEquals("PAYMENT_PROCESSING",assertThrows(BillingDomainException.class,()->service.reserveReplacement("user",1L,order,com.stripe.param.checkout.SessionCreateParams.builder().setCustomer("cus_old").build(),Instant.now().plusSeconds(1860))).getCode());verify(orders,never()).insert(any(RechargeOrderEntity.class));
        }
        verify(stripe,never()).execute(any());assertNull(f.root().getEntitlementInvoiceId());assertEquals(1L,f.user().getPaymentRecoveryId());assertEquals(now,f.snapshot().businessNow());
    }

    private static void installNativeAdvice(PaymentIntent intent,String key,String value){
        var body=com.google.gson.JsonParser.parseString(intent.toJson()).getAsJsonObject();body.getAsJsonObject("last_payment_error").addProperty(key,value);
        intent.setLastResponse(new com.stripe.net.StripeResponse(200,com.stripe.net.HttpHeaders.of(Map.of()),body.toString()));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"do_not_try_again","update_payment_method","network_only"})
    void nativeParentPaymentIntentAdviceIsSavedAndCannotEnableAnAutomaticRecovery(String advice){
        enable();var snap=snapshot();installNativeAdvice(snap.paymentIntent(),"network_only".equals(advice)?"network_advice_code":"advice_code",advice);
        when(stripe.readInvoice("in_old")).thenReturn(snap);var user=user();when(users.selectByUserForUpdate("user")).thenReturn(user);when(tx.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        var created=new java.util.concurrent.atomic.AtomicReference<SubscriptionPaymentRecoveryEntity>();when(recoveries.insert(any(SubscriptionPaymentRecoveryEntity.class))).thenAnswer(call->{var r=(SubscriptionPaymentRecoveryEntity)call.getArgument(0);r.setId(1L);created.set(r);return 1;});
        var pending=new ArrayList<SubscriptionRecoveryActionEntity>();when(actions.insert(any(SubscriptionRecoveryActionEntity.class))).thenAnswer(call->{pending.add(call.getArgument(0));return 1;});
        assertTrue(service.tryProcessEvent(event()));assertEquals("OPEN_MANUAL",created.get().getStatus());assertEquals("network_only".equals(advice)?"unrecognized_advice":advice,created.get().getLatestAdviceCode());
        assertEquals(0,pending.stream().filter(a->Set.of("RETRY_PAY","SEND_EMAIL").contains(a.getActionType())).count());assertEquals(2,pending.stream().filter(a->Set.of("CAPTURE_FAILURE","NOTIFY_INTERNAL_FAILURE").contains(a.getActionType())).count());verify(stripe,never()).execute(any());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"without_pi","with_pi"})
    void nativeHttpDeclineAdviceCancelsFutureSlotsWithoutSendingAnInsufficientFundsEmail(String expansion)throws Exception{
        var f=retryGuardFixture();var body="{\"error\":{\"decline_code\":\"insufficient_funds\",\"advice_code\":\"do_not_try_again\",\"client_secret\":\"must_not_persist\"}}";
        var error=ApiResource.GSON.fromJson("{\"decline_code\":\"insufficient_funds\"}",com.stripe.model.StripeError.class);error.setLastResponse(new com.stripe.net.StripeResponse(402,com.stripe.net.HttpHeaders.of(Map.of()),body));
        if("with_pi".equals(expansion)){var intent=new PaymentIntent();intent.setId("pi_old");intent.setLatestCharge("ch_retry");error.setPaymentIntent(intent);}
        var decline=new com.stripe.exception.CardException("declined","req_advice","card_declined",null,"insufficient_funds","ch_retry",402,null);decline.setStripeError(error);var invoice=mock(Invoice.class);when(invoice.pay(anyMap(),any(com.stripe.net.RequestOptions.class))).thenThrow(decline);
        when(actions.complete(eq(10L),anyInt(),anyString(),anyString(),anyString(),any())).thenAnswer(call->{f.slot().setStatus(call.getArgument(3));return 1;});
        when(stripe.execute(any())).thenAnswer(call->new SubscriptionRecoveryStripeGateway().execute(call.getArgument(0)));
        try(var api=mockStatic(Invoice.class)){api.when(()->Invoice.retrieve("in_old")).thenReturn(invoice);service.runDueAction(10L);}
        assertEquals("OPEN_MANUAL",f.root().getStatus());assertEquals("do_not_try_again",f.root().getLatestAdviceCode());assertEquals("SUCCEEDED",f.slot().getStatus());assertEquals("SKIPPED",f.ledger().get(1).getStatus());
        assertEquals(0,f.ledger().stream().filter(a->"SEND_EMAIL".equals(a.getActionType())).count());assertFalse(f.root().getFailureEvidence().contains("must_not_persist"));verify(stripe,times(1)).execute(any());
    }
    @Test void consistentPartialCashAtD30StillCancelsButALaggingInvoiceWaitsForTheReceiptToSettle(){
        var f=retryGuardFixture();f.root().setDeadlineAt(com.studyagent.common.datetime.DateTimeFormats.fromInstant(now));f.snapshot().paymentIntent().setStatus("succeeded");f.snapshot().paymentIntent().setAmountReceived(100L);f.snapshot().charge().setStatus("succeeded");f.snapshot().charge().setPaid(true);f.snapshot().charge().setCaptured(true);f.snapshot().charge().setAmountCaptured(100L);
        var quota=injectQuota();service.reconcile(1L);assertEquals("CLOSING",f.root().getStatus());assertEquals(0,f.ledger().stream().filter(a->"CANCEL_SUBSCRIPTION".equals(a.getActionType())).count());verifyNoInteractions(quota);
        f.snapshot().invoice().setAmountPaid(100L);f.snapshot().invoice().setAmountRemaining(2800L);service.reconcile(1L);
        assertEquals("DEADLINE",f.root().getCloseReason());assertEquals(1,f.ledger().stream().filter(a->"CANCEL_SUBSCRIPTION".equals(a.getActionType())).count());assertNull(f.root().getAccountClosedAt());verifyNoInteractions(quota);verify(stripe,never()).execute(any());
    }

    @Test void recoveryGrantPassesVerifiedBusinessTimeToLocalAddonResume() {
        var snap=paidSnapshot();var root=explicitTimeGrantRoot();ownedFixture(root,snap);var quota=injectQuota();
        assertTrue(service.tryProcessEvent(event()));
        assertLocalAddonTime(quota,"resumeEligibleAddonsAt",snap.businessNow());
    }
    @Test void replacementGrantPassesVerifiedBusinessTimeToLocalAddonResume() {
        replacementPaymentFixture(2900L,"cus_old");var quota=injectQuota();
        assertTrue(service.tryProcessEvent(replacementPaidEvent()));
        assertLocalAddonTime(quota,"resumeEligibleAddonsAt",stripe.readInvoice("in_new").businessNow());
    }
    @Test void recoveryDisputeHoldPassesVerifiedBusinessTimeToLocalAddonPause() {
        var snap=paidSnapshot();var root=confirmedPaidRoot(snap);ownedFixture(root,snap);var quota=injectQuota();
        var dispute=new Dispute();dispute.setId("dp_time");dispute.setStatus("needs_response");when(stripe.readDispute("dp_time")).thenReturn(dispute);when(stripe.readCharge("ch_old")).thenReturn(snap.charge());
        assertTrue(service.tryProcessEvent(ApiResource.GSON.fromJson("{\"id\":\"evt_time\",\"type\":\"charge.dispute.created\",\"data\":{\"object\":{\"id\":\"dp_time\",\"invoice\":\"in_old\",\"charge\":\"ch_old\"}}}",Event.class)));
        assertLocalAddonTime(quota,"pauseAddonsAt",snap.businessNow());
    }
    @Test void recoveryDisputeWonPassesVerifiedBusinessTimeToLocalAddonResume() {
        var snap=paidSnapshot();var root=confirmedPaidRoot(snap);root.setGrantState("HELD");root.setDisputeState("OPEN");root.setLastDisputeId("dp_time");ownedFixture(root,snap);var quota=injectQuota();
        var dispute=new Dispute();dispute.setId("dp_time");dispute.setStatus("won");when(stripe.readDispute("dp_time")).thenReturn(dispute);when(stripe.readCharge("ch_old")).thenReturn(snap.charge());
        assertTrue(service.tryProcessEvent(ApiResource.GSON.fromJson("{\"id\":\"evt_time\",\"type\":\"charge.dispute.closed\",\"data\":{\"object\":{\"id\":\"dp_time\",\"invoice\":\"in_old\",\"charge\":\"ch_old\"}}}",Event.class)));
        assertLocalAddonTime(quota,"resumeEligibleAddonsAt",snap.businessNow());
    }
    @Test void recoveryGrantRejectsMissingGatewayBusinessTimeInsteadOfUsingTheHostClock() {
        var paid=paidSnapshot();
        var snap=new SubscriptionRecoveryStripeGateway.Snapshot(paid.invoice(),paid.subscription(),paid.schedule(),paid.lines(),paid.paymentIntent(),paid.charge(),null);
        var root=explicitTimeGrantRoot();ownedFixture(root,snap);var quota=injectQuota();
        assertEquals("RECOVERY_BUSINESS_TIME_MISSING",assertThrows(IllegalStateException.class,()->service.tryProcessEvent(event())).getMessage());
        assertFalse(mockingDetails(quota).getInvocations().stream().anyMatch(call->call.getMethod().getName().startsWith("resume")));
    }
    private SubscriptionPaymentRecoveryEntity explicitTimeGrantRoot(){
        var root=existingRoot();root.setGrantState("NONE");root.setDisplayTier("pro");
        root.setContractSnapshot("{\"price_id\":\"price_old\",\"quantity\":1,\"recovery_admission\":{\"sourceKey\":\"formal\",\"cancelMode\":\"RENEW\",\"contractHash\":\"original\"}}");
        return root;
    }
    private static void assertLocalAddonTime(BillingQuotaGateway quota,String method,Instant expected){
        var invocations=mockingDetails(quota).getInvocations();
        assertTrue(invocations.stream().anyMatch(call->call.getMethod().getName().equals(method)
                &&call.getArguments().length==4&&Objects.equals(expected,call.getArgument(3))),
                "Recovery must pass the gateway business time to "+method);
        assertFalse(invocations.stream().anyMatch(call->Set.of("pauseAddons","resumeEligibleAddons").contains(call.getMethod().getName())),
                "Recovery must never invoke add-on lifecycle methods that resolve Stripe time");
    }
    @Test void disabledNewUnrelatedEventDoesNotReadStripeOrMutateRecovery() {
        assertFalse(service.tryProcessEvent(event()));verifyNoInteractions(stripe);
        verify(recoveries,never()).insert(any(SubscriptionPaymentRecoveryEntity.class));
    }
    @Test void scheduleIdWithMissingSnapshotThrowsRetryReadBeforeAnyRecoveryWrites() {
        enable();var snapshot=snapshot();snapshot.subscription().setSchedule("sched_missing");
        when(stripe.readInvoice("in_old")).thenReturn(snapshot);
        assertThrows(IllegalStateException.class,()->service.tryProcessEvent(event()));
        verify(recoveries,never()).insert(any(SubscriptionPaymentRecoveryEntity.class));
        verify(actions,never()).insert(any(SubscriptionRecoveryActionEntity.class));
        verify(users,never()).update(any(UserSubscriptionEntity.class),any());
    }
    @Test void anAttachedInvoiceCanNeverDispatchAForgedAutomaticRetryAction() {
        var root=existingRoot();ownedFixture(root,snapshot());var child=existingRoot();child.setId(2L);child.setOwnerRecoveryId(1L);child.setStripeInvoiceId("in_child");when(recoveries.selectById(2L)).thenReturn(child);var action=readyAction("RETRY_PAY","in_child");action.setRecoveryId(2L);action.setScheduledAt(com.studyagent.common.datetime.DateTimeFormats.fromInstant(now));action.setNextRunAt(action.getScheduledAt());
        service.runDueAction(10L);assertEquals("SKIPPED",action.getStatus());assertEquals("SKIPPED_NON_PRIMARY",action.getSkipReason());verify(stripe,never()).execute(any());
    }
    @Test void remoteReadFailureCannotBecomeLegacyFalse() {
        enable();when(stripe.readInvoice("in_old")).thenThrow(new IllegalStateException("RECOVERY_ADMISSION_READ_TIMEOUT:in_old"));
        assertThrows(IllegalStateException.class,()->service.tryProcessEvent(event()));
        verify(recoveries,never()).insert(any(SubscriptionPaymentRecoveryEntity.class));
    }
    @Test void processingTheLaterFailureFirstUsesProviderHistoryRatherThanStartingANewThirtyDayWindow() {
        enable();var snap=snapshot();snap.charge().setCreated(now.plusSeconds(7*86400L).getEpochSecond());snap.charge().setCustomer("cus_old");snap.charge().setInvoice("in_old");snap.charge().setPaymentIntent("pi_old");var earlier=ApiResource.GSON.fromJson(snap.charge().toJson(),Charge.class);earlier.setId("ch_first");earlier.setCreated(now.getEpochSecond());var incoming=event();incoming.setId("evt_later");incoming.setCreated(now.plusSeconds(7*86400L).getEpochSecond());var current=new SubscriptionRecoveryStripeGateway.Snapshot(snap.invoice(),snap.subscription(),snap.schedule(),snap.lines(),snap.paymentIntent(),snap.charge(),now.plusSeconds(7*86400L));var real=spy(new SubscriptionRecoveryStripeGateway(java.time.Clock.fixed(now.plusSeconds(7*86400L),java.time.ZoneOffset.UTC)));doReturn(current).when(real).readInvoice("in_old");doReturn(current.invoice()).when(real).readInvoiceHeader("in_old");org.springframework.test.util.ReflectionTestUtils.setField(service,"stripe",real);when(users.selectByUserForUpdate("user")).thenReturn(user());when(tx.getTransaction(any())).thenReturn(mock(TransactionStatus.class));when(recoveries.insert(any(SubscriptionPaymentRecoveryEntity.class))).thenAnswer(i->{((SubscriptionPaymentRecoveryEntity)i.getArgument(0)).setId(1L);return 1;});var events=mock(EventCollection.class);when(events.autoPagingIterable()).thenReturn(List.of(incoming,event()));var charges=mock(ChargeCollection.class);when(charges.autoPagingIterable()).thenReturn(List.of(snap.charge(),earlier));
        try(var eventApi=mockStatic(Event.class);var chargeApi=mockStatic(Charge.class)){
            eventApi.when(()->Event.list(anyMap())).thenReturn(events);chargeApi.when(()->Charge.list(anyMap())).thenReturn(charges);assertTrue(service.tryProcessEvent(incoming));var capture=org.mockito.ArgumentCaptor.forClass(SubscriptionPaymentRecoveryEntity.class);verify(recoveries).insert(capture.capture());assertEquals(com.studyagent.common.datetime.DateTimeFormats.fromInstant(now),capture.getValue().getFirstFailedAt());assertEquals(com.studyagent.common.datetime.DateTimeFormats.fromInstant(now.plusSeconds(30*86400L)),capture.getValue().getDeadlineAt());
            var attempts=org.mockito.ArgumentCaptor.forClass(SubscriptionRecoveryActionEntity.class);verify(actions,atLeast(4)).insert(attempts.capture());var retries=attempts.getAllValues().stream().filter(a->"RETRY_PAY".equals(a.getActionType())).toList();assertEquals("SKIPPED_LATE_ADMISSION",retries.get(0).getSkipReason());assertEquals("READY",retries.get(1).getStatus());
        }
    }
    @Test void replayingTheFirstFailureAfterALaterChargeDoesNotExtendTheThirtyDayWindow() {
        enable();var original=snapshot();original.charge().setCreated(now.plusSeconds(7*86400L).getEpochSecond());var snap=new SubscriptionRecoveryStripeGateway.Snapshot(original.invoice(),original.subscription(),original.schedule(),original.lines(),original.paymentIntent(),original.charge(),now.plusSeconds(7*86400L));when(stripe.readInvoice("in_old")).thenReturn(snap);when(users.selectByUserForUpdate("user")).thenReturn(user());when(tx.getTransaction(any())).thenReturn(mock(TransactionStatus.class));when(recoveries.insert(any(SubscriptionPaymentRecoveryEntity.class))).thenAnswer(i->{((SubscriptionPaymentRecoveryEntity)i.getArgument(0)).setId(1L);return 1;});
        assertTrue(service.tryProcessEvent(event()));var captured=org.mockito.ArgumentCaptor.forClass(SubscriptionPaymentRecoveryEntity.class);verify(recoveries).insert(captured.capture());assertEquals(com.studyagent.common.datetime.DateTimeFormats.fromInstant(now),captured.getValue().getFirstFailedAt());assertEquals(com.studyagent.common.datetime.DateTimeFormats.fromInstant(now.plusSeconds(30*86400L)),captured.getValue().getDeadlineAt());
        var evidence=com.google.gson.JsonParser.parseString(captured.getValue().getContractSnapshot()).getAsJsonObject().getAsJsonObject("first_failure_evidence");assertEquals("invoice_failure_event",evidence.get("selected_source").getAsString());assertEquals(now.getEpochSecond(),evidence.get("selected_at").getAsLong());
        var actionCapture=org.mockito.ArgumentCaptor.forClass(SubscriptionRecoveryActionEntity.class);verify(actions,atLeast(4)).insert(actionCapture.capture());var slots=actionCapture.getAllValues().stream().filter(a->"RETRY_PAY".equals(a.getActionType())).toList();assertEquals(4,slots.size());for(int slot=1;slot<=4;slot++)assertEquals(com.studyagent.common.datetime.DateTimeFormats.fromInstant(now.plusSeconds(7*slot*86400L)),slots.get(slot-1).getScheduledAt());
    }
    @Test void releasedTrialScheduleCannotUseTheFormalCohortWhenConversionProvenanceIsUnknown() {
        enable();var snap=snapshot();snap.subscription().setMetadata(Map.of("purchase_type","subscription_intro_trial","plan_code","pro_trial_to_monthly","conversion_plan_code","pro_monthly"));when(stripe.readInvoice("in_old")).thenReturn(snap);when(users.selectByUserForUpdate("user")).thenReturn(user());when(tx.getTransaction(any())).thenReturn(mock(TransactionStatus.class));when(recoveries.insert(any(SubscriptionPaymentRecoveryEntity.class))).thenAnswer(i->{((SubscriptionPaymentRecoveryEntity)i.getArgument(0)).setId(1L);return 1;});
        assertThrows(IllegalStateException.class,()->service.tryProcessEvent(event()));verify(recoveries,never()).insert(any(SubscriptionPaymentRecoveryEntity.class));verify(actions,never()).insert(any(SubscriptionRecoveryActionEntity.class));
    }
    @Test void releasedFirstConversionRequiresItsTrialKeyEvenWhenTheFormalKeyIsVerified() {
        var snap=historicalTrialFixture(false);assertFalse(service.tryProcessEvent(event()));verify(recoveries,never()).insert(any(SubscriptionPaymentRecoveryEntity.class));
    }
    @Test void aProvenReleasedFirstConversionUsesTheOriginalTrialSource() {
        historicalTrialFixture(false);properties.setVerifiedCohorts(Set.of("pro_trial_to_monthly:pro_monthly:month_1:NO_SCHEDULE:RENEW"));assertTrue(service.tryProcessEvent(event()));var capture=org.mockito.ArgumentCaptor.forClass(SubscriptionPaymentRecoveryEntity.class);verify(recoveries).insert(capture.capture());assertTrue(capture.getValue().getContractSnapshot().contains("historical_schedule"));assertTrue(capture.getValue().getContractSnapshot().contains("pro_trial_to_monthly:pro_monthly:month_1:NO_SCHEDULE:RENEW"));
    }
    @Test void aLaterRenewalWithPersistentTrialMetadataUsesTheProvenFormalSource() {
        historicalTrialFixture(true);assertTrue(service.tryProcessEvent(event()));var capture=org.mockito.ArgumentCaptor.forClass(SubscriptionPaymentRecoveryEntity.class);verify(recoveries).insert(capture.capture());var admission=com.google.gson.JsonParser.parseString(capture.getValue().getContractSnapshot()).getAsJsonObject().getAsJsonObject("recovery_admission");assertEquals("formal",admission.get("sourceKey").getAsString());
    }
    @Test void aScheduleReleasedBeforeTheConversionBoundaryCannotProveThatConversion() {
        historicalTrialFixture(false);stripe.listSubscriptionSchedules("cus_old","sub_old").get(0).setReleasedAt(now.minusSeconds(1).getEpochSecond());assertThrows(IllegalStateException.class,()->service.tryProcessEvent(event()));verify(recoveries,never()).insert(any(SubscriptionPaymentRecoveryEntity.class));
    }
    private SubscriptionRecoveryStripeGateway.Snapshot historicalTrialFixture(boolean later) {
        enable();var snap=snapshot();snap.subscription().setMetadata(Map.of("purchase_type","subscription_intro_trial","plan_code","pro_trial_to_monthly","conversion_plan_code","pro_monthly"));if(later)snap.lines().get(0).getPeriod().setStart(now.plusSeconds(30*86400L).getEpochSecond());
        var history=ApiResource.GSON.fromJson("{\"id\":\"sched_released\",\"customer\":\"cus_old\",\"released_subscription\":\"sub_old\",\"status\":\"released\",\"released_at\":1794362410,\"phases\":[{\"start_date\":1793757600,\"end_date\":1794362400,\"items\":[{\"price\":\"price_intro\",\"quantity\":1}]},{\"start_date\":1794362400,\"end_date\":1796954400,\"items\":[{\"price\":\"price_old\",\"quantity\":1}]}]}",SubscriptionSchedule.class);when(stripe.listSubscriptionSchedules("cus_old","sub_old")).thenReturn(List.of(history));when(stripe.readInvoice("in_old")).thenReturn(snap);var intro=new SubscriptionPlanEntity();intro.setPlanCode("pro_trial_to_monthly");intro.setTier("pro");var formal=new SubscriptionPlanEntity();formal.setPlanCode("pro_monthly");formal.setTier("pro");formal.setBillingInterval("month");when(plans.selectList(any())).thenReturn(List.of(intro),List.of(formal),List.of(formal));when(users.selectByUserForUpdate("user")).thenReturn(user());when(tx.getTransaction(any())).thenReturn(mock(TransactionStatus.class));when(recoveries.insert(any(SubscriptionPaymentRecoveryEntity.class))).thenAnswer(i->{((SubscriptionPaymentRecoveryEntity)i.getArgument(0)).setId(1L);return 1;});return snap;
    }
    @Test void legacyFirstConversionWithoutAnyScheduleUsesItsPaidIntroInvoiceBoundary(){
        invoiceHistoryTrialFixture(false);properties.setVerifiedCohorts(Set.of("pro_trial_to_monthly:pro_monthly:month_1:NO_SCHEDULE:RENEW"));
        assertTrue(assertDoesNotThrow(()->service.tryProcessEvent(event())));var captured=org.mockito.ArgumentCaptor.forClass(SubscriptionPaymentRecoveryEntity.class);verify(recoveries).insert(captured.capture());assertTrue(captured.getValue().getContractSnapshot().contains("historical_invoice_pair"));assertTrue(captured.getValue().getContractSnapshot().contains("pro_trial_to_monthly:pro_monthly:month_1:NO_SCHEDULE:RENEW"));
    }
    @Test void legacyLaterRenewalUsesFormalOnlyAfterItsFirstFormalInvoiceIsProven(){
        invoiceHistoryTrialFixture(true);assertTrue(assertDoesNotThrow(()->service.tryProcessEvent(event())));var captured=org.mockito.ArgumentCaptor.forClass(SubscriptionPaymentRecoveryEntity.class);verify(recoveries).insert(captured.capture());var admission=com.google.gson.JsonParser.parseString(captured.getValue().getContractSnapshot()).getAsJsonObject().getAsJsonObject("recovery_admission");assertEquals("formal",admission.get("sourceKey").getAsString());
    }
    @Test void legacyFirstConversionCannotBorrowAnAllowedFormalCase(){
        invoiceHistoryTrialFixture(false);assertFalse(assertDoesNotThrow(()->service.tryProcessEvent(event())));verify(recoveries,never()).insert(any(SubscriptionPaymentRecoveryEntity.class));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"wrong_customer","unpaid_intro","gap","metadata_conflict","currency_mismatch","out_of_band","mixed_history","truncated_history","earlier_formal","competing_formal","missing_currency"})
    void incompleteOrConflictingInvoiceHistoryCannotUnlockAFormalCase(String problem){
        invoiceHistoryTrialFixture(true);var history=stripe.listInvoices("sub_old");var intro=history.get(0);
        switch(problem){case "wrong_customer" -> intro.setCustomer("cus_other");case "unpaid_intro" -> {intro.setStatus("open");intro.setAmountPaid(0L);}case "gap" -> intro.getLines().getData().get(0).getPeriod().setEnd(now.minusSeconds(1).getEpochSecond());case "metadata_conflict" -> stripe.readInvoice("in_old").subscription().getMetadata().put("plan_code","pro_trial_to_yearly");
            case "currency_mismatch" -> intro.setCurrency("hkd");case "missing_currency" -> {intro.setCurrency(null);history.get(1).setCurrency(null);}case "out_of_band" -> intro.setPaidOutOfBand(true);
            case "mixed_history" -> intro.getLines().getData().add(ApiResource.GSON.fromJson(intro.getLines().getData().get(0).toJson(),InvoiceLineItem.class));
            case "truncated_history" -> intro.getLines().setHasMore(true);
            case "earlier_formal","competing_formal" -> {var other=ApiResource.GSON.fromJson(history.get(1).toJson(),Invoice.class);other.setId("in_other_formal");if("earlier_formal".equals(problem))other.getLines().getData().get(0).getPeriod().setStart(now.minusSeconds(7*86400L).getEpochSecond());else other.getLines().getData().get(0).getPrice().setId("price_other_formal");var expanded=new ArrayList<>(history);expanded.add(other);when(stripe.listInvoices("sub_old")).thenReturn(expanded);}
        }
        assertThrows(IllegalStateException.class,()->service.tryProcessEvent(event()));verify(recoveries,never()).insert(any(SubscriptionPaymentRecoveryEntity.class));verify(users,never()).update(any(UserSubscriptionEntity.class),any());
    }
    private SubscriptionRecoveryStripeGateway.Snapshot invoiceHistoryTrialFixture(boolean later){
        enable();var snap=snapshot();snap.subscription().setMetadata(new HashMap<>(Map.of("purchase_type","subscription_intro_trial","plan_code","pro_trial_to_monthly","conversion_plan_code","pro_monthly")));
        if(later){snap.lines().get(0).getPeriod().setStart(now.plusSeconds(30*86400L).getEpochSecond());snap.lines().get(0).getPeriod().setEnd(now.plusSeconds(60*86400L).getEpochSecond());}else snap.invoice().setBillingReason("subscription_update");
        var current=new SubscriptionRecoveryStripeGateway.Snapshot(snap.invoice(),snap.subscription(),null,snap.lines(),snap.paymentIntent(),snap.charge(),later?now.plusSeconds(30*86400L):now);
        var intro=ApiResource.GSON.fromJson("{\"id\":\"in_intro_history\",\"object\":\"invoice\",\"customer\":\"cus_old\",\"subscription\":\"sub_old\",\"status\":\"paid\",\"amount_paid\":299,\"paid_out_of_band\":false,\"currency\":\"usd\",\"billing_reason\":\"subscription_create\",\"lines\":{\"has_more\":false,\"data\":[{\"id\":\"il_intro\",\"type\":\"subscription\",\"subscription\":\"sub_old\",\"proration\":false,\"quantity\":1,\"price\":{\"id\":\"price_intro\",\"recurring\":{\"interval\":\"week\",\"interval_count\":1}},\"period\":{\"start\":1793757600,\"end\":1794362400}}]}}",Invoice.class);
        var first=ApiResource.GSON.fromJson(intro.toJson(),Invoice.class);first.setId("in_first_formal_history");first.setBillingReason("subscription_update");first.getLines().getData().get(0).getPrice().setId("price_old");first.getLines().getData().get(0).getPrice().getRecurring().setInterval("month");first.getLines().getData().get(0).getPeriod().setStart(now.getEpochSecond());first.getLines().getData().get(0).getPeriod().setEnd(now.plusSeconds(30*86400L).getEpochSecond());
        when(stripe.listInvoices("sub_old")).thenReturn(later?List.of(intro,first,snap.invoice()):List.of(intro,snap.invoice()));when(stripe.readInvoice("in_old")).thenReturn(current);when(stripe.listSubscriptionSchedules("cus_old","sub_old")).thenReturn(List.of());
        var introPlan=new SubscriptionPlanEntity();introPlan.setPlanCode("pro_trial_to_monthly");introPlan.setTier("pro");var formal=new SubscriptionPlanEntity();formal.setPlanCode("pro_monthly");formal.setTier("pro");formal.setBillingInterval("month");
        when(plans.selectList(any())).thenAnswer(call->{var query=(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SubscriptionPlanEntity>)call.getArgument(0);query.getSqlSegment();if(query.getParamNameValuePairs().containsValue("price_other_formal")){var other=new SubscriptionPlanEntity();other.setPlanCode("plus_monthly");other.setTier("plus");return List.of(other);}return query.getParamNameValuePairs().containsValue("price_intro")?List.of(introPlan):List.of(formal);});
        when(users.selectByUserForUpdate("user")).thenReturn(user());when(tx.getTransaction(any())).thenReturn(mock(TransactionStatus.class));when(recoveries.insert(any(SubscriptionPaymentRecoveryEntity.class))).thenAnswer(call->{((SubscriptionPaymentRecoveryEntity)call.getArgument(0)).setId(1L);return 1;});return current;
    }
    @Test void unknownFirstFailureHistoryCannotCreateALockOrFallBackToLegacy() {
        enable();when(stripe.readInvoice("in_old")).thenReturn(snapshot());doThrow(new IllegalStateException("RECOVERY_FIRST_FAILURE_HISTORY_UNKNOWN")).when(stripe).readFirstFailure(any(),any());assertThrows(IllegalStateException.class,()->service.tryProcessEvent(event()));verify(recoveries,never()).insert(any(SubscriptionPaymentRecoveryEntity.class));verify(users,never()).update(any(UserSubscriptionEntity.class),any());verify(actions,never()).insert(any(SubscriptionRecoveryActionEntity.class));
    }
    @Test void aPreCutoffInvoiceDoesNotReadAnUnknownScheduleOrCreateRecovery() {
        enable();var old=event();var raw=com.google.gson.JsonParser.parseString(old.toJson()).getAsJsonObject();raw.getAsJsonObject("data").getAsJsonObject("object").addProperty("created",now.minusSeconds(7200).getEpochSecond());old=ApiResource.GSON.fromJson(raw,Event.class);when(stripe.readInvoice("in_old")).thenThrow(new IllegalStateException("RECOVERY_ADMISSION_UNKNOWN_SCHEDULE"));assertFalse(service.tryProcessEvent(old));verify(stripe,never()).readInvoice(anyString());verify(recoveries,never()).insert(any(SubscriptionPaymentRecoveryEntity.class));
    }
    @Test void oldInvoiceCutoffCanBeCheckedFromTheHeaderWithoutReadingItsSchedule() {
        enable();var header=new Invoice();header.setId("in_old");header.setCreated(now.minusSeconds(7200).getEpochSecond());doReturn(header).when(stripe).readInvoiceHeader("in_old");when(stripe.readInvoice("in_old")).thenThrow(new IllegalStateException("metadata unavailable"));assertFalse(service.tryProcessEvent(event()));verify(stripe).readInvoiceHeader("in_old");verify(stripe,never()).readInvoice("in_old");
    }
    @Test void aPreCutoffFailureEventDoesNotNeedOptionalContractReads() {
        enable();var old=event();old.setCreated(now.minusSeconds(7200).getEpochSecond());when(stripe.readInvoice("in_old")).thenThrow(new IllegalStateException("RECOVERY_ADMISSION_READ_TIMEOUT"));assertFalse(service.tryProcessEvent(old));verify(stripe,never()).readInvoice(anyString());
    }
    @Test void aLocalTrialMirrorCannotBecomeAFormalCohortWhenTheOriginalPhaseEvidenceIsMissing() {
        enable();properties.setVerifiedCohorts(Set.of("formal:pro_monthly:month_1:EQUIVALENT_SCHEDULE:RENEW"));var snap=snapshot();snap.subscription().setSchedule("sched_incomplete_origin");var schedule=ApiResource.GSON.fromJson("{\"id\":\"sched_incomplete_origin\",\"subscription\":\"sub_old\",\"status\":\"active\",\"end_behavior\":\"release\",\"default_settings\":{\"collection_method\":\"charge_automatically\"},\"current_phase\":{\"start_date\":1794362400,\"end_date\":1796954400},\"phases\":[{\"start_date\":1794362400,\"end_date\":1796954400,\"items\":[{\"price\":\"price_old\",\"quantity\":1}]}]}",SubscriptionSchedule.class);var current=new SubscriptionRecoveryStripeGateway.Snapshot(snap.invoice(),snap.subscription(),schedule,snap.lines(),snap.paymentIntent(),snap.charge(),now);when(stripe.readInvoice("in_old")).thenReturn(current);var user=user();user.setPlanCode("pro_trial_to_monthly");user.setSubscriptionPhase("intro");when(users.selectByStripeSubscriptionId("sub_old")).thenReturn(user);when(users.selectByUserForUpdate("user")).thenReturn(user);when(tx.getTransaction(any())).thenReturn(mock(TransactionStatus.class));when(recoveries.insert(any(SubscriptionPaymentRecoveryEntity.class))).thenAnswer(i->{((SubscriptionPaymentRecoveryEntity)i.getArgument(0)).setId(1L);return 1;});assertThrows(IllegalStateException.class,()->service.tryProcessEvent(event()));verify(recoveries,never()).insert(any(SubscriptionPaymentRecoveryEntity.class));
    }
    @Test void firstInsufficientFundsCreatesExactlyFourFixedSlotsAndClearsGrace() {
        enable();var snapshot=snapshot();when(stripe.readInvoice("in_old")).thenReturn(snapshot);
        var user=user();when(users.selectByUserForUpdate("user")).thenReturn(user);
        when(tx.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(recoveries.insert(any(SubscriptionPaymentRecoveryEntity.class))).thenAnswer(i->{((SubscriptionPaymentRecoveryEntity)i.getArgument(0)).setId(1L);return 1;});
        when(users.update(any(UserSubscriptionEntity.class),any())).thenReturn(1);
        assertTrue(service.tryProcessEvent(event()));
        var captor=org.mockito.ArgumentCaptor.forClass(SubscriptionRecoveryActionEntity.class);
        verify(actions,atLeast(6)).insert(captor.capture());
        var retries=captor.getAllValues().stream().filter(a->"RETRY_PAY".equals(a.getActionType())).toList();
        assertEquals(4,retries.size());assertNull(user.getGraceEndAt());assertEquals(1L,user.getPaymentRecoveryId());
        assertEquals(now.plusSeconds(7*86400),retries.get(0).getScheduledAt().atZone(com.studyagent.common.datetime.DateTimeFormats.APP_ZONE).toInstant());
        assertTrue(retries.stream().allMatch(a->a.getProviderIdempotencyKey().contains("in_old")));
        assertEquals(0,captor.getAllValues().stream().filter(a->"SEND_EMAIL".equals(a.getActionType())).count());
        for(var notification:captor.getAllValues().stream().filter(a->Set.of("CAPTURE_FAILURE","NOTIFY_INTERNAL_FAILURE").contains(a.getActionType())).toList()){
            var payload=com.google.gson.JsonParser.parseString(notification.getPayloadJson()).getAsJsonObject();assertEquals(2900L,payload.get("amount_minor").getAsLong());assertEquals("usd",payload.get("currency").getAsString());
        }
        verify(orders,never()).selectOne(any());
    }
    @Test void knownOwnedInvoiceStaysOwnedWhenAcceptNewIsOff() {
        var root=new SubscriptionPaymentRecoveryEntity();root.setId(1L);root.setClerkUserId("user");root.setStripeInvoiceId("in_old");root.setStripeSubscriptionId("sub_old");root.setStatus("PAID");root.setGrantState("GRANTED");
        when(recoveries.selectByInvoice("in_old")).thenReturn(root);
        when(stripe.readInvoice("in_old")).thenThrow(new IllegalStateException("read timeout"));
        assertThrows(IllegalStateException.class,()->service.tryProcessEvent(event()));verify(stripe).readInvoice("in_old");
    }
    @Test void partialPaymentAtDeadlineStillBeginsSubscriptionClosure() {
        var snap=snapshot();snap.invoice().setAmountPaid(100L);snap.invoice().setAmountRemaining(2800L);
        var root=existingRoot();root.setStatus("OPEN_MANUAL");root.setDeadlineAt(com.studyagent.common.datetime.DateTimeFormats.fromInstant(now.minusSeconds(1)));
        ownedFixture(root,snap);assertTrue(service.tryProcessEvent(event()));
        assertEquals("CLOSING",root.getStatus());assertEquals("DEADLINE",root.getCloseReason());
        var capture=org.mockito.ArgumentCaptor.forClass(SubscriptionRecoveryActionEntity.class);verify(actions,atLeastOnce()).insert(capture.capture());
        assertTrue(capture.getAllValues().stream().anyMatch(a->"CANCEL_SUBSCRIPTION".equals(a.getActionType())));
    }
    @Test void unresolvedPaymentInReviewCannotPermitTheNextWeeklySlot() {
        enable();properties.setAutomaticPayEnabled(true);var snap=snapshot();var root=existingRoot();ownedFixture(root,snap);
        root.setContractSnapshot("{\"price_id\":\"price_old\",\"recovery_admission\":{\"contractHash\":\""+RecoveryContractClassifier.classify(snap.subscription(),null,true,"price_old",1L).contractHash()+"\"}}");
        var due=new SubscriptionRecoveryActionEntity();due.setId(10L);due.setRecoveryId(1L);due.setActionType("RETRY_PAY");due.setTargetId("in_old");due.setStatus("READY");due.setVersion(0);due.setScheduledAt(com.studyagent.common.datetime.DateTimeFormats.fromInstant(now));due.setNextRunAt(due.getScheduledAt());
        var unknown=new SubscriptionRecoveryActionEntity();unknown.setId(9L);unknown.setActionType("RETRY_PAY");unknown.setStatus("REVIEW");
        when(actions.selectById(10L)).thenReturn(due);when(actions.selectForUpdate(10L)).thenReturn(due);when(actions.selectByRecovery(1L)).thenReturn(List.of(unknown,due));
        assertDoesNotThrow(()->service.runDueAction(10L));verify(actions,never()).claim(anyLong(),anyInt(),anyString(),any(),any());verify(stripe,never()).execute(any());
    }
    @Test void deadlineMustExpireAnOpenReplacementBeforeCancelingOldSubscription() {
        var snap=snapshot();var root=existingRoot();root.setDeadlineAt(com.studyagent.common.datetime.DateTimeFormats.fromInstant(now.minusSeconds(1)));ownedFixture(root,snap);
        var replacement=new SubscriptionRecoveryReplacementEntity();replacement.setState("OPEN");replacement.setCheckoutSessionId("cs_open");when(replacements.selectActive("sub_old")).thenReturn(replacement);
        assertTrue(service.tryProcessEvent(event()));
        var capture=org.mockito.ArgumentCaptor.forClass(SubscriptionRecoveryActionEntity.class);verify(actions,atLeastOnce()).insert(capture.capture());
        assertTrue(capture.getAllValues().stream().anyMatch(a->"EXPIRE_CHECKOUT".equals(a.getActionType())));
        assertTrue(capture.getAllValues().stream().noneMatch(a->"CANCEL_SUBSCRIPTION".equals(a.getActionType())));assertNull(root.getAccountClosedAt());
    }
    @Test void payingOldInvoiceMustExpireAnUnpaidReplacementWithoutGrantingAgain() {
        enable();var snap=paidSnapshot();var root=existingRoot();root.setStatus("PAID");root.setGrantState("GRANTED");root.setEntitlementInvoiceId("in_old");root.setContractSnapshot("{\"price_id\":\"price_old\",\"quantity\":1}");ownedFixture(root,snap);
        var replacement=new SubscriptionRecoveryReplacementEntity();replacement.setState("OPEN");replacement.setCheckoutSessionId("cs_open");when(replacements.selectActive("sub_old")).thenReturn(replacement);
        assertTrue(service.tryProcessEvent(event()));
        var capture=org.mockito.ArgumentCaptor.forClass(SubscriptionRecoveryActionEntity.class);verify(actions,atLeastOnce()).insert(capture.capture());
        assertTrue(capture.getAllValues().stream().anyMatch(a->"EXPIRE_CHECKOUT".equals(a.getActionType())));assertEquals("GRANTED",root.getGrantState());
    }
    @Test void paidAtMissingMustHoldGrantAndNeverInventAProcessingTime() {
        enable();var snap=paidSnapshot();snap.invoice().setStatusTransitions(null);var root=existingRoot();root.setGrantState("NONE");root.setContractSnapshot("{\"price_id\":\"price_old\",\"quantity\":1}");ownedFixture(root,snap);
        assertTrue(service.tryProcessEvent(event()));assertEquals("PAID",root.getStatus());assertEquals("HELD",root.getGrantState());assertNull(root.getEntitlementStartAt());assertEquals("PAID_TIME_UNVERIFIED",root.getReviewReason());
    }
    @Test void paidRecoveryCreatesOneOrderWithRequiredLegacySubscriptionFields() {
        enable();var snap=paidSnapshot();var root=existingRoot();root.setDisplayTier("pro");
        root.setContractSnapshot("{\"price_id\":\"price_old\",\"quantity\":1,\"recovery_admission\":{\"sourceKey\":\"formal\",\"contractHash\":\"original\"}}");
        ownedFixture(root,snap);var quota=injectQuota();
        var history=new ArrayList<RechargeOrderEntity>();
        when(orders.selectOne(any())).thenAnswer(call->history.isEmpty()?null:history.get(0));
        when(orders.insert(any(RechargeOrderEntity.class))).thenAnswer(call->{history.add(call.getArgument(0));return 1;});
        assertTrue(service.tryProcessEvent(event()));assertTrue(service.tryProcessEvent(event()));
        assertEquals(1,history.size());var order=history.get(0);
        assertAll(()->assertEquals("subscription",order.getFeatureCode()),
                ()->assertEquals("pro_monthly",order.getPackageCode()),
                ()->assertEquals(0L,order.getQuotaAmount()),
                ()->assertEquals("completed",order.getStatus()),
                ()->assertEquals("in_old",order.getStripeInvoiceId()),
                ()->assertEquals(2900,order.getPriceCents()));
        verify(quota,times(1)).resetFromPaidInvoiceAt(eq("user"),eq("sub_old"),eq("pro_monthly"),any(),any(),eq("in_old"),any());
        verify(stripe,never()).execute(any());
    }
    private SubscriptionRecoveryStripeGateway.Snapshot paidSnapshot(){
        var snap=snapshot();snap.invoice().setStatus("paid");snap.invoice().setAmountPaid(2900L);snap.invoice().setAmountRemaining(0L);
        var times=new Invoice.StatusTransitions();times.setPaidAt(now.getEpochSecond());snap.invoice().setStatusTransitions(times);
        snap.paymentIntent().setStatus("succeeded");snap.paymentIntent().setCurrency("usd");snap.paymentIntent().setAmountReceived(2900L);snap.paymentIntent().setCustomer("cus_old");
        snap.invoice().setStartingBalance(0L);snap.charge().setStatus("succeeded");snap.charge().setPaid(true);snap.charge().setCaptured(true);snap.charge().setAmount(2900L);snap.charge().setAmountCaptured(2900L);snap.charge().setCurrency("usd");snap.charge().setCustomer("cus_old");snap.charge().setPaymentIntent("pi_old");snap.charge().setInvoice("in_old");return snap;
    }
    @Test void restrictedPortalCannotChooseAnotherStripeCustomer() {
        var root=existingRoot();when(recoveries.selectById(1L)).thenReturn(root);properties.setPortalMethodOnlyConfigurationId("bpc_safe");
        assertThrows(IllegalArgumentException.class,()->service.createPortalSession("user",1L,"payment_method_update",Map.of("customer","cus_other","configuration","bpc_safe")));
        verify(stripe,never()).execute(any());verify(actions,never()).insert(any(SubscriptionRecoveryActionEntity.class));
    }
    @Test void restrictedPortalPersistsOneClaimedActionButNeverStoresTheOpaqueUrl() {
        var root=existingRoot();ownedFixture(root,snapshot());properties.setPortalMethodOnlyConfigurationId("bpc_safe");
        var saved=new java.util.concurrent.atomic.AtomicReference<SubscriptionRecoveryActionEntity>();
        when(actions.insert(any(SubscriptionRecoveryActionEntity.class))).thenAnswer(i->{var a=(SubscriptionRecoveryActionEntity)i.getArgument(0);a.setId(10L);saved.set(a);return 1;});
        when(actions.selectById(10L)).thenAnswer(i->saved.get());when(actions.selectForUpdate(10L)).thenAnswer(i->saved.get());
        when(actions.claim(eq(10L),anyInt(),anyString(),any(),any())).thenReturn(1);when(actions.markDispatched(eq(10L),anyInt(),anyString(),any(),anyString())).thenReturn(1);
        when(actions.complete(eq(10L),anyInt(),anyString(),anyString(),anyString(),any())).thenAnswer(i->{saved.get().setStatus(i.getArgument(3));saved.get().setResultJson(i.getArgument(4));return 1;});
        var portal=new com.stripe.model.billingportal.Session();portal.setId("bps_safe");portal.setUrl("https://billing.stripe.test/opaque-user-secret");
        when(stripe.execute(any())).thenReturn(new SubscriptionRecoveryStripeGateway.WriteResult("SUCCEEDED","req_portal",null,portal));
        var result=service.createPortalSession("user",1L,"payment_method_update",Map.of("customer","cus_old","configuration","bpc_safe"));
        assertEquals(portal.getUrl(),result.getUrl());assertEquals("CREATE_PORTAL",saved.get().getActionType());
        assertFalse(saved.get().getPayloadJson().contains("opaque-user-secret"));assertFalse(saved.get().getResultJson().contains("opaque-user-secret"));assertTrue(saved.get().getResultJson().contains("bps_safe"));
    }
    @Test void readyCheckoutIntentCannotDispatchAfterOldInvoiceAlreadyPaid() {
        var root=existingRoot();root.setStatus("PAID");root.setGrantState("GRANTED");ownedFixture(root,paidSnapshot());
        var action=readyAction("CREATE_CHECKOUT","cus_old");action.setPayloadJson("{\"metadata\":{\"order_no\":\"order_pending\"}}");
        var rep=new SubscriptionRecoveryReplacementEntity();rep.setState("CREATING");rep.setRecoveryId(1L);rep.setOrderNo("order_pending");rep.setOldSubscriptionId("sub_old");rep.setActiveSubscriptionKey("sub_old");
        when(replacements.selectActive("sub_old")).thenReturn(rep);when(replacements.selectByOrder("order_pending")).thenReturn(rep);
        service.runDueAction(10L);assertEquals("SKIPPED",action.getStatus());verify(actions,never()).claim(anyLong(),anyInt(),anyString(),any(),any());verify(stripe,never()).execute(any());assertNull(rep.getActiveSubscriptionKey());
    }
    @Test void readyDeadlineCancelCannotDispatchAfterOldInvoiceAlreadyPaid() {
        var root=existingRoot();root.setStatus("PAID");root.setGrantState("GRANTED");root.setCloseReason("DEADLINE");ownedFixture(root,paidSnapshot());
        var action=readyAction("CANCEL_SUBSCRIPTION","sub_old");service.runDueAction(10L);
        assertEquals("SKIPPED",action.getStatus());verify(actions,never()).claim(anyLong(),anyInt(),anyString(),any(),any());verify(stripe,never()).execute(any());
    }
    @Test void aHistoricalPaidInvoiceCannotFundTheCurrentUnpaidRecoveryWindow() {
        var root=existingRoot();root.setContractSnapshot("{\"price_id\":\"price_old\",\"quantity\":1}");ownedFixture(root,snapshot());var historical=oldPaidInvoice();when(stripe.readInvoice("in_historical")).thenReturn(historical);var quota=injectQuota();
        assertFalse(service.tryProcessEvent(historicalEvent("invoice.paid")));assertEquals("OPEN_AUTO",root.getStatus());assertNull(root.getEntitlementInvoiceId());verify(recoveries,never()).insert(any(SubscriptionPaymentRecoveryEntity.class));verifyNoInteractions(quota);
    }
    @Test void aHistoricalRefundCannotSelectAnOldPaymentForTheCurrentUnpaidWindow() {
        var root=existingRoot();root.setContractSnapshot("{\"price_id\":\"price_old\",\"quantity\":1}");ownedFixture(root,snapshot());var historical=oldPaidInvoice();historical.charge().setAmountRefunded(100L);when(stripe.readInvoice("in_historical")).thenReturn(historical);when(stripe.readCharge("ch_old")).thenReturn(historical.charge());when(recoveries.selectBySubscription(eq("sub_old"),anyLong(),anyInt())).thenReturn(List.of(root));var quota=injectQuota();
        assertFalse(service.tryProcessEvent(historicalEvent("charge.refunded")));assertEquals("OPEN_AUTO",root.getStatus());assertNull(root.getEntitlementInvoiceId());verify(recoveries,never()).insert(any(SubscriptionPaymentRecoveryEntity.class));verifyNoInteractions(quota);
    }
    @Test void anAlreadyRecordedHistoricalChildDoesNotStopCurrentRetrySlotsOrGrantQuota() {
        var root=existingRoot();root.setContractSnapshot("{\"price_id\":\"price_old\",\"quantity\":1}");ownedFixture(root,snapshot());var historical=oldPaidInvoice();var child=existingRoot();child.setId(2L);child.setOwnerRecoveryId(1L);child.setStripeInvoiceId("in_historical");doReturn(child).when(recoveries).selectByInvoice("in_historical");doReturn(child).when(recoveries).selectById(2L);doReturn(child).when(recoveries).selectForUpdate(2L);when(stripe.readInvoice("in_historical")).thenReturn(historical);var retry=readyAction("RETRY_PAY","in_old");when(actions.selectByRecovery(1L)).thenReturn(List.of(retry));var quota=injectQuota();
        assertTrue(service.tryProcessEvent(historicalEvent("invoice.paid")));assertEquals("OPEN_AUTO",root.getStatus());assertNull(root.getEntitlementInvoiceId());assertEquals("READY",retry.getStatus());verifyNoInteractions(quota);
    }
    @Test void theNormalNextCyclesRefundIsNotAttachedToAnExpiredRecoveryPaidPeriod() {
        var root=confirmedPaidRoot(paidSnapshot());ownedFixture(root,paidSnapshot());var next=paidSnapshot();long start=com.studyagent.common.datetime.DateTimeFormats.toEpochSecond(root.getEntitlementEndAt());next.invoice().setId("in_historical");next.invoice().setCreated(start+1);next.invoice().getStatusTransitions().setPaidAt(start+3600);next.lines().get(0).getPeriod().setStart(start);next.lines().get(0).getPeriod().setEnd(start+30*86400L);next.charge().setInvoice("in_historical");next.charge().setAmountRefunded(100L);when(stripe.readInvoice("in_historical")).thenReturn(next);when(recoveries.selectBySubscription(eq("sub_old"),anyLong(),anyInt())).thenReturn(List.of(root));var quota=injectQuota();
        assertFalse(service.tryProcessEvent(historicalEvent("charge.refunded")));assertEquals("in_old",root.getEntitlementInvoiceId());assertEquals("GRANTED",root.getGrantState());verify(recoveries,never()).insert(any(SubscriptionPaymentRecoveryEntity.class));verifyNoInteractions(quota);
    }
    private SubscriptionRecoveryStripeGateway.Snapshot oldPaidInvoice(){
        var currentOwner=users.selectByUserForUpdate("user");when(users.selectByStripeSubscriptionId("sub_old")).thenReturn(currentOwner);
        var child=new java.util.concurrent.atomic.AtomicReference<SubscriptionPaymentRecoveryEntity>();when(recoveries.insert(any(SubscriptionPaymentRecoveryEntity.class))).thenAnswer(i->{SubscriptionPaymentRecoveryEntity row=i.getArgument(0);row.setId(2L);child.set(row);return 1;});when(recoveries.selectByInvoice("in_historical")).thenAnswer(i->child.get());when(recoveries.selectById(2L)).thenAnswer(i->child.get());when(recoveries.selectForUpdate(2L)).thenAnswer(i->child.get());
        var historical=paidSnapshot();historical.invoice().setId("in_historical");historical.invoice().setCreated(now.minusSeconds(31*86400L).getEpochSecond());historical.invoice().getStatusTransitions().setPaidAt(now.minusSeconds(86400).getEpochSecond());historical.charge().setInvoice("in_historical");return historical;
    }
    private Event historicalEvent(String type){return ApiResource.GSON.fromJson("{\"id\":\"evt_historical\",\"type\":\""+type+"\",\"created\":1794362400,\"data\":{\"object\":{\"id\":\""+(type.startsWith("charge.")?"ch_old":"in_historical")+"\",\"invoice\":\"in_historical\",\"subscription\":\"sub_old\"}}}",Event.class);}
    @Test void extraPrimaryPaymentNeverChangesAnAlreadyGrantedChildSource() {
        var root=existingRoot();root.setStatus("PAID");root.setGrantState("GRANTED");root.setEntitlementInvoiceId("in_child");ownedFixture(root,paidSnapshot());stubGrantedChildSource(root);
        assertTrue(service.tryProcessEvent(event()));assertEquals("GRANTED",root.getGrantState());assertEquals("in_child",root.getEntitlementInvoiceId());assertEquals("MULTIPLE_OLD_INVOICES_PAID",root.getReviewReason());
    }
    @Test void childPaymentStillProcessingPreventsClosingAnAlreadyCanceledSubscription() {
        var snap=snapshot();snap.invoice().setStatus("void");snap.subscription().setStatus("canceled");var root=existingRoot();root.setStatus("OPEN_MANUAL");ownedFixture(root,snap);var quota=injectQuota();
        var childInvoice=ApiResource.GSON.fromJson("{\"id\":\"in_child\",\"object\":\"invoice\",\"customer\":\"cus_old\",\"subscription\":\"sub_old\",\"status\":\"open\",\"amount_paid\":100,\"amount_remaining\":2800,\"auto_advance\":false,\"payment_intent\":\"pi_child\"}",Invoice.class);
        var processing=new PaymentIntent();processing.setStatus("processing");
        when(stripe.listInvoices("sub_old")).thenReturn(List.of(snap.invoice(),childInvoice));when(stripe.readInvoice("in_child")).thenReturn(new SubscriptionRecoveryStripeGateway.Snapshot(childInvoice,snap.subscription(),null,snap.lines(),processing,null,now));
        assertTrue(service.tryProcessEvent(event()));assertNull(root.getAccountClosedAt());verify(quota,never()).clearPlanQuotaAt(anyString(),anyString(),anyString(),anyString(),any());
    }
    @Test void replacementInvoicePaidIsOwnedBeforeTheNewSubscriptionBecomesCurrent() {
        var root=existingRoot();var rep=new SubscriptionRecoveryReplacementEntity();rep.setRecoveryId(1L);rep.setOrderNo("order_new");rep.setState("OPEN");rep.setCheckoutSessionId("cs_new");rep.setClerkUserId("user");
        when(replacements.selectByNewSubscription("sub_new")).thenReturn(rep);when(replacements.selectByOrder("order_new")).thenReturn(rep);
        ownedFixture(root,snapshot());var session=new com.stripe.model.checkout.Session();session.setId("cs_new");session.setCustomer("cus_old");session.setSubscription("sub_new");session.setStatus("open");session.setPaymentStatus("unpaid");session.setMetadata(Map.of("order_no","order_new"));when(stripe.readCheckout("cs_new")).thenReturn(session);
        Event paid=ApiResource.GSON.fromJson("{\"id\":\"evt_new_paid\",\"type\":\"invoice.paid\",\"data\":{\"object\":{\"id\":\"in_new\",\"object\":\"invoice\",\"subscription\":\"sub_new\",\"customer\":\"cus_old\"}}}",Event.class);
        assertTrue(service.tryProcessEvent(paid));verify(stripe).readCheckout("cs_new");
    }
    @Test void wonSelectedDisputeRestoresExistingPeriodWithoutAnotherQuotaGrant() {
        var root=existingRoot();root.setContractSnapshot("{\"price_id\":\"price_old\",\"quantity\":1}");root.setStatus("PAID");root.setGrantState("HELD");root.setEntitlementInvoiceId("in_old");root.setEntitlementEndAt(com.studyagent.common.datetime.DateTimeFormats.fromInstant(now.plusSeconds(3600)));root.setEntitlementStartAt(com.studyagent.common.datetime.DateTimeFormats.fromInstant(now.minusSeconds(3600)));ownedFixture(root,paidSnapshot());var quota=injectQuota();
        var charge=new Charge();charge.setId("ch_fin");charge.setAmount(2900L);charge.setAmountRefunded(0L);var dispute=new Dispute();dispute.setId("dp_fin");dispute.setStatus("won");
        when(stripe.invoiceForPayment("pi_old","ch_fin")).thenReturn(paidSnapshot().invoice());when(stripe.readCharge("ch_fin")).thenReturn(charge);when(stripe.readDispute("dp_fin")).thenReturn(dispute);
        Event won=ApiResource.GSON.fromJson("{\"id\":\"evt_won\",\"type\":\"charge.dispute.closed\",\"data\":{\"object\":{\"id\":\"dp_fin\",\"object\":\"dispute\",\"charge\":\"ch_fin\",\"payment_intent\":\"pi_old\"}}}",Event.class);
        assertTrue(service.tryProcessEvent(won));assertEquals("GRANTED",root.getGrantState());verify(quota,never()).resetFromPaidInvoiceAt(anyString(),anyString(),anyString(),any(),any(),anyString(),any());
    }
    private void stubGrantedChildSource(SubscriptionPaymentRecoveryEntity root){
        root.setContractSnapshot("{\"price_id\":\"price_old\",\"quantity\":1}");
        var snapshot=paidSnapshot();snapshot.invoice().setId("in_child");snapshot.charge().setInvoice("in_child");when(stripe.readInvoice("in_child")).thenReturn(snapshot);
        var child=existingRoot();child.setId(2L);child.setOwnerRecoveryId(root.getId());child.setStripeInvoiceId("in_child");when(recoveries.selectByInvoice("in_child")).thenReturn(child);
    }
    private SubscriptionRecoveryActionEntity readyAction(String type,String target){var action=new SubscriptionRecoveryActionEntity();action.setId(10L);action.setRecoveryId(1L);action.setActionType(type);action.setTargetId(target);action.setActionKey("recovery:1:cancel:sub_old");action.setStatus("READY");action.setVersion(0);action.setScheduledAt(com.studyagent.common.datetime.DateTimeFormats.now().minusMinutes(1));action.setNextRunAt(action.getScheduledAt());action.setPayloadJson("{}");when(actions.selectById(10L)).thenReturn(action);when(actions.selectForUpdate(10L)).thenReturn(action);return action;}
    private BillingQuotaGateway injectQuota(){var gateway=mock(BillingQuotaGateway.class);org.springframework.beans.factory.ObjectProvider<BillingQuotaGateway> provider=mock(org.springframework.beans.factory.ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(gateway);org.springframework.test.util.ReflectionTestUtils.setField(service,"quotaProvider",provider);return gateway;}
    @Test void paidChildWithDifferentQuantityIsHeldWithoutAnyGrant() {
        enable();var snap=paidSnapshot();snap.invoice().setId("in_child");snap.lines().get(0).setQuantity(2L);var root=existingRoot();root.setContractSnapshot("{\"price_id\":\"price_old\",\"quantity\":1}");root.setGrantState("NONE");ownedFixture(root,snap);var quota=injectQuota();
        var child=existingRoot();child.setId(2L);child.setOwnerRecoveryId(1L);child.setStripeInvoiceId("in_child");when(recoveries.selectById(2L)).thenReturn(child);snap.charge().setInvoice("in_child");when(recoveries.selectByInvoice("in_child")).thenReturn(child);when(recoveries.selectForUpdate(2L)).thenReturn(child);when(stripe.readInvoice("in_child")).thenReturn(snap);
        var event=ApiResource.GSON.fromJson("{\"id\":\"evt_child\",\"type\":\"invoice.paid\",\"data\":{\"object\":{\"id\":\"in_child\",\"object\":\"invoice\",\"subscription\":\"sub_old\"}}}",Event.class);
        assertTrue(service.tryProcessEvent(event));assertNull(root.getEntitlementInvoiceId());assertEquals("HELD",root.getGrantState());assertEquals("PAID_CONTRACT_UNVERIFIED",root.getReviewReason());verifyNoInteractions(quota);
    }
    @Test void mismatchedPaymentCustomerCannotFundARecoveryPeriod() {
        enable();var snap=paidSnapshot();snap.paymentIntent().setCustomer("cus_other");var root=existingRoot();root.setContractSnapshot("{\"price_id\":\"price_old\",\"quantity\":1}");root.setGrantState("NONE");ownedFixture(root,snap);var quota=injectQuota();
        assertTrue(service.tryProcessEvent(event()));assertEquals("HELD",root.getGrantState());assertEquals("CASH_PAYMENT_UNVERIFIED",root.getReviewReason());verifyNoInteractions(quota);
    }
    @Test void oldOpenInvoiceMustActuallyCloseBeforeRenewalSyncIsConfirmed() {
        var snap=paidSnapshot();var root=confirmedPaidRoot(snap);ownedFixture(root,snap);stubConfirmedShift(root,snap,2900L);
        var other=ApiResource.GSON.fromJson("{\"id\":\"in_other\",\"object\":\"invoice\",\"subscription\":\"sub_old\",\"status\":\"open\",\"amount_paid\":0,\"auto_advance\":false}",Invoice.class);
        when(stripe.listInvoices("sub_old")).thenReturn(List.of(snap.invoice(),other));service.reconcile(1L);
        assertNotEquals("CONFIRMED",root.getAnchorSyncState());var capture=org.mockito.ArgumentCaptor.forClass(SubscriptionRecoveryActionEntity.class);verify(actions,atLeastOnce()).insert(capture.capture());assertTrue(capture.getAllValues().stream().anyMatch(a->"VOID_INVOICE".equals(a.getActionType())));
    }
    @Test void unexpectedNextInvoiceAmountNeverMarksRenewalConfirmed() {
        var snap=paidSnapshot();var root=confirmedPaidRoot(snap);ownedFixture(root,snap);stubConfirmedShift(root,snap,100L);service.reconcile(1L);
        assertEquals("REVIEW",root.getAnchorSyncState());assertEquals("GRANTED",root.getGrantState());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"invoice.paid","invoice.payment_succeeded","invoice.finalized"})
    void newerInvoiceEnvelopeStillIsolatesRegisteredTechnicalInvoices(String type) {
        var snap=paidSnapshot();var root=confirmedPaidRoot(snap);
        ownedFixture(root,snap);var quota=injectQuota();
        var account=users.selectByUserForUpdate("user");when(users.selectByStripeSubscriptionId("sub_old")).thenReturn(account);
        var header=new Invoice();header.setId("in_technical");header.setSubscription("sub_old");when(stripe.readInvoiceHeader("in_technical")).thenReturn(header);
        var shift=readyAction("SHIFT_RENEWAL","sub_old");shift.setStatus("SUCCEEDED");shift.setResultJson("{\"latest_invoice\":\"in_technical\",\"before_latest_invoice\":\"in_old\"}");when(actions.selectByRecovery(1L)).thenReturn(List.of(shift));
        var event=ApiResource.GSON.fromJson("{\"id\":\"evt_new_schema\",\"api_version\":\"2025-12-15.clover\",\"type\":\""+type+"\",\"data\":{\"object\":{\"id\":\"in_technical\",\"object\":\"invoice\",\"parent\":{\"type\":\"subscription_details\",\"subscription_details\":{\"subscription\":\"sub_old\"}}}}}",Event.class);
        assertTrue(assertThrows(IllegalStateException.class,()->service.tryProcessEvent(event)).getMessage().contains("TECHNICAL_INVOICE_PENDING"));
        verifyNoInteractions(orders,quota);root.setTechnicalInvoiceIds("[\"in_technical\"]");
        assertTrue(service.tryProcessEvent(event));verifyNoInteractions(orders,quota);
        assertEquals("PAID",root.getStatus());assertEquals("GRANTED",root.getGrantState());
    }
    @Test void missingInvoiceSubscriptionCannotFallBackWhenItsHeaderReadFails() {
        var event=ApiResource.GSON.fromJson("{\"id\":\"evt_missing_subscription\",\"type\":\"invoice.paid\",\"data\":{\"object\":{\"id\":\"in_technical\",\"object\":\"invoice\"}}}",Event.class);
        when(stripe.readInvoiceHeader("in_technical")).thenThrow(new IllegalStateException("TEST_HEADER_READ_UNKNOWN"));
        assertEquals("TEST_HEADER_READ_UNKNOWN",assertThrows(IllegalStateException.class,()->service.tryProcessEvent(event)).getMessage());
        verifyNoInteractions(orders);
    }
    @Test void anExplicitLegacyNonSubscriptionInvoiceStillUsesItsOriginalHandler() {
        var event=ApiResource.GSON.fromJson("{\"id\":\"evt_one_off\",\"type\":\"invoice.paid\",\"data\":{\"object\":{\"id\":\"in_one_off\",\"object\":\"invoice\",\"subscription\":null}}}",Event.class);
        assertFalse(service.tryProcessEvent(event));verifyNoInteractions(stripe,orders);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"1,3499,3499,true,true","2,6998,6998,true,true","1,3499,3599,true,false","1,3499,2999,true,false","1,3499,3499,false,false"})
    void regionalRenewalChecksTheSelectedCurrencyAmountInsteadOfThePriceDefault(int quantity,long originalAmount,long previewAmount,boolean originalAmountKnown,boolean confirmed) {
        var snap=paidSnapshot();var root=confirmedPaidRoot(snap);root.setCurrency("sgd");
        root.setContractSnapshot("{\"price_id\":\"price_old\",\"quantity\":"+quantity+",\"recovery_admission\":{\"cancelMode\":\"RENEW\"}}");
        snap.invoice().setCurrency("sgd");snap.invoice().setAmountDue(originalAmount);snap.invoice().setAmountPaid(originalAmount);
        snap.paymentIntent().setCurrency("sgd");snap.paymentIntent().setAmountReceived(originalAmount);
        snap.charge().setCurrency("sgd");snap.charge().setAmount(originalAmount);snap.charge().setAmountCaptured(originalAmount);
        var original=snap.lines().get(0);original.setCurrency("sgd");original.setAmount(originalAmountKnown?originalAmount:null);original.setQuantity((long)quantity);
        original.getPrice().setCurrency("usd");original.getPrice().setUnitAmount(2999L);
        snap.subscription().setCurrency("sgd");snap.subscription().getItems().getData().get(0).setQuantity((long)quantity);
        ownedFixture(root,snap);stubConfirmedShift(root,snap,previewAmount);
        var next=new Invoice();next.setCurrency("sgd");next.setStartingBalance(0L);next.setAmountDue(previewAmount);
        var line=ApiResource.GSON.fromJson(original.toJson(),InvoiceLineItem.class);line.setAmount(previewAmount);line.getPeriod().setStart(com.studyagent.common.datetime.DateTimeFormats.toEpochSecond(root.getEntitlementEndAt()));
        when(stripe.preview("sub_old")).thenReturn(new SubscriptionRecoveryStripeGateway.Preview(next,List.of(line)));
        service.reconcile(1L);
        assertEquals(confirmed?"CONFIRMED":"REVIEW",root.getAnchorSyncState());assertEquals("GRANTED",root.getGrantState());
        verify(stripe,never()).execute(any());
    }
    @Test void renewalCannotReleaseProtectionWhileItsAlignedSubscriptionIsStillPaused() {
        var snap=paidSnapshot();var root=confirmedPaidRoot(snap);ownedFixture(root,snap);stubConfirmedShift(root,snap,2900L);
        var pause=new Subscription.PauseCollection();pause.setBehavior("keep_as_draft");snap.subscription().setPauseCollection(pause);
        service.reconcile(1L);
        assertEquals("REVIEW",root.getAnchorSyncState());assertEquals("ACTIVE",root.getProtectionState());assertEquals("GRANTED",root.getGrantState());
    }
    @Test void completedRenewalChecksFormalPriceCurrencyAndExpectedInvoice() {
        var snap=paidSnapshot();var root=confirmedPaidRoot(snap);ownedFixture(root,snap);stubConfirmedShift(root,snap,2900L);service.reconcile(1L);
        verify(stripe).preview("sub_old");assertEquals("CONFIRMED",root.getAnchorSyncState());
    }
    @Test void confirmedCashGrantsLocallyEvenWhenTheScheduleContractCannotBeRead() {
        enable();var snap=paidSnapshot();var root=existingRoot();root.setDisplayTier("pro");root.setContractSnapshot("{\"price_id\":\"price_old\",\"quantity\":1,\"recovery_admission\":{\"sourceKey\":\"formal\",\"cancelMode\":\"RENEW\",\"contractHash\":\"original\"}}");snap.subscription().setSchedule("sched_unreadable");ownedFixture(root,snap);var quota=injectQuota();
        assertTrue(service.tryProcessEvent(event()));assertEquals("GRANTED",root.getGrantState());assertEquals("REVIEW",root.getAnchorSyncState());assertEquals("RENEWAL_CONTRACT_READ_UNKNOWN",root.getReviewReason());verify(quota).resetFromPaidInvoiceAt(eq("user"),eq("sub_old"),eq("pro_monthly"),eq(now),any(),eq("in_old"),any());
    }
    @Test void confirmedCashIsNotRolledBackByAnUnrelatedInvoiceListingFailure() {
        enable();var snap=paidSnapshot();var root=existingRoot();root.setDisplayTier("pro");root.setContractSnapshot("{\"price_id\":\"price_old\",\"quantity\":1,\"recovery_admission\":{\"sourceKey\":\"formal\",\"cancelMode\":\"RENEW\",\"contractHash\":\"original\"}}");ownedFixture(root,snap);when(stripe.listInvoices("sub_old")).thenThrow(new IllegalStateException("list unavailable"));var quota=injectQuota();
        assertTrue(service.tryProcessEvent(event()));assertEquals("GRANTED",root.getGrantState());assertEquals("REVIEW",root.getAnchorSyncState());assertEquals("OLD_INVOICE_SCOPE_READ_UNKNOWN",root.getReviewReason());verify(quota).resetFromPaidInvoiceAt(eq("user"),eq("sub_old"),eq("pro_monthly"),eq(now),any(),eq("in_old"),any());
    }
    @Test void dayTwentyEightPaymentStartsAFullCycleAndDuplicateEventsCannotResetQuotaAgain() {
        enable();var original=paidSnapshot();Instant paid=now.plusSeconds(28*86400L);original.invoice().getStatusTransitions().setPaidAt(paid.getEpochSecond());var snap=new SubscriptionRecoveryStripeGateway.Snapshot(original.invoice(),original.subscription(),original.schedule(),original.lines(),original.paymentIntent(),original.charge(),paid);var root=existingRoot();root.setFirstFailedAt(com.studyagent.common.datetime.DateTimeFormats.fromInstant(now));root.setDeadlineAt(com.studyagent.common.datetime.DateTimeFormats.fromInstant(now.plusSeconds(30*86400L)));root.setDisplayTier("pro");root.setContractSnapshot("{\"price_id\":\"price_old\",\"quantity\":1,\"recovery_admission\":{\"sourceKey\":\"formal\",\"contractHash\":\"original\"}}");ownedFixture(root,snap);var quota=injectQuota();
        assertTrue(service.tryProcessEvent(event()));assertTrue(service.tryProcessEvent(event()));assertEquals("GRANTED",root.getGrantState());assertEquals(com.studyagent.common.datetime.DateTimeFormats.fromInstant(paid),root.getEntitlementStartAt());assertEquals(com.studyagent.common.datetime.DateTimeFormats.fromInstant(SubscriptionRecoveryPolicy.paidPeriodEnd(paid,"month",1)),root.getEntitlementEndAt());verify(quota,times(1)).resetFromPaidInvoiceAt("user","sub_old","pro_monthly",paid,SubscriptionRecoveryPolicy.quotaPeriodEnd(paid,"month",1),"in_old",snap.businessNow());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"basic_trial_to_monthly,basic_monthly,month","basic_trial_to_yearly,basic_yearly,year","pro_trial_to_monthly,pro_monthly,month","pro_trial_to_yearly,pro_yearly,year","pro_trial_to_yearly,pro_monthly,month"})
    void originalTrialSourceAndActualConversionContractProduceTheirOwnAdmissionKey(String source,String target,String interval) {
        enable();var snap=snapshot();snap.subscription().setSchedule("sched_conversion");snap.lines().get(0).getPrice().getRecurring().setInterval(interval);var schedule=ApiResource.GSON.fromJson("{\"id\":\"sched_conversion\",\"subscription\":\"sub_old\",\"status\":\"active\",\"end_behavior\":\"release\",\"default_settings\":{\"collection_method\":\"charge_automatically\"},\"current_phase\":{\"start_date\":1794362400,\"end_date\":1796954400},\"phases\":[{\"start_date\":1793757600,\"end_date\":1794362400,\"items\":[{\"price\":\"price_original_trial\",\"quantity\":1}]},{\"start_date\":1794362400,\"end_date\":1796954400,\"items\":[{\"price\":\"price_old\",\"quantity\":1}]}]}",SubscriptionSchedule.class);var current=new SubscriptionRecoveryStripeGateway.Snapshot(snap.invoice(),snap.subscription(),schedule,snap.lines(),snap.paymentIntent(),snap.charge(),now);when(stripe.readInvoice("in_old")).thenReturn(current);
        var formal=new SubscriptionPlanEntity();formal.setPlanCode(target);formal.setTier(target.substring(0,target.indexOf('_')));formal.setBillingInterval(interval);var intro=new SubscriptionPlanEntity();intro.setPlanCode(source);intro.setTier(formal.getTier());intro.setIsActive(false);when(plans.selectList(any())).thenReturn(List.of(formal),List.of(intro));String key=source+":"+target+":"+interval+"_1:EQUIVALENT_SCHEDULE:RENEW";properties.setVerifiedCohorts(Set.of(key));when(users.selectByUserForUpdate("user")).thenReturn(user());when(tx.getTransaction(any())).thenReturn(mock(TransactionStatus.class));when(recoveries.insert(any(SubscriptionPaymentRecoveryEntity.class))).thenAnswer(i->{((SubscriptionPaymentRecoveryEntity)i.getArgument(0)).setId(1L);return 1;});
        assertTrue(service.tryProcessEvent(event()));var capture=org.mockito.ArgumentCaptor.forClass(SubscriptionPaymentRecoveryEntity.class);verify(recoveries).insert(capture.capture());var admission=com.google.gson.JsonParser.parseString(capture.getValue().getContractSnapshot()).getAsJsonObject().getAsJsonObject("recovery_admission");assertEquals(source,admission.get("sourceKey").getAsString());assertEquals(key,admission.get("caseKey").getAsString());assertEquals(target,capture.getValue().getPlanCode());
    }
    @Test void originalInvoicePriceKeepsItsHistoricalRegionalVersionAndAmount() {
        enable();var snap=snapshot();snap.invoice().setCurrency("brl");snap.invoice().setMetadata(Map.of("pricing_country","BR"));snap.lines().get(0).setCurrency("brl");snap.lines().get(0).getPrice().setCurrency("brl");when(stripe.readInvoice("in_old")).thenReturn(snap);when(users.selectByUserForUpdate("user")).thenReturn(user());when(tx.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        var binding=new ZonePricingSelectionService.RegistryBinding("Z025","2025-legacy","pro_monthly","subscription_plan",null,null);var selector=mock(ZonePricingSelectionService.class);when(selector.registryBinding("price_old")).thenReturn(Optional.of(binding));org.springframework.test.util.ReflectionTestUtils.setField(service,"zonePricing",selector);var current=new SubscriptionPlanEntity();current.setPlanCode("pro_monthly");current.setTier("pro");current.setBillingInterval("month");current.setStripePriceId("price_current");current.setPriceCents(9999);when(plans.selectOne(any())).thenReturn(current);
        when(recoveries.insert(any(SubscriptionPaymentRecoveryEntity.class))).thenAnswer(i->{((SubscriptionPaymentRecoveryEntity)i.getArgument(0)).setId(1L);return 1;});assertTrue(service.tryProcessEvent(event()));var captured=org.mockito.ArgumentCaptor.forClass(SubscriptionPaymentRecoveryEntity.class);verify(recoveries).insert(captured.capture());var saved=com.google.gson.JsonParser.parseString(captured.getValue().getContractSnapshot()).getAsJsonObject();assertEquals("price_old",saved.get("price_id").getAsString());assertEquals("Z025",saved.get("pricing_zone").getAsString());assertEquals("2025-legacy",saved.get("price_version").getAsString());assertEquals("BR",saved.get("pricing_country").getAsString());assertEquals(2900L,captured.getValue().getAmountDueMinor());
    }
    @Test void annualCashGrantsOneFullYearAndOnlyTheFirstMonthlyQuotaOnce() {
        var snap=paidSnapshot();var price=snap.lines().get(0).getPrice();price.getRecurring().setInterval("year");snap.subscription().getItems().getData().get(0).setPrice(price);var root=existingRoot();root.setBillingInterval("year");root.setPlanCode("pro_yearly");root.setDisplayTier("pro");root.setContractSnapshot("{\"price_id\":\"price_old\",\"quantity\":1,\"recovery_admission\":{\"sourceKey\":\"formal\",\"cancelMode\":\"RENEW\",\"contractHash\":\""+RecoveryContractClassifier.classify(snap.subscription(),null,true,"price_old",1L).contractHash()+"\"}}");ownedFixture(root,snap);var quota=injectQuota();var plan=new SubscriptionPlanEntity();plan.setPlanCode("pro_yearly");plan.setTier("pro");plan.setBillingInterval("year");when(plans.selectList(any())).thenReturn(List.of(plan));
        var receipt=new java.util.concurrent.atomic.AtomicReference<RechargeOrderEntity>();when(orders.selectOne(any())).thenAnswer(i->receipt.get());when(orders.insert(any(RechargeOrderEntity.class))).thenAnswer(i->{receipt.set(i.getArgument(0));return 1;});
        assertTrue(service.tryProcessEvent(event()));assertTrue(service.tryProcessEvent(event()));assertEquals(SubscriptionRecoveryPolicy.paidPeriodEnd(now,"year",1),root.getEntitlementEndAt().atZone(com.studyagent.common.datetime.DateTimeFormats.APP_ZONE).toInstant());verify(quota,times(1)).resetFromPaidInvoiceAt("user","sub_old","pro_yearly",now,SubscriptionRecoveryPolicy.quotaPeriodEnd(now,"year",1),"in_old",snap.businessNow());assertNull(users.selectByUserForUpdate("user").getIntroTrialUsedAt());
    }
    @Test void cancelingTheOldPlanOnlyRepairsObservedCollectionChangesOnTheCurrentNewPlan() {
        var snap=snapshot();var root=existingRoot();root.setStatus("CLOSING");root.setCloseReason("REPLACEMENT_PAID");ownedFixture(root,snap);var user=users.selectByUserForUpdate("user");user.setStripeSubscriptionId("sub_new");user.setPaymentRecoveryId(null);
        var before=ApiResource.GSON.fromJson("{\"id\":\"in_new\",\"customer\":\"cus_old\",\"subscription\":\"sub_new\",\"status\":\"open\",\"auto_advance\":true}",Invoice.class);var unrelated=ApiResource.GSON.fromJson("{\"id\":\"in_other\",\"customer\":\"cus_old\",\"subscription\":\"sub_other\",\"status\":\"open\",\"auto_advance\":true}",Invoice.class);var after=ApiResource.GSON.fromJson(before.toJson(),Invoice.class);after.setAutoAdvance(false);when(stripe.listCustomerInvoices("cus_old")).thenReturn(List.of(before,unrelated),List.of(after));
        var action=readyAction("CANCEL_SUBSCRIPTION","sub_old");when(actions.claim(eq(10L),anyInt(),anyString(),any(),any())).thenReturn(1);when(actions.markDispatched(eq(10L),anyInt(),anyString(),any(),anyString())).thenReturn(1);when(actions.complete(eq(10L),anyInt(),anyString(),anyString(),anyString(),any())).thenAnswer(i->{action.setStatus(i.getArgument(3));action.setResultJson(i.getArgument(4));return 1;});var canceled=new Subscription();canceled.setId("sub_old");canceled.setStatus("canceled");when(stripe.execute(any())).thenReturn(new SubscriptionRecoveryStripeGateway.WriteResult("SUCCEEDED","req_cancel",null,canceled));
        service.runDueAction(10L);var first=org.mockito.ArgumentCaptor.forClass(SubscriptionRecoveryActionEntity.class);verify(actions,atLeastOnce()).insert(first.capture());var check=first.getAllValues().stream().filter(a->"VERIFY_COLLECTION".equals(a.getActionType())).findFirst().orElseThrow();check.setId(200L);when(actions.selectById(200L)).thenReturn(check);when(actions.selectForUpdate(200L)).thenReturn(check);when(actions.selectById(10L)).thenReturn(action);when(actions.claimCollectionCheck(eq(200L),anyInt(),anyString(),any(),any())).thenReturn(1);when(actions.markDispatched(eq(200L),anyInt(),anyString(),any(),anyString())).thenReturn(1);service.runDueAction(200L);var capture=org.mockito.ArgumentCaptor.forClass(SubscriptionRecoveryActionEntity.class);verify(actions,atLeastOnce()).insert(capture.capture());var repairs=capture.getAllValues().stream().filter(a->"RESTORE_ADVANCE".equals(a.getActionType())).toList();assertEquals(1,repairs.size());assertEquals("in_new",repairs.get(0).getTargetId());assertEquals(true,SubscriptionRecoveryStripeGateway.thaw(repairs.get(0).getPayloadJson()).get("auto_advance"));assertTrue(repairs.get(0).getPayloadJson().contains("payment_recovery_restore_action"));assertTrue(repairs.get(0).getResultJson().contains("sub_new"));
    }
    @Test void unknownCancellationCanOnlyFlagObservedCollectionChangesForManualReview() {
        var snap=snapshot();snap.subscription().setStatus("canceled");snap.invoice().setStatus("void");var root=existingRoot();root.setStatus("CLOSING");root.setCloseReason("REPLACEMENT_PAID");ownedFixture(root,snap);var user=users.selectByUserForUpdate("user");user.setStripeSubscriptionId("sub_new");user.setPaymentRecoveryId(null);
        var action=readyAction("CANCEL_SUBSCRIPTION","sub_old");action.setStatus("UNKNOWN");action.setFirstDispatchedAt(com.studyagent.common.datetime.DateTimeFormats.now().minusMinutes(5));action.setResultJson("{\"provider_state\":\"UNKNOWN\",\"cancel_target_subscription_id\":\"sub_old\",\"before_other_collection\":[{\"invoice_id\":\"in_new\",\"subscription_id\":\"sub_new\",\"auto_advance\":true}]}");
        var current=ApiResource.GSON.fromJson("{\"id\":\"in_new\",\"customer\":\"cus_old\",\"subscription\":\"sub_new\",\"status\":\"open\",\"auto_advance\":false}",Invoice.class);when(stripe.listCustomerInvoices("cus_old")).thenReturn(List.of(current));
        service.runDueAction(10L);assertEquals("SUCCEEDED",action.getStatus());var queued=org.mockito.ArgumentCaptor.forClass(SubscriptionRecoveryActionEntity.class);verify(actions,atLeastOnce()).insert(queued.capture());var followup=queued.getAllValues().stream().filter(a->"VERIFY_COLLECTION".equals(a.getActionType())).findFirst().orElseThrow();followup.setId(200L);when(actions.selectById(200L)).thenReturn(followup);when(actions.selectForUpdate(200L)).thenReturn(followup);when(actions.selectById(10L)).thenReturn(action);when(actions.claimCollectionCheck(eq(200L),anyInt(),anyString(),any(),any())).thenReturn(1);when(actions.markDispatched(eq(200L),anyInt(),anyString(),any(),anyString())).thenReturn(1);service.runDueAction(200L);assertEquals("OTHER_SUBSCRIPTION_COLLECTION_REQUIRES_REVIEW",root.getReviewReason());verify(stripe,never()).execute(any());verify(actions,never()).insert(org.mockito.ArgumentMatchers.<com.studyagent.infra.entity.SubscriptionRecoveryActionEntity>argThat(a->"RESTORE_ADVANCE".equals(a.getActionType())));
    }
    @Test void collectionScopeReadRetriesDurablyEvenAfterTheRecoveryAccountHasClosed() {
        var root=existingRoot();root.setStatus("SWITCHED");root.setAccountClosedAt(com.studyagent.common.datetime.DateTimeFormats.now());ownedFixture(root,snapshot());users.selectByUserForUpdate("user").setStripeSubscriptionId("sub_new");users.selectByUserForUpdate("user").setPaymentRecoveryId(null);
        var source=new SubscriptionRecoveryActionEntity();source.setId(9L);source.setRecoveryId(1L);source.setStatus("SUCCEEDED");source.setActionType("CANCEL_SUBSCRIPTION");source.setResultJson("{\"provider_state\":\"SUCCEEDED\",\"cancel_target_subscription_id\":\"sub_old\",\"before_other_collection\":[{\"invoice_id\":\"in_new\",\"subscription_id\":\"sub_new\",\"auto_advance\":true}]}");when(actions.selectById(9L)).thenReturn(source);when(actions.selectForUpdate(9L)).thenReturn(source);
        var action=readyAction("VERIFY_COLLECTION","cus_old");action.setPayloadJson("{\"cancel_action_id\":9}");when(actions.claimCollectionCheck(eq(10L),anyInt(),anyString(),any(),any())).thenReturn(1);when(actions.claim(eq(10L),anyInt(),anyString(),any(),any())).thenReturn(1);when(actions.markDispatched(eq(10L),anyInt(),anyString(),any(),anyString())).thenReturn(1);when(actions.complete(eq(10L),anyInt(),anyString(),anyString(),anyString(),any())).thenAnswer(i->{action.setStatus(i.getArgument(3));action.setResultJson(i.getArgument(4));action.setVersion(action.getVersion()+1);action.setLeaseToken(null);return 1;});when(stripe.execute(any())).thenReturn(new SubscriptionRecoveryStripeGateway.WriteResult("UNKNOWN",null,null,null));
        var after=ApiResource.GSON.fromJson("{\"id\":\"in_new\",\"customer\":\"cus_old\",\"subscription\":\"sub_new\",\"status\":\"open\",\"auto_advance\":false}",Invoice.class);when(stripe.listCustomerInvoices("cus_old")).thenThrow(new IllegalStateException("read unavailable")).thenReturn(List.of(after));
        service.runDueAction(10L);assertEquals("UNKNOWN",action.getStatus());service.runDueAction(10L);assertEquals("SUCCEEDED",action.getStatus());verify(stripe,times(2)).listCustomerInvoices("cus_old");verify(stripe,never()).execute(any());var capture=org.mockito.ArgumentCaptor.forClass(SubscriptionRecoveryActionEntity.class);verify(actions,atLeastOnce()).insert(capture.capture());assertEquals(1,capture.getAllValues().stream().filter(a->"RESTORE_ADVANCE".equals(a.getActionType())).count());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"FAILED","REVIEW"})
    void aFinishedCancellationFollowupDoesNotClaimThatAnUnrunScopeReadWasVerified(String sourceState) {
        var root=existingRoot();ownedFixture(root,snapshot());var source=new SubscriptionRecoveryActionEntity();source.setId(9L);source.setRecoveryId(1L);source.setActionType("CANCEL_SUBSCRIPTION");source.setStatus(sourceState);when(actions.selectById(9L)).thenReturn(source);var action=readyAction("VERIFY_COLLECTION","cus_old");action.setPayloadJson("{\"cancel_action_id\":9}");when(actions.claimCollectionCheck(eq(10L),anyInt(),anyString(),any(),any())).thenReturn(1);when(actions.markDispatched(eq(10L),anyInt(),anyString(),any(),anyString())).thenReturn(1);when(actions.complete(eq(10L),anyInt(),anyString(),anyString(),anyString(),any())).thenReturn(1);
        service.runDueAction(10L);var result=org.mockito.ArgumentCaptor.forClass(String.class);verify(actions).complete(eq(10L),anyInt(),anyString(),eq("SUCCEEDED"),result.capture(),any());assertFalse(com.google.gson.JsonParser.parseString(result.getValue()).getAsJsonObject().get("collection_scope_read_complete").getAsBoolean());verify(stripe,never()).listCustomerInvoices(anyString());
    }
    @Test void collectionRepairCannotUndoTheNewSubscriptionsRecoveryProtection() {
        var snap=snapshot();var root=existingRoot();root.setStatus("CLOSING");root.setCloseReason("REPLACEMENT_PAID");ownedFixture(root,snap);var user=users.selectByUserForUpdate("user");user.setStripeSubscriptionId("sub_new");user.setPaymentRecoveryId(2L);when(access.resolveForUpdate("user","sub_new")).thenReturn(new PaymentRecoveryAccess(true,false,null,null,null));
        var target=snapshot();target.invoice().setId("in_new");target.invoice().setSubscription("sub_new");target.invoice().setAutoAdvance(false);target.subscription().setId("sub_new");when(stripe.readInvoice("in_new")).thenReturn(target);
        var source=new SubscriptionRecoveryActionEntity();source.setId(9L);source.setRecoveryId(1L);source.setActionType("CANCEL_SUBSCRIPTION");source.setStatus("SUCCEEDED");source.setResultJson("{\"provider_state\":\"SUCCEEDED\",\"cancel_target_subscription_id\":\"sub_old\",\"before_other_collection\":[{\"invoice_id\":\"in_new\",\"subscription_id\":\"sub_new\"}]}");when(actions.selectForUpdate(9L)).thenReturn(source);
        var action=readyAction("RESTORE_ADVANCE","in_new");action.setPayloadJson("{\"auto_advance\":true}");action.setResultJson("{\"cause_action_id\":9,\"expected_subscription_id\":\"sub_new\"}");when(actions.claim(eq(10L),anyInt(),anyString(),any(),any())).thenReturn(1);when(actions.markDispatched(eq(10L),anyInt(),anyString(),any(),anyString())).thenReturn(1);when(stripe.execute(any())).thenReturn(new SubscriptionRecoveryStripeGateway.WriteResult("UNKNOWN",null,null,null));
        service.runDueAction(10L);assertEquals("SKIPPED",action.getStatus());verify(stripe,never()).execute(any());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void canceledConfirmedPaidCycleDoesNotPreviewAndKeepsRightsBeforeItsEnd(boolean lastSecond) {
        var f=paidCancellationFixture(false,false);
        if(lastSecond){
            var original=f.snapshot();var at=f.root().getEntitlementEndAt().atZone(com.studyagent.common.datetime.DateTimeFormats.APP_ZONE).toInstant().minusSeconds(1);
            when(stripe.readInvoice("in_old")).thenReturn(new SubscriptionRecoveryStripeGateway.Snapshot(original.invoice(),original.subscription(),null,original.lines(),original.paymentIntent(),original.charge(),at));
        }
        when(stripe.preview("sub_old")).thenThrow(new IllegalStateException("invoice_upcoming_none"));
        assertDoesNotThrow(()->{service.reconcile(1L);service.reconcile(1L);});
        assertEquals("pro",f.user().getTier());assertEquals(1L,f.user().getPaymentRecoveryId());
        assertEquals(f.root().getEntitlementEndAt(),f.user().getCurrentPeriodEnd());
        assertEquals("GRANTED",f.root().getGrantState());assertEquals("CONFIRMED",f.root().getAnchorSyncState());
        assertNull(f.root().getAccountClosedAt());assertEquals("NONE",f.root().getReviewState());
        verify(stripe,never()).preview(anyString());verify(stripe,never()).execute(any());verifyNoInteractions(f.quota());
    }

    @Test void canceledUnconfirmedPaidCycleNeedsReviewWithoutPreviewOrEarlyClear() {
        var f=paidCancellationFixture(false,false);f.root().setAnchorSyncState("PENDING");
        when(stripe.preview("sub_old")).thenThrow(new IllegalStateException("invoice_upcoming_none"));
        assertDoesNotThrow(()->service.reconcile(1L));
        assertEquals("REVIEW",f.root().getAnchorSyncState());assertEquals("REMOTE_CANCELED_PAID_CYCLE_PRESERVED",f.root().getReviewReason());
        assertEquals("GRANTED",f.root().getGrantState());assertEquals(f.root().getEntitlementEndAt(),f.user().getCurrentPeriodEnd());
        assertNull(f.root().getAccountClosedAt());verifyNoInteractions(f.quota());verify(stripe,never()).preview(anyString());
    }

    @Test void paidExpiryUsesUtcMirrorAsTheSameInstantInsteadOfClearingEightHoursEarly() {
        var f=paidCancellationFixture(true,true);
        f.user().setCurrentPeriodTimeZone("UTC");
        f.user().setCurrentPeriodEnd(f.root().getEntitlementEndAt().atZone(com.studyagent.common.datetime.DateTimeFormats.APP_ZONE).toInstant().atOffset(java.time.ZoneOffset.UTC).toLocalDateTime());
        service.reconcile(1L);assertEquals("free",f.user().getTier());assertNull(f.user().getPaymentRecoveryId());
        assertEquals("GRANTED",f.root().getGrantState());assertNull(f.root().getNextReconcileAt());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"reconcile","customer.subscription.deleted","customer.subscription.updated"})
    void canceledPaidCycleExpiresAccountOnlyOnceAndKeepsItsReceipt(String entry) {
        var f=paidCancellationFixture(true,true);String admission=f.root().getContractSnapshot();
        var end=f.root().getEntitlementEndAt();
        for(int i=0;i<2;i++){
            if("reconcile".equals(entry))service.reconcile(1L);
            else assertTrue(service.tryProcessEvent(paidLifecycleEvent(entry)));
        }
        assertEquals("free",f.user().getTier());assertNull(f.user().getPlanCode());assertEquals("canceled",f.user().getStatus());
        assertNull(f.user().getPaymentRecoveryId());assertNull(f.user().getStripeSubscriptionId());assertNull(f.user().getStripeScheduleId());
        assertNull(f.user().getCurrentPeriodEnd());assertNull(f.user().getQuotaPeriodEnd());assertNull(f.user().getPendingPlanCode());
        assertNull(f.user().getCurrentPeriodTimeZone());assertNull(f.user().getQuotaPeriodTimeZone());assertEquals("cus_old",f.user().getStripeCustomerId());
        assertEquals("PAID",f.root().getStatus());assertEquals("GRANTED",f.root().getGrantState());assertEquals("in_old",f.root().getEntitlementInvoiceId());
        assertEquals(admission,f.root().getContractSnapshot());assertEquals(end,f.root().getEntitlementEndAt());
        assertNotNull(f.root().getAccountClosedAt());assertEquals("PAID_PERIOD_ENDED",f.root().getCloseReason());assertNull(f.root().getNextReconcileAt());
        verify(f.quota(),times(1)).clearPlanQuotaAt("user","sub_old","pro_monthly","recovery:1:paid-period-ended",f.snapshot().businessNow());
        verify(f.quota(),times(1)).pauseAddonsAt("user","sub_old","recovery:1:paid-period-ended:addons",f.snapshot().businessNow());
        // A late receipt must remain historical, not become a second grant or a false double-payment hold.
        var late=ApiResource.GSON.fromJson("{\"id\":\"evt_old_receipt\",\"type\":\"invoice.paid\",\"data\":{\"object\":{\"id\":\"in_old\"}}}",Event.class);
        assertTrue(service.tryProcessEvent(late));assertEquals("GRANTED",f.root().getGrantState());assertEquals("NONE",f.root().getReviewState());
        verify(f.quota(),never()).resetFromPaidInvoiceAt(anyString(),anyString(),anyString(),any(),any(),anyString(),any());
        verify(stripe,never()).preview(anyString());verify(stripe,never()).execute(any());verify(orders,never()).insert(any(RechargeOrderEntity.class));
        assertRecoveryHistoryWasNotDeleted();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"active","newer_utc_cycle","unclassified_period","financial_hold"})
    void paidExpiryDoesNotClearAnUnconfirmedEndOrAnotherPaidPeriod(String state) {
        var f=paidCancellationFixture(true,true);
        switch(state){
            case "active" -> f.snapshot().subscription().setStatus("active");
            case "newer_utc_cycle" -> {f.user().setCurrentPeriodTimeZone("UTC");f.user().setCurrentPeriodEnd(now.plusSeconds(60*86400).atOffset(java.time.ZoneOffset.UTC).toLocalDateTime());}
            case "unclassified_period" -> {f.user().setCurrentPeriodTimeZone("unknown");}
            case "financial_hold" -> f.root().setDisputeState("OPEN");
        }
        service.reconcile(1L);
        assertEquals("pro",f.user().getTier());assertEquals(1L,f.user().getPaymentRecoveryId());assertNull(f.root().getAccountClosedAt());
        verify(f.quota(),never()).clearPlanQuotaAt(anyString(),anyString(),anyString(),anyString(),any());
        verify(f.quota(),never()).pauseAddonsAt(anyString(),anyString(),anyString(),any());verify(stripe,never()).execute(any());
    }

    private record PaidCancellationFixture(SubscriptionPaymentRecoveryEntity root,UserSubscriptionEntity user,
            SubscriptionRecoveryStripeGateway.Snapshot snapshot,BillingQuotaGateway quota){}
    private PaidCancellationFixture paidCancellationFixture(boolean expired,boolean periodEnd) {
        var paid=paidSnapshot();var root=confirmedPaidRoot(paid);root.setAnchorSyncState("CONFIRMED");root.setReviewState("NONE");
        var saved=com.google.gson.JsonParser.parseString(root.getContractSnapshot()).getAsJsonObject();saved.addProperty("quantity",1);
        saved.getAsJsonObject("recovery_admission").addProperty("sourceKey","formal");saved.getAsJsonObject("recovery_admission").addProperty("cancelMode",periodEnd?"PERIOD_END":"RENEW");root.setContractSnapshot(saved.toString());
        var end=root.getEntitlementEndAt().atZone(com.studyagent.common.datetime.DateTimeFormats.APP_ZONE).toInstant();
        paid.subscription().setStatus("canceled");paid.subscription().setEndedAt(expired?end.getEpochSecond():now.getEpochSecond());
        paid.subscription().setCancelAtPeriodEnd(periodEnd);if(periodEnd)paid.subscription().setCancelAt(end.getEpochSecond());
        var snapshot=new SubscriptionRecoveryStripeGateway.Snapshot(paid.invoice(),paid.subscription(),null,paid.lines(),paid.paymentIntent(),paid.charge(),expired?end:now);
        ownedFixture(root,snapshot);var user=users.selectByUserForUpdate("user");user.setStripeCustomerId("cus_old");user.setTier("pro");user.setStatus("active");
        user.setCurrentPeriodStart(root.getEntitlementStartAt());user.setCurrentPeriodEnd(root.getEntitlementEndAt());user.setQuotaPeriodStart(root.getEntitlementStartAt());user.setQuotaPeriodEnd(root.getEntitlementEndAt());
        user.setCurrentPeriodTimeZone("Asia/Shanghai");user.setQuotaPeriodTimeZone("Asia/Shanghai");user.setPendingPlanCode("plus_monthly");
        var existing=new RechargeOrderEntity();existing.setStripeInvoiceId("in_old");existing.setStatus("completed");when(orders.selectOne(any())).thenReturn(existing);
        when(recoveries.selectBySubscription("sub_old",0L,100)).thenReturn(List.of(root));
        var shift=new SubscriptionRecoveryActionEntity();shift.setActionType("SHIFT_RENEWAL");shift.setStatus("SUCCEEDED");shift.setResultJson("{\"latest_invoice\":\"in_old\",\"before_latest_invoice\":\"in_old\"}");when(actions.selectByRecovery(1L)).thenReturn(List.of(shift));
        return new PaidCancellationFixture(root,user,snapshot,injectQuota());
    }
    private Event paidLifecycleEvent(String type) {
        return ApiResource.GSON.fromJson("{\"id\":\"evt_paid_end\",\"type\":\""+type+"\",\"data\":{\"object\":{\"id\":\"sub_old\"}}}",Event.class);
    }

    @Test void technicalCanceledCallbackUpdatesRawStateButKeepsTheFullPaidPeriod() {
        var snap=paidSnapshot();var root=confirmedPaidRoot(snap);snap.subscription().setStatus("canceled");snap.subscription().setCurrentPeriodEnd(now.plusSeconds(2*86400).getEpochSecond());ownedFixture(root,snap);var user=users.selectByUserForUpdate("user");user.setStatus("active");user.setCurrentPeriodEnd(root.getEntitlementEndAt());when(recoveries.selectBySubscription("sub_old",0L,100)).thenReturn(List.of(root));
        var event=ApiResource.GSON.fromJson("{\"id\":\"evt_technical_cancel\",\"type\":\"customer.subscription.deleted\",\"data\":{\"object\":{\"id\":\"sub_old\"}}}",Event.class);
        assertTrue(service.tryProcessEvent(event));assertEquals("canceled",user.getStatus());assertEquals(root.getEntitlementEndAt(),user.getCurrentPeriodEnd());assertEquals("GRANTED",root.getGrantState());
    }
    @Test void technicalTrialCallbackDoesNotConsumeTrialEligibilityOrChangeTheSavedPaidCycle() {
        var snap=paidSnapshot();var root=confirmedPaidRoot(snap);ownedFixture(root,snap);var user=users.selectByUserForUpdate("user");user.setStatus("active");user.setSubscriptionPhase(IntroTrialPlans.PHASE_STANDARD);user.setCurrentPeriodEnd(root.getEntitlementEndAt());when(recoveries.selectBySubscription("sub_old",0L,100)).thenReturn(List.of(root));
        var event=ApiResource.GSON.fromJson("{\"id\":\"evt_technical_trial\",\"type\":\"customer.subscription.updated\",\"data\":{\"object\":{\"id\":\"sub_old\"}}}",Event.class);
        assertTrue(service.tryProcessEvent(event));assertEquals("trialing",user.getStatus());assertEquals(IntroTrialPlans.PHASE_STANDARD,user.getSubscriptionPhase());assertNull(user.getIntroTrialUsedAt());assertNull(user.getIntroTrialConvertedAt());assertEquals(root.getEntitlementEndAt(),user.getCurrentPeriodEnd());
    }
    @Test void admissionWaitsForAnAlreadyReservedPriceRolloutWithoutCreatingABusinessLock() {
        enable();when(stripe.readInvoice("in_old")).thenReturn(snapshot());when(users.selectByUserForUpdate("user")).thenReturn(user());when(tx.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        var guard=mock(SubscriptionContractMutationCoordinator.class);when(guard.inFlight("sub_old",true)).thenReturn(true);org.springframework.test.util.ReflectionTestUtils.setField(service,"contractMutations",guard);
        var error=assertThrows(IllegalStateException.class,()->service.tryProcessEvent(event()));assertTrue(error.getMessage().contains("CONTRACT_MUTATION"));verify(recoveries,never()).insert(any(SubscriptionPaymentRecoveryEntity.class));verify(actions,never()).insert(any(SubscriptionRecoveryActionEntity.class));
    }
    @Test void aRetiredPaidRootCannotReconcileOrPauseTheNormalNextCycle() {
        var snap=paidSnapshot();var root=confirmedPaidRoot(snap);root.setAnchorSyncState("CONFIRMED");root.setNextReconcileAt(com.studyagent.common.datetime.DateTimeFormats.now());ownedFixture(root,snap);users.selectByUserForUpdate("user").setPaymentRecoveryId(null);snap.subscription().setCurrentPeriodEnd(now.plusSeconds(60*86400).getEpochSecond());
        service.reconcile(1L);assertNull(root.getNextReconcileAt());assertEquals("GRANTED",root.getGrantState());verify(stripe,never()).readInvoice(anyString());verify(actions,never()).insert(any(SubscriptionRecoveryActionEntity.class));
    }
    @Test void anOldPendingShiftCannotClaimAfterThePointerMovesToTheNextCycle() {
        enable();var snap=paidSnapshot();var root=confirmedPaidRoot(snap);var pause=new Subscription.PauseCollection();pause.setBehavior("keep_as_draft");snap.subscription().setPauseCollection(pause);root.setContractSnapshot("{\"price_id\":\"price_old\",\"quantity\":1,\"recovery_admission\":{\"sourceKey\":\"formal\",\"cancelMode\":\"RENEW\",\"contractHash\":\""+RecoveryContractClassifier.classify(snap.subscription(),null,true,"price_old",1L).contractHash()+"\"}}");ownedFixture(root,snap);users.selectByUserForUpdate("user").setPaymentRecoveryId(null);
        var action=readyAction("SHIFT_RENEWAL","sub_old");when(actions.claim(eq(10L),anyInt(),anyString(),any(),any())).thenReturn(1);when(actions.markDispatched(eq(10L),anyInt(),anyString(),any(),anyString())).thenReturn(1);when(stripe.execute(any())).thenReturn(new SubscriptionRecoveryStripeGateway.WriteResult("UNKNOWN",null,null,null));
        service.runDueAction(10L);assertEquals("SKIPPED",action.getStatus());verify(stripe,never()).execute(any());
    }
    @Test void anEarlierPauseIntentCannotRePauseAnAlreadyConfirmedPaidCycle() {
        var snap=paidSnapshot();var root=confirmedPaidRoot(snap);root.setAnchorSyncState("CONFIRMED");ownedFixture(root,snap);var action=readyAction("PAUSE_COLLECTION","sub_old");action.setPayloadJson("{\"pause_collection\":{\"behavior\":\"keep_as_draft\"}}");
        when(actions.claim(eq(10L),anyInt(),anyString(),any(),any())).thenReturn(1);when(actions.markDispatched(eq(10L),anyInt(),anyString(),any(),anyString())).thenReturn(1);when(stripe.execute(any())).thenReturn(new SubscriptionRecoveryStripeGateway.WriteResult("UNKNOWN",null,null,null));
        service.runDueAction(10L);assertEquals("SKIPPED",action.getStatus());verify(stripe,never()).execute(any());
    }
    @Test void unknownCheckoutCreationCanBeConfirmedFromTheOwnedExpiredSessionWithoutCreatingAgain() {
        var root=existingRoot();ownedFixture(root,snapshot());var action=readyAction("CREATE_CHECKOUT","cus_old");action.setStatus("UNKNOWN");action.setFirstDispatchedAt(com.studyagent.common.datetime.DateTimeFormats.now().minusMinutes(5));action.setPayloadJson("{\"customer\":\"cus_old\",\"mode\":\"subscription\",\"line_items\":[{\"price\":\"price_old\",\"quantity\":1}],\"metadata\":{\"order_no\":\"order_new\",\"purchase_type\":\"subscription_replacement\",\"recovery_id\":\"1\",\"clerk_user_id\":\"user\",\"old_subscription_id\":\"sub_old\"}}");
        var reserved=new SubscriptionRecoveryReplacementEntity();reserved.setRecoveryId(1L);reserved.setClerkUserId("user");reserved.setOldSubscriptionId("sub_old");reserved.setOrderNo("order_new");reserved.setState("CREATING");reserved.setActiveSubscriptionKey("sub_old");reserved.setQuoteSnapshot(action.getPayloadJson());when(replacements.selectByOrder("order_new")).thenReturn(reserved);when(replacements.selectActive("sub_old")).thenReturn(reserved);
        var session=ApiResource.GSON.fromJson("{\"id\":\"cs_existing\",\"object\":\"checkout.session\",\"customer\":\"cus_old\",\"mode\":\"subscription\",\"status\":\"expired\",\"payment_status\":\"unpaid\",\"metadata\":{\"order_no\":\"order_new\",\"purchase_type\":\"subscription_replacement\",\"recovery_id\":\"1\",\"clerk_user_id\":\"user\",\"old_subscription_id\":\"sub_old\"},\"line_items\":{\"data\":[{\"id\":\"li_saved\",\"quantity\":1,\"price\":{\"id\":\"price_old\"}}],\"has_more\":false}}",com.stripe.model.checkout.Session.class);
        when(stripe.findCheckout("cus_old","order_new")).thenReturn(session);when(stripe.readCheckout("cs_existing")).thenReturn(session);
        var order=new RechargeOrderEntity();order.setOrderNo("order_new");order.setStatus("pending");when(orders.selectOne(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(order);
        service.runDueAction(10L);assertEquals("SUCCEEDED",action.getStatus());assertEquals("EXPIRED",reserved.getState());assertNull(reserved.getActiveSubscriptionKey());assertTrue(action.getResultJson().contains("cs_existing"));assertEquals("cs_existing",order.getStripeSessionId());verify(orders).updateById(order);verify(stripe,never()).execute(any());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"paid","partial","void","canceled","insufficient_funds","processing"})
    void readyReminderChecksTheFreshProviderStateBeforeClaiming(String state) {
        var action=unknownMailFixture(10);action.setStatus("READY");action.setFirstDispatchedAt(null);
        var snap=stripe.readInvoice("in_old");
        switch(state){
            case "paid" -> {snap.invoice().setStatus("paid");snap.invoice().setAmountPaid(2900L);snap.invoice().setAmountRemaining(0L);}
            case "partial" -> snap.invoice().setAmountPaid(100L);
            case "void" -> snap.invoice().setStatus("void");
            case "canceled" -> snap.subscription().setStatus("canceled");
            case "insufficient_funds" -> snap.paymentIntent().getLastPaymentError().setDeclineCode("insufficient_funds");
            case "processing" -> snap.paymentIntent().setStatus("processing");
        }
        var sender=mock(com.studyagent.infra.notification.BillingPaymentRecoveryEmailSender.class);org.springframework.test.util.ReflectionTestUtils.setField(service,"emailSender",sender);
        service.runDueAction(10L);verifyNoInteractions(sender);verify(actions,never()).claim(anyLong(),anyInt(),anyString(),any(),any());
        assertEquals("processing".equals(state)?"READY":"SKIPPED",action.getStatus());
    }
    @Test void anUnknownReminderDoesNotReplayWhenTheCurrentNativeFailureIsInsufficientFunds() {
        var action=unknownMailFixture(10);stripe.readInvoice("in_old").paymentIntent().getLastPaymentError().setDeclineCode("insufficient_funds");
        var sender=mock(com.studyagent.infra.notification.BillingPaymentRecoveryEmailSender.class);org.springframework.test.util.ReflectionTestUtils.setField(service,"emailSender",sender);
        service.runDueAction(10L);verifyNoInteractions(sender);verify(actions,never()).claimUnknownMail(anyLong(),anyInt(),anyString(),any(),any());assertEquals("SKIPPED",action.getStatus());
    }
    @Test void unknownReminderWithinTheProviderWindowReusesTheFrozenBodyAndOriginalKey() {
        var action=unknownMailFixture(10);var first=action.getFirstDispatchedAt();var sender=mock(com.studyagent.infra.notification.BillingPaymentRecoveryEmailSender.class);org.springframework.test.util.ReflectionTestUtils.setField(service,"emailSender",sender);
        when(sender.send(action.getPayloadJson(),action.getProviderIdempotencyKey())).thenReturn(new com.studyagent.infra.notification.BillingPaymentRecoveryEmailSender.SendResult("SUCCEEDED","msg_once",null));
        when(actions.claimUnknownMail(eq(10L),anyInt(),anyString(),any(),any())).thenReturn(1);when(actions.markDispatched(eq(10L),anyInt(),anyString(),any(),anyString())).thenReturn(1);when(actions.complete(eq(10L),anyInt(),anyString(),anyString(),anyString(),any())).thenAnswer(i->{action.setStatus(i.getArgument(3));action.setResultJson(i.getArgument(4));return 1;});
        service.runDueAction(10L);verify(sender).send(action.getPayloadJson(),"recovery:in_old:manual-email");assertEquals(first,action.getFirstDispatchedAt());assertEquals("SUCCEEDED",action.getStatus());assertTrue(action.getResultJson().contains("msg_once"));verify(stripe,never()).execute(any());
    }
    @Test void unknownReminderOutsideTheProviderWindowDoesNotResendOrBlockPayment() {
        var action=unknownMailFixture(24*60+1);var sender=mock(com.studyagent.infra.notification.BillingPaymentRecoveryEmailSender.class);org.springframework.test.util.ReflectionTestUtils.setField(service,"emailSender",sender);
        service.runDueAction(10L);verifyNoInteractions(sender);assertEquals("REVIEW",action.getStatus());assertNotEquals("OPEN",recoveries.selectById(1L).getReviewState());verify(actions,never()).claimUnknownMail(anyLong(),anyInt(),anyString(),any(),any());
    }
    @Test void insufficientFundsBecomesManualOnceAndDuplicateFailuresSendOneFrozenReminderThroughTheRealSender() throws Exception {
        enable();properties.setEmailEnabled(true);properties.setEmailFrom("billing@example.test");
        var snap=snapshot();when(stripe.readInvoice("in_old")).thenReturn(snap);
        var account=user();when(users.selectByStripeSubscriptionId("sub_old")).thenReturn(account);when(users.selectByUserForUpdate("user")).thenReturn(account);
        when(tx.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        var persistedRoot=new java.util.concurrent.atomic.AtomicReference<SubscriptionPaymentRecoveryEntity>();
        when(recoveries.selectByInvoice("in_old")).thenAnswer(call->persistedRoot.get());
        when(recoveries.insert(any(SubscriptionPaymentRecoveryEntity.class))).thenAnswer(call->{
            SubscriptionPaymentRecoveryEntity row=call.getArgument(0);row.setId(1L);persistedRoot.set(row);return 1;
        });
        var ledger=failureLedger();
        org.springframework.test.util.ReflectionTestUtils.setField(service,"frontendUrl","https://app.example.test");
        var clerk=mock(com.studyagent.service.domain.user.ClerkClient.class);
        when(clerk.getUserEmail("user")).thenReturn("own@example.test");
        var requests=new ArrayList<org.springframework.web.reactive.function.client.ClientRequest>();
        var web=org.springframework.web.reactive.function.client.WebClient.builder().exchangeFunction(request->{
            requests.add(request);
            return reactor.core.publisher.Mono.just(org.springframework.web.reactive.function.client.ClientResponse
                    .create(org.springframework.http.HttpStatus.OK).header("Content-Type","application/json")
                    .body("{\"id\":\"msg_manual_once\"}").build());
        }).build();
        // Real prepare/send implementation, with only provider HTTP replaced at the exchange boundary.
        var sender=spy(new com.studyagent.infra.notification.BillingPaymentRecoveryEmailSender(clerk,web,properties));
        org.springframework.test.util.ReflectionTestUtils.setField(sender,"apiKey","test-only");
        org.springframework.test.util.ReflectionTestUtils.setField(service,"emailSender",sender);
        bindSequenceActions(ledger);
        assertTrue(service.tryProcessEvent(failureEvent("evt_first_funds","ch_old")));
        var root=persistedRoot.get();assertNotNull(root);assertEquals(1L,account.getPaymentRecoveryId());assertNull(account.getGraceEndAt());
        assertEquals(4,ledger.stream().filter(a->"RETRY_PAY".equals(a.getActionType())).count());
        assertEquals("OPEN_AUTO",root.getStatus());assertEquals("insufficient_funds",root.getLatestDeclineCode());
        assertEquals(0,ledger.stream().filter(a->"SEND_EMAIL".equals(a.getActionType())).count());
        verifyNoInteractions(sender,clerk);assertTrue(requests.isEmpty());

        // Re-read protection independently from native state before the new decline callback.
        ownedFixture(root,snap);when(users.selectByUserForUpdate("user")).thenReturn(account);when(users.selectByUser("user")).thenReturn(account);
        var pause=new Subscription.PauseCollection();pause.setBehavior("keep_as_draft");snap.subscription().setPauseCollection(pause);
        snap.paymentIntent().getLastPaymentError().setDeclineCode("generic_decline");
        snap.charge().setId("ch_manual");snap.paymentIntent().setLatestCharge("ch_manual");
        var manualFailure=failureEvent("evt_manual_decline","ch_manual");
        assertTrue(service.tryProcessEvent(manualFailure));assertTrue(service.tryProcessEvent(manualFailure));
        assertEquals("OPEN_MANUAL",root.getStatus());assertEquals("generic_decline",root.getLatestDeclineCode());
        assertTrue(ledger.stream().filter(a->"RETRY_PAY".equals(a.getActionType())).allMatch(a->"SKIPPED".equals(a.getStatus())));
        var reminders=ledger.stream().filter(a->"SEND_EMAIL".equals(a.getActionType())).toList();assertEquals(1,reminders.size());
        var reminder=reminders.get(0);assertEquals("READY",reminder.getStatus());
        assertEquals("recovery:in_old:manual-email",reminder.getActionKey());assertEquals(reminder.getActionKey(),reminder.getProviderIdempotencyKey());
        assertTrue(requests.isEmpty());verifyNoInteractions(sender,clerk);
        String originalKey=reminder.getProviderIdempotencyKey();
        service.runDueAction(reminder.getId());
        assertEquals("SUCCEEDED",reminder.getStatus());assertTrue(reminder.getResultJson().contains("msg_manual_once"));
        String frozen=reminder.getPayloadJson();
        var body=new com.fasterxml.jackson.databind.ObjectMapper().readTree(frozen);
        assertEquals("own@example.test",body.path("to").get(0).asText());assertEquals("billing@example.test",body.path("from").asText());
        assertTrue(body.path("text").asText().contains("29.00 USD"));
        assertTrue(body.path("text").asText().endsWith("https://app.example.test/?payment_recovery_id=1"));
        assertTrue(service.tryProcessEvent(manualFailure));assertTrue(service.tryProcessEvent(failureEvent("evt_manual_replayed","ch_manual")));
        service.runDueAction(reminder.getId());service.reconcile(1L);service.runDueAction(reminder.getId());
        assertSame(reminder,ledger.stream().filter(a->"SEND_EMAIL".equals(a.getActionType())).findFirst().orElseThrow());
        assertEquals(1,ledger.stream().filter(a->"SEND_EMAIL".equals(a.getActionType())).count());
        assertEquals(frozen,reminder.getPayloadJson());assertEquals(originalKey,reminder.getProviderIdempotencyKey());assertEquals("SUCCEEDED",reminder.getStatus());
        verify(sender,times(1)).prepare(root,"https://app.example.test/?payment_recovery_id=1");
        verify(sender,times(1)).send(frozen,originalKey);verify(clerk,times(1)).getUserEmail("user");
        assertEquals(1,requests.size());assertEquals(org.springframework.http.HttpMethod.POST,requests.get(0).method());
        assertEquals("https://api.resend.com/emails",requests.get(0).url().toString());assertEquals(originalKey,requests.get(0).headers().getFirst("Idempotency-Key"));
        verify(recoveries,times(1)).insert(any(SubscriptionPaymentRecoveryEntity.class));
        verify(stripe,never()).execute(any());verify(orders,never()).insert(any(RechargeOrderEntity.class));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"paid","partial","activeReplacement"})
    void frozenReadyReminderWaitsOrSkipsWhenPaymentOrAnActiveReplacementArrivesBeforeDispatch(String state) {
        enable();var reminder=unknownMailFixture(10);reminder.setStatus("READY");reminder.setFirstDispatchedAt(null);
        reminder.setActionKey("recovery:in_old:manual-email");
        var root=recoveries.selectById(1L);root.setDisplayTier("pro");var snap=stripe.readInvoice("in_old");
        root.setContractSnapshot("{\"price_id\":\"price_old\",\"quantity\":1,\"recovery_admission\":{\"sourceKey\":\"formal\",\"cancelMode\":\"RENEW\",\"contractHash\":\""
                +RecoveryContractClassifier.classify(snap.subscription(),null,true,"price_old",1L).contractHash()+"\"}}");
        var ledger=failureLedger();ledger.add(reminder);bindSequenceActions(ledger);
        var sender=mock(com.studyagent.infra.notification.BillingPaymentRecoveryEmailSender.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"emailSender",sender);
        String frozen=reminder.getPayloadJson(),key=reminder.getActionKey(),providerKey=reminder.getProviderIdempotencyKey();
        var scheduledAt=reminder.getScheduledAt();
        if("paid".equals(state)){
            injectQuota();var paid=paidSnapshot();paid.invoice().setAutoAdvance(false);paid.subscription().setPauseCollection(snap.subscription().getPauseCollection());
            when(stripe.readInvoice("in_old")).thenReturn(paid);when(stripe.listInvoices("sub_old")).thenReturn(List.of(paid.invoice()));
            service.reconcile(1L);assertEquals("PAID",root.getStatus());assertEquals("GRANTED",root.getGrantState());
        }else if("partial".equals(state)){
            snap.invoice().setAmountPaid(100L);snap.invoice().setAmountRemaining(2800L);
            service.reconcile(1L);assertEquals(100L,root.getAmountPaidMinor());assertEquals("PARTIAL_PAYMENT",root.getReviewReason());
        }else{
            // Reserve through the actual service; the mock mapper only persists its result.
            var active=new java.util.concurrent.atomic.AtomicReference<SubscriptionRecoveryReplacementEntity>();
            when(replacements.selectActive("sub_old")).thenAnswer(call->active.get());
            when(replacements.insert(any(SubscriptionRecoveryReplacementEntity.class))).thenAnswer(call->{
                SubscriptionRecoveryReplacementEntity row=call.getArgument(0);row.setId(71L);active.set(row);return 1;
            });
            var order=new RechargeOrderEntity();order.setOrderNo("order_mail_hold");order.setClerkUserId("user");order.setOrderType("subscription_replacement");order.setTargetPlanCode("pro_monthly");
            var params=com.stripe.param.checkout.SessionCreateParams.builder().setCustomer("cus_old")
                    .setMode(com.stripe.param.checkout.SessionCreateParams.Mode.SUBSCRIPTION).putMetadata("order_no","order_mail_hold").build();
            var reservation=service.reserveReplacement("user",1L,order,params,Instant.now().plusSeconds(1860));
            assertEquals(71L,reservation.replacementId());assertEquals("CREATING",active.get().getState());assertEquals("sub_old",active.get().getActiveSubscriptionKey());
            assertEquals("READY",actions.selectById(reservation.actionId()).getStatus());
        }
        service.runDueAction(reminder.getId());service.runDueAction(reminder.getId());
        assertEquals("activeReplacement".equals(state)?"READY":"SKIPPED",reminder.getStatus());
        if(!"activeReplacement".equals(state))assertEquals("SKIPPED_TERMINAL",reminder.getSkipReason());
        assertEquals(frozen,reminder.getPayloadJson());assertEquals(key,reminder.getActionKey());assertEquals(providerKey,reminder.getProviderIdempotencyKey());
        assertEquals(scheduledAt,reminder.getScheduledAt());assertNull(reminder.getFirstDispatchedAt());
        assertEquals(1,ledger.stream().filter(a->"SEND_EMAIL".equals(a.getActionType())).count());
        verifyNoInteractions(sender);verify(actions,never()).claim(anyLong(),anyInt(),anyString(),any(),any());verify(stripe,never()).execute(any());
    }

    private SubscriptionRecoveryActionEntity unknownMailFixture(int minutes){
        var snap=snapshot();var pause=new Subscription.PauseCollection();pause.setBehavior("keep_as_draft");snap.subscription().setPauseCollection(pause);var root=existingRoot();root.setStatus("OPEN_MANUAL");root.setLatestDeclineCode("generic_decline");root.setAmountPaidMinor(0L);root.setAmountRemainingMinor(2900L);root.setDeadlineAt(com.studyagent.common.datetime.DateTimeFormats.now().plusDays(1));ownedFixture(root,snap);properties.setEmailEnabled(true);
        snap.paymentIntent().getLastPaymentError().setDeclineCode("generic_decline");
        var action=readyAction("SEND_EMAIL","in_old");action.setStatus("UNKNOWN");action.setProviderIdempotencyKey("recovery:in_old:manual-email");action.setFirstDispatchedAt(com.studyagent.common.datetime.DateTimeFormats.now().minusMinutes(minutes));action.setUpdatedAt(com.studyagent.common.datetime.DateTimeFormats.now().minusMinutes(2));action.setPayloadJson("{\"from\":\"billing@example.test\",\"to\":[\"own@example.test\"],\"subject\":\"Subscription payment pending\",\"text\":\"Frozen original body\"}");return action;
    }
    @Test void verifiedSilentDraftCleanupWaitsForFinalizeAndVoidBeforeClosingTheAccount() {
        var snap=snapshot();snap.invoice().setStatus("void");snap.subscription().setStatus("canceled");var root=existingRoot();root.setContractSnapshot("{\"price_id\":\"price_old\",\"quantity\":1,\"recovery_admission\":{\"silentCleanupVerified\":true}}");ownedFixture(root,snap);var quota=injectQuota();
        var draft=ApiResource.GSON.fromJson("{\"id\":\"in_draft\",\"subscription\":\"sub_old\",\"status\":\"draft\",\"auto_advance\":false,\"amount_paid\":0}",Invoice.class);when(stripe.listInvoices("sub_old")).thenReturn(List.of(snap.invoice(),draft));
        service.reconcile(1L);assertNull(root.getAccountClosedAt());verify(quota,never()).clearPlanQuotaAt(anyString(),anyString(),anyString(),anyString(),any());var capture=org.mockito.ArgumentCaptor.forClass(SubscriptionRecoveryActionEntity.class);verify(actions,atLeastOnce()).insert(capture.capture());assertTrue(capture.getAllValues().stream().anyMatch(a->"FINALIZE_INVOICE".equals(a.getActionType()) && a.getPayloadJson().contains("false")));
    }
    @Test void protectedUnverifiedDraftCanRemainForIndependentManualReviewAfterAccountClosure() {
        var snap=snapshot();snap.invoice().setStatus("void");snap.subscription().setStatus("canceled");var root=existingRoot();ownedFixture(root,snap);injectQuota();
        var draft=ApiResource.GSON.fromJson("{\"id\":\"in_draft\",\"subscription\":\"sub_old\",\"status\":\"draft\",\"auto_advance\":false,\"amount_paid\":0}",Invoice.class);when(stripe.listInvoices("sub_old")).thenReturn(List.of(snap.invoice(),draft));
        service.reconcile(1L);assertNotNull(root.getAccountClosedAt());assertEquals("OPEN",root.getReviewState());assertEquals("draft",draft.getStatus());verify(stripe,never()).execute(any());
    }
    @Test void rejectedDraftCleanupDoesNotKeepAnOtherwiseClosedUnpaidAccountLocked(){
        var snap=snapshot();snap.invoice().setStatus("void");snap.subscription().setStatus("canceled");var root=existingRoot();root.setStatus("CLOSING");root.setCloseReason("DEADLINE");root.setContractSnapshot("{\"price_id\":\"price_old\",\"quantity\":1,\"recovery_admission\":{\"silentCleanupVerified\":true}}");ownedFixture(root,snap);var quota=injectQuota();
        var other=snapshot();other.invoice().setId("in_draft");other.invoice().setStatus("draft");other.invoice().setAutoAdvance(false);other.lines().get(0).getPrice().setId("price_unverified_intro");when(stripe.readInvoice("in_draft")).thenReturn(other);when(stripe.listInvoices("sub_old")).thenReturn(List.of(snap.invoice(),other.invoice()));
        var ledger=failureLedger();bindSequenceActions(ledger);service.reconcile(1L);assertNull(root.getAccountClosedAt());
        var finalize=ledger.stream().filter(a->"FINALIZE_INVOICE".equals(a.getActionType())).findFirst().orElseThrow();service.runDueAction(finalize.getId());
        assertEquals("SKIPPED",finalize.getStatus());assertEquals("SKIPPED_UNVERIFIED_TARGET",finalize.getSkipReason());assertNull(root.getAccountClosedAt());
        service.reconcile(1L);service.reconcile(1L);
        assertNotNull(root.getAccountClosedAt());assertEquals("EXPIRED",root.getStatus());assertEquals("DEADLINE",root.getCloseReason());assertEquals("OPEN",root.getReviewState());
        assertEquals("draft",other.invoice().getStatus());assertEquals(Boolean.FALSE,other.invoice().getAutoAdvance());assertEquals("free",users.selectByUser("user").getTier());assertNull(users.selectByUser("user").getStripeSubscriptionId());
        verify(stripe,never()).execute(any());verify(quota,times(1)).clearPlanQuotaAt(anyString(),anyString(),anyString(),anyString(),any());assertRecoveryHistoryWasNotDeleted();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"subscription_active","advance_enabled","amount_unknown","finalize_unknown","other_skip"})
    void deferredDraftCannotBypassOtherAccountClosurePrerequisites(String condition){
        var snap=snapshot();snap.invoice().setStatus("void");snap.subscription().setStatus("subscription_active".equals(condition)?"active":"canceled");var root=existingRoot();root.setStatus("CLOSING");root.setCloseReason("DEADLINE");root.setContractSnapshot("{\"price_id\":\"price_old\",\"quantity\":1,\"recovery_admission\":{\"silentCleanupVerified\":true}}");ownedFixture(root,snap);var quota=injectQuota();
        var draft=ApiResource.GSON.fromJson("{\"id\":\"in_draft\",\"subscription\":\"sub_old\",\"status\":\"draft\",\"auto_advance\":false,\"amount_paid\":0}",Invoice.class);if("advance_enabled".equals(condition))draft.setAutoAdvance(true);if("amount_unknown".equals(condition))draft.setAmountPaid(null);when(stripe.listInvoices("sub_old")).thenReturn(List.of(snap.invoice(),draft));
        var finalize=readyAction("FINALIZE_INVOICE","in_draft");finalize.setActionKey("recovery:1:FINALIZE_INVOICE:in_draft");finalize.setStatus("finalize_unknown".equals(condition)?"UNKNOWN":"SKIPPED");finalize.setSkipReason("other_skip".equals(condition)?"SKIPPED_TERMINAL":"SKIPPED_UNVERIFIED_TARGET");var ledger=failureLedger();ledger.add(finalize);bindSequenceActions(ledger);
        service.reconcile(1L);assertNull(root.getAccountClosedAt());assertEquals("CLOSING",root.getStatus());assertEquals("draft",draft.getStatus());verify(quota,never()).clearPlanQuotaAt(anyString(),anyString(),anyString(),anyString(),any());verify(stripe,never()).execute(any());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"void","paid"})
    void protectionIntentFrozenBeforeCloseIsNotDispatchedAfterTheInvoiceBecameTerminal(String status){
        var snap=snapshot();var root=existingRoot();root.setStatus("CLOSING");root.setCloseReason("DEADLINE");ownedFixture(root,snap);
        var target=snapshot();target.invoice().setId("in_closed");target.invoice().setStatus(status);target.invoice().setAutoAdvance(false);
        if("paid".equals(status)){target.invoice().setAmountPaid(2900L);target.invoice().setAmountRemaining(0L);}
        when(stripe.readInvoice("in_closed")).thenReturn(target);
        var action=readyAction("DISABLE_ADVANCE","in_closed");action.setActionKey("recovery:in_closed:disable-advance");action.setProviderIdempotencyKey(action.getActionKey());action.setPayloadJson("{\"auto_advance\":false}");
        var ledger=failureLedger();ledger.add(action);bindSequenceActions(ledger);
        service.runDueAction(10L);
        assertEquals("SKIPPED",action.getStatus());assertEquals("SKIPPED_TERMINAL",action.getSkipReason());
        assertNull(root.getReviewState());verify(actions,never()).claim(anyLong(),anyInt(),anyString(),any(),any());verify(stripe,never()).execute(any());assertRecoveryHistoryWasNotDeleted();
    }
    @Test void finalizationCannotIncludeMixedGoodsEvenWhenTheCohortHasSilentCleanupEvidence() {
        var snap=snapshot();var root=existingRoot();root.setStatus("CLOSING");root.setCloseReason("DEADLINE");root.setContractSnapshot("{\"price_id\":\"price_old\",\"quantity\":1,\"recovery_admission\":{\"silentCleanupVerified\":true}}");ownedFixture(root,snap);
        var mixed=snapshot();mixed.invoice().setId("in_mixed");mixed.invoice().setStatus("draft");mixed.invoice().setAutoAdvance(false);var extra=ApiResource.GSON.fromJson(mixed.lines().get(0).toJson(),InvoiceLineItem.class);extra.setType("invoiceitem");extra.setId("il_extra");var target=new SubscriptionRecoveryStripeGateway.Snapshot(mixed.invoice(),mixed.subscription(),null,List.of(mixed.lines().get(0),extra),mixed.paymentIntent(),mixed.charge(),now);when(stripe.readInvoice("in_mixed")).thenReturn(target);
        var action=readyAction("FINALIZE_INVOICE","in_mixed");action.setPayloadJson("{\"auto_advance\":false}");when(actions.claim(eq(10L),anyInt(),anyString(),any(),any())).thenReturn(1);when(actions.markDispatched(eq(10L),anyInt(),anyString(),any(),anyString())).thenReturn(1);when(stripe.execute(any())).thenReturn(new SubscriptionRecoveryStripeGateway.WriteResult("UNKNOWN",null,null,null));
        service.runDueAction(10L);verify(stripe,never()).execute(any());assertEquals("SKIPPED",action.getStatus());
    }
    @Test void supersededPassLatePaymentRecordsCashWithoutGrantingPassOrRefundingItAutomatically() {
        var root=existingRoot();ownedFixture(root,snapshot());var quota=injectQuota();var end=passCleanupIntent();end.setPayloadJson("{\"study_pass_id\":7,\"subscription_id\":\"sub_pass\",\"checkout_id\":\"cs_pass\"}");when(actions.selectStudyPassTargets("cs_pass")).thenReturn(List.of(end));
        var passSub=new Subscription();passSub.setId("sub_pass");passSub.setCustomer("cus_old");passSub.setStatus("canceled");when(stripe.readSubscription("sub_pass")).thenReturn(passSub);
        var session=new com.stripe.model.checkout.Session();session.setId("cs_pass");session.setCustomer("cus_old");session.setSubscription("sub_pass");session.setInvoice("in_pass");session.setStatus("complete");session.setPaymentStatus("paid");when(stripe.readCheckout("cs_pass")).thenReturn(session);
        var paid=paidSnapshot();paid.invoice().setId("in_pass");paid.invoice().setSubscription("sub_pass");paid.subscription().setId("sub_pass");paid.charge().setInvoice("in_pass");when(stripe.readInvoice("in_pass")).thenReturn(paid);
        var pass=new StudyPassEntity();pass.setId(7L);pass.setClerkUserId("user");pass.setStatus("superseded");pass.setOrderId(5L);pass.setPassCode("pass_reading");pass.setStripeSubscriptionId("sub_pass");var mapper=mock(StudyPassMapper.class);when(mapper.selectById(7L)).thenReturn(pass);org.springframework.test.util.ReflectionTestUtils.setField(service,"passes",mapper);
        var order=new RechargeOrderEntity();order.setId(5L);order.setClerkUserId("user");order.setOrderType("study_pass");order.setStatus("pending");order.setPriceCents(2900);when(orders.selectById(5L)).thenReturn(order);
        var event=ApiResource.GSON.fromJson("{\"id\":\"evt_pass_paid_late\",\"type\":\"checkout.session.completed\",\"data\":{\"object\":{\"id\":\"cs_pass\"}}}",Event.class);
        assertTrue(service.tryProcessEvent(event));assertEquals("completed",order.getStatus());assertEquals("in_pass",order.getStripeInvoiceId());assertEquals("superseded",pass.getStatus());assertTrue(root.getReviewReason().contains("SUPERSEDED_STUDY_PASS_PAYMENT"));verifyNoInteractions(quota);verify(stripe,never()).execute(any());
    }
    @Test void supersededPassSubscriptionEventIsOwnedWithoutCallingTheLegacyPassHandler() {
        var root=existingRoot();ownedFixture(root,snapshot());var end=passCleanupIntent();when(actions.selectStudyPassTargets("sub_pass")).thenReturn(List.of(end));
        var passSub=new Subscription();passSub.setId("sub_pass");passSub.setCustomer("cus_old");passSub.setStatus("active");when(stripe.readSubscription("sub_pass")).thenReturn(passSub);
        var event=ApiResource.GSON.fromJson("{\"id\":\"evt_pass_late\",\"type\":\"customer.subscription.updated\",\"data\":{\"object\":{\"id\":\"sub_pass\"}}}",Event.class);
        assertTrue(service.tryProcessEvent(event));verify(stripe,never()).execute(any());var capture=org.mockito.ArgumentCaptor.forClass(SubscriptionRecoveryActionEntity.class);verify(actions).insert(capture.capture());assertEquals("sub_pass",capture.getValue().getTargetId());assertEquals("CANCEL_SUBSCRIPTION",capture.getValue().getActionType());
    }
    @Test void unknownPassCleanupMaySafelyRebuildTheSameDurableChildIntents() {
        var root=existingRoot();ownedFixture(root,snapshot());var action=readyAction("END_STUDY_PASS","7");action.setActionKey("recovery:1:end-pass:7");action.setPayloadJson(passCleanupIntent().getPayloadJson());action.setStatus("UNKNOWN");action.setFirstDispatchedAt(com.studyagent.common.datetime.DateTimeFormats.now().minusMinutes(2));
        var passSub=new Subscription();passSub.setId("sub_pass");passSub.setCustomer("cus_old");passSub.setStatus("active");when(stripe.readSubscription("sub_pass")).thenReturn(passSub);
        service.runDueAction(10L);assertEquals("SUCCEEDED",action.getStatus());verify(stripe,never()).execute(any());var capture=org.mockito.ArgumentCaptor.forClass(SubscriptionRecoveryActionEntity.class);verify(actions).insert(capture.capture());assertEquals("sub_pass",capture.getValue().getTargetId());
    }
    private SubscriptionRecoveryActionEntity passCleanupIntent(){var end=new SubscriptionRecoveryActionEntity();end.setId(90L);end.setRecoveryId(1L);end.setActionType("END_STUDY_PASS");end.setActionKey("recovery:1:end-pass:7");end.setTargetId("7");end.setStatus("SUCCEEDED");end.setPayloadJson("{\"study_pass_id\":7,\"subscription_id\":\"sub_pass\",\"checkout_id\":null}");return end;}
    @Test void freshMissingStripeProtectionBlocksAnOtherwiseEligibleAutomaticSlot() {
        enable();properties.setAutomaticPayEnabled(true);var snap=snapshot();var root=existingRoot();ownedFixture(root,snap);root.setContractSnapshot("{\"price_id\":\"price_old\",\"quantity\":1,\"recovery_admission\":{\"contractHash\":\""+RecoveryContractClassifier.classify(snap.subscription(),null,true,"price_old",1L).contractHash()+"\"}}");
        var action=readyAction("RETRY_PAY","in_old");action.setScheduledAt(com.studyagent.common.datetime.DateTimeFormats.fromInstant(now));action.setNextRunAt(action.getScheduledAt());when(actions.claim(eq(10L),anyInt(),anyString(),any(),any())).thenReturn(1);when(actions.markDispatched(eq(10L),anyInt(),anyString(),any(),anyString())).thenReturn(1);when(stripe.execute(any())).thenReturn(new SubscriptionRecoveryStripeGateway.WriteResult("UNKNOWN",null,null,null));
        service.runDueAction(10L);verify(actions,never()).claim(anyLong(),anyInt(),anyString(),any(),any());verify(stripe,never()).execute(any());assertEquals("PENDING",root.getProtectionState());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(longs={360,3600,3601})
    void automaticSlotDispatchUsesItsFrozenTimeAndOneHourBoundary(long delaySeconds) {
        enable();properties.setAutomaticPayEnabled(true);var snap=snapshot();var pause=new Subscription.PauseCollection();pause.setBehavior("keep_as_draft");snap.subscription().setPauseCollection(pause);var current=new SubscriptionRecoveryStripeGateway.Snapshot(snap.invoice(),snap.subscription(),snap.schedule(),snap.lines(),snap.paymentIntent(),snap.charge(),now.plusSeconds(delaySeconds));var root=existingRoot();ownedFixture(root,current);root.setContractSnapshot("{\"price_id\":\"price_old\",\"quantity\":1,\"recovery_admission\":{\"contractHash\":\""+RecoveryContractClassifier.classify(snap.subscription(),null,true,"price_old",1L).contractHash()+"\"}}");
        var action=readyAction("RETRY_PAY","in_old");action.setScheduledAt(com.studyagent.common.datetime.DateTimeFormats.fromInstant(now));action.setNextRunAt(action.getScheduledAt());action.setRetrySlot(1);action.setPayloadJson("{\"off_session\":true}");when(actions.claim(eq(10L),anyInt(),anyString(),any(),any())).thenReturn(1);when(actions.markDispatched(eq(10L),anyInt(),anyString(),any(),anyString())).thenReturn(1);when(stripe.execute(any())).thenReturn(new SubscriptionRecoveryStripeGateway.WriteResult("UNKNOWN",null,null,null));
        service.runDueAction(10L);if(delaySeconds<=3600){verify(stripe,times(1)).execute(action);verify(actions,times(1)).claim(eq(10L),anyInt(),anyString(),any(),any());}else{assertEquals("SKIPPED_LATE",action.getSkipReason());verify(stripe,never()).execute(any());}assertEquals(com.studyagent.common.datetime.DateTimeFormats.fromInstant(now),action.getScheduledAt());
    }
    @Test void exitingReplacementAfterTheFixedSlotDoesNotCatchUpTheCoveredPayment() {
        enable();properties.setAutomaticPayEnabled(true);var snap=snapshot();var pause=new Subscription.PauseCollection();pause.setBehavior("keep_as_draft");snap.subscription().setPauseCollection(pause);var current=new SubscriptionRecoveryStripeGateway.Snapshot(snap.invoice(),snap.subscription(),snap.schedule(),snap.lines(),snap.paymentIntent(),snap.charge(),now.plusSeconds(120));var root=existingRoot();ownedFixture(root,current);root.setContractSnapshot("{\"price_id\":\"price_old\",\"quantity\":1}");
        var action=readyAction("RETRY_PAY","in_old");action.setScheduledAt(com.studyagent.common.datetime.DateTimeFormats.fromInstant(now));action.setNextRunAt(action.getScheduledAt());var replacement=new SubscriptionRecoveryReplacementEntity();replacement.setPausedAt(com.studyagent.common.datetime.DateTimeFormats.fromInstant(now.minusSeconds(30)));replacement.setClosedAt(com.studyagent.common.datetime.DateTimeFormats.fromInstant(now.plusSeconds(60)));replacement.setState("EXPIRED");when(replacements.selectByRecovery(1L)).thenReturn(List.of(replacement));
        service.runDueAction(10L);assertEquals("SKIPPED_REPLACEMENT",action.getSkipReason());verify(stripe,never()).execute(any());
    }
    @Test void staleUnpaidReadCannotEraseAlreadyCommittedPaidInvoiceFacts() {
        var root=confirmedPaidRoot(paidSnapshot());root.setStripeInvoiceStatus("paid");root.setAmountDueMinor(2900L);root.setAmountPaidMinor(2900L);root.setAmountRemainingMinor(0L);ownedFixture(root,snapshot());
        assertTrue(service.tryProcessEvent(event()));assertEquals("paid",root.getStripeInvoiceStatus());assertEquals(2900L,root.getAmountPaidMinor());assertEquals("GRANTED",root.getGrantState());
    }
    @Test void aStaleOpenDisputeCannotUndoTheSameDisputesConfirmedWin() {
        var root=confirmedPaidRoot(paidSnapshot());root.setLastDisputeId("dp_same");root.setDisputeState("WON");ownedFixture(root,paidSnapshot());injectQuota();
        var dispute=new Dispute();dispute.setId("dp_same");dispute.setStatus("needs_response");when(stripe.readDispute("dp_same")).thenReturn(dispute);when(stripe.readCharge("ch_old")).thenReturn(paidSnapshot().charge());
        var event=ApiResource.GSON.fromJson("{\"id\":\"evt_old_dispute\",\"type\":\"charge.dispute.created\",\"data\":{\"object\":{\"id\":\"dp_same\",\"invoice\":\"in_old\",\"charge\":\"ch_old\"}}}",Event.class);
        assertTrue(service.tryProcessEvent(event));assertEquals("WON",root.getDisputeState());assertEquals("GRANTED",root.getGrantState());
    }
    @Test void selectedHeldChildIsRereadWhenItsMissingPaidTimeBecomesAvailable() {
        var main=snapshot();var root=existingRoot();root.setStatus("PAID");root.setGrantState("HELD");root.setEntitlementInvoiceId("in_child");root.setContractSnapshot("{\"price_id\":\"price_old\",\"quantity\":1}");ownedFixture(root,main);var quota=injectQuota();
        var child=paidSnapshot();child.invoice().setId("in_child");child.charge().setInvoice("in_child");when(stripe.readInvoice("in_child")).thenReturn(child);when(stripe.listInvoices("sub_old")).thenReturn(List.of(main.invoice(),child.invoice()));
        var row=existingRoot();row.setId(2L);row.setOwnerRecoveryId(1L);row.setStripeInvoiceId("in_child");when(recoveries.selectByInvoice("in_child")).thenReturn(row);
        service.reconcile(1L);assertEquals("GRANTED",root.getGrantState());verify(quota).resetFromPaidInvoiceAt(eq("user"),eq("sub_old"),eq("pro_monthly"),eq(now),any(),eq("in_child"),any());
    }
    @Test void olderPartialRefundReadCannotEraseAnAlreadyConfirmedFullRefund() {
        var snap=paidSnapshot();var root=confirmedPaidRoot(snap);root.setStatus("CLOSING");root.setCloseReason("PAYMENT_REFUNDED");root.setGrantState("HELD");root.setRefundedAmountMinor(2900L);root.setRefundState("FULL");ownedFixture(root,snap);injectQuota();
        var oldCharge=new Charge();oldCharge.setId("ch_old");oldCharge.setAmount(2900L);oldCharge.setAmountRefunded(100L);when(stripe.readCharge("ch_old")).thenReturn(oldCharge);
        var event=ApiResource.GSON.fromJson("{\"id\":\"evt_old_refund\",\"type\":\"charge.refunded\",\"data\":{\"object\":{\"id\":\"ch_old\",\"invoice\":\"in_old\"}}}",Event.class);
        assertTrue(service.tryProcessEvent(event));assertEquals(2900L,root.getRefundedAmountMinor());assertEquals("HELD",root.getGrantState());assertEquals("CLOSING",root.getStatus());
    }
    @Test void unknownPassCancellationCannotUseTheMainSubscriptionsCanceledState() {
        var snap=snapshot();snap.subscription().setStatus("canceled");snap.invoice().setStatus("void");var root=existingRoot();ownedFixture(root,snap);injectQuota();
        var action=readyAction("CANCEL_SUBSCRIPTION","sub_pass");action.setStatus("UNKNOWN");action.setFirstDispatchedAt(com.studyagent.common.datetime.DateTimeFormats.now().minusSeconds(60));var pass=new Subscription();pass.setId("sub_pass");pass.setStatus("active");when(stripe.readSubscription("sub_pass")).thenReturn(pass);
        service.runDueAction(10L);assertEquals("UNKNOWN",action.getStatus());verify(stripe).readSubscription("sub_pass");
    }
    @Test void olderMethodChoiceCannotDispatchAfterTheCustomerSelectedAnotherCard() {
        var root=existingRoot();ownedFixture(root,snapshot());var action=methodAction("pm_old");
        var customer=new Customer();customer.setId("cus_old");var defaults=new Customer.InvoiceSettings();defaults.setDefaultPaymentMethod("pm_new");customer.setInvoiceSettings(defaults);when(stripe.readCustomer("cus_old")).thenReturn(customer);
        service.runDueAction(10L);assertEquals("SKIPPED",action.getStatus());verify(stripe,never()).execute(any());
    }
    @Test void methodWriteCannotTouchAnOldSubscriptionAfterReplacementBecameCurrent() {
        var root=existingRoot();ownedFixture(root,snapshot());var action=methodAction("pm_new");users.selectByUserForUpdate("user").setStripeSubscriptionId("sub_new");
        service.runDueAction(10L);assertEquals("SKIPPED",action.getStatus());verify(stripe,never()).execute(any());
    }
    @Test void aNewMethodWriteWaitsForAnOlderUnknownWriteToReconcile() {
        var root=existingRoot();ownedFixture(root,snapshot());var action=methodAction("pm_new");var older=new SubscriptionRecoveryActionEntity();older.setId(9L);older.setActionType("UPDATE_METHOD");older.setStatus("UNKNOWN");when(actions.selectByRecovery(1L)).thenReturn(List.of(older,action));
        var customer=new Customer();customer.setId("cus_old");var defaults=new Customer.InvoiceSettings();defaults.setDefaultPaymentMethod("pm_new");customer.setInvoiceSettings(defaults);when(stripe.readCustomer("cus_old")).thenReturn(customer);
        service.runDueAction(10L);verify(actions,never()).claim(anyLong(),anyInt(),anyString(),any(),any());verify(stripe,never()).execute(any());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"SUCCEEDED","SKIPPED"})
    void observedDriftAfterATerminalMethodWriteCreatesOneRepairIntent(String previousStatus) {
        var snap=snapshot();var pause=new Subscription.PauseCollection();pause.setBehavior("keep_as_draft");snap.subscription().setPauseCollection(pause);snap.subscription().setDefaultPaymentMethod("pm_old");snap.invoice().setDefaultPaymentMethod("pm_new");var root=existingRoot();ownedFixture(root,snap);
        var customer=new Customer();customer.setId("cus_old");var defaults=new Customer.InvoiceSettings();defaults.setDefaultPaymentMethod("pm_new");customer.setInvoiceSettings(defaults);when(stripe.readCustomer("cus_old")).thenReturn(customer);var method=new PaymentMethod();method.setCustomer("cus_old");when(stripe.readPaymentMethod("pm_new")).thenReturn(method);
        var succeeded=new SubscriptionRecoveryActionEntity();succeeded.setId(9L);succeeded.setActionType("UPDATE_METHOD");succeeded.setTargetId("sub_old");succeeded.setStatus(previousStatus);succeeded.setActionKey("recovery:1:method:subscription:pm_new");succeeded.setPayloadJson("{\"resource\":\"subscription\",\"default_payment_method\":\"pm_new\"}");when(actions.selectByKey(succeeded.getActionKey())).thenReturn(succeeded);when(actions.selectByRecovery(1L)).thenReturn(List.of(succeeded));
        service.reconcile(1L);var capture=org.mockito.ArgumentCaptor.forClass(SubscriptionRecoveryActionEntity.class);verify(actions).insert(capture.capture());assertTrue(capture.getValue().getActionKey().contains(":repair:"));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"READY","FAILED"})
    void aNewDefaultCardSupersedesOnlyKnownUnsentOrRejectedMethodWrites(String previousStatus) {
        var snap=snapshot();var pause=new Subscription.PauseCollection();pause.setBehavior("keep_as_draft");snap.subscription().setPauseCollection(pause);snap.subscription().setDefaultPaymentMethod("pm_old");snap.invoice().setDefaultPaymentMethod("pm_new");var root=existingRoot();ownedFixture(root,snap);
        var customer=new Customer();customer.setId("cus_old");var defaults=new Customer.InvoiceSettings();defaults.setDefaultPaymentMethod("pm_new");customer.setInvoiceSettings(defaults);when(stripe.readCustomer("cus_old")).thenReturn(customer);var method=new PaymentMethod();method.setCustomer("cus_old");when(stripe.readPaymentMethod("pm_new")).thenReturn(method);
        var previous=new SubscriptionRecoveryActionEntity();previous.setId(9L);previous.setActionType("UPDATE_METHOD");previous.setTargetId("sub_old");previous.setStatus(previousStatus);previous.setPayloadJson("{\"resource\":\"subscription\",\"default_payment_method\":\"pm_obsolete\"}");when(actions.selectByRecovery(1L)).thenReturn(List.of(previous));
        service.reconcile(1L);assertEquals("SKIPPED",previous.getStatus());assertEquals("SKIPPED_SUPERSEDED",previous.getSkipReason());verify(actions).updateById(previous);verify(actions).insert(any(SubscriptionRecoveryActionEntity.class));
    }
    @Test void scheduledSubscriptionCardReviewPersistsAHoldThatBlocksAutomaticPayment() {
        enable();properties.setAutomaticPayEnabled(true);var snap=snapshot();var pause=new Subscription.PauseCollection();pause.setBehavior("keep_as_draft");snap.subscription().setPauseCollection(pause);snap.subscription().setSchedule("sched_old");snap.subscription().setDefaultPaymentMethod("pm_old");snap.invoice().setDefaultPaymentMethod("pm_old");var schedule=ApiResource.GSON.fromJson("{\"id\":\"sched_old\",\"subscription\":\"sub_old\",\"status\":\"active\",\"end_behavior\":\"release\",\"default_settings\":{\"collection_method\":\"charge_automatically\"},\"current_phase\":{\"start_date\":1794362400,\"end_date\":1796954400},\"phases\":[{\"start_date\":1794362400,\"end_date\":1796954400,\"items\":[{\"price\":\"price_old\",\"quantity\":1}]}]}",SubscriptionSchedule.class);var current=new SubscriptionRecoveryStripeGateway.Snapshot(snap.invoice(),snap.subscription(),schedule,snap.lines(),snap.paymentIntent(),snap.charge(),now);var root=existingRoot();ownedFixture(root,current);root.setContractSnapshot("{\"price_id\":\"price_old\",\"quantity\":1,\"recovery_admission\":{\"contractHash\":\""+RecoveryContractClassifier.classify(snap.subscription(),schedule,true,"price_old",1L).contractHash()+"\"}}");
        var customer=new Customer();customer.setId("cus_old");var defaults=new Customer.InvoiceSettings();defaults.setDefaultPaymentMethod("pm_new");customer.setInvoiceSettings(defaults);when(stripe.readCustomer("cus_old")).thenReturn(customer);var method=new PaymentMethod();method.setCustomer("cus_old");when(stripe.readPaymentMethod("pm_new")).thenReturn(method);var ledger=failureLedger();
        service.reconcile(1L);var scheduleWrite=ledger.stream().filter(a->"UPDATE_METHOD".equals(a.getActionType()) && "sched_old".equals(a.getTargetId())).findFirst().orElseThrow();assertEquals("READY",scheduleWrite.getStatus());assertEquals(Map.of("resource","schedule","default_payment_method","pm_new"),SubscriptionRecoveryStripeGateway.thaw(scheduleWrite.getPayloadJson()));assertNotEquals("OPEN",root.getReviewState());
        var action=readyAction("RETRY_PAY","in_old");action.setScheduledAt(com.studyagent.common.datetime.DateTimeFormats.fromInstant(now));action.setNextRunAt(action.getScheduledAt());service.runDueAction(10L);verify(stripe,never()).execute(any());verify(actions,never()).claim(anyLong(),anyInt(),anyString(),any(),any());
    }
    @Test void aFutureScheduleCardOverrideCannotBeIgnoredWhenCurrentInvoiceAndSubscriptionAlreadyMatch() {
        var snap=snapshot();snap.subscription().setSchedule("sched_old");snap.subscription().setDefaultPaymentMethod("pm_new");snap.invoice().setDefaultPaymentMethod("pm_new");var pause=new Subscription.PauseCollection();pause.setBehavior("keep_as_draft");snap.subscription().setPauseCollection(pause);var schedule=ApiResource.GSON.fromJson("{\"id\":\"sched_old\",\"subscription\":\"sub_old\",\"status\":\"active\",\"current_phase\":{\"start_date\":1794362400,\"end_date\":1796954400},\"phases\":[{\"start_date\":1794362400,\"end_date\":1796954400},{\"start_date\":1796954400,\"end_date\":1799546400,\"default_payment_method\":\"pm_obsolete\"}]}",SubscriptionSchedule.class);var current=new SubscriptionRecoveryStripeGateway.Snapshot(snap.invoice(),snap.subscription(),schedule,snap.lines(),snap.paymentIntent(),snap.charge(),now);var root=existingRoot();ownedFixture(root,current);
        var customer=new Customer();customer.setId("cus_old");var defaults=new Customer.InvoiceSettings();defaults.setDefaultPaymentMethod("pm_new");customer.setInvoiceSettings(defaults);when(stripe.readCustomer("cus_old")).thenReturn(customer);var method=new PaymentMethod();method.setCustomer("cus_old");when(stripe.readPaymentMethod("pm_new")).thenReturn(method);var ledger=failureLedger();service.reconcile(1L);
        assertTrue(ledger.stream().anyMatch(a->"UPDATE_METHOD".equals(a.getActionType()) && "REVIEW".equals(a.getStatus())));verify(stripe,never()).execute(any());
    }
    @Test void compatibleScheduleOnlyNeedsTheOpenInvoiceOverrideSynchronized() {
        var snap=compatibleScheduleMethodSnapshot();var root=existingRoot();ownedFixture(root,snap);confirmedCustomerMethod();var ledger=failureLedger();
        service.reconcile(1L);
        var methods=ledger.stream().filter(a->"UPDATE_METHOD".equals(a.getActionType())).toList();assertEquals(1,methods.size());
        var action=methods.get(0);assertEquals("READY",action.getStatus());assertEquals("in_old",action.getTargetId());
        assertEquals(Map.of("resource","invoice","default_payment_method","pm_new"),SubscriptionRecoveryStripeGateway.thaw(action.getPayloadJson()));
        assertNotEquals("OPEN",root.getReviewState());verify(stripe,never()).execute(any());
    }
    @Test void compatibleScheduleAllowsOnlyThePersistedInvoiceMethodWrite() {
        var snap=compatibleScheduleMethodSnapshot();var root=existingRoot();ownedFixture(root,snap);confirmedCustomerMethod();
        var action=methodAction("pm_new");action.setTargetId("in_old");action.setPayloadJson("{\"resource\":\"invoice\",\"default_payment_method\":\"pm_new\"}");
        when(actions.selectByRecovery(1L)).thenReturn(List.of(action));service.runDueAction(10L);
        verify(stripe).execute(action);assertEquals("in_old",action.getTargetId());
    }
    @Test void compatibleScheduleResolvesPersistedScheduleMethodReview() {
        var snap=compatibleScheduleMethodSnapshot();var root=existingRoot();ownedFixture(root,snap);confirmedCustomerMethod();
        root.setReviewState("OPEN");root.setReviewReason("PAYMENT_METHOD_SCHEDULE_SYNC_REQUIRES_REVIEW");
        var hold=new SubscriptionRecoveryActionEntity();hold.setId(9L);hold.setActionType("UPDATE_METHOD");hold.setTargetId("sub_old");hold.setStatus("REVIEW");hold.setLastError("PAYMENT_METHOD_SCHEDULE_SYNC_REQUIRES_REVIEW");hold.setPayloadJson("{\"resource\":\"subscription\",\"default_payment_method\":\"pm_new\"}");
        when(actions.selectByRecovery(1L)).thenReturn(List.of(hold));
        service.reconcile(1L);
        assertEquals("SUPERSEDED",hold.getStatus());assertNull(hold.getLastError());assertEquals("NONE",root.getReviewState());assertNull(root.getReviewReason());
    }
    @Test void invoiceMethodWriteStillStopsWhenTheFutureScheduleMethodConflicts() {
        var snap=compatibleScheduleMethodSnapshot();snap.schedule().getDefaultSettings().setDefaultPaymentMethod("pm_future_override");
        var root=existingRoot();ownedFixture(root,snap);confirmedCustomerMethod();var action=methodAction("pm_new");action.setTargetId("in_old");
        action.setPayloadJson("{\"resource\":\"invoice\",\"default_payment_method\":\"pm_new\"}");when(actions.selectByRecovery(1L)).thenReturn(List.of(action));
        service.runDueAction(10L);verify(stripe,never()).execute(any());assertEquals("READY",action.getStatus());assertNull(root.getReviewReason());
    }
    private void confirmedCustomerMethod(){
        var customer=new Customer();customer.setId("cus_old");var defaults=new Customer.InvoiceSettings();defaults.setDefaultPaymentMethod("pm_new");customer.setInvoiceSettings(defaults);
        when(stripe.readCustomer("cus_old")).thenReturn(customer);var method=new PaymentMethod();method.setCustomer("cus_old");when(stripe.readPaymentMethod("pm_new")).thenReturn(method);
    }
    private SubscriptionRecoveryStripeGateway.Snapshot compatibleScheduleMethodSnapshot(){
        var snap=snapshot();snap.subscription().setSchedule("sched_old");snap.subscription().setDefaultPaymentMethod("pm_new");snap.invoice().setDefaultPaymentMethod(null);
        var pause=new Subscription.PauseCollection();pause.setBehavior("keep_as_draft");snap.subscription().setPauseCollection(pause);
        var schedule=ApiResource.GSON.fromJson("""
            {"id":"sched_old","subscription":"sub_old","status":"active","end_behavior":"release",
             "default_settings":{"collection_method":"charge_automatically","default_payment_method":"pm_new"},
             "current_phase":{"start_date":1794362400,"end_date":1796954400},
             "phases":[{"start_date":1794362400,"end_date":1796954400,"items":[{"price":"price_old","quantity":1}]}]}
            """,SubscriptionSchedule.class);
        return new SubscriptionRecoveryStripeGateway.Snapshot(snap.invoice(),snap.subscription(),schedule,snap.lines(),snap.paymentIntent(),snap.charge(),now);
    }
    private SubscriptionRecoveryActionEntity methodAction(String method){
        var action=readyAction("UPDATE_METHOD","sub_old");action.setPayloadJson("{\"resource\":\"subscription\",\"default_payment_method\":\""+method+"\"}");
        when(actions.claim(eq(10L),anyInt(),anyString(),any(),any())).thenReturn(1);when(actions.markDispatched(eq(10L),anyInt(),anyString(),any(),anyString())).thenReturn(1);when(stripe.execute(any())).thenReturn(new SubscriptionRecoveryStripeGateway.WriteResult("UNKNOWN",null,null,null));return action;
    }
    @Test void paidChildFoundByPollingWinsBeforeDeadlineCanCloseTheUnpaidMainInvoice() {
        var main=snapshot();main.subscription().setStatus("canceled");var root=existingRoot();root.setDeadlineAt(com.studyagent.common.datetime.DateTimeFormats.fromInstant(now.minusSeconds(1)));root.setFirstFailedAt(com.studyagent.common.datetime.DateTimeFormats.fromInstant(now.minusSeconds(30*86400)));root.setContractSnapshot("{\"price_id\":\"price_old\",\"quantity\":1}");ownedFixture(root,main);var quota=injectQuota();
        var child=paidSnapshot();child.invoice().setId("in_child");child.charge().setInvoice("in_child");when(stripe.listInvoices("sub_old")).thenReturn(List.of(main.invoice(),child.invoice()));when(stripe.readInvoice("in_child")).thenReturn(child);
        var row=existingRoot();row.setId(2L);row.setOwnerRecoveryId(1L);row.setStripeInvoiceId("in_child");when(recoveries.selectByInvoice("in_child")).thenReturn(row);when(recoveries.selectForUpdate(2L)).thenReturn(row);
        service.reconcile(1L);assertEquals("PAID",root.getStatus());assertEquals("GRANTED",root.getGrantState());assertEquals("in_child",root.getEntitlementInvoiceId());assertNull(root.getAccountClosedAt());verify(quota,never()).clearPlanQuotaAt(anyString(),anyString(),anyString(),anyString(),any());
    }
    @Test void unverifiedExtraMainPaymentCannotFreezeAnAlreadyGrantedChildSource() {
        var snap=paidSnapshot();snap.invoice().setPaidOutOfBand(true);var root=existingRoot();root.setStatus("PAID");root.setGrantState("GRANTED");root.setEntitlementInvoiceId("in_child");ownedFixture(root,snap);stubGrantedChildSource(root);
        assertTrue(service.tryProcessEvent(event()));assertEquals("GRANTED",root.getGrantState());assertEquals("in_child",root.getEntitlementInvoiceId());
    }
    @Test void fullyRefundedReplacementCashIsHeldBeforeTheNewPlanCanGrant() {
        var order=replacementPaymentFixture(2900L,"cus_old");var snap=stripe.readInvoice("in_new");snap.charge().setAmountRefunded(2900L);snap.charge().setRefunded(true);var quota=injectQuota();
        assertTrue(service.tryProcessEvent(replacementPaidEvent()));assertEquals("refunded",order.getStatus());verify(quota,never()).resetFromPaidInvoiceAt(anyString(),anyString(),anyString(),any(),any(),anyString(),any());
    }
    @Test void disputeArrivingBeforeTheChildRecordNeverGrantsQuotaEvenTransiently() {
        var root=existingRoot();root.setContractSnapshot("{\"price_id\":\"price_old\",\"quantity\":1}");ownedFixture(root,snapshot());var quota=injectQuota();
        var paid=paidSnapshot();paid.invoice().setId("in_child");paid.charge().setInvoice("in_child");when(stripe.readInvoice("in_child")).thenReturn(paid);when(stripe.readCharge("ch_old")).thenReturn(paid.charge());
        var dispute=new Dispute();dispute.setId("dp_child");dispute.setStatus("needs_response");when(stripe.readDispute("dp_child")).thenReturn(dispute);
        when(recoveries.selectBySubscription("sub_old",0L,100)).thenReturn(List.of(root));when(recoveries.selectBySubscription("sub_old",1L,100)).thenReturn(List.of());
        var stored=new java.util.concurrent.atomic.AtomicReference<SubscriptionPaymentRecoveryEntity>();when(recoveries.selectByInvoice("in_child")).thenAnswer(i->stored.get());when(recoveries.selectForUpdate(2L)).thenAnswer(i->stored.get());when(recoveries.selectById(2L)).thenAnswer(i->stored.get());
        when(recoveries.insert(any(SubscriptionPaymentRecoveryEntity.class))).thenAnswer(i->{var child=(SubscriptionPaymentRecoveryEntity)i.getArgument(0);child.setId(2L);stored.set(child);return 1;});
        var event=ApiResource.GSON.fromJson("{\"id\":\"evt_dispute_child\",\"type\":\"charge.dispute.created\",\"data\":{\"object\":{\"id\":\"dp_child\",\"invoice\":\"in_child\",\"charge\":\"ch_old\"}}}",Event.class);
        assertTrue(service.tryProcessEvent(event));assertEquals("HELD",root.getGrantState());verify(quota,never()).resetFromPaidInvoiceAt(anyString(),anyString(),anyString(),any(),any(),anyString(),any());
    }
    @Test void disputedCashBeforeAnyPaymentWebhookNeverCreatesAnEntitlementGrant() {
        enable();var snap=paidSnapshot();snap.charge().setDisputed(true);var root=existingRoot();root.setContractSnapshot("{\"price_id\":\"price_old\",\"quantity\":1}");ownedFixture(root,snap);var quota=injectQuota();
        assertTrue(service.tryProcessEvent(event()));assertEquals("HELD",root.getGrantState());verifyNoInteractions(quota);
    }
    @Test void unknownChildProtectionReadsItsOwnTargetInsteadOfTheMainInvoice() {
        var main=snapshot();var root=existingRoot();ownedFixture(root,main);var action=readyAction("DISABLE_ADVANCE","in_child");action.setStatus("UNKNOWN");action.setFirstDispatchedAt(com.studyagent.common.datetime.DateTimeFormats.now().minusSeconds(60));
        var child=snapshot();child.invoice().setId("in_child");child.invoice().setAutoAdvance(false);when(stripe.readInvoice("in_child")).thenReturn(child);
        service.runDueAction(10L);assertEquals("SUCCEEDED",action.getStatus());verify(stripe).readInvoice("in_child");
    }
    @Test void unknownAtomicShiftCannotConfirmWhileCollectionIsStillPaused() {
        var snap=paidSnapshot();var root=confirmedPaidRoot(snap);ownedFixture(root,snap);var pause=new Subscription.PauseCollection();pause.setBehavior("keep_as_draft");snap.subscription().setPauseCollection(pause);
        var action=readyAction("SHIFT_RENEWAL","sub_old");action.setStatus("UNKNOWN");action.setFirstDispatchedAt(com.studyagent.common.datetime.DateTimeFormats.now().minusSeconds(60));
        service.runDueAction(10L);assertEquals("UNKNOWN",action.getStatus());
    }
    @Test void genuinelyUnadmittedNextCycleDetachesExpiredPaidPointerBeforeLegacyHandling() {
        var root=existingRoot();root.setStatus("PAID");root.setGrantState("GRANTED");root.setEntitlementInvoiceId("in_old");root.setEntitlementEndAt(com.studyagent.common.datetime.DateTimeFormats.fromInstant(now));
        var snap=snapshot();snap.invoice().setId("in_next");snap.lines().get(0).getPeriod().setStart(now.getEpochSecond());
        ownedFixture(root,snap);var user=users.selectByUserForUpdate("user");when(users.selectByStripeSubscriptionId("sub_old")).thenReturn(user);when(stripe.readInvoice("in_next")).thenReturn(snap);
        var event=ApiResource.GSON.fromJson("{\"id\":\"evt_next\",\"type\":\"invoice.payment_failed\",\"data\":{\"object\":{\"id\":\"in_next\",\"subscription\":\"sub_old\"}}}",Event.class);
        assertFalse(service.tryProcessEvent(event));assertNull(user.getPaymentRecoveryId());assertEquals("GRANTED",root.getGrantState());
    }
    @Test void notificationCompletionKeepsAliasesEnrichedWhileTheExternalAdapterWasRunning() {
        var root=existingRoot();ownedFixture(root,snapshot());var action=readyAction("CAPTURE_FAILURE","in_old");action.setPayloadJson("{\"amount_minor\":2900}");action.setResultJson("{\"failure_event_ids\":[\"evt_first\"]}");var current=new java.util.concurrent.atomic.AtomicReference<SubscriptionRecoveryActionEntity>(action);when(actions.selectForUpdate(10L)).thenAnswer(i->current.get());when(actions.claim(eq(10L),anyInt(),anyString(),any(),any())).thenReturn(1);when(actions.markDispatched(eq(10L),anyInt(),anyString(),any(),anyString())).thenReturn(1);when(actions.complete(eq(10L),anyInt(),anyString(),anyString(),anyString(),any())).thenReturn(1);
        var analytics=mock(com.studyagent.common.analytics.AnalyticsService.class);org.springframework.test.util.ReflectionTestUtils.setField(service,"analytics",analytics);doAnswer(i->{var fresh=new SubscriptionRecoveryActionEntity();org.springframework.beans.BeanUtils.copyProperties(action,fresh);fresh.setResultJson("{\"failure_event_ids\":[\"evt_first\",\"evt_later\"],\"failure_charge_ids\":[\"ch_observed\"]}");current.set(fresh);return null;}).when(analytics).capture(anyString(),anyString(),anyMap());
        service.runDueAction(10L);var details=org.mockito.ArgumentCaptor.forClass(String.class);verify(actions).complete(eq(10L),anyInt(),anyString(),eq("UNKNOWN"),details.capture(),any());assertTrue(details.getValue().contains("evt_later"));assertTrue(details.getValue().contains("ch_observed"));
    }
    @Test void notificationAdapterReturningVoidIsNeverRecordedAsProviderAcknowledged() {
        var root=existingRoot();ownedFixture(root,snapshot());var action=readyAction("CAPTURE_FAILURE","in_old");action.setPayloadJson("{\"amount_minor\":2900}");
        var analytics=mock(com.studyagent.common.analytics.AnalyticsService.class);org.springframework.test.util.ReflectionTestUtils.setField(service,"analytics",analytics);
        when(actions.claim(eq(10L),anyInt(),anyString(),any(),any())).thenReturn(1);when(actions.markDispatched(eq(10L),anyInt(),anyString(),any(),anyString())).thenReturn(1);
        service.runDueAction(10L);verify(actions).complete(eq(10L),anyInt(),anyString(),eq("UNKNOWN"),anyString(),any());
    }
    @Test void confirmedReplacementWithPromotionRecordsActualCashAndRetainsTheConfirmedQuote() {
        var order=replacementPaymentFixture(2500L,"cus_old");
        assertTrue(service.tryProcessEvent(replacementPaidEvent()));assertEquals(2500,order.getPriceCents());assertEquals(2900,order.getQuotedAmountCents());
    }
    @Test void replacementPaymentFromAnotherCustomerCannotGrantTheNewPlan() {
        replacementPaymentFixture(2900L,"cus_other");var quota=injectQuota();
        assertThrows(IllegalStateException.class,()->service.tryProcessEvent(replacementPaidEvent()));verifyNoInteractions(quota);
    }
    @Test void lateReplacementReceiptCannotOverwriteAnUnrelatedCurrentSubscription() {
        var order=replacementPaymentFixture(2900L,"cus_old");var current=user();current.setStripeSubscriptionId("sub_other");current.setPlanCode("plus_monthly");current.setPaymentRecoveryId(null);when(users.selectByUserForUpdate("user")).thenReturn(current);var quota=injectQuota();
        assertTrue(service.tryProcessEvent(replacementPaidEvent()));assertEquals("sub_other",current.getStripeSubscriptionId());assertEquals("plus_monthly",current.getPlanCode());assertEquals("completed",order.getStatus());assertEquals("REPLACEMENT_RECEIPT_AFTER_OWNERSHIP_CHANGED",recoveries.selectById(1L).getReviewReason());verifyNoInteractions(quota);
    }
    @Test void closedRecoveryRetainsTheReplacementCashReceiptWithoutReopeningMembership() {
        var order=replacementPaymentFixture(2900L,"cus_old");var root=recoveries.selectById(1L);root.setAccountClosedAt(com.studyagent.common.datetime.DateTimeFormats.now());var quota=injectQuota();
        assertTrue(service.tryProcessEvent(replacementPaidEvent()));assertEquals("completed",order.getStatus());verifyNoInteractions(quota);assertEquals("REPLACEMENT_RECEIPT_AFTER_OWNERSHIP_CHANGED",root.getReviewReason());
    }
    @Test void aLateRefundedReplacementKeepsItsFinancialStatusAfterOwnershipChanged() {
        var order=replacementPaymentFixture(2900L,"cus_old");recoveries.selectById(1L).setAccountClosedAt(com.studyagent.common.datetime.DateTimeFormats.now());stripe.readInvoice("in_new").charge().setAmountRefunded(2900L);var quota=injectQuota();
        assertTrue(service.tryProcessEvent(replacementPaidEvent()));assertEquals("refunded",order.getStatus());verifyNoInteractions(quota);
    }
    @Test void replacementSubscriptionMetadataMustMatchTheReservedOrder() {
        replacementPaymentFixture(2900L,"cus_old");stripe.readInvoice("in_new").subscription().setMetadata(Map.of("order_no","other_order"));var quota=injectQuota();
        assertThrows(IllegalStateException.class,()->service.tryProcessEvent(replacementPaidEvent()));verifyNoInteractions(quota);
    }
    private Event replacementPaidEvent(){return ApiResource.GSON.fromJson("{\"id\":\"evt_replace_paid\",\"type\":\"checkout.session.completed\",\"data\":{\"object\":{\"id\":\"cs_new\",\"object\":\"checkout.session\"}}}",Event.class);}
    private RechargeOrderEntity replacementPaymentFixture(long actual,String paymentCustomer){
        var root=existingRoot();ownedFixture(root,snapshot());injectQuota();
        var rep=new SubscriptionRecoveryReplacementEntity();rep.setRecoveryId(1L);rep.setClerkUserId("user");rep.setOrderNo("order_new");rep.setState("OPEN");rep.setOldSubscriptionId("sub_old");rep.setCheckoutSessionId("cs_new");rep.setTargetPlanCode("pro_monthly");rep.setQuoteSnapshot("{\"line_items\":[{\"price\":\"price_old\",\"quantity\":1}],\"currency\":\"usd\"}");
        when(replacements.selectBySession("cs_new")).thenReturn(rep);when(replacements.selectByOrder("order_new")).thenReturn(rep);
        var session=new com.stripe.model.checkout.Session();session.setId("cs_new");session.setCustomer("cus_old");session.setSubscription("sub_new");session.setInvoice("in_new");session.setStatus("complete");session.setPaymentStatus("paid");session.setAmountSubtotal(2900L);session.setAmountTotal(actual);session.setCurrency("usd");session.setMetadata(Map.of("order_no","order_new","purchase_type","subscription_replacement","recovery_id","1","clerk_user_id","user","old_subscription_id","sub_old"));when(stripe.readCheckout("cs_new")).thenReturn(session);
        var paid=paidSnapshot();paid.invoice().setId("in_new");paid.invoice().setSubscription("sub_new");paid.invoice().setAmountPaid(actual);paid.invoice().setAmountDue(actual);paid.paymentIntent().setAmountReceived(actual);paid.paymentIntent().setCustomer(paymentCustomer);paid.charge().setAmount(actual);paid.charge().setAmountCaptured(actual);paid.charge().setInvoice("in_new");paid.subscription().setId("sub_new");paid.subscription().setMetadata(session.getMetadata());paid.subscription().setCurrentPeriodEnd(now.plusSeconds(30*86400).getEpochSecond());paid.lines().get(0).setSubscription("sub_new");when(stripe.readInvoice("in_new")).thenReturn(paid);
        var order=new RechargeOrderEntity();order.setOrderNo("order_new");order.setClerkUserId("user");order.setOrderType("subscription_replacement");order.setTargetPlanCode("pro_monthly");order.setPriceCents(2900);order.setQuotedAmountCents(2900);order.setCurrency("usd");when(orders.selectOne(any())).thenReturn(order);
        var plan=new SubscriptionPlanEntity();plan.setPlanCode("pro_monthly");plan.setTier("pro");plan.setBillingInterval("month");when(plans.selectOne(any())).thenReturn(plan);return order;
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"month","year"})
    void completedCancellationFromRenewalDoesNotRequireAnotherInvoiceOrRepeatTheWrite(String interval) {
        var f=paidPreferenceFixture(interval);var admitted=com.google.gson.JsonParser.parseString(f.root().getContractSnapshot()).getAsJsonObject().get("recovery_admission").deepCopy();
        when(stripe.preview("sub_old")).thenThrow(new IllegalStateException("invoice_upcoming_none"));
        when(stripe.execute(any())).thenAnswer(call->{
            f.snapshot().subscription().setCancelAtPeriodEnd(true);
            f.snapshot().subscription().setCancelAt(com.studyagent.common.datetime.DateTimeFormats.toEpochSecond(f.root().getEntitlementEndAt()));
            return new SubscriptionRecoveryStripeGateway.WriteResult("SUCCEEDED","req_cancel",null,f.snapshot().subscription());
        });
        assertDoesNotThrow(()->service.setCancellationPreference("user",1L,true));
        assertDoesNotThrow(()->service.setCancellationPreference("user",1L,true));
        assertDoesNotThrow(()->service.reconcile(1L));
        assertEquals(1,f.ledger().stream().filter(a->"SET_CANCEL_PREFERENCE".equals(a.getActionType())).count());
        assertEquals("CONFIRMED",f.root().getAnchorSyncState());assertEquals("RELEASED",f.root().getProtectionState());assertEquals("GRANTED",f.root().getGrantState());assertEquals("NONE",f.root().getReviewState());
        assertEquals(admitted,com.google.gson.JsonParser.parseString(f.root().getContractSnapshot()).getAsJsonObject().get("recovery_admission"));
        assertTrue(users.selectByUser("user").getCancelAtPeriodEnd());verify(stripe,times(1)).execute(any());verify(stripe,never()).preview(anyString());verifyNoInteractions(f.quota());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"READY","UNKNOWN","FOREIGN","MISMATCH","FIXED_DATE","PRICE","QUANTITY","CURRENCY","NEW_SCHEDULE"})
    void pendingOrConflictingCancellationCannotConfirmRenewalOrClearPaidRights(String condition) {
        var f=paidPreferenceFixture("year");var sub=f.snapshot().subscription();sub.setCancelAtPeriodEnd(true);sub.setCancelAt(com.studyagent.common.datetime.DateTimeFormats.toEpochSecond(f.root().getEntitlementEndAt()));
        var cancel=readyAction("SET_CANCEL_PREFERENCE","FOREIGN".equals(condition)?"sub_other":"sub_old");cancel.setActionKey("cancel_intent");cancel.setStatus(Set.of("READY","UNKNOWN").contains(condition)?condition:"SUCCEEDED");cancel.setPayloadJson("{\"cancel_at_period_end\":true}");f.ledger().add(cancel);
        var saved=com.google.gson.JsonParser.parseString(f.root().getContractSnapshot()).getAsJsonObject();saved.add("cancellation_intent",com.google.gson.JsonParser.parseString("{\"action_key\":\"cancel_intent\",\"requested\":true}"));f.root().setContractSnapshot(saved.toString());
        if("MISMATCH".equals(condition))sub.setCancelAt(sub.getCancelAt()-1);
        if("FIXED_DATE".equals(condition))sub.setCancelAtPeriodEnd(false);
        if("PRICE".equals(condition)){var price=ApiResource.GSON.fromJson(sub.getItems().getData().get(0).getPrice().toJson(),Price.class);price.setId("price_changed");sub.getItems().getData().get(0).setPrice(price);}
        if("QUANTITY".equals(condition))sub.getItems().getData().get(0).setQuantity(2L);
        if("CURRENCY".equals(condition))sub.setCurrency("sgd");
        if("NEW_SCHEDULE".equals(condition))sub.setSchedule("sched_changed");
        when(stripe.preview("sub_old")).thenThrow(new IllegalStateException("invoice_upcoming_none"));
        assertDoesNotThrow(()->service.reconcile(1L));
        if(!Set.of("READY","UNKNOWN").contains(condition)){assertEquals("REVIEW",f.root().getAnchorSyncState());assertNotEquals("NONE",f.root().getReviewState());}
        assertEquals("GRANTED",f.root().getGrantState());assertNotNull(f.root().getEntitlementEndAt());verify(stripe,never()).preview(anyString());verify(stripe,never()).execute(any());verifyNoInteractions(f.quota());
    }

    @Test void untrackedPeriodEndCancellationIsReviewedInsteadOfAssumingAnInvoiceWillExist() {
        var f=paidPreferenceFixture("month");f.snapshot().subscription().setCancelAtPeriodEnd(true);f.snapshot().subscription().setCancelAt(com.studyagent.common.datetime.DateTimeFormats.toEpochSecond(f.root().getEntitlementEndAt()));
        when(stripe.preview("sub_old")).thenThrow(new IllegalStateException("invoice_upcoming_none"));
        assertDoesNotThrow(()->service.reconcile(1L));assertEquals("REVIEW",f.root().getAnchorSyncState());assertEquals("GRANTED",f.root().getGrantState());verify(stripe,never()).preview(anyString());verifyNoInteractions(f.quota());
    }

    @Test void completedResumeOfAnAdmittedPeriodEndContractMustVerifyTheNextInvoice() {
        var f=paidPreferenceFixture("month");var saved=com.google.gson.JsonParser.parseString(f.root().getContractSnapshot()).getAsJsonObject();saved.getAsJsonObject("recovery_admission").addProperty("cancelMode","PERIOD_END");
        saved.add("cancellation_intent",com.google.gson.JsonParser.parseString("{\"action_key\":\"resume_intent\",\"requested\":false}"));f.root().setContractSnapshot(saved.toString());
        var resume=readyAction("SET_CANCEL_PREFERENCE","sub_old");resume.setActionKey("resume_intent");resume.setStatus("SUCCEEDED");resume.setPayloadJson("{\"cancel_at_period_end\":false}");f.ledger().add(resume);
        service.reconcile(1L);assertEquals("CONFIRMED",f.root().getAnchorSyncState());verify(stripe).preview("sub_old");verifyNoInteractions(f.quota());
    }

    @Test void anOppositePreferenceArrivingDuringPreviewCannotBeConfirmedByTheOldRead() {
        var f=paidPreferenceFixture("month");f.root().setAnchorSyncState("PENDING");var preview=stripe.preview("sub_old");clearInvocations(stripe);
        when(stripe.preview("sub_old")).thenAnswer(call->{
            var saved=com.google.gson.JsonParser.parseString(f.root().getContractSnapshot()).getAsJsonObject();saved.add("cancellation_intent",com.google.gson.JsonParser.parseString("{\"action_key\":\"new_cancel\",\"requested\":true}"));f.root().setContractSnapshot(saved.toString());
            return preview;
        });
        service.reconcile(1L);assertEquals("PENDING",f.root().getAnchorSyncState());assertEquals("GRANTED",f.root().getGrantState());verifyNoInteractions(f.quota());
    }

    @Test void aReadFailureAfterConfirmedCancellationDoesNotReportThatTheWriteFailed() {
        var f=paidPreferenceFixture("year");var reads=new java.util.concurrent.atomic.AtomicInteger();
        when(stripe.readInvoice("in_old")).thenAnswer(call->{
            if(reads.incrementAndGet()>=3)throw new PaymentRecoveryReadException("in_old",0,null,null,new java.net.SocketTimeoutException());
            return f.snapshot();
        });
        when(stripe.execute(any())).thenAnswer(call->{f.snapshot().subscription().setCancelAtPeriodEnd(true);f.snapshot().subscription().setCancelAt(com.studyagent.common.datetime.DateTimeFormats.toEpochSecond(f.root().getEntitlementEndAt()));return new SubscriptionRecoveryStripeGateway.WriteResult("SUCCEEDED","req_cancel",null,f.snapshot().subscription());});
        assertDoesNotThrow(()->service.setCancellationPreference("user",1L,true));
        var cancel=f.ledger().stream().filter(a->"SET_CANCEL_PREFERENCE".equals(a.getActionType())).findFirst().orElseThrow();
        assertEquals("SUCCEEDED",cancel.getStatus());assertNotNull(f.root().getNextReconcileAt());assertEquals("GRANTED",f.root().getGrantState());verify(stripe,times(1)).execute(any());verifyNoInteractions(f.quota());
    }

    @Test void aReadFailureBeforeCancellationCannotCreateOrDispatchAnIntent() {
        var f=paidPreferenceFixture("year");var failure=new PaymentRecoveryReadException("in_old",0,null,null,new java.net.SocketTimeoutException());
        when(stripe.readInvoice("in_old")).thenThrow(failure);
        assertSame(failure,assertThrows(PaymentRecoveryReadException.class,()->service.setCancellationPreference("user",1L,true)));
        assertTrue(f.ledger().stream().noneMatch(a->"SET_CANCEL_PREFERENCE".equals(a.getActionType())));assertEquals("GRANTED",f.root().getGrantState());verify(stripe,never()).execute(any());verifyNoInteractions(f.quota());
    }

    private record PaidPreferenceFixture(SubscriptionPaymentRecoveryEntity root,SubscriptionRecoveryStripeGateway.Snapshot snapshot,List<SubscriptionRecoveryActionEntity> ledger,BillingQuotaGateway quota) {}
    private PaidPreferenceFixture paidPreferenceFixture(String interval) {
        var snap=paidSnapshot();var root=confirmedPaidRoot(snap);root.setBillingInterval(interval);root.setPlanCode("year".equals(interval)?"pro_yearly":"pro_monthly");
        var end=SubscriptionRecoveryPolicy.paidPeriodEnd(now,interval,1);root.setEntitlementEndAt(com.studyagent.common.datetime.DateTimeFormats.fromInstant(end));root.setAnchorSyncState("CONFIRMED");root.setProtectionState("RELEASED");root.setReviewState("NONE");
        snap.lines().get(0).getPrice().getRecurring().setInterval(interval);snap.lines().get(0).getPeriod().setEnd(end.getEpochSecond());snap.subscription().setCurrentPeriodEnd(end.getEpochSecond());snap.subscription().setTrialEnd(end.getEpochSecond());snap.subscription().setCancelAtPeriodEnd(false);
        ownedFixture(root,snap);users.selectByUser("user").setPlanCode(root.getPlanCode());stubConfirmedShift(root,snap,2900L);
        var shift=actions.selectByRecovery(1L).get(0);var ledger=failureLedger();ledger.add(shift);bindSequenceActions(ledger);
        return new PaidPreferenceFixture(root,snap,ledger,injectQuota());
    }

    @Test void cancellationIntentIsDurableAndDoesNotClearThePaidPeriod() {
        var snap=paidSnapshot();var root=confirmedPaidRoot(snap);ownedFixture(root,snap);
        when(actions.insert(any(SubscriptionRecoveryActionEntity.class))).thenAnswer(i->{((SubscriptionRecoveryActionEntity)i.getArgument(0)).setId(99L);return 1;});
        assertEquals("PAYMENT_PROCESSING",assertThrows(BillingDomainException.class,()->service.setCancellationPreference("user",1L,true)).getCode());
        var capture=org.mockito.ArgumentCaptor.forClass(SubscriptionRecoveryActionEntity.class);verify(actions).insert(capture.capture());
        assertEquals("SET_CANCEL_PREFERENCE",capture.getValue().getActionType());assertTrue(capture.getValue().getPayloadJson().contains("cancel_at_period_end"));
        assertEquals("GRANTED",root.getGrantState());assertNotNull(root.getEntitlementEndAt());verify(stripe,never()).execute(any());
    }
    @Test void resumeWhileAnOlderCancellationIsInFlightMustPersistTheOppositeIntent() {
        var snap=snapshot();var root=existingRoot();root.setContractSnapshot("{\"cancellation_intent\":{\"action_key\":\"old_cancel\",\"requested\":true}}");ownedFixture(root,snap);
        var previous=new SubscriptionRecoveryActionEntity();previous.setId(98L);previous.setActionKey("old_cancel");previous.setActionType("SET_CANCEL_PREFERENCE");previous.setStatus("DISPATCHING");when(actions.selectByKey("old_cancel")).thenReturn(previous);when(actions.selectByRecovery(1L)).thenReturn(List.of(previous));
        when(actions.insert(any(SubscriptionRecoveryActionEntity.class))).thenAnswer(i->{((SubscriptionRecoveryActionEntity)i.getArgument(0)).setId(99L);return 1;});
        assertEquals("PAYMENT_PROCESSING",assertThrows(BillingDomainException.class,()->service.setCancellationPreference("user",1L,false)).getCode());
        var capture=org.mockito.ArgumentCaptor.forClass(SubscriptionRecoveryActionEntity.class);verify(actions).insert(capture.capture());assertTrue(capture.getValue().getPayloadJson().contains("false"));
    }
    @Test void supersededCancellationIntentNeverDispatchesAnOlderPreference() {
        var snap=snapshot();var root=existingRoot();root.setContractSnapshot("{\"cancellation_intent\":{\"action_key\":\"new_intent\"}}");ownedFixture(root,snap);
        var action=readyAction("SET_CANCEL_PREFERENCE","sub_old");action.setActionKey("old_intent");action.setPayloadJson("{\"cancel_at_period_end\":true}");
        service.runDueAction(10L);assertEquals("SKIPPED",action.getStatus());verify(stripe,never()).execute(any());
    }
    @Test void fullyRefundedCashObservedBeforePaidEventNeverGrantsQuota() {
        enable();var snap=paidSnapshot();snap.charge().setAmountRefunded(2900L);snap.charge().setRefunded(true);
        var root=existingRoot();root.setGrantState("NONE");root.setContractSnapshot("{\"price_id\":\"price_old\",\"quantity\":1}");ownedFixture(root,snap);var quota=injectQuota();
        assertTrue(service.tryProcessEvent(event()));assertEquals("HELD",root.getGrantState());assertEquals("FULL",root.getRefundState());verifyNoInteractions(quota);
    }
    @Test void confirmedCardUpdateQueuesTargetUpdatesButNeverImmediatePayment() {
        var snap=snapshot();var pause=new Subscription.PauseCollection();pause.setBehavior("keep_as_draft");snap.subscription().setPauseCollection(pause);snap.subscription().setDefaultPaymentMethod("pm_old");snap.invoice().setDefaultPaymentMethod("pm_old");var root=existingRoot();ownedFixture(root,snap);
        var customer=new Customer();customer.setId("cus_old");var defaults=new Customer.InvoiceSettings();defaults.setDefaultPaymentMethod("pm_new");customer.setInvoiceSettings(defaults);when(stripe.readCustomer("cus_old")).thenReturn(customer);
        var method=new PaymentMethod();method.setId("pm_new");method.setCustomer("cus_old");when(stripe.readPaymentMethod("pm_new")).thenReturn(method);
        service.reconcile(1L);var capture=org.mockito.ArgumentCaptor.forClass(SubscriptionRecoveryActionEntity.class);verify(actions,atLeastOnce()).insert(capture.capture());
        assertEquals(2,capture.getAllValues().stream().filter(a->"UPDATE_METHOD".equals(a.getActionType())).count());assertTrue(capture.getAllValues().stream().noneMatch(a->"RETRY_PAY".equals(a.getActionType())));verify(stripe,never()).execute(any());
    }
    @Test void aLateEarlierFailureNeverBorrowsTheLatestChargeOrItsAutomaticAttribution() {
        var snap=snapshot();snap.charge().setId("ch_d7");snap.paymentIntent().setLatestCharge("ch_d7");
        var root=existingRoot();ownedFixture(root,snap);var ledger=failureLedger();
        var retry=readyAction("RETRY_PAY","in_old");retry.setResultJson("{\"provider_state\":\"DECLINED\",\"charge_id\":\"ch_d7\"}");ledger.add(retry);
        assertTrue(service.tryProcessEvent(failureEvent("evt_d7","ch_d7")));
        var original=ApiResource.GSON.fromJson("{\"object\":\"charge\",\"id\":\"ch_d0\",\"customer\":\"cus_old\",\"invoice\":\"in_old\",\"payment_intent\":\"pi_old\",\"status\":\"failed\"}",Charge.class);
        when(stripe.readCharge("ch_d0")).thenReturn(original);
        assertTrue(service.tryProcessEvent(failureEvent("evt_d0","ch_d0")));
        assertEquals(4,failureChannelCount(ledger));
        var earlier=ledger.stream().filter(a->"CAPTURE_FAILURE".equals(a.getActionType())&&a.getPayloadJson().contains("ch_d0")).findFirst().orElseThrow();
        assertTrue(earlier.getPayloadJson().contains("UNKNOWN"));assertFalse(earlier.getResultJson().contains("evt_d7"));assertFalse(earlier.getResultJson().contains("failure_action_ids"));
    }
    @Test void aFailureWithoutAnExplicitChargeDoesNotBorrowTheCurrentAttempt() {
        var root=existingRoot();ownedFixture(root,snapshot());var ledger=failureLedger();assertTrue(service.tryProcessEvent(event()));
        var captured=ledger.stream().filter(a->"CAPTURE_FAILURE".equals(a.getActionType())).findFirst().orElseThrow();
        assertFalse(captured.getResultJson().contains("ch_old"));assertTrue(captured.getPayloadJson().contains("UNKNOWN"));
        assertFalse(com.google.gson.JsonParser.parseString(captured.getPayloadJson()).getAsJsonObject().get("actual_attempt").getAsBoolean());
    }
    private Event failureEvent(String eventId,String chargeId){
        return ApiResource.GSON.fromJson("{\"id\":\""+eventId+"\",\"type\":\"invoice.payment_failed\",\"created\":1794362400,\"data\":{\"object\":{\"id\":\"in_old\",\"object\":\"invoice\",\"subscription\":\"sub_old\",\"charge\":\""+chargeId+"\"}}}",Event.class);
    }
    @Test void failureEvidenceRetainsItsFirstKeyWhenTheSameEventLaterObtainsACharge() {
        var snap=snapshot();var root=existingRoot();snap.paymentIntent().setLatestCharge(null);var withoutCharge=new SubscriptionRecoveryStripeGateway.Snapshot(snap.invoice(),snap.subscription(),snap.schedule(),snap.lines(),snap.paymentIntent(),null,now);ownedFixture(root,withoutCharge);var ledger=failureLedger();
        assertTrue(service.tryProcessEvent(event()));assertEquals(2,failureChannelCount(ledger));String firstKey=ledger.get(0).getActionKey();
        snap.charge().setId("ch_later");when(stripe.readInvoice("in_old")).thenReturn(snap);assertTrue(service.tryProcessEvent(failureEvent("evt_failed","ch_later")));assertEquals(2,failureChannelCount(ledger));assertEquals(firstKey,ledger.get(0).getActionKey());assertTrue(ledger.get(0).getResultJson().contains("ch_later"));
    }
    @Test void authenticationRequiredIsTrackedWithoutCountingANewActualCollectionAttempt() {
        var root=existingRoot();ownedFixture(root,snapshot());var ledger=failureLedger();var event=event();event.setType("invoice.payment_action_required");assertTrue(service.tryProcessEvent(event));
        var evidence=ledger.stream().filter(a->"CAPTURE_FAILURE".equals(a.getActionType())).findFirst().orElseThrow();var payload=com.google.gson.JsonParser.parseString(evidence.getPayloadJson()).getAsJsonObject();assertTrue(payload.get("requires_action").getAsBoolean());assertFalse(payload.get("actual_attempt").getAsBoolean());
    }
    @Test void twoDifferentChargeFailuresOnTheSamePaymentIntentRemainSeparateAttempts() {
        var snap=snapshot();var root=existingRoot();ownedFixture(root,snap);var ledger=failureLedger();assertTrue(service.tryProcessEvent(failureEvent("evt_failed","ch_old")));
        snap.charge().setId("ch_second");snap.paymentIntent().setLatestCharge("ch_second");var second=failureEvent("evt_second","ch_second");assertTrue(service.tryProcessEvent(second));assertEquals(4,failureChannelCount(ledger));
    }
    @Test void aConfirmedApiDeclineRecordsItsFailureBeforeAnyWebhookAndMergesTheLaterCallback() {
        enable();properties.setAutomaticPayEnabled(true);var snap=snapshot();var pause=new Subscription.PauseCollection();pause.setBehavior("keep_as_draft");snap.subscription().setPauseCollection(pause);var root=existingRoot();ownedFixture(root,snap);root.setContractSnapshot("{\"price_id\":\"price_old\",\"quantity\":1,\"recovery_admission\":{\"contractHash\":\""+RecoveryContractClassifier.classify(snap.subscription(),null,true,"price_old",1L).contractHash()+"\"}}");
        var action=readyAction("RETRY_PAY","in_old");action.setScheduledAt(com.studyagent.common.datetime.DateTimeFormats.fromInstant(now));action.setNextRunAt(action.getScheduledAt());action.setRetrySlot(1);var ledger=failureLedger();ledger.add(action);
        when(actions.claim(eq(10L),anyInt(),anyString(),any(),any())).thenReturn(1);when(actions.markDispatched(eq(10L),anyInt(),anyString(),any(),anyString())).thenReturn(1);when(actions.complete(eq(10L),anyInt(),anyString(),anyString(),anyString(),any())).thenReturn(1);
        var decline=ApiResource.GSON.fromJson("{\"id\":\"pi_old\",\"latest_charge\":\"ch_retry\",\"last_payment_error\":{\"decline_code\":\"insufficient_funds\"}}",PaymentIntent.class);when(stripe.execute(any())).thenReturn(new SubscriptionRecoveryStripeGateway.WriteResult("DECLINED","req_retry","insufficient_funds",decline));
        service.runDueAction(10L);assertEquals(2,ledger.stream().filter(a->Set.of("CAPTURE_FAILURE","NOTIFY_INTERNAL_FAILURE").contains(a.getActionType())).count());var evidence=ledger.stream().filter(a->"CAPTURE_FAILURE".equals(a.getActionType())).findFirst().orElseThrow();assertTrue(evidence.getPayloadJson().contains("automatic"));assertTrue(evidence.getResultJson().contains("ch_retry"));
        snap.charge().setId("ch_retry");snap.paymentIntent().setLatestCharge("ch_retry");var callback=failureEvent("evt_retry_callback","ch_retry");assertTrue(service.tryProcessEvent(callback));assertEquals(2,ledger.stream().filter(a->Set.of("CAPTURE_FAILURE","NOTIFY_INTERNAL_FAILURE").contains(a.getActionType())).count());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void uncollectibleDeadlineClosureRescansADraftCreatedByCancellationAndNeverRepeatsWrites(boolean scheduled) {
        var f=retryGuardFixture();var root=f.root();var main=f.snapshot();var quota=injectQuota();
        root.setDisplayTier("pro");
        var saved=com.google.gson.JsonParser.parseString(root.getContractSnapshot()).getAsJsonObject();
        saved.getAsJsonObject("recovery_admission").addProperty("silentCleanupVerified",true);root.setContractSnapshot(saved.toString());
        main.invoice().setStatus("uncollectible");
        var originalDeadline=root.getDeadlineAt();var originalSlotKey=f.slot().getActionKey();var originalSlotTime=f.slot().getScheduledAt();
        f.ledger().get(1).setActionKey("recovery:in_old:retry:14");
        service.reconcile(1L);service.runDueAction(10L);service.reconcile(1L);
        assertEquals("OPEN_MANUAL",root.getStatus());assertEquals(originalDeadline,root.getDeadlineAt());
        assertTrue(f.ledger().stream().filter(a->"RETRY_PAY".equals(a.getActionType())).allMatch(a->"SKIPPED".equals(a.getStatus())));
        assertNull(root.getAccountClosedAt());verify(stripe,never()).execute(any());verifyNoInteractions(quota);

        var schedule=ApiResource.GSON.fromJson("{\"id\":\"sched_old\",\"customer\":\"cus_old\",\"subscription\":\"sub_old\",\"status\":\"active\"}",SubscriptionSchedule.class);
        if(scheduled)main.subscription().setSchedule("sched_old");
        var deadline=com.studyagent.common.datetime.DateTimeFormats.toEpochSecond(originalDeadline);
        var d30=new SubscriptionRecoveryStripeGateway.Snapshot(main.invoice(),main.subscription(),scheduled?schedule:null,main.lines(),main.paymentIntent(),main.charge(),Instant.ofEpochSecond(deadline));
        when(stripe.readInvoice("in_old")).thenReturn(d30);
        var invoices=new ArrayList<Invoice>();invoices.add(main.invoice());
        when(stripe.listInvoices("sub_old")).thenAnswer(call->new ArrayList<>(invoices));
        bindSequenceActions(f.ledger());
        var late=snapshot();late.invoice().setId("in_cancel_race");late.invoice().setStatus("draft");late.invoice().setAutoAdvance(true);
        when(stripe.readInvoice("in_cancel_race")).thenReturn(new SubscriptionRecoveryStripeGateway.Snapshot(late.invoice(),main.subscription(),scheduled?schedule:null,late.lines(),late.paymentIntent(),late.charge(),d30.businessNow()));
        var writes=new ArrayList<String>();
        when(stripe.execute(any())).thenAnswer(call->{
            SubscriptionRecoveryActionEntity a=call.getArgument(0);assertFalse(writes.contains(a.getActionKey()),"completed action must never execute twice");writes.add(a.getActionKey());
            var params=SubscriptionRecoveryStripeGateway.thaw(a.getPayloadJson());
            if(Set.of("CANCEL_SUBSCRIPTION","CANCEL_SCHEDULE").contains(a.getActionType())){
                assertEquals(false,params.get("invoice_now"));assertEquals(false,params.get("prorate"));
                main.subscription().setStatus("canceled");schedule.setStatus("canceled");invoices.add(late.invoice());
                return new SubscriptionRecoveryStripeGateway.WriteResult("SUCCEEDED","req_cancel",null,scheduled?schedule:main.subscription());
            }
            var target="in_old".equals(a.getTargetId())?main.invoice():late.invoice();
            switch(a.getActionType()){
                case "DISABLE_ADVANCE" -> {assertEquals(false,params.get("auto_advance"));target.setAutoAdvance(false);}
                case "FINALIZE_INVOICE" -> {assertEquals(false,params.get("auto_advance"));assertFalse(target.getAutoAdvance());target.setStatus("open");}
                case "VOID_INVOICE" -> {assertFalse(target.getAutoAdvance());target.setStatus("void");}
                default -> fail("unexpected network write: "+a.getActionType());
            }
            return new SubscriptionRecoveryStripeGateway.WriteResult("SUCCEEDED","req_"+a.getActionType()+a.getTargetId(),null,target);
        });
        service.reconcile(1L);service.reconcile(1L);
        assertEquals("CLOSING",root.getStatus());assertEquals("DEADLINE",root.getCloseReason());assertNull(root.getAccountClosedAt());
        runSequenceAction(f.ledger(),scheduled?"CANCEL_SCHEDULE":"CANCEL_SUBSCRIPTION",scheduled?"sched_old":"sub_old");
        assertEquals(2,invoices.size());assertEquals("draft",late.invoice().getStatus());assertTrue(late.invoice().getAutoAdvance());
        assertTrue(f.ledger().stream().anyMatch(a->"DISABLE_ADVANCE".equals(a.getActionType())&&"in_cancel_race".equals(a.getTargetId())),"cancel response must actively rescan the new draft without a webhook");
        runSequenceAction(f.ledger(),"DISABLE_ADVANCE","in_cancel_race");
        runSequenceAction(f.ledger(),"VOID_INVOICE","in_old");
        assertEquals("CLOSING",root.getStatus());assertNull(root.getAccountClosedAt());
        runSequenceAction(f.ledger(),"FINALIZE_INVOICE","in_cancel_race");
        assertEquals("open",late.invoice().getStatus());assertFalse(late.invoice().getAutoAdvance());assertNull(root.getAccountClosedAt());
        runSequenceAction(f.ledger(),"VOID_INVOICE","in_cancel_race");
        assertEquals("EXPIRED",root.getStatus());assertNotNull(root.getAccountClosedAt());assertEquals("free",f.user().getTier());assertEquals("free",f.user().getPlanCode());assertNull(f.user().getStripeSubscriptionId());
        var closedAt=root.getAccountClosedAt();int actionCount=f.ledger().size();
        service.reconcile(1L);service.reconcile(1L);
        for(var action:new ArrayList<>(f.ledger()))service.runDueAction(action.getId());
        assertEquals(actionCount,f.ledger().size());assertEquals(closedAt,root.getAccountClosedAt());assertEquals(5,writes.size());
        assertEquals(f.ledger().size(),f.ledger().stream().map(SubscriptionRecoveryActionEntity::getActionKey).distinct().count());
        assertEquals(2,f.ledger().stream().filter(a->"RETRY_PAY".equals(a.getActionType())).count());assertSame(f.slot(),f.ledger().get(0));
        assertEquals(originalSlotKey,f.slot().getActionKey());assertEquals(originalSlotTime,f.slot().getScheduledAt());assertEquals(originalDeadline,root.getDeadlineAt());
        verify(quota,times(1)).clearPlanQuotaAt("user","sub_old","pro_monthly","recovery:1:closed",d30.businessNow());
        verify(quota,never()).resetFromPaidInvoiceAt(anyString(),anyString(),anyString(),any(),any(),anyString(),any());
        verify(stripe,times(5)).execute(any());verify(orders,never()).insert(any(RechargeOrderEntity.class));
        assertRecoveryHistoryWasNotDeleted();
    }

    @Test void anUncollectibleInvoicePaidInsideItsOriginalWindowGrantsOneFullPeriodInsteadOfClosingAtD30() {
        var f=retryGuardFixture();var root=f.root();var quota=injectQuota();root.setDisplayTier("pro");
        f.snapshot().invoice().setStatus("uncollectible");var deadline=root.getDeadlineAt();
        service.reconcile(1L);service.runDueAction(10L);
        assertEquals("OPEN_MANUAL",root.getStatus());assertEquals(deadline,root.getDeadlineAt());verify(stripe,never()).execute(any());
        var paid=paidSnapshot();paid.invoice().setAutoAdvance(false);paid.subscription().setPauseCollection(f.snapshot().subscription().getPauseCollection());
        when(stripe.readInvoice("in_old")).thenReturn(paid);when(stripe.listInvoices("sub_old")).thenReturn(List.of(paid.invoice()));
        var history=new ArrayList<RechargeOrderEntity>();when(orders.selectOne(any())).thenAnswer(call->history.isEmpty()?null:history.get(0));
        when(orders.insert(any(RechargeOrderEntity.class))).thenAnswer(call->{history.add(call.getArgument(0));return 1;});
        service.reconcile(1L);service.reconcile(1L);
        var periodEnd=SubscriptionRecoveryPolicy.paidPeriodEnd(now,"month",1);
        assertEquals("PAID",root.getStatus());assertEquals("GRANTED",root.getGrantState());assertEquals("in_old",root.getEntitlementInvoiceId());
        assertEquals(com.studyagent.common.datetime.DateTimeFormats.fromInstant(now),root.getEntitlementStartAt());
        assertEquals(com.studyagent.common.datetime.DateTimeFormats.fromInstant(periodEnd),root.getEntitlementEndAt());assertEquals(root.getEntitlementEndAt(),f.user().getCurrentPeriodEnd());
        assertEquals(deadline,root.getDeadlineAt());assertNull(root.getCloseReason());assertNull(root.getAccountClosedAt());assertEquals("pro_monthly",f.user().getPlanCode());
        assertEquals(1,history.size());assertEquals("completed",history.get(0).getStatus());assertEquals("in_old",history.get(0).getStripeInvoiceId());
        assertEquals(2,f.ledger().stream().filter(a->"RETRY_PAY".equals(a.getActionType())).count());
        assertTrue(f.ledger().stream().noneMatch(a->Set.of("CANCEL_SUBSCRIPTION","CANCEL_SCHEDULE","VOID_INVOICE").contains(a.getActionType())));
        // Reach the original D30 after receipt; the paid period is still current.
        var atOriginalDeadline=new SubscriptionRecoveryStripeGateway.Snapshot(paid.invoice(),paid.subscription(),paid.schedule(),paid.lines(),paid.paymentIntent(),paid.charge(),deadline.atZone(com.studyagent.common.datetime.DateTimeFormats.APP_ZONE).toInstant());
        when(stripe.readInvoice("in_old")).thenReturn(atOriginalDeadline);
        service.reconcile(1L);service.reconcile(1L);
        assertEquals("PAID",root.getStatus());assertEquals("GRANTED",root.getGrantState());assertEquals("in_old",root.getEntitlementInvoiceId());
        assertEquals(com.studyagent.common.datetime.DateTimeFormats.fromInstant(now),root.getEntitlementStartAt());assertEquals(com.studyagent.common.datetime.DateTimeFormats.fromInstant(periodEnd),root.getEntitlementEndAt());
        assertEquals(root.getEntitlementEndAt(),f.user().getCurrentPeriodEnd());assertEquals("pro_monthly",f.user().getPlanCode());assertEquals("sub_old",f.user().getStripeSubscriptionId());
        assertEquals(deadline,root.getDeadlineAt());assertNull(root.getCloseReason());assertNull(root.getAccountClosedAt());assertEquals(1,history.size());
        assertTrue(f.ledger().stream().noneMatch(a->Set.of("CANCEL_SUBSCRIPTION","CANCEL_SCHEDULE","VOID_INVOICE").contains(a.getActionType())));
        verify(quota,times(1)).resetFromPaidInvoiceAt(eq("user"),eq("sub_old"),eq("pro_monthly"),eq(now),any(),eq("in_old"),eq(now));
        verify(quota,never()).clearPlanQuotaAt(anyString(),anyString(),anyString(),anyString(),any());verify(stripe,never()).execute(any());assertRecoveryHistoryWasNotDeleted();
    }

    @Test void equivalentScheduleIsReleasedBeforeFrozenShiftAndItsTechnicalDraftIsSilentlyClosed() {
        enable();var paid=paidSnapshot();var root=existingRoot();root.setDisplayTier("pro");
        root.setFirstFailedAt(com.studyagent.common.datetime.DateTimeFormats.fromInstant(now.minusSeconds(86400)));
        paid.subscription().setCurrency("usd");paid.subscription().setLatestInvoice("in_old");paid.lines().get(0).getPrice().setUnitAmount(2900L);
        paid.subscription().getItems().getData().get(0).setPrice(paid.lines().get(0).getPrice());
        var pause=new Subscription.PauseCollection();pause.setBehavior("keep_as_draft");paid.subscription().setPauseCollection(pause);paid.subscription().setSchedule("sched_equivalent");
        var schedule=ApiResource.GSON.fromJson("{\"id\":\"sched_equivalent\",\"customer\":\"cus_old\",\"subscription\":\"sub_old\",\"status\":\"active\",\"end_behavior\":\"release\",\"default_settings\":{\"collection_method\":\"charge_automatically\"},\"current_phase\":{\"start_date\":1794362400,\"end_date\":1796954400},\"phases\":[{\"start_date\":1794362400,\"end_date\":1796954400,\"items\":[{\"price\":\"price_old\",\"quantity\":1}]}]}",SubscriptionSchedule.class);
        var classified=RecoveryContractClassifier.classify(paid.subscription(),schedule,true,"price_old",1L);assertEquals("EQUIVALENT_SCHEDULE",classified.scheduleClass());
        root.setContractSnapshot("{\"price_id\":\"price_old\",\"quantity\":1,\"recovery_admission\":{\"sourceKey\":\"formal\",\"cancelMode\":\"RENEW\",\"silentCleanupVerified\":true,\"contractHash\":\""+classified.contractHash()+"\"}}");
        var initial=new SubscriptionRecoveryStripeGateway.Snapshot(paid.invoice(),paid.subscription(),schedule,paid.lines(),paid.paymentIntent(),paid.charge(),now);
        ownedFixture(root,initial);var quota=injectQuota();var user=users.selectByUserForUpdate("user");var ledger=failureLedger();bindSequenceActions(ledger);
        var remote=new java.util.concurrent.atomic.AtomicReference<>(initial);when(stripe.readInvoice("in_old")).thenAnswer(call->remote.get());
        var invoices=new ArrayList<Invoice>();invoices.add(paid.invoice());when(stripe.listInvoices("sub_old")).thenAnswer(call->new ArrayList<>(invoices));
        var history=new ArrayList<RechargeOrderEntity>();when(orders.selectOne(any())).thenAnswer(call->history.isEmpty()?null:history.get(0));
        when(orders.insert(any(RechargeOrderEntity.class))).thenAnswer(call->{history.add(call.getArgument(0));return 1;});
        var periodEnd=SubscriptionRecoveryPolicy.paidPeriodEnd(now,"month",1);long end=periodEnd.getEpochSecond();
        var previewInvoice=new Invoice();previewInvoice.setCurrency("usd");previewInvoice.setAmountDue(2900L);previewInvoice.setStartingBalance(0L);
        var previewLine=ApiResource.GSON.fromJson(paid.lines().get(0).toJson(),InvoiceLineItem.class);previewLine.setAmount(2900L);previewLine.getPeriod().setStart(end);
        when(stripe.preview("sub_old")).thenReturn(new SubscriptionRecoveryStripeGateway.Preview(previewInvoice,List.of(previewLine)));
        var technical=new Invoice();technical.setId("in_shift_technical");technical.setCustomer("cus_old");technical.setSubscription("sub_old");technical.setCurrency("usd");technical.setStatus("draft");technical.setBillingReason("subscription_update");technical.setAmountDue(0L);technical.setAmountPaid(0L);technical.setAmountRemaining(0L);technical.setAutoAdvance(true);
        // A zero-value technical draft has no cash evidence, even though its parent invoice is paid.
        when(stripe.readInvoice("in_shift_technical")).thenAnswer(call->new SubscriptionRecoveryStripeGateway.Snapshot(technical,paid.subscription(),null,paid.lines(),null,null,now));
        var writes=new ArrayList<String>();var frozen=new HashMap<String,String>();
        when(stripe.execute(any())).thenAnswer(call->{
            SubscriptionRecoveryActionEntity action=call.getArgument(0);writes.add(action.getActionType());
            var params=SubscriptionRecoveryStripeGateway.thaw(action.getPayloadJson());
            assertEquals(frozen.computeIfAbsent(action.getActionKey(),key->action.getPayloadJson()),action.getPayloadJson());
            switch(action.getActionType()){
                case "RELEASE_SCHEDULE" -> {
                    assertEquals(List.of("RELEASE_SCHEDULE"),writes);assertEquals(true,params.get("preserve_cancel_date"));
                    schedule.setStatus("released");schedule.setReleasedSubscription("sub_old");paid.subscription().setSchedule((String)null);
                    remote.set(new SubscriptionRecoveryStripeGateway.Snapshot(paid.invoice(),paid.subscription(),null,paid.lines(),paid.paymentIntent(),paid.charge(),now));
                    return new SubscriptionRecoveryStripeGateway.WriteResult("SUCCEEDED","req_release",null,schedule);
                }
                case "SHIFT_RENEWAL" -> {
                    assertEquals(List.of("RELEASE_SCHEDULE","SHIFT_RENEWAL"),writes);assertNull(paid.subscription().getSchedule());
                    assertEquals(end,((Number)params.get("trial_end")).longValue());assertEquals("none",params.get("proration_behavior"));assertEquals("",params.get("pause_collection"));
                    assertEquals(Map.of("payment_recovery_invoice_id","in_old"),params.get("metadata"));
                    paid.subscription().setTrialEnd(end);paid.subscription().setCurrentPeriodEnd(end);paid.subscription().setPauseCollection(null);paid.subscription().setStatus("trialing");paid.subscription().setLatestInvoice(technical.getId());invoices.add(technical);
                    return new SubscriptionRecoveryStripeGateway.WriteResult("SUCCEEDED","req_shift",null,paid.subscription());
                }
                case "DISABLE_ADVANCE" -> {assertEquals(false,params.get("auto_advance"));technical.setAutoAdvance(false);}
                case "FINALIZE_INVOICE" -> {assertEquals(false,params.get("auto_advance"));assertFalse(technical.getAutoAdvance());technical.setStatus("open");}
                case "VOID_INVOICE" -> {assertFalse(technical.getAutoAdvance());technical.setStatus("void");}
                default -> fail("unexpected write "+action.getActionType());
            }
            return new SubscriptionRecoveryStripeGateway.WriteResult("SUCCEEDED","req_"+action.getActionType(),null,technical);
        });
        service.reconcile(1L);
        assertEquals("PAID",root.getStatus());assertEquals("GRANTED",root.getGrantState());assertEquals("PENDING",root.getAnchorSyncState());
        var shift=ledger.stream().filter(action->"SHIFT_RENEWAL".equals(action.getActionType())).findFirst().orElseThrow();String shiftBody=shift.getPayloadJson();String shiftKey=shift.getActionKey();
        service.runDueAction(shift.getId());assertEquals("READY",shift.getStatus());assertTrue(writes.isEmpty(),"a live schedule blocks SHIFT until release");
        runSequenceAction(ledger,"RELEASE_SCHEDULE","sched_equivalent");assertEquals("READY",shift.getStatus());assertEquals("GRANTED",root.getGrantState());
        runSequenceAction(ledger,"SHIFT_RENEWAL","sub_old");
        assertEquals(shiftBody,shift.getPayloadJson());assertEquals(shiftKey,shift.getActionKey());assertTrue(shift.getResultJson().contains("in_shift_technical"));
        assertTrue(root.getTechnicalInvoiceIds().contains("in_shift_technical"));assertNotEquals("CONFIRMED",root.getAnchorSyncState());
        runSequenceAction(ledger,"DISABLE_ADVANCE","in_shift_technical");runSequenceAction(ledger,"FINALIZE_INVOICE","in_shift_technical");
        assertNotEquals("CONFIRMED",root.getAnchorSyncState());runSequenceAction(ledger,"VOID_INVOICE","in_shift_technical");
        assertEquals("CONFIRMED",root.getAnchorSyncState());assertEquals("RELEASED",root.getProtectionState());assertEquals("PAID",root.getStatus());assertEquals("GRANTED",root.getGrantState());
        assertEquals("in_old",root.getEntitlementInvoiceId());assertEquals(com.studyagent.common.datetime.DateTimeFormats.fromInstant(now),root.getEntitlementStartAt());assertEquals(com.studyagent.common.datetime.DateTimeFormats.fromInstant(periodEnd),root.getEntitlementEndAt());assertEquals(root.getEntitlementEndAt(),user.getCurrentPeriodEnd());
        int count=ledger.size();service.reconcile(1L);service.reconcile(1L);for(var action:new ArrayList<>(ledger))service.runDueAction(action.getId());
        assertEquals(count,ledger.size());assertEquals(5,count);assertEquals(5,ledger.stream().map(SubscriptionRecoveryActionEntity::getActionKey).distinct().count());assertEquals(List.of("RELEASE_SCHEDULE","SHIFT_RENEWAL","DISABLE_ADVANCE","FINALIZE_INVOICE","VOID_INVOICE"),writes);
        assertEquals(1,history.size());verify(quota,times(1)).resetFromPaidInvoiceAt(eq("user"),eq("sub_old"),eq("pro_monthly"),eq(now),any(),eq("in_old"),eq(now));
        verify(quota,never()).clearPlanQuotaAt(anyString(),anyString(),anyString(),anyString(),any());verify(stripe,times(5)).execute(any());assertRecoveryHistoryWasNotDeleted();
    }

    // In-memory persistence only: the actual service owns transitions, eligibility, dispatch and reconciliation.
    private void bindSequenceActions(List<SubscriptionRecoveryActionEntity> ledger){
        when(actions.selectById(anyLong())).thenAnswer(call->ledger.stream().filter(a->Objects.equals(a.getId(),call.getArgument(0))).findFirst().orElse(null));
        when(actions.selectForUpdate(anyLong())).thenAnswer(call->ledger.stream().filter(a->Objects.equals(a.getId(),call.getArgument(0))).findFirst().orElse(null));
        when(actions.claim(anyLong(),anyInt(),anyString(),any(),any())).thenReturn(1);
        when(actions.markDispatched(anyLong(),anyInt(),anyString(),any(),anyString())).thenReturn(1);
        when(actions.complete(anyLong(),anyInt(),anyString(),anyString(),anyString(),any())).thenAnswer(call->{
            var action=ledger.stream().filter(a->Objects.equals(a.getId(),call.getArgument(0))).findFirst().orElseThrow();
            action.setStatus(call.getArgument(3));action.setResultJson(call.getArgument(4));return 1;
        });
    }
    private void runSequenceAction(List<SubscriptionRecoveryActionEntity> ledger,String type,String target){
        var matches=ledger.stream().filter(a->type.equals(a.getActionType())&&target.equals(a.getTargetId())).toList();
        assertEquals(1,matches.size(),type+" "+target+" has one durable action key");var action=matches.get(0);assertEquals("READY",action.getStatus());
        service.runDueAction(action.getId());assertEquals("SUCCEEDED",action.getStatus(),type+" "+target);
    }
    private void assertRecoveryHistoryWasNotDeleted(){
        for(Object mapper:List.of(recoveries,actions,replacements,orders))assertTrue(mockingDetails(mapper).getInvocations().stream().noneMatch(call->call.getMethod().getName().startsWith("delete")),"recovery, actions, replacements and accounting history must be retained");
    }
    private long failureChannelCount(java.util.List<SubscriptionRecoveryActionEntity> ledger){return ledger.stream().filter(a->Set.of("CAPTURE_FAILURE","NOTIFY_INTERNAL_FAILURE").contains(a.getActionType())).count();}
    private java.util.List<SubscriptionRecoveryActionEntity> failureLedger(){
        var ledger=new java.util.ArrayList<SubscriptionRecoveryActionEntity>();when(actions.selectByRecovery(1L)).thenAnswer(i->new java.util.ArrayList<>(ledger));when(actions.selectByKey(anyString())).thenAnswer(i->ledger.stream().filter(a->java.util.Objects.equals(i.getArgument(0),a.getActionKey())).findFirst().orElse(null));when(actions.insert(any(SubscriptionRecoveryActionEntity.class))).thenAnswer(i->{SubscriptionRecoveryActionEntity a=i.getArgument(0);a.setId(100L+ledger.size());ledger.add(a);return 1;});return ledger;
    }
    private SubscriptionPaymentRecoveryEntity confirmedPaidRoot(SubscriptionRecoveryStripeGateway.Snapshot snap){
        var root=existingRoot();root.setStatus("PAID");root.setGrantState("GRANTED");root.setEntitlementInvoiceId("in_old");root.setEntitlementStartAt(com.studyagent.common.datetime.DateTimeFormats.fromInstant(now));root.setEntitlementEndAt(com.studyagent.common.datetime.DateTimeFormats.fromInstant(now.plusSeconds(30*86400)));root.setAnchorSyncState("PENDING");
        root.setContractSnapshot("{\"price_id\":\"price_old\",\"quantity\":1,\"recovery_admission\":{\"cancelMode\":\"RENEW\"}}");
        snap.subscription().setTrialEnd(now.plusSeconds(30*86400).getEpochSecond());snap.subscription().setCurrentPeriodEnd(snap.subscription().getTrialEnd());snap.subscription().setStatus("trialing");snap.subscription().setCurrency("usd");snap.lines().get(0).getPrice().setUnitAmount(2900L);snap.subscription().getItems().getData().get(0).setPrice(snap.lines().get(0).getPrice());return root;
    }
    private void stubConfirmedShift(SubscriptionPaymentRecoveryEntity root,SubscriptionRecoveryStripeGateway.Snapshot snap,long amount){
        var shift=new SubscriptionRecoveryActionEntity();shift.setId(10L);shift.setActionType("SHIFT_RENEWAL");shift.setStatus("SUCCEEDED");shift.setResultJson("{\"latest_invoice\":\"in_old\",\"before_latest_invoice\":\"in_old\"}");when(actions.selectByRecovery(1L)).thenReturn(List.of(shift));
        var invoice=new Invoice();invoice.setCurrency("usd");invoice.setAmountDue(amount);invoice.setStartingBalance(0L);var line=ApiResource.GSON.fromJson(snap.lines().get(0).toJson(),InvoiceLineItem.class);line.setAmount(2900L);line.getPeriod().setStart(com.studyagent.common.datetime.DateTimeFormats.toEpochSecond(root.getEntitlementEndAt()));
        when(stripe.preview("sub_old")).thenReturn(new SubscriptionRecoveryStripeGateway.Preview(invoice,List.of(line)));
    }
    private SubscriptionPaymentRecoveryEntity existingRoot(){
        var r=new SubscriptionPaymentRecoveryEntity();r.setId(1L);r.setClerkUserId("user");r.setStripeCustomerId("cus_old");r.setStripeSubscriptionId("sub_old");r.setStripeInvoiceId("in_old");r.setStatus("OPEN_AUTO");r.setProtectionState("ACTIVE");r.setPlanCode("pro_monthly");r.setCurrency("usd");r.setBillingInterval("month");r.setIntervalCount(1);r.setVersion(0);r.setFirstFailedAt(com.studyagent.common.datetime.DateTimeFormats.fromInstant(now));r.setDeadlineAt(com.studyagent.common.datetime.DateTimeFormats.fromInstant(now.plusSeconds(86400)));return r;
    }
    private void ownedFixture(SubscriptionPaymentRecoveryEntity root,SubscriptionRecoveryStripeGateway.Snapshot snap){
        snap.invoice().setAutoAdvance(false);
        when(recoveries.selectByInvoice("in_old")).thenReturn(root);when(recoveries.selectById(1L)).thenReturn(root);when(recoveries.selectForUpdate(1L)).thenReturn(root);
        var user=user();user.setPaymentRecoveryId(1L);when(users.selectByUserForUpdate("user")).thenReturn(user);when(users.selectByUser("user")).thenReturn(user);
        when(stripe.readInvoice("in_old")).thenReturn(snap);when(stripe.listInvoices("sub_old")).thenReturn(List.of(snap.invoice()));when(tx.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    }
    private void enable(){
        when(stripe.readInvoiceHeader(anyString())).thenAnswer(i->{var value=stripe.readInvoice(i.getArgument(0));return value==null?null:value.invoice();});
        when(stripe.readFirstFailure(any(),any())).thenAnswer(i->{Event event=i.getArgument(1);return new SubscriptionRecoveryStripeGateway.FirstFailure(Instant.ofEpochSecond(event.getCreated()),"invoice_failure_event",event.getId(),null);});
        properties.setAcceptNew(true);properties.setAcceptInvoicesCreatedAfter(now.minusSeconds(3600));
        properties.setAllowedUserIds(Set.of("user"));properties.setVerifiedCohorts(Set.of("formal:pro_monthly:month_1:NO_SCHEDULE:RENEW"));
        when(users.selectByStripeSubscriptionId("sub_old")).thenReturn(user());
        var plan=new SubscriptionPlanEntity();plan.setPlanCode("pro_monthly");plan.setTier("pro");plan.setBillingInterval("month");plan.setStripePriceId("price_old");
        when(plans.selectList(any())).thenReturn(List.of(plan));
    }
    private UserSubscriptionEntity user(){var u=new UserSubscriptionEntity();u.setId(1L);u.setClerkUserId("user");u.setStripeSubscriptionId("sub_old");u.setPlanCode("pro_monthly");u.setVersion(0);u.setGraceEndAt(java.time.LocalDateTime.of(2026,11,14,10,0));return u;}
    private Event event(){return ApiResource.GSON.fromJson("""
        {"id":"evt_failed","type":"invoice.payment_failed","created":1794362400,"livemode":false,
        "data":{"object":{"id":"in_old","object":"invoice","subscription":"sub_old"}}}
        """,Event.class);}
    private SubscriptionRecoveryStripeGateway.Snapshot snapshot(){
        Invoice invoice=ApiResource.GSON.fromJson("""
            {"id":"in_old","object":"invoice","customer":"cus_old","subscription":"sub_old","status":"open",
             "created":1794362400,"billing_reason":"subscription_cycle","currency":"usd","amount_due":2900,"amount_paid":0,"amount_remaining":2900,
             "livemode":false,"auto_advance":true,"payment_intent":"pi_old"}
            """,Invoice.class);
        Subscription sub=ApiResource.GSON.fromJson("{\"id\":\"sub_old\",\"customer\":\"cus_old\",\"status\":\"past_due\",\"cancel_at_period_end\":false,\"items\":{\"data\":[{\"price\":{\"id\":\"price_old\"},\"quantity\":1}]}}",Subscription.class);
        InvoiceLineItem line=ApiResource.GSON.fromJson("""
            {"id":"il_old","type":"subscription","subscription":"sub_old","subscription_item":"si_old","proration":false,"currency":"usd",
             "price":{"id":"price_old","type":"recurring","currency":"usd","recurring":{"interval":"month","interval_count":1}},
             "period":{"start":1794362400,"end":1796954400},"quantity":1}
            """,InvoiceLineItem.class);
        PaymentIntent pi=ApiResource.GSON.fromJson("{\"id\":\"pi_old\",\"status\":\"requires_payment_method\",\"latest_charge\":\"ch_old\",\"last_payment_error\":{\"decline_code\":\"insufficient_funds\"}}",PaymentIntent.class);
        Charge ch=ApiResource.GSON.fromJson("{\"object\":\"charge\",\"id\":\"ch_old\",\"customer\":\"cus_old\",\"invoice\":\"in_old\",\"payment_intent\":\"pi_old\",\"created\":1794362400,\"paid\":false,\"status\":\"failed\"}",Charge.class);
        return new SubscriptionRecoveryStripeGateway.Snapshot(invoice,sub,null,List.of(line),pi,ch,now);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"UNKNOWN", "FAILED"})
    void partiallyAppliedProtectionSurvivesServiceRebuildWithoutReplayingItsOriginalWrites(String pauseOutcome) {
        enable();
        var snap = snapshot();
        when(stripe.readInvoice("in_old")).thenReturn(snap);
        var account = user();
        when(users.selectByStripeSubscriptionId("sub_old")).thenReturn(account);
        when(users.selectByUserForUpdate("user")).thenReturn(account);
        when(tx.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        var persistedRoot = new java.util.concurrent.atomic.AtomicReference<SubscriptionPaymentRecoveryEntity>();
        when(recoveries.selectByInvoice("in_old")).thenAnswer(call -> persistedRoot.get());
        when(recoveries.insert(any(SubscriptionPaymentRecoveryEntity.class))).thenAnswer(call -> {
            SubscriptionPaymentRecoveryEntity row = call.getArgument(0);
            row.setId(1L);
            persistedRoot.set(row);
            return 1;
        });
        var ledger = failureLedger();

        assertTrue(service.tryProcessEvent(event()), "The first failure is owned by recovery");
        var root = persistedRoot.get();
        assertNotNull(root);
        assertEquals("PENDING", root.getProtectionState());
        assertNull(account.getGraceEndAt());
        var firstFailure = root.getFirstFailedAt();
        var deadline = root.getDeadlineAt();
        var originalContract = root.getContractSnapshot();
        var retryRows = ledger.stream().filter(a -> "RETRY_PAY".equals(a.getActionType())).toList();
        assertEquals(4, retryRows.size());
        var frozenRetries = retryRows.stream().map(this::protectionRetrySignature).toList();
        for (int slot = 1; slot <= 4; slot++) {
            var retry = retryRows.get(slot - 1);
            assertEquals(slot, retry.getRetrySlot());
            assertEquals("recovery:in_old:retry:" + slot, retry.getActionKey());
            assertEquals(retry.getActionKey(), retry.getProviderIdempotencyKey());
            assertEquals(Map.of("off_session", true), SubscriptionRecoveryStripeGateway.thaw(retry.getPayloadJson()));
            assertEquals(com.studyagent.common.datetime.DateTimeFormats.fromInstant(
                    SubscriptionRecoveryPolicy.retryAt(now, slot)), retry.getScheduledAt());
        }
        var disable = ledger.stream().filter(a -> "DISABLE_ADVANCE".equals(a.getActionType())).findFirst().orElseThrow();
        var pause = ledger.stream().filter(a -> "PAUSE_COLLECTION".equals(a.getActionType())).findFirst().orElseThrow();
        assertEquals("recovery:in_old:disable-advance", disable.getActionKey());
        assertEquals("recovery:1:pause:sub_old", pause.getActionKey());
        assertEquals(Map.of("auto_advance", false), SubscriptionRecoveryStripeGateway.thaw(disable.getPayloadJson()));
        assertEquals(Map.of("pause_collection", Map.of("behavior", "keep_as_draft")),
                SubscriptionRecoveryStripeGateway.thaw(pause.getPayloadJson()));
        String disableKey = disable.getProviderIdempotencyKey(), disableBody = disable.getPayloadJson();
        String pauseKey = pause.getProviderIdempotencyKey(), pauseBody = pause.getPayloadJson();

        // Reuse the owned-record fixture, but retain the account actually changed by takeover.
        ownedFixture(root, snap);
        when(users.selectByUserForUpdate("user")).thenReturn(account);
        when(users.selectByUser("user")).thenReturn(account);
        snap.invoice().setAutoAdvance(true);
        when(actions.selectById(anyLong())).thenAnswer(call -> ledger.stream()
                .filter(a -> Objects.equals(a.getId(), call.getArgument(0))).findFirst().orElse(null));
        when(actions.selectForUpdate(anyLong())).thenAnswer(call -> ledger.stream()
                .filter(a -> Objects.equals(a.getId(), call.getArgument(0))).findFirst().orElse(null));
        when(actions.claim(anyLong(), anyInt(), anyString(), any(), any())).thenReturn(1);
        when(actions.markDispatched(anyLong(), anyInt(), anyString(), any(), anyString())).thenReturn(1);
        when(actions.complete(anyLong(), anyInt(), anyString(), anyString(), anyString(), any())).thenAnswer(call -> {
            SubscriptionRecoveryActionEntity row = ledger.stream()
                    .filter(a -> Objects.equals(a.getId(), call.getArgument(0))).findFirst().orElseThrow();
            row.setStatus(call.getArgument(3));
            row.setResultJson(call.getArgument(4));
            row.setLeaseToken(null);
            row.setLeaseUntil(null);
            return 1;
        });
        when(stripe.execute(any())).thenAnswer(call -> {
            SubscriptionRecoveryActionEntity row = call.getArgument(0);
            if (Objects.equals(disable.getId(), row.getId())) {
                assertEquals(disableKey, row.getProviderIdempotencyKey());
                assertEquals(disableBody, row.getPayloadJson());
                snap.invoice().setAutoAdvance(false);
                return new SubscriptionRecoveryStripeGateway.WriteResult("SUCCEEDED", "req_disable", null, snap.invoice());
            }
            assertSame(pause, row, "No payment or repair action may be silently dispatched by reconcile");
            assertEquals(pauseKey, row.getProviderIdempotencyKey());
            assertEquals(pauseBody, row.getPayloadJson());
            return new SubscriptionRecoveryStripeGateway.WriteResult(pauseOutcome, "req_pause", "test_pause_outcome", null);
        });
        Runnable assertWindowAndFrozenWrites = () -> {
            assertSame(root, recoveries.selectByInvoice("in_old"));
            assertEquals(1L, account.getPaymentRecoveryId());
            assertEquals("sub_old", account.getStripeSubscriptionId());
            assertNull(account.getGraceEndAt());
            assertEquals("OPEN_AUTO", root.getStatus());
            assertEquals("NONE", root.getGrantState());
            assertNull(root.getEntitlementInvoiceId());
            assertNull(root.getAccountClosedAt());
            assertEquals(firstFailure, root.getFirstFailedAt());
            assertEquals(deadline, root.getDeadlineAt());
            assertEquals(originalContract, root.getContractSnapshot());
            assertEquals(frozenRetries, ledger.stream().filter(a -> "RETRY_PAY".equals(a.getActionType()))
                    .map(this::protectionRetrySignature).toList());
            assertTrue(retryRows.stream().allMatch(a -> "READY".equals(a.getStatus())));
            assertEquals(disableKey, disable.getProviderIdempotencyKey());
            assertEquals(disableBody, disable.getPayloadJson());
            assertEquals(pauseKey, pause.getProviderIdempotencyKey());
            assertEquals(pauseBody, pause.getPayloadJson());
        };

        service.runDueAction(disable.getId());
        assertEquals("SUCCEEDED", disable.getStatus());
        assertFalse(snap.invoice().getAutoAdvance());
        assertEquals("PENDING", root.getProtectionState());
        assertWindowAndFrozenWrites.run();
        service.runDueAction(pause.getId());
        assertEquals(pauseOutcome, pause.getStatus());
        assertNull(snap.subscription().getPauseCollection());
        assertEquals("PENDING", root.getProtectionState());
        assertWindowAndFrozenWrites.run();
        if ("FAILED".equals(pauseOutcome)) {
            assertEquals("OPEN", root.getReviewState());
            assertEquals("REMOTE_ACTION_FAILED:PAUSE_COLLECTION", root.getReviewReason());
        }

        // Fresh service instance; only mapper-backed state and simulated provider state survive.
        // This is not a real process kill or a real database persistence test.
        var rebuilt = new SubscriptionPaymentRecoveryService(users, recoveries, actions, replacements,
                plans, orders, stripe, properties, access, tx);
        rebuilt.runDueAction(disable.getId());
        rebuilt.runDueAction(pause.getId());
        rebuilt.reconcile(1L);
        assertTrue(rebuilt.tryProcessEvent(event()), "Owned replay must not fall back to legacy handling");
        assertEquals("SUCCEEDED", disable.getStatus());
        assertEquals(pauseOutcome, pause.getStatus());
        assertEquals("PENDING", root.getProtectionState());
        assertWindowAndFrozenWrites.run();
        verify(stripe, times(1)).execute(disable);
        verify(stripe, times(1)).execute(pause);
        verify(stripe, times(2)).execute(any());

        // Independent read evidence later observes the protection applied/repaired remotely.
        // FAILED stays FAILED and in review; it is never replayed. UNKNOWN confirms by read.
        var observedPause = new Subscription.PauseCollection();
        observedPause.setBehavior("keep_as_draft");
        snap.subscription().setPauseCollection(observedPause);
        rebuilt.runDueAction(pause.getId());
        rebuilt.reconcile(1L);
        assertTrue(rebuilt.tryProcessEvent(event()));
        assertEquals("UNKNOWN".equals(pauseOutcome) ? "SUCCEEDED" : "FAILED", pause.getStatus());
        assertEquals("ACTIVE", root.getProtectionState());
        if ("FAILED".equals(pauseOutcome)) {
            assertEquals("OPEN", root.getReviewState());
            assertEquals("REMOTE_ACTION_FAILED:PAUSE_COLLECTION", root.getReviewReason());
        }
        assertWindowAndFrozenWrites.run();
        verify(recoveries, times(1)).insert(any(SubscriptionPaymentRecoveryEntity.class));
        verify(stripe, times(1)).execute(disable);
        verify(stripe, times(1)).execute(pause);
        verify(stripe, times(2)).execute(any());
        verifyNoInteractions(orders);
        // repairProtection may persist separate READY repair intents; this test does not
        // claim that no such intents exist, or that a scheduler cannot dispatch them.
    }

    private String protectionRetrySignature(SubscriptionRecoveryActionEntity row) {
        return row.getId() + "|" + row.getActionKey() + "|" + row.getProviderIdempotencyKey()
                + "|" + row.getPayloadJson() + "|" + row.getRetrySlot() + "|" + row.getScheduledAt();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"price","quantity","fixed_cancel"})
    void aContractChangedAfterTakeoverCannotWithholdNativeCashOrRebuildTheAdmittedContract(String change) {
        // UNKNOWN at payment time is already covered by confirmedCashGrantsLocallyEvenWhenTheScheduleContractCannotBeRead.
        var f=postAdmissionContractFixture(false);var originalContract=f.root().getContractSnapshot();
        var originalDeadline=f.root().getDeadlineAt();var changed=changeAdmittedContract(f.remote().get(),change);f.remote().set(changed);
        assertEquals("price_old",changed.lines().get(0).getPrice().getId(),"The paid invoice keeps its admitted contract");
        assertEquals(1L,changed.lines().get(0).getQuantity());
        service.reconcile(1L);service.reconcile(1L);assertTrue(service.tryProcessEvent(event()));
        assertOriginalCashPeriodAndOneGrant(f);
        assertEquals("REVIEW",f.root().getAnchorSyncState());assertEquals("OPEN",f.root().getReviewState());
        assertEquals("CONTRACT_CHANGED_AFTER_ADMISSION",f.root().getReviewReason());
        assertEquals(originalContract,f.root().getContractSnapshot());assertEquals(originalDeadline,f.root().getDeadlineAt());
        assertTrue(f.ledger().stream().noneMatch(a->Set.of("RELEASE_SCHEDULE","SHIFT_RENEWAL").contains(a.getActionType())),"A changed contract must not acquire a replacement synchronization intent");
        verify(stripe,never()).execute(any());assertRecoveryHistoryWasNotDeleted();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "RELEASE_SCHEDULE,price","RELEASE_SCHEDULE,quantity","RELEASE_SCHEDULE,fixed_cancel","RELEASE_SCHEDULE,schedule_unknown",
        "SHIFT_RENEWAL,price","SHIFT_RENEWAL,quantity","SHIFT_RENEWAL,fixed_cancel","SHIFT_RENEWAL,schedule_unknown"})
    void synchronizationRechecksChangedContractsWithoutReplayingOrReplacingItsFrozenIntent(String type,String change) {
        var f=postAdmissionContractFixture("RELEASE_SCHEDULE".equals(type));
        service.reconcile(1L);assertOriginalCashPeriodAndOneGrant(f);
        assertEquals("PENDING",f.root().getAnchorSyncState(),f.root().getReviewReason());
        var originalContract=f.root().getContractSnapshot();
        var frozen=f.ledger().stream().filter(a->Set.of("RELEASE_SCHEDULE","SHIFT_RENEWAL").contains(a.getActionType()))
                .map(a->List.of(a.getId().toString(),a.getActionKey(),a.getProviderIdempotencyKey(),a.getPayloadJson())).toList();
        assertEquals("RELEASE_SCHEDULE".equals(type)?2:1,frozen.size());
        var action=f.ledger().stream().filter(a->type.equals(a.getActionType())).findFirst().orElseThrow();
        assertEquals("READY",action.getStatus());
        f.remote().set(changeAdmittedContract(f.remote().get(),change));
        // A newly unreadable Schedule must enter durable review just like a conflicting contract.
        // This deliberately detects the current pre-lock contract() exception rather than accepting it as success.
        assertDoesNotThrow(()->service.runDueAction(action.getId()),"Contract reread must preserve paid membership and record review: "+type+" / "+change);
        assertEquals("REVIEW",f.root().getAnchorSyncState());assertEquals("OPEN",f.root().getReviewState());
        assertNotNull(f.root().getReviewReason());assertFalse(f.root().getReviewReason().isBlank());
        if(!"schedule_unknown".equals(change)){
            assertEquals("SKIPPED",action.getStatus());assertEquals("SKIPPED_CONTRACT_CHANGED",action.getSkipReason());
            assertEquals("RENEWAL_CONTRACT_OR_CANCEL_REVIEW",f.root().getReviewReason());
        }else{
            assertEquals("REVIEW",action.getStatus());assertEquals("RECOVERY_ADMISSION_UNKNOWN_SCHEDULE",action.getLastError());
            assertEquals("RENEWAL_CONTRACT_RECHECK_INCOMPLETE",f.root().getReviewReason());assertNull(action.getFirstDispatchedAt());
        }
        assertTrue(Set.of("READY","REVIEW","SKIPPED").contains(action.getStatus()),"No dispatch may follow an unknown or conflicting contract");
        service.runDueAction(action.getId());service.reconcile(1L);assertTrue(service.tryProcessEvent(event()));
        assertOriginalCashPeriodAndOneGrant(f);
        assertEquals("REVIEW",f.root().getAnchorSyncState());assertEquals(originalContract,f.root().getContractSnapshot());
        assertEquals(frozen,f.ledger().stream().filter(a->Set.of("RELEASE_SCHEDULE","SHIFT_RENEWAL").contains(a.getActionType()))
                .map(a->List.of(a.getId().toString(),a.getActionKey(),a.getProviderIdempotencyKey(),a.getPayloadJson())).toList(),"Neither a new action key nor new parameters may replace the original frozen intent");
        verify(stripe,never()).execute(any());assertRecoveryHistoryWasNotDeleted();
    }

    @Test void grantedTrialRecoveryCanPlanMissingRenewalActionsAfterCompletedIntroWasPruned() {
        var f=completedIntroContractFixture();service.reconcile(1L);
        when(recoveries.update(isNull(),any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(1);
        assertOriginalCashPeriodAndOneGrant(f);
        f.ledger().removeIf(a->Set.of("RELEASE_SCHEDULE","SHIFT_RENEWAL").contains(a.getActionType()));
        f.root().setAnchorSyncState("REVIEW");f.root().setReviewState("OPEN");f.root().setReviewReason("CONTRACT_CHANGED_AFTER_ADMISSION");
        String originalAdmission=f.root().getContractSnapshot();
        f.remote().set(prunedIntroSnapshot(f.remote().get(),null));
        service.reconcile(1L);service.reconcile(1L);
        assertOriginalCashPeriodAndOneGrant(f);
        assertEquals("PENDING",f.root().getAnchorSyncState(),f.root().getReviewReason());
        assertEquals("NONE",f.root().getReviewState());assertNull(f.root().getReviewReason());
        assertEquals(1,f.ledger().stream().filter(a->"RELEASE_SCHEDULE".equals(a.getActionType())).count());
        assertEquals(1,f.ledger().stream().filter(a->"SHIFT_RENEWAL".equals(a.getActionType())).count());
        assertEquals(originalAdmission,f.root().getContractSnapshot(),"Historical admission must remain immutable");
        verify(stripe,never()).execute(any());
    }

    @Test void paidTrialRecoveryAcceptsPrunedIntroOnlyWhenAllRemainingContractTermsMatch() {
        var f=completedIntroContractFixture();String originalAdmission=f.root().getContractSnapshot();
        f.remote().set(prunedIntroSnapshot(f.remote().get(),null));
        service.reconcile(1L);service.reconcile(1L);
        assertOriginalCashPeriodAndOneGrant(f);
        assertEquals("PENDING",f.root().getAnchorSyncState(),f.root().getReviewReason());
        assertEquals(2,f.ledger().stream().filter(a->Set.of("RELEASE_SCHEDULE","SHIFT_RENEWAL").contains(a.getActionType())).count());
        assertEquals(originalAdmission,f.root().getContractSnapshot());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"automatic_tax","collection_method","default_tax_rates","phase_tax","phase_discount","phase_end"})
    void prunedIntroMustNotHideChangedCommercialTerms(String change) {
        var f=completedIntroContractFixture();String originalAdmission=f.root().getContractSnapshot();
        f.remote().set(prunedIntroSnapshot(f.remote().get(),change));
        service.reconcile(1L);service.reconcile(1L);
        assertOriginalCashPeriodAndOneGrant(f);
        assertEquals("REVIEW",f.root().getAnchorSyncState());
        assertEquals("CONTRACT_CHANGED_AFTER_ADMISSION",f.root().getReviewReason());
        assertTrue(f.ledger().stream().noneMatch(a->Set.of("RELEASE_SCHEDULE","SHIFT_RENEWAL").contains(a.getActionType())));
        assertEquals(originalAdmission,f.root().getContractSnapshot());verify(stripe,never()).execute(any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"tax_changed","schedule_unknown","canceled","elapsed","unrelated_review","in_progress","missing_snapshot"})
    void grantedRecoveryMustNotPlanMissingIntentsWhenRevalidationIsUnsafe(String condition) {
        var f=completedIntroContractFixture();service.reconcile(1L);
        f.ledger().removeIf(a->Set.of("RELEASE_SCHEDULE","SHIFT_RENEWAL").contains(a.getActionType()));
        f.root().setAnchorSyncState("REVIEW");f.root().setReviewState("OPEN");f.root().setReviewReason("CONTRACT_CHANGED_AFTER_ADMISSION");
        var snapshot=prunedIntroSnapshot(f.remote().get(),"tax_changed".equals(condition)?"automatic_tax":null);
        switch(condition){
            case "schedule_unknown" -> snapshot=new SubscriptionRecoveryStripeGateway.Snapshot(snapshot.invoice(),snapshot.subscription(),null,snapshot.lines(),snapshot.paymentIntent(),snapshot.charge(),snapshot.businessNow());
            case "canceled" -> snapshot.subscription().setStatus("canceled");
            case "elapsed" -> snapshot=new SubscriptionRecoveryStripeGateway.Snapshot(snapshot.invoice(),snapshot.subscription(),snapshot.schedule(),snapshot.lines(),snapshot.paymentIntent(),snapshot.charge(),f.root().getEntitlementEndAt().atZone(com.studyagent.common.datetime.DateTimeFormats.APP_ZONE).toInstant());
            case "unrelated_review" -> f.root().setReviewReason("UNRELATED_FINANCIAL_REVIEW");
            case "in_progress" -> f.root().setReviewState("IN_PROGRESS");
            case "missing_snapshot" -> {var saved=com.google.gson.JsonParser.parseString(f.root().getContractSnapshot()).getAsJsonObject();saved.getAsJsonObject("recovery_admission").remove("scheduleContract");f.root().setContractSnapshot(saved.toString());}
        }
        String original=f.root().getContractSnapshot();f.remote().set(snapshot);
        service.reconcile(1L);service.reconcile(1L);
        assertOriginalCashPeriodAndOneGrant(f);
        assertEquals("REVIEW",f.root().getAnchorSyncState());
        assertTrue(f.ledger().stream().noneMatch(a->Set.of("RELEASE_SCHEDULE","SHIFT_RENEWAL").contains(a.getActionType())));
        if("unrelated_review".equals(condition))assertEquals("UNRELATED_FINANCIAL_REVIEW",f.root().getReviewReason());
        if("in_progress".equals(condition))assertEquals("IN_PROGRESS",f.root().getReviewState());
        assertEquals(original,f.root().getContractSnapshot());verify(stripe,never()).execute(any());
    }

    private PostAdmissionContractFixture completedIntroContractFixture() {
        var f=postAdmissionContractFixture(true);var snapshot=f.remote().get();
        var nativeSchedule=com.google.gson.JsonParser.parseString(snapshot.schedule().toJson()).getAsJsonObject();
        var current=nativeSchedule.getAsJsonArray("phases").get(0).deepCopy().getAsJsonObject();
        var intro=current.deepCopy();long start=current.get("start_date").getAsLong();
        intro.addProperty("start_date",start-7*86400);intro.addProperty("end_date",start);
        intro.getAsJsonArray("items").get(0).getAsJsonObject().addProperty("price","price_intro_history");
        var phases=new com.google.gson.JsonArray();phases.add(intro);phases.add(current);nativeSchedule.add("phases",phases);
        var schedule=ApiResource.GSON.fromJson(nativeSchedule,SubscriptionSchedule.class);
        var classified=RecoveryContractClassifier.classify(snapshot.subscription(),schedule,true,"price_old",1L);
        var saved=com.google.gson.JsonParser.parseString(f.root().getContractSnapshot()).getAsJsonObject();
        var admission=saved.getAsJsonObject("recovery_admission");
        admission.addProperty("sourceKey","pro_trial_to_monthly");admission.addProperty("scheduleClass","EQUIVALENT_SCHEDULE");
        admission.addProperty("caseKey","pro_trial_to_monthly:pro_monthly:month_1:EQUIVALENT_SCHEDULE:RENEW");
        admission.addProperty("contractHash",classified.contractHash());
        admission.add("scheduleContract",com.google.gson.JsonParser.parseString(classified.contractJson()));
        f.root().setContractSnapshot(saved.toString());snapshot.invoice().setBillingReason("subscription_update");
        f.remote().set(new SubscriptionRecoveryStripeGateway.Snapshot(snapshot.invoice(),snapshot.subscription(),schedule,
                snapshot.lines(),snapshot.paymentIntent(),snapshot.charge(),snapshot.businessNow()));
        return f;
    }

    private SubscriptionRecoveryStripeGateway.Snapshot prunedIntroSnapshot(SubscriptionRecoveryStripeGateway.Snapshot snapshot,String change) {
        var rawSchedule=com.google.gson.JsonParser.parseString(snapshot.schedule().toJson()).getAsJsonObject();
        rawSchedule.getAsJsonArray("phases").remove(0);
        var rawSubscription=com.google.gson.JsonParser.parseString(snapshot.subscription().toJson()).getAsJsonObject();
        if(change!=null)switch(change){
            case "automatic_tax" -> rawSubscription.add("automatic_tax",com.google.gson.JsonParser.parseString("{\"enabled\":true}"));
            case "collection_method" -> rawSubscription.addProperty("collection_method","send_invoice");
            case "default_tax_rates" -> rawSubscription.add("default_tax_rates",com.google.gson.JsonParser.parseString("[{\"id\":\"txr_new\",\"object\":\"tax_rate\"}]"));
            case "phase_tax" -> rawSchedule.getAsJsonArray("phases").get(0).getAsJsonObject().add("default_tax_rates",com.google.gson.JsonParser.parseString("[{\"id\":\"txr_new\",\"object\":\"tax_rate\"}]"));
            case "phase_discount" -> rawSchedule.getAsJsonArray("phases").get(0).getAsJsonObject().addProperty("coupon","co_new");
            case "phase_end" -> {long end=rawSchedule.getAsJsonObject("current_phase").get("end_date").getAsLong()+86400;rawSchedule.getAsJsonObject("current_phase").addProperty("end_date",end);rawSchedule.getAsJsonArray("phases").get(0).getAsJsonObject().addProperty("end_date",end);}
            default -> throw new IllegalArgumentException(change);
        }
        return new SubscriptionRecoveryStripeGateway.Snapshot(snapshot.invoice(),ApiResource.GSON.fromJson(rawSubscription,Subscription.class),
                ApiResource.GSON.fromJson(rawSchedule,SubscriptionSchedule.class),snapshot.lines(),snapshot.paymentIntent(),snapshot.charge(),snapshot.businessNow());
    }

    private record PostAdmissionContractFixture(SubscriptionPaymentRecoveryEntity root,
            java.util.concurrent.atomic.AtomicReference<SubscriptionRecoveryStripeGateway.Snapshot> remote,
            List<SubscriptionRecoveryActionEntity> ledger,List<RechargeOrderEntity> history,
            UserSubscriptionEntity user,com.studyagent.service.domain.billing.BillingQuotaGateway quota){}
    private PostAdmissionContractFixture postAdmissionContractFixture(boolean scheduled){
        enable();var paid=paidSnapshot();var root=existingRoot();root.setDisplayTier("pro");
        var pause=new Subscription.PauseCollection();pause.setBehavior("keep_as_draft");paid.subscription().setPauseCollection(pause);
        SubscriptionSchedule schedule=null;
        if(scheduled){
            paid.subscription().setSchedule("sched_original");
            schedule=ApiResource.GSON.fromJson("{\"id\":\"sched_original\",\"customer\":\"cus_old\",\"subscription\":\"sub_old\",\"status\":\"active\",\"end_behavior\":\"release\",\"default_settings\":{\"collection_method\":\"charge_automatically\"},\"current_phase\":{\"start_date\":1794362400,\"end_date\":1796954400},\"phases\":[{\"start_date\":1794362400,\"end_date\":1796954400,\"items\":[{\"price\":\"price_old\",\"quantity\":1}]}]}",SubscriptionSchedule.class);
        }
        var classified=RecoveryContractClassifier.classify(paid.subscription(),schedule,true,"price_old",1L);
        assertEquals(scheduled?"EQUIVALENT_SCHEDULE":"NO_SCHEDULE",classified.scheduleClass());
        root.setContractSnapshot("{\"price_id\":\"price_old\",\"quantity\":1,\"recovery_admission\":{\"sourceKey\":\"formal\",\"cancelMode\":\"RENEW\",\"silentCleanupVerified\":true,\"contractHash\":\""+classified.contractHash()+"\"}}");
        var snapshot=new SubscriptionRecoveryStripeGateway.Snapshot(paid.invoice(),paid.subscription(),schedule,paid.lines(),paid.paymentIntent(),paid.charge(),now);
        ownedFixture(root,snapshot);var quota=injectQuota();var ledger=failureLedger();bindSequenceActions(ledger);
        var remote=new java.util.concurrent.atomic.AtomicReference<>(snapshot);
        when(stripe.readInvoice("in_old")).thenAnswer(call->remote.get());when(stripe.listInvoices("sub_old")).thenAnswer(call->List.of(remote.get().invoice()));
        var history=new ArrayList<RechargeOrderEntity>();when(orders.selectOne(any())).thenAnswer(call->history.isEmpty()?null:history.get(0));
        when(orders.insert(any(RechargeOrderEntity.class))).thenAnswer(call->{history.add(call.getArgument(0));return 1;});
        return new PostAdmissionContractFixture(root,remote,ledger,history,users.selectByUserForUpdate("user"),quota);
    }
    private SubscriptionRecoveryStripeGateway.Snapshot changeAdmittedContract(SubscriptionRecoveryStripeGateway.Snapshot snapshot,String change){
        var subscription=snapshot.subscription();SubscriptionSchedule schedule=snapshot.schedule();
        switch(change){
            case "price" -> {var otherPrice=new Price();otherPrice.setId("price_changed_after_admission");subscription.getItems().getData().get(0).setPrice(otherPrice);}
            case "quantity" -> subscription.getItems().getData().get(0).setQuantity(2L);
            case "fixed_cancel" -> {subscription.setCancelAtPeriodEnd(false);subscription.setCancelAt(now.plusSeconds(7*86400).getEpochSecond());}
            case "schedule_unknown" -> {subscription.setSchedule("sched_unreadable_after_admission");schedule=null;}
            default -> throw new IllegalArgumentException(change);
        }
        return new SubscriptionRecoveryStripeGateway.Snapshot(snapshot.invoice(),subscription,schedule,snapshot.lines(),snapshot.paymentIntent(),snapshot.charge(),snapshot.businessNow());
    }
    private void assertOriginalCashPeriodAndOneGrant(PostAdmissionContractFixture f){
        var end=SubscriptionRecoveryPolicy.paidPeriodEnd(now,"month",1);var quotaEnd=SubscriptionRecoveryPolicy.quotaPeriodEnd(now,"month",1);
        assertEquals("PAID",f.root().getStatus());assertEquals("GRANTED",f.root().getGrantState());assertEquals("in_old",f.root().getEntitlementInvoiceId());
        assertEquals(com.studyagent.common.datetime.DateTimeFormats.fromInstant(now),f.root().getEntitlementStartAt());
        assertEquals(com.studyagent.common.datetime.DateTimeFormats.fromInstant(end),f.root().getEntitlementEndAt());
        assertEquals(f.root().getEntitlementStartAt(),f.user().getCurrentPeriodStart());assertEquals(f.root().getEntitlementEndAt(),f.user().getCurrentPeriodEnd());
        assertEquals("pro_monthly",f.user().getPlanCode());assertNull(f.root().getAccountClosedAt());
        assertEquals(1,f.history().size());assertEquals("in_old",f.history().get(0).getStripeInvoiceId());assertEquals("completed",f.history().get(0).getStatus());
        verify(f.quota(),times(1)).resetFromPaidInvoiceAt("user","sub_old","pro_monthly",now,quotaEnd,"in_old",now);
        verify(f.quota(),never()).clearPlanQuotaAt(anyString(),anyString(),anyString(),anyString(),any());
    }

    @Test void discountedTaxedOriginalInvoiceKeepsItsActualAmountsAfterTheCurrentPriceChanges() {
        enable();var original=snapshot();
        var nativeLine=ApiResource.GSON.fromJson(original.lines().get(0).toJson(),com.google.gson.JsonObject.class);
        nativeLine.addProperty("amount",10000L);nativeLine.getAsJsonObject("price").addProperty("unit_amount",10000L);
        nativeLine.add("discount_amounts",com.google.gson.JsonParser.parseString("[{\"amount\":2000,\"discount\":\"di_original\"}]"));
        nativeLine.add("tax_amounts",com.google.gson.JsonParser.parseString("[{\"amount\":800,\"inclusive\":false,\"tax_rate\":\"txr_original\"}]"));
        var line=ApiResource.GSON.fromJson(nativeLine,InvoiceLineItem.class);
        original.invoice().setSubtotal(10000L);original.invoice().setTotal(8800L);original.invoice().setStartingBalance(-200L);
        original.invoice().setAmountDue(8600L);original.invoice().setAmountPaid(0L);original.invoice().setAmountRemaining(8600L);
        var snap=new SubscriptionRecoveryStripeGateway.Snapshot(original.invoice(),original.subscription(),null,List.of(line),original.paymentIntent(),original.charge(),now);
        when(stripe.readInvoice("in_old")).thenReturn(snap);
        var user=user();when(users.selectByUserForUpdate("user")).thenReturn(user);when(tx.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(recoveries.insert(any(SubscriptionPaymentRecoveryEntity.class))).thenAnswer(call->{((SubscriptionPaymentRecoveryEntity)call.getArgument(0)).setId(1L);return 1;});
        var ledger=failureLedger();assertTrue(service.tryProcessEvent(event()));
        var captured=org.mockito.ArgumentCaptor.forClass(SubscriptionPaymentRecoveryEntity.class);verify(recoveries).insert(captured.capture());var root=captured.getValue();
        assertEquals(8600L,root.getAmountDueMinor());assertEquals(0L,root.getAmountPaidMinor());assertEquals(8600L,root.getAmountRemainingMinor());assertEquals("usd",root.getCurrency());
        var saved=com.google.gson.JsonParser.parseString(root.getContractSnapshot()).getAsJsonObject();
        assertEquals("price_old",saved.get("price_id").getAsString());assertEquals("si_old",saved.get("subscription_item_id").getAsString());assertEquals("il_old",saved.get("line_id").getAsString());
        assertEquals(1794362400L,saved.get("period_start").getAsLong());assertEquals(1796954400L,saved.get("period_end").getAsLong());assertEquals(1L,saved.get("quantity").getAsLong());
        assertEquals(10000L,saved.get("unit_amount").getAsLong());assertEquals(8800L,saved.get("invoice_total").getAsLong());assertEquals(8600L,saved.get("invoice_amount_due").getAsLong());
        assertEquals(2000L,saved.getAsJsonArray("discounts").get(0).getAsJsonObject().get("amount").getAsLong());
        assertEquals(800L,saved.getAsJsonArray("taxes").get(0).getAsJsonObject().get("amount").getAsLong());
        assertFalse(saved.getAsJsonArray("taxes").get(0).getAsJsonObject().get("inclusive").getAsBoolean());
        String frozen=root.getContractSnapshot();
        // This invoice is already owned. A changed current contract must not rewrite its original cash/Price snapshot.
        var current=ApiResource.GSON.fromJson(snap.subscription().toJson(),Subscription.class);
        var currentPrice=new Price();currentPrice.setId("price_current");currentPrice.setUnitAmount(99000L);current.getItems().getData().get(0).setPrice(currentPrice);
        var changed=new SubscriptionRecoveryStripeGateway.Snapshot(snap.invoice(),current,null,snap.lines(),snap.paymentIntent(),snap.charge(),now);
        ownedFixture(root,changed);clearInvocations(plans);assertTrue(service.tryProcessEvent(event()));
        assertEquals(frozen,root.getContractSnapshot());assertEquals(8600L,root.getAmountDueMinor());assertEquals(0L,root.getAmountPaidMinor());assertEquals(8600L,root.getAmountRemainingMinor());
        assertEquals("price_current",current.getItems().getData().get(0).getPrice().getId());assertEquals("price_old",changed.lines().get(0).getPrice().getId());
        verify(plans,never()).selectList(any());verify(orders,never()).insert(any(RechargeOrderEntity.class));verify(stripe,never()).execute(any());
        for(var action:ledger)if(Set.of("CAPTURE_FAILURE","NOTIFY_INTERNAL_FAILURE").contains(action.getActionType())){
            var payload=com.google.gson.JsonParser.parseString(action.getPayloadJson()).getAsJsonObject();assertEquals(8600L,payload.get("amount_minor").getAsLong());assertEquals("usd",payload.get("currency").getAsString());
        }
    }

    @Test void actionRequiredReplaysKeepTheOwnedWindowGraceAndCanceledFixedSlots() {
        var snap=snapshot();snap.paymentIntent().setStatus("requires_action");
        var root=existingRoot();root.setDeadlineAt(com.studyagent.common.datetime.DateTimeFormats.fromInstant(now.plusSeconds(30*86400L)));
        ownedFixture(root,snap);var user=users.selectByUserForUpdate("user");var grace=user.getGraceEndAt();var first=root.getFirstFailedAt();var deadline=root.getDeadlineAt();
        var ledger=failureLedger();var scheduled=new ArrayList<java.time.LocalDateTime>();
        for(int slot=1;slot<=4;slot++){
            var retry=new SubscriptionRecoveryActionEntity();retry.setId((long)slot);retry.setActionType("RETRY_PAY");retry.setStatus("READY");retry.setRetrySlot(slot);
            retry.setScheduledAt(com.studyagent.common.datetime.DateTimeFormats.fromInstant(now.plusSeconds(slot*7*86400L)));retry.setProviderIdempotencyKey("unchanged-slot-"+slot);retry.setPayloadJson("{\"off_session\":true}");scheduled.add(retry.getScheduledAt());ledger.add(retry);
        }
        var required=event();required.setType("invoice.payment_action_required");assertTrue(service.tryProcessEvent(required));assertTrue(service.tryProcessEvent(required));
        assertEquals("OPEN_MANUAL",root.getStatus());assertEquals(first,root.getFirstFailedAt());assertEquals(deadline,root.getDeadlineAt());assertEquals(grace,user.getGraceEndAt());
        var retries=ledger.stream().filter(a->"RETRY_PAY".equals(a.getActionType())).toList();assertEquals(4,retries.size());
        for(int i=0;i<4;i++){var retry=retries.get(i);assertEquals("SKIPPED",retry.getStatus());assertEquals("MANUAL_ONLY",retry.getSkipReason());assertEquals(scheduled.get(i),retry.getScheduledAt());assertEquals("unchanged-slot-"+(i+1),retry.getProviderIdempotencyKey());assertEquals("{\"off_session\":true}",retry.getPayloadJson());}
        assertEquals(2,ledger.stream().filter(a->Set.of("CAPTURE_FAILURE","NOTIFY_INTERNAL_FAILURE").contains(a.getActionType())).count());
        for(var action:ledger)if(Set.of("CAPTURE_FAILURE","NOTIFY_INTERNAL_FAILURE").contains(action.getActionType())){
            var payload=com.google.gson.JsonParser.parseString(action.getPayloadJson()).getAsJsonObject();assertTrue(payload.get("requires_action").getAsBoolean());assertFalse(payload.get("actual_attempt").getAsBoolean());
        }
        assertEquals(0,ledger.stream().filter(a->"SEND_EMAIL".equals(a.getActionType())).count());verify(orders,never()).insert(any(RechargeOrderEntity.class));verify(stripe,never()).execute(any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"FINALIZE_INVOICE","VOID_INVOICE"})
    void verifiedTrialRolloverCanCloseWithFrozenEvidence(String type){
        var f=trialCloseFixture(type);String saved=f.root().getContractSnapshot(),key=f.action().getProviderIdempotencyKey(),body=f.action().getPayloadJson();var end=f.root().getEntitlementEndAt();
        when(stripe.execute(any())).thenAnswer(call->{var a=(SubscriptionRecoveryActionEntity)call.getArgument(0);assertEquals(key,a.getProviderIdempotencyKey());assertEquals(body,a.getPayloadJson());f.target().invoice().setStatus("FINALIZE_INVOICE".equals(type)?"open":"void");return new SubscriptionRecoveryStripeGateway.WriteResult("SUCCEEDED","req_trial_close",null,f.target().invoice());});
        service.runDueAction(10L);service.runDueAction(10L);
        assertEquals("SUCCEEDED",f.action().getStatus());verify(stripe,times(1)).execute(any());assertEquals(saved,f.root().getContractSnapshot());assertEquals(end,f.root().getEntitlementEndAt());assertEquals("PAID",f.root().getStatus());assertEquals("GRANTED",f.root().getGrantState());assertRecoveryHistoryWasNotDeleted();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"requires_payment_method","canceled"})
    void verifiedUnpaidTrialAttemptCanCloseWithoutDiscardingItsHistory(String status){
        var f=trialCloseFixture("VOID_INVOICE");var t=f.target();var pi=new PaymentIntent();pi.setStatus(status);pi.setAmountReceived(0L);
        var charge=new Charge();charge.setStatus("failed");charge.setPaid(false);charge.setAmountCaptured(0L);
        when(stripe.readInvoice("in_trial_extra")).thenReturn(new SubscriptionRecoveryStripeGateway.Snapshot(t.invoice(),t.subscription(),null,t.lines(),pi,charge,now));
        when(stripe.execute(any())).thenReturn(new SubscriptionRecoveryStripeGateway.WriteResult("SUCCEEDED","req_trial_unpaid",null,null));
        service.runDueAction(10L);service.runDueAction(10L);
        assertEquals("SUCCEEDED",f.action().getStatus());assertEquals("PAID",f.root().getStatus());assertEquals("GRANTED",f.root().getGrantState());
        verify(stripe,times(1)).execute(any());assertRecoveryHistoryWasNotDeleted();
    }
    @Test
    void protectedTrialFinalizationMayCreateAnUnattemptedConfirmationIntent(){
        var f=trialCloseFixture("VOID_INVOICE");var t=f.target();var pi=new PaymentIntent();pi.setStatus("requires_confirmation");pi.setAmountReceived(0L);
        when(stripe.readInvoice("in_trial_extra")).thenReturn(new SubscriptionRecoveryStripeGateway.Snapshot(t.invoice(),t.subscription(),null,t.lines(),pi,null,now));
        when(stripe.execute(any())).thenReturn(new SubscriptionRecoveryStripeGateway.WriteResult("SUCCEEDED","req_trial_unattempted",null,null));
        service.runDueAction(10L);assertEquals("SUCCEEDED",f.action().getStatus());assertEquals("GRANTED",f.root().getGrantState());verify(stripe,times(1)).execute(any());assertRecoveryHistoryWasNotDeleted();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"missing_proof","old_proof","formal_source","wrong_trial_source","wrong_proof_customer","wrong_proof_subscription","unpaid_trial","out_of_band_trial","proof_quantity","proof_item","not_first_formal","proof_price","proof_boundary","target_price","target_item","target_quantity","target_currency","price_currency","target_period_start","target_period_end","proration","mixed_goods","wrong_reason","unprotected","wrong_livemode","needs_auth","needs_confirmation","confirmation_action","confirmation_error","unknown_pi_amount","unknown_charge_amount"})
    void ambiguousTrialRolloverNeverAcquiresAClosingLeaseOrChangesPaidRights(String change){
        var f=trialCloseFixture("FINALIZE_INVOICE");var json=com.google.gson.JsonParser.parseString(f.root().getContractSnapshot()).getAsJsonObject();var proof=json.getAsJsonObject("source_provenance");var prior=proof.getAsJsonObject("trial_invoice");var first=proof.getAsJsonObject("first_formal_invoice");var line=f.target().lines().get(0);
        switch(change){
            case "missing_proof"->json.remove("source_provenance");case "old_proof"->prior.remove("paid_out_of_band");case "formal_source"->json.getAsJsonObject("recovery_admission").addProperty("sourceKey","formal");case "wrong_trial_source"->proof.addProperty("original_trial_source","basic_trial_to_monthly");
            case "wrong_proof_customer"->prior.addProperty("customer_id","cus_other");case "wrong_proof_subscription"->prior.addProperty("subscription_id","sub_other");case "unpaid_trial"->prior.addProperty("status","open");case "out_of_band_trial"->prior.addProperty("paid_out_of_band",true);case "proof_quantity"->prior.addProperty("quantity",2);case "proof_item"->prior.addProperty("subscription_item_id","si_other");case "not_first_formal"->first.addProperty("invoice_id","in_other");case "proof_price"->first.addProperty("price_id","price_other");case "proof_boundary"->prior.addProperty("period_end",now.minusSeconds(1).getEpochSecond());
            case "target_price"->line.getPrice().setId("price_other");case "target_item"->line.setSubscriptionItem("si_other");case "target_quantity"->line.setQuantity(2L);case "target_currency"->f.target().invoice().setCurrency("eur");case "price_currency"->line.getPrice().setCurrency("eur");case "target_period_start"->line.getPeriod().setStart(now.plusSeconds(1).getEpochSecond());case "target_period_end"->line.getPeriod().setEnd(now.plusSeconds(14*86400).getEpochSecond());case "proration"->line.setProration(true);
            case "mixed_goods"->{var extra=ApiResource.GSON.fromJson(line.toJson(),InvoiceLineItem.class);extra.setType("invoiceitem");var original=f.target();var mixed=new SubscriptionRecoveryStripeGateway.Snapshot(original.invoice(),original.subscription(),null,List.of(line,extra),null,null,now);when(stripe.readInvoice("in_trial_extra")).thenReturn(mixed);}
            case "confirmation_action","confirmation_error"->{var pi=new PaymentIntent();pi.setStatus("requires_confirmation");pi.setAmountReceived(0L);if("confirmation_action".equals(change))pi.setNextAction(new PaymentIntent.NextAction());else pi.setLastPaymentError(new com.stripe.model.StripeError());var t=f.target();when(stripe.readInvoice("in_trial_extra")).thenReturn(new SubscriptionRecoveryStripeGateway.Snapshot(t.invoice(),t.subscription(),null,t.lines(),pi,null,now));}
            case "needs_auth","needs_confirmation","unknown_pi_amount"->{var pi=new PaymentIntent();pi.setStatus("needs_auth".equals(change)?"requires_action":"needs_confirmation".equals(change)?"requires_confirmation":"requires_payment_method");if(!"unknown_pi_amount".equals(change))pi.setAmountReceived(0L);if("needs_confirmation".equals(change))pi.setLatestCharge("ch_unverified");var t=f.target();when(stripe.readInvoice("in_trial_extra")).thenReturn(new SubscriptionRecoveryStripeGateway.Snapshot(t.invoice(),t.subscription(),null,t.lines(),pi,null,now));}
            case "unknown_charge_amount"->{var ch=new Charge();ch.setStatus("failed");ch.setPaid(false);var t=f.target();when(stripe.readInvoice("in_trial_extra")).thenReturn(new SubscriptionRecoveryStripeGateway.Snapshot(t.invoice(),t.subscription(),null,t.lines(),null,ch,now));}
            case "wrong_reason"->f.target().invoice().setBillingReason("manual");case "unprotected"->f.target().invoice().setAutoAdvance(true);case "wrong_livemode"->f.target().invoice().setLivemode(true);default->throw new AssertionError(change);
        }
        f.root().setContractSnapshot(json.toString());var saved=f.root().getContractSnapshot();var end=f.root().getEntitlementEndAt();when(stripe.execute(any())).thenReturn(new SubscriptionRecoveryStripeGateway.WriteResult("UNKNOWN","req_unexpected_close",null,null));service.runDueAction(10L);
        assertEquals("SKIPPED",f.action().getStatus());assertEquals("SKIPPED_UNVERIFIED_TARGET",f.action().getSkipReason());assertEquals("REVIEW",f.root().getAnchorSyncState());assertEquals("UNVERIFIED_OR_MIXED_INVOICE_CLOSE_TARGET",f.root().getReviewReason());assertEquals("PAID",f.root().getStatus());assertEquals("GRANTED",f.root().getGrantState());assertEquals(end,f.root().getEntitlementEndAt());assertEquals(saved,f.root().getContractSnapshot());verify(actions,never()).claim(anyLong(),anyInt(),anyString(),any(),any());verify(stripe,never()).execute(any());assertRecoveryHistoryWasNotDeleted();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"paid","processing","cash_ahead"})
    void trialRolloverWithNativeCashOrPaymentInFlightWaitsBeforeClosing(String state){
        var f=trialCloseFixture("VOID_INVOICE");var t=f.target();PaymentIntent pi=null;
        if("paid".equals(state))t.invoice().setAmountPaid(100L);else{pi=new PaymentIntent();pi.setStatus("processing".equals(state)?"processing":"succeeded");pi.setAmountReceived("cash_ahead".equals(state)?100L:0L);}
        when(stripe.readInvoice("in_trial_extra")).thenReturn(new SubscriptionRecoveryStripeGateway.Snapshot(t.invoice(),t.subscription(),null,t.lines(),pi,null,now));
        service.runDueAction(10L);assertEquals("READY",f.action().getStatus());assertEquals("PAYMENT_DURING_INVOICE_CLOSE",f.root().getReviewReason());assertEquals("GRANTED",f.root().getGrantState());verify(actions,never()).claim(anyLong(),anyInt(),anyString(),any(),any());verify(stripe,never()).execute(any());
    }
    private record TrialCloseFixture(SubscriptionPaymentRecoveryEntity root,SubscriptionRecoveryActionEntity action,SubscriptionRecoveryStripeGateway.Snapshot target){}
    private TrialCloseFixture trialCloseFixture(String type){
        var paid=paidSnapshot();var root=confirmedPaidRoot(paid);root.setStripeLivemode(false);root.setPlanCode("pro_monthly");
        long boundary=now.getEpochSecond();var prior=new LinkedHashMap<String,Object>();prior.put("invoice_id","in_trial_paid");prior.put("customer_id","cus_old");prior.put("subscription_id","sub_old");prior.put("price_id","price_intro");prior.put("plan_code","pro_trial_to_monthly");prior.put("period_start",boundary-7*86400L);prior.put("period_end",boundary);prior.put("status","paid");prior.put("amount_paid",299L);prior.put("currency","usd");prior.put("quantity",1L);prior.put("subscription_item_id","si_old");prior.put("paid_out_of_band",false);prior.put("recurring_interval","week");prior.put("recurring_interval_count",1L);
        var first=new LinkedHashMap<>(prior);first.put("invoice_id","in_old");first.put("price_id","price_old");first.put("plan_code","pro_monthly");first.put("period_start",boundary);first.put("period_end",boundary+30*86400L);first.put("status","open");first.put("amount_paid",0L);first.put("recurring_interval","month");
        var saved=new LinkedHashMap<String,Object>();saved.put("price_id","price_old");saved.put("quantity",1L);saved.put("subscription_item_id","si_old");saved.put("period_start",boundary);saved.put("period_end",boundary+30*86400L);saved.put("recovery_admission",Map.of("sourceKey","pro_trial_to_monthly","cancelMode","RENEW","silentCleanupVerified",true));saved.put("source_provenance",Map.of("kind","historical_invoice_pair","source","pro_trial_to_monthly","original_trial_source","pro_trial_to_monthly","first_formal_period_start",boundary,"trial_invoice",prior,"first_formal_invoice",first));root.setContractSnapshot(new com.google.gson.Gson().toJson(saved));ownedFixture(root,paid);
        var other=snapshot();other.invoice().setId("in_trial_extra");other.invoice().setStatus("FINALIZE_INVOICE".equals(type)?"draft":"open");other.invoice().setAutoAdvance(false);other.invoice().setAmountDue(299L);other.invoice().setAmountRemaining(299L);other.invoice().setPaymentIntent((String)null);var line=other.lines().get(0);line.getPrice().setId("price_intro");line.getPrice().getRecurring().setInterval("week");line.getPeriod().setStart(boundary);line.getPeriod().setEnd(boundary+7*86400L);var target=new SubscriptionRecoveryStripeGateway.Snapshot(other.invoice(),other.subscription(),null,other.lines(),null,null,now);when(stripe.readInvoice("in_trial_extra")).thenReturn(target);
        var action=readyAction(type,"in_trial_extra");action.setActionKey("recovery:1:"+type+":in_trial_extra");action.setProviderIdempotencyKey(action.getActionKey());action.setPayloadJson("FINALIZE_INVOICE".equals(type)?"{\"auto_advance\":false}":"{}");var ledger=failureLedger();ledger.add(action);bindSequenceActions(ledger);return new TrialCloseFixture(root,action,target);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"pro_trial_to_yearly,pro_yearly,year","pro_trial_to_yearly,pro_monthly,month"})
    void originalYearlyTrialEvidenceSupportsOnlyItsVerifiedFormalTarget(String source,String target,String interval){
        var f=trialCloseFixture("VOID_INVOICE");var paid=stripe.readInvoice("in_old");paid.lines().get(0).getPrice().getRecurring().setInterval(interval);f.root().setPlanCode(target);f.root().setBillingInterval(interval);
        var json=com.google.gson.JsonParser.parseString(f.root().getContractSnapshot()).getAsJsonObject();json.getAsJsonObject("recovery_admission").addProperty("sourceKey",source);var p=json.getAsJsonObject("source_provenance");p.addProperty("source",source);p.addProperty("original_trial_source",source);p.getAsJsonObject("trial_invoice").addProperty("plan_code",source);var first=p.getAsJsonObject("first_formal_invoice");first.addProperty("plan_code",target);first.addProperty("recurring_interval",interval);
        long end=SubscriptionRecoveryPolicy.paidPeriodEnd(now,interval,1).getEpochSecond();json.addProperty("period_end",end);first.addProperty("period_end",end);f.root().setContractSnapshot(json.toString());f.root().setEntitlementEndAt(com.studyagent.common.datetime.DateTimeFormats.fromInstant(java.time.Instant.ofEpochSecond(end)));
        var original=f.root().getContractSnapshot();when(stripe.execute(any())).thenReturn(new SubscriptionRecoveryStripeGateway.WriteResult("SUCCEEDED","req_trial_year",null,null));service.runDueAction(10L);assertEquals("SUCCEEDED",f.action().getStatus());assertEquals(original,f.root().getContractSnapshot());assertEquals("GRANTED",f.root().getGrantState());verify(stripe,times(1)).execute(any());
    }

}
