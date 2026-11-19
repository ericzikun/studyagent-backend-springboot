package com.studyagent.infra.service.billing;

import com.stripe.model.*;
import com.stripe.model.testhelpers.TestClock;
import com.studyagent.infra.entity.UserSubscriptionEntity;
import com.studyagent.infra.mapper.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import com.stripe.net.ApiResource;
import com.studyagent.infra.entity.SubscriptionPaymentRecoveryEntity;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RecoveryTestClockSupportTest {
    @TempDir Path directory;
    RecoveryTestClockStripe stripe=mock(RecoveryTestClockStripe.class);
    UserSubscriptionMapper users=mock(UserSubscriptionMapper.class);
    SubscriptionPaymentRecoveryMapper roots=mock(SubscriptionPaymentRecoveryMapper.class);
    PlatformTransactionManager transactions=mock(PlatformTransactionManager.class);
    String runId="recovery-clock-unit",owner="user_clock_test";
    RecoveryTestClockProperties properties;
    @BeforeEach void setup()throws Exception{
        properties=new RecoveryTestClockProperties();properties.setEnabled(true);properties.setDirectory(directory.toString());properties.setAllowedUserIds(Set.of(owner));properties.setOperatorToken("independent-test-operator-token-longer-than-thirty-two");
        var c=new Customer();c.setId("cus_owned");c.setLivemode(false);c.setTestClock("clock_owned");c.setMetadata(Map.of("recovery_test_run",runId,"clerk_user_id",owner));when(stripe.customer("cus_owned")).thenReturn(c);
        var clock=new TestClock();clock.setId("clock_owned");clock.setName(runId);clock.setLivemode(false);clock.setStatus("ready");clock.setFrozenTime(Instant.now().getEpochSecond());when(stripe.clock("clock_owned")).thenReturn(clock);
        var user=new UserSubscriptionEntity();user.setId(1L);user.setClerkUserId(owner);user.setTier("free");when(users.selectByUserForUpdate(owner)).thenReturn(user);when(users.selectByUser(owner)).thenReturn(user);
        when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    }
    RecoveryTestClockSupport service()throws Exception {
        var service=new RecoveryTestClockSupport(properties,new MockEnvironment().withProperty("spring.profiles.active","recovery-test-clock"),"sk_test_isolated",stripe,users,roots,transactions);
        var application=mock(SubscriptionPaymentRecoveryService.class);
        @SuppressWarnings("unchecked") var provider=(ObjectProvider<SubscriptionPaymentRecoveryService>)mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(application);when(provider.getObject()).thenReturn(application);ReflectionTestUtils.setField(service,"recovery",provider);return service;
    }
    @Test void bindsOnlyTheOwnedTestCustomerAndPersistsAcrossRestart()throws Exception{
        service().register(runId,owner,"cus_owned","clock_owned");assertEquals("cus_owned",users.selectByUser(owner).getStripeCustomerId());
        assertEquals(runId,service().status(runId).get("runId"));verifyNoMoreStripeWrites();
    }
    @Test void registerIsIdempotentAndCannotChangeTheCustomer()throws Exception{
        var s=service();s.register(runId,owner,"cus_owned","clock_owned");s.register(runId,owner,"cus_owned","clock_owned");
        assertThrows(IllegalStateException.class,()->s.register(runId,owner,"cus_other","clock_owned"));verifyNoMoreStripeWrites();
    }
    @Test void liveCustomerIsRejectedBeforeAnyDatabaseMutation()throws Exception{
        stripe.customer("cus_owned").setLivemode(true);assertThrows(IllegalStateException.class,()->service().register(runId,owner,"cus_owned","clock_owned"));verify(users,never()).updateById(any(UserSubscriptionEntity.class));
    }
    @Test void anotherMetadataOwnerCannotBindTheCustomer()throws Exception{
        stripe.customer("cus_owned").setMetadata(Map.of("recovery_test_run",runId,"clerk_user_id","user_else"));assertThrows(IllegalStateException.class,()->service().register(runId,owner,"cus_owned","clock_owned"));verify(users,never()).updateById(any(UserSubscriptionEntity.class));
    }
    @Test void unlistedUserIsRejectedWithoutRemoteReads()throws Exception{
        assertThrows(IllegalStateException.class,()->service().register(runId,"user_else","cus_owned","clock_owned"));verifyNoInteractions(stripe);
    }
    @Test void existingDifferentCustomerIsNeverOverwritten()throws Exception{
        users.selectByUser(owner).setStripeCustomerId("cus_existing");assertThrows(IllegalStateException.class,()->service().register(runId,owner,"cus_owned","clock_owned"));verify(users,never()).updateById(any(UserSubscriptionEntity.class));
    }
    @Test void pathTraversalIsRejectedWithoutRemoteReads()throws Exception{
        assertThrows(IllegalArgumentException.class,()->service().register("../outside",owner,"cus_owned","clock_owned"));verifyNoInteractions(stripe);
    }
    private RecoveryTestClockStore.Run prepared()throws Exception{
        var run=new RecoveryTestClockStore.Run();run.runId=runId;run.userId=owner;run.customerId="cus_owned";run.clockId="clock_owned";run.subscriptionId="sub_owned";run.invoiceId="in_pay";
        var user=users.selectByUser(owner);user.setStripeCustomerId(run.customerId);user.setStripeSubscriptionId(run.subscriptionId);
        var sub=ApiResource.GSON.fromJson("{\"id\":\"sub_owned\",\"customer\":\"cus_owned\",\"test_clock\":\"clock_owned\",\"livemode\":false,\"latest_invoice\":\"in_pay\",\"pause_collection\":{\"behavior\":\"keep_as_draft\"}}",Subscription.class);when(stripe.subscription(run.subscriptionId)).thenReturn(sub);
        var invoice=new Invoice();invoice.setId(run.invoiceId);invoice.setCustomer(run.customerId);invoice.setSubscription(run.subscriptionId);invoice.setLivemode(false);invoice.setStatus("open");when(stripe.invoice(run.invoiceId)).thenReturn(invoice);
        run.before=new RecoveryTestClockStripe.Capture(null,null,Set.of(),Set.of(),Set.of(),null,Instant.now());save(run);return run;
    }
    private void save(RecoveryTestClockStore.Run run)throws Exception{var store=new RecoveryTestClockStore(directory);store.locked(()->{store.save(run);return null;});}
    @Test void unknownPayIsReadBackAfterRestartAndIsNeverSubmittedAgain()throws Exception{
        var run=prepared();var operation=new RecoveryTestClockStore.Operation();operation.type="PAY";operation.target=run.invoiceId;operation.key="test-clock:"+runId+":first-pay";operation.parameters=Map.of("off_session",true);operation.state="UNKNOWN";run.operations.put("first-pay",operation);save(run);
        service().firstPay(runId);service().firstPay(runId);verifyNoMoreStripeWrites();assertEquals("UNKNOWN",new RecoveryTestClockStore(directory).read(runId).operations.get("first-pay").state);
    }
    @Test void dispatchIntentIsDurableBeforeTheSingleNativeAttempt()throws Exception{
        var run=prepared();when(stripe.write(eq("PAY"),eq(run.invoiceId),anyMap(),anyString())).thenAnswer(call->{
            var saved=new RecoveryTestClockStore(directory).read(runId).operations.get("first-pay");assertEquals("DISPATCHING",saved.state);assertNotNull(saved.submittedAt);assertEquals(Map.of("off_session",true),saved.parameters);assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());throw new java.io.IOException("response lost");
        });
        service().firstPay(runId);service().firstPay(runId);verify(stripe,times(1)).write(eq("PAY"),eq(run.invoiceId),eq(Map.of("off_session",true)),eq("test-clock:"+runId+":first-pay"));
        assertEquals("UNKNOWN",new RecoveryTestClockStore(directory).read(runId).operations.get("first-pay").state);
    }
    @Test void unknownAdvanceCanOnlyCompleteByReadingItsOriginalTarget()throws Exception{
        var run=prepared();long target=stripe.clock(run.clockId).getFrozenTime()+7*86400;
        var operation=new RecoveryTestClockStore.Operation();operation.type="ADVANCE";operation.target=run.clockId;operation.key="test-clock:"+runId+":advance-D7";operation.state="UNKNOWN";operation.parameters=Map.of("frozen_time",target);run.operations.put("advance-D7",operation);save(run);
        service().advance(runId,"D7");assertEquals("UNKNOWN",new RecoveryTestClockStore(directory).read(runId).operations.get("advance-D7").state);
        stripe.clock(run.clockId).setFrozenTime(target);service().advance(runId,"D7");assertEquals("SUCCEEDED",new RecoveryTestClockStore(directory).read(runId).operations.get("advance-D7").state);verifyNoMoreStripeWrites();
    }
    @Test void advancingPastTheFailureWithoutAProofCannotMintATimeline()throws Exception{
        prepared();assertThrows(IllegalStateException.class,()->service().advance(runId,"D7"));verifyNoMoreStripeWrites();
    }
    @Test void firstFailureCannotBypassApplicationAdmission()throws Exception{
        prepared();var s=service();@SuppressWarnings("unchecked") var provider=(ObjectProvider<SubscriptionPaymentRecoveryService>)mock(ObjectProvider.class);
        var application=mock(SubscriptionPaymentRecoveryService.class);when(provider.getIfAvailable()).thenReturn(application);when(provider.getObject()).thenReturn(application);
        doThrow(new IllegalStateException("CASE_NOT_ADMITTED")).when(application).requireTestClockAdmission(owner,"in_pay");ReflectionTestUtils.setField(s,"recovery",provider);
        assertThrows(IllegalStateException.class,()->s.firstPay(runId));verifyNoMoreStripeWrites();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"D7,604800","D14,1209600","D21,1814400","D28,2419200","D30_BEFORE,2591999","D30,2592000"})
    void checkpointsKeepTheOriginalCalendarDaysAndAreSubmittedAtMostOnce(String point,long offset)throws Exception{
        var run=prepared();run.proof=RecoveryOwnedClockEvidenceTest.persistableProof();var first=run.proof.beforeClock().frozenTime();stripe.clock(run.clockId).setFrozenTime(first.getEpochSecond());save(run);
        var root=new SubscriptionPaymentRecoveryEntity();root.setClerkUserId(owner);root.setStripeSubscriptionId(run.subscriptionId);root.setFirstFailedAt(com.studyagent.common.datetime.DateTimeFormats.fromInstant(first));when(roots.selectByInvoice(run.invoiceId)).thenReturn(root);
        when(stripe.write(eq("ADVANCE"),eq(run.clockId),anyMap(),anyString())).thenAnswer(call->{long target=((Number)((Map<?,?>)call.getArgument(2)).get("frozen_time")).longValue();assertEquals(first.getEpochSecond()+offset,target);stripe.clock(run.clockId).setFrozenTime(target);return stripe.clock(run.clockId);});
        service().advance(runId,point);service().advance(runId,point);
        verify(stripe,times(1)).write(eq("ADVANCE"),eq(run.clockId),eq(Map.of("frozen_time",first.getEpochSecond()+offset)),anyString());
        assertEquals(first.plusSeconds(offset),service().localTime(owner,run.subscriptionId,run.customerId));assertNull(service().localTime("user_else",run.subscriptionId,run.customerId));assertNull(service().localTime(owner,"sub_else",run.customerId));
        verify(users,never()).updateById(any(UserSubscriptionEntity.class));
    }
    @Test void checkpointCannotAdvanceAReplacementSubscription()throws Exception{
        var run=prepared();run.proof=RecoveryOwnedClockEvidenceTest.persistableProof();save(run);var root=new SubscriptionPaymentRecoveryEntity();root.setClerkUserId(owner);root.setStripeSubscriptionId(run.subscriptionId);root.setFirstFailedAt(com.studyagent.common.datetime.DateTimeFormats.fromInstant(run.proof.beforeClock().frozenTime()));when(roots.selectByInvoice(run.invoiceId)).thenReturn(root);
        users.selectByUser(owner).setStripeSubscriptionId("sub_new");assertThrows(IllegalStateException.class,()->service().advance(runId,"D7"));verifyNoMoreStripeWrites();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"MONTH_1,2027-02-28T23:00:00Z","MONTH_1_AFTER,2027-02-28T23:00:01Z","MONTH_2,2027-03-31T23:00:00Z","PAID_END,2028-01-31T23:00:00Z"})
    void annualCheckpointsUseUtcCalendarMonthsAndActualPaidCycle(String point,String expected)throws Exception{
        var run=prepared();run.proof=RecoveryOwnedClockEvidenceTest.persistableProof();stripe.clock(run.clockId).setFrozenTime(run.proof.beforeClock().frozenTime().getEpochSecond());save(run);
        var root=new SubscriptionPaymentRecoveryEntity();root.setClerkUserId(owner);root.setStripeSubscriptionId(run.subscriptionId);root.setFirstFailedAt(com.studyagent.common.datetime.DateTimeFormats.fromInstant(run.proof.beforeClock().frozenTime()));root.setStatus("PAID");root.setGrantState("GRANTED");root.setBillingInterval("year");root.setEntitlementStartAt(com.studyagent.common.datetime.DateTimeFormats.fromInstant(Instant.parse("2027-01-31T23:00:00Z")));root.setEntitlementEndAt(com.studyagent.common.datetime.DateTimeFormats.fromInstant(Instant.parse("2028-01-31T23:00:00Z")));when(roots.selectByInvoice(run.invoiceId)).thenReturn(root);
        when(stripe.write(eq("ADVANCE"),eq(run.clockId),anyMap(),anyString())).thenAnswer(call->{stripe.clock(run.clockId).setFrozenTime(((Number)((Map<?,?>)call.getArgument(2)).get("frozen_time")).longValue());return stripe.clock(run.clockId);});service().advance(runId,point);
        assertEquals(Instant.parse(expected),service().localTime(owner,run.subscriptionId,run.customerId));verify(stripe).write(eq("ADVANCE"),eq(run.clockId),eq(Map.of("frozen_time",Instant.parse(expected).getEpochSecond())),anyString());
        assertEquals(com.studyagent.common.datetime.DateTimeFormats.fromInstant(Instant.parse("2028-01-31T23:00:00Z")),root.getEntitlementEndAt());
    }
    @Test void withdrawingOperatorPermissionDoesNotInvalidateExistingReadOnlyClockEvidence()throws Exception{
        var run=prepared();run.observedBusinessTime=Instant.parse("2027-01-01T00:00:00Z");save(run);var s=service();properties.setAllowedUserIds(Set.of());
        assertEquals(run.observedBusinessTime,s.localTime(owner,run.subscriptionId,run.customerId));
        stripe.clock(run.clockId).setFrozenTime(run.observedBusinessTime.plusSeconds(86400).getEpochSecond());s.observeReadyClock(stripe.subscription(run.subscriptionId),stripe.clock(run.clockId));
        assertEquals(run.observedBusinessTime.plusSeconds(86400),s.localTime(owner,run.subscriptionId,run.customerId));
        assertThrows(IllegalStateException.class,()->s.pause(runId,run.subscriptionId));verifyNoMoreStripeWrites();
    }
    @Test void corruptLocalEvidenceReturnsUnavailableAndCanRecoverAfterFileRestoration()throws Exception{
        var run=prepared();run.observedBusinessTime=Instant.parse("2027-01-01T00:00:00Z");save(run);var s=service();var file=directory.resolve(runId+".json");var bytes=java.nio.file.Files.readAllBytes(file);java.nio.file.Files.writeString(file,"interrupted invalid evidence");
        assertNull(s.localTime(owner,run.subscriptionId,run.customerId));java.nio.file.Files.write(file,bytes);assertEquals(run.observedBusinessTime,s.localTime(owner,run.subscriptionId,run.customerId));verifyNoMoreStripeWrites();
    }
    void verifyNoMoreStripeWrites()throws Exception{verify(stripe,never()).write(anyString(),anyString(),anyMap(),anyString());}
}
