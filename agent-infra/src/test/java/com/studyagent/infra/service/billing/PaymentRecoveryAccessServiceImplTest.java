package com.studyagent.infra.service.billing;

import com.studyagent.common.datetime.DateTimeFormats;
import com.studyagent.infra.entity.*;
import com.studyagent.infra.mapper.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentRecoveryAccessServiceImplTest {
    private final UserSubscriptionMapper users = mock(UserSubscriptionMapper.class);
    private final SubscriptionPaymentRecoveryMapper recoveries = mock(SubscriptionPaymentRecoveryMapper.class);
    private final SubscriptionRecoveryActionMapper actions = mock(SubscriptionRecoveryActionMapper.class);
    private final SubscriptionRecoveryReplacementMapper replacements = mock(SubscriptionRecoveryReplacementMapper.class);
    private final Instant now = Instant.parse("2026-11-11T02:00:00Z");
    private final PaymentRecoveryAccessServiceImpl service = new PaymentRecoveryAccessServiceImpl(users, recoveries, actions, replacements, Clock.fixed(now, ZoneOffset.UTC));

    @Test void admissionUsesCurrentLockedRowsInsteadOfAnEarlierRepeatableReadSnapshot() {
        var recovery = fixture("OPEN_AUTO");
        var freshUser = users.selectByUser("user");
        when(users.selectByUserForUpdate("user")).thenReturn(freshUser);
        when(recoveries.selectForUpdate(1L)).thenReturn(recovery);
        clearInvocations(users, recoveries);
        assertTrue(service.resolveForUpdate("user", "sub_old").locked());
        verify(users).selectByUserForUpdate("user");
        verify(recoveries).selectForUpdate(1L);
        verify(users, never()).selectByUser(anyString());
        verify(recoveries, never()).selectById(anyLong());
    }
    @Test void nullLegacyPointerPreservesLegacyPath() {
        UserSubscriptionEntity u = new UserSubscriptionEntity(); u.setStripeSubscriptionId("sub_old");
        when(users.selectByUser("user")).thenReturn(u);
        assertNull(service.resolve("user", "sub_old").summary());
        verifyNoInteractions(recoveries, actions, replacements);
    }
    @Test void oldRecoveryCannotLockNewSubscription() {
        fixture("OPEN_AUTO");
        assertNull(service.resolve("user", "sub_new").summary());
    }
    @Test void unpaidIsLockedEvenWhenProtectionHasNotCompletedAndNoActionIsOffered() {
        var r = fixture("OPEN_AUTO"); r.setProtectionState("PENDING");
        var access = service.resolve("user", "sub_old");
        assertTrue(access.locked()); assertFalse(access.paidCycleValid());
        assertEquals(List.of(), access.summary().allowedActions());
        assertEquals("pro", access.summary().displayTier());
    }
    @Test void paidPeriodSurvivesRemoteCanceledAndAnchorReview() {
        var r = fixture("PAID"); r.setGrantState("GRANTED"); r.setAnchorSyncState("REVIEW");
        r.setEntitlementStartAt(DateTimeFormats.fromInstant(now.minusSeconds(60)));
        r.setEntitlementEndAt(DateTimeFormats.fromInstant(now.plusSeconds(3600)));
        var access = service.resolve("user", "sub_old");
        assertFalse(access.locked()); assertTrue(access.paidCycleValid());
        assertEquals(now.plusSeconds(3600), access.paidPeriodEnd());
        assertTrue(access.summary().allowedActions().isEmpty());
    }
    @Test void endedHistoryDoesNotLockFreeOrOfferRecoveryPayment() {
        var r = fixture("EXPIRED"); r.setAccountClosedAt(DateTimeFormats.fromInstant(now));
        var u = users.selectByUser("user"); u.setStripeSubscriptionId(null); u.setTier("free");
        var access = service.resolve("user", null);
        assertFalse(access.locked()); assertFalse(access.paidCycleValid());
        assertEquals("EXPIRED", access.summary().state());
        assertTrue(access.summary().allowedActions().isEmpty());
    }
    @Test void amountSummaryComesFromSelectedInvoiceAndNotExtraPayments() {
        var r = fixture("PAID"); r.setEntitlementInvoiceId("in_child");
        var child = new SubscriptionPaymentRecoveryEntity(); child.setOwnerRecoveryId(1L);
        child.setClerkUserId("user");child.setStripeSubscriptionId("sub_old");
        child.setAmountDueMinor(2000L); child.setAmountPaidMinor(2000L); child.setAmountRemainingMinor(0L);child.setCurrency("usd");
        when(recoveries.selectByInvoice("in_child")).thenReturn(child);
        var summary = service.resolve("user", "sub_old").summary();
        assertEquals(2000L, summary.amountPaidMinor()); assertEquals("1", summary.recoveryId());
    }
    @Test void unknownStateFailsClosedAndNeverOffersPayment() {
        fixture("FUTURE_STATE");
        var access = service.resolve("user", "sub_old");
        assertTrue(access.locked()); assertTrue(access.summary().allowedActions().isEmpty());
    }
    @Test void aHeldPaidSourceCannotOfferAnotherChargeOrPaidAccess() {
        var r=fixture("PAID");r.setGrantState("HELD");
        var access=service.resolve("user","sub_old");
        assertTrue(access.locked());assertFalse(access.paidCycleValid());assertTrue(access.summary().allowedActions().isEmpty());
    }
    @Test void paidPeriodEndIsExclusive() {
        var r=fixture("PAID");r.setGrantState("GRANTED");
        r.setEntitlementStartAt(DateTimeFormats.fromInstant(now.minusSeconds(3600)));
        r.setEntitlementEndAt(DateTimeFormats.fromInstant(now));
        assertFalse(service.resolve("user","sub_old").paidCycleValid());
    }
    @Test void foreignUserOrSupplementaryPointerCannotBecomeCurrentRecovery() {
        var r=fixture("OPEN_AUTO");r.setClerkUserId("other");
        assertNull(service.resolve("user","sub_old").summary());
        r.setClerkUserId("user");r.setOwnerRecoveryId(2L);
        assertNull(service.resolve("user","sub_old").summary());
    }
    @Test void selectedInvoiceCannotBelongToAnotherWindow() {
        var r=fixture("PAID");r.setEntitlementInvoiceId("in_foreign");
        var foreign=new SubscriptionPaymentRecoveryEntity();foreign.setOwnerRecoveryId(999L);
        when(recoveries.selectByInvoice("in_foreign")).thenReturn(foreign);
        assertThrows(IllegalStateException.class,()->service.resolve("user","sub_old"));
    }
    @Test void protectedOpenWindowOffersOnlyRecoveryActionsAndActiveCheckoutLeavesOnlySafeCardUpdate() {
        var props=new PaymentRecoveryProperties();props.setPortalMethodOnlyConfigurationId("bpc_restricted");
        var resolver=new PaymentRecoveryAccessServiceImpl(users,recoveries,actions,replacements,Clock.fixed(now,ZoneOffset.UTC),props);
        var r=fixture("OPEN_AUTO");r.setStripeInvoiceStatus("open");
        assertEquals(List.of("pay_open_invoice","buy_replacement","update_payment_method"),resolver.resolve("user","sub_old").summary().allowedActions());
        var replacement=new SubscriptionRecoveryReplacementEntity();replacement.setState("CREATING");when(replacements.selectActive("sub_old")).thenReturn(replacement);
        assertEquals(List.of("update_payment_method"),resolver.resolve("user","sub_old").summary().allowedActions());
    }
    @Test void independentFinancialHoldCannotBeOverriddenByGrantedPaidPeriod() {
        var r=fixture("PAID");r.setGrantState("GRANTED");r.setDisputeState("OPEN");r.setEntitlementStartAt(DateTimeFormats.fromInstant(now.minusSeconds(60)));r.setEntitlementEndAt(DateTimeFormats.fromInstant(now.plusSeconds(3600)));
        var result=service.resolve("user","sub_old");assertTrue(result.locked());assertFalse(result.paidCycleValid());
    }
    @Test void refundOfExtraPrimaryInvoiceCannotFreezeTheSelectedChildPaidCycle() {
        var r=fixture("PAID");r.setGrantState("GRANTED");r.setEntitlementInvoiceId("in_child");r.setRefundState("FULL");r.setEntitlementStartAt(DateTimeFormats.fromInstant(now.minusSeconds(60)));r.setEntitlementEndAt(DateTimeFormats.fromInstant(now.plusSeconds(3600)));
        var child=new SubscriptionPaymentRecoveryEntity();child.setOwnerRecoveryId(1L);child.setClerkUserId("user");child.setStripeSubscriptionId("sub_old");child.setRefundState("NONE");child.setDisputeState("NONE");
        when(recoveries.selectByInvoice("in_child")).thenReturn(child);
        var result=service.resolve("user","sub_old");assertFalse(result.locked());assertTrue(result.paidCycleValid());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"RETRY_PAY","UPDATE_METHOD"})
    void nextRetryDoesNotAdvertiseASlotBlockedByUnknownPaymentOrMethodReview(String blockedType) {
        var root=fixture("OPEN_AUTO");root.setDeadlineAt(DateTimeFormats.fromInstant(now.plusSeconds(4*86400)));var config=new PaymentRecoveryProperties();config.setAutomaticPayEnabled(true);org.springframework.test.util.ReflectionTestUtils.setField(service,"config",config);
        var future=new SubscriptionRecoveryActionEntity();future.setActionType("RETRY_PAY");future.setStatus("READY");future.setScheduledAt(DateTimeFormats.fromInstant(now.plusSeconds(86400)));var blocked=new SubscriptionRecoveryActionEntity();blocked.setActionType(blockedType);blocked.setStatus("REVIEW");when(actions.selectByRecovery(1L)).thenReturn(List.of(future,blocked));
        assertNull(service.resolve("user","sub_old").summary().nextRetryAt());
    }
    private SubscriptionPaymentRecoveryEntity fixture(String state) {
        var u = new UserSubscriptionEntity();u.setClerkUserId("user"); u.setStripeSubscriptionId("sub_old");u.setPaymentRecoveryId(1L);u.setStatus("canceled");
        when(users.selectByUser("user")).thenReturn(u);
        var r = new SubscriptionPaymentRecoveryEntity();r.setId(1L);r.setClerkUserId("user");r.setStripeSubscriptionId("sub_old");
        r.setStripeInvoiceId("in_old");r.setStatus(state);r.setDisplayTier("pro");r.setPlanCode("pro_monthly");
        r.setFirstFailedAt(DateTimeFormats.fromInstant(now.minusSeconds(86400)));r.setDeadlineAt(DateTimeFormats.fromInstant(now.plusSeconds(29*86400)));
        r.setAmountDueMinor(2900L);r.setAmountPaidMinor(0L);r.setAmountRemainingMinor(2900L);r.setCurrency("usd");
        r.setProtectionState("ACTIVE");r.setGrantState("NONE");r.setAnchorSyncState("NONE");r.setReviewState("NONE");
        when(recoveries.selectById(1L)).thenReturn(r);
        return r;
    }
}
