package com.studyagent.infra.service.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.model.*;
import com.studyagent.common.datetime.DateTimeFormats;
import com.studyagent.infra.entity.*;
import com.studyagent.infra.mapper.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RecoveryTestClockWindowsTest {
    @TempDir Path directory;
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    final RecoveryTestClockStripe stripe=mock(RecoveryTestClockStripe.class);
    final UserSubscriptionMapper users=mock(UserSubscriptionMapper.class);
    final SubscriptionPaymentRecoveryMapper roots=mock(SubscriptionPaymentRecoveryMapper.class);
    final SubscriptionRecoveryActionMapper actions=mock(SubscriptionRecoveryActionMapper.class);
    final PlatformTransactionManager transactions=mock(PlatformTransactionManager.class);
    final String runId="recovery-windows-unit",owner="user_windows";
    RecoveryTestClockStore.Run run;
    UserSubscriptionEntity user;
    SubscriptionPaymentRecoveryEntity root;
    Subscription sub;
    RecoveryTestClockProperties properties;

    RecoveryTestClockSupport service()throws Exception {
        var s=new RecoveryTestClockSupport(properties,new MockEnvironment().withProperty("spring.profiles.active","recovery-test-clock"),"sk_test_windows",stripe,users,roots,transactions);
        ReflectionTestUtils.setField(s,"actions",actions);
        var application=mock(SubscriptionPaymentRecoveryService.class);
        @SuppressWarnings("unchecked") var provider=(ObjectProvider<SubscriptionPaymentRecoveryService>)mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(application);when(provider.getObject()).thenReturn(application);
        ReflectionTestUtils.setField(s,"recovery",provider);return s;
    }
    @BeforeEach void setup()throws Exception {
        properties=new RecoveryTestClockProperties();properties.setEnabled(true);properties.setDirectory(directory.toString());properties.setAllowedUserIds(Set.of(owner));properties.setOperatorToken("independent-windows-token-longer-than-thirty-two");
        run=new RecoveryTestClockStore.Run();run.runId=runId;run.userId=owner;run.customerId="cus_owned";run.clockId="clock_owned";run.subscriptionId="sub_owned";run.invoiceId="in_pay";
        var frame=RecoveryOwnedClockEvidenceTest.persistableProof();var scope=frame.scope();
        var raw=json.writeValueAsString(frame).replace('"'+scope.runId()+'"','"'+runId+'"').replace('"'+scope.clockId()+'"',"\"clock_owned\"").replace('"'+scope.customerId()+'"',"\"cus_owned\"").replace('"'+scope.subscriptionId()+'"',"\"sub_owned\"").replace("\"in_new\"","\"in_pay\"");
        run.proof=json.readValue(raw,RecoveryOwnedClockEvidence.Frame.class);new RecoveryOwnedClockEvidence.Registry().register(run.proof);
        run.observedBusinessTime=run.proof.beforeClock().frozenTime().plusSeconds(40*86400);
        var pay=new RecoveryTestClockStore.Operation();pay.type="PAY";pay.target="in_pay";pay.key="test-clock:"+runId+":first-pay";pay.state="DECLINED";pay.parameters=Map.of("off_session",true);run.operations.put("first-pay",pay);
        var customer=new Customer();customer.setId(run.customerId);customer.setLivemode(false);customer.setTestClock(run.clockId);customer.setMetadata(Map.of("recovery_test_run",runId,"clerk_user_id",owner));when(stripe.customer(run.customerId)).thenReturn(customer);
        var clock=new com.stripe.model.testhelpers.TestClock();clock.setId(run.clockId);clock.setLivemode(false);clock.setName(runId);clock.setStatus("ready");clock.setFrozenTime(run.observedBusinessTime.getEpochSecond());when(stripe.clock(run.clockId)).thenReturn(clock);
        sub=new Subscription();sub.setId(run.subscriptionId);sub.setCustomer(run.customerId);sub.setTestClock(run.clockId);sub.setLivemode(false);sub.setStatus("active");sub.setCurrentPeriodStart(clock.getFrozenTime()-86400);sub.setCurrentPeriodEnd(clock.getFrozenTime()+29*86400);sub.setLatestInvoice("in_next_paid");sub.setCancelAtPeriodEnd(false);when(stripe.subscription(run.subscriptionId)).thenReturn(sub);
        for(var id:List.of("in_pay","in_next_paid")){var i=new Invoice();i.setId(id);i.setCustomer(run.customerId);i.setSubscription(run.subscriptionId);i.setLivemode(false);i.setStatus("paid");when(stripe.invoice(id)).thenReturn(i);}
        user=new UserSubscriptionEntity();user.setClerkUserId(owner);user.setStripeCustomerId(run.customerId);user.setStripeSubscriptionId(run.subscriptionId);user.setTier("pro");user.setStatus("active");user.setCurrentPeriodEnd(DateTimeFormats.fromInstant(Instant.ofEpochSecond(sub.getCurrentPeriodEnd())));when(users.selectByUser(owner)).thenReturn(user);
        root=new SubscriptionPaymentRecoveryEntity();root.setId(1L);root.setClerkUserId(owner);root.setStripeSubscriptionId(run.subscriptionId);root.setStatus("PAID");root.setGrantState("GRANTED");root.setAnchorSyncState("CONFIRMED");root.setProtectionState("RELEASED");root.setReviewState("NONE");when(roots.selectByInvoice(run.invoiceId)).thenReturn(root);when(actions.selectByRecovery(1L)).thenReturn(List.of());
        save();
    }
    void save()throws Exception {var store=new RecoveryTestClockStore(directory);store.locked(()->{store.save(run);return null;});}
    @SuppressWarnings("unchecked") Map<String,Object> next(RecoveryTestClockSupport s,String previous){return ReflectionTestUtils.invokeMethod(s,"nextWindow",runId,previous);}

    @Test void nextWindowPreservesPriorProofAndPaidMembershipAcrossRestartAndRepeatedRequest()throws Exception {
        var original=run.proof;var originalEnd=user.getCurrentPeriodEnd();
        var result=assertDoesNotThrow(()->next(service(),"in_pay"));assertEquals(2,result.get("window"));
        next(service(),"in_pay");
        var saved=json.readTree(Files.readAllBytes(directory.resolve(runId+".json")));
        assertEquals(2,saved.get("window").asInt());assertEquals(1,saved.get("history").size());
        assertEquals(original,json.treeToValue(saved.get("history").get(0).get("proof"),RecoveryOwnedClockEvidence.Frame.class));assertEquals("DECLINED",saved.get("history").get(0).get("operations").get("first-pay").get("state").asText());
        assertTrue(saved.get("proof").isNull());assertTrue(saved.get("invoiceId").isNull());assertTrue(saved.get("operations").isEmpty());
        assertEquals(originalEnd,user.getCurrentPeriodEnd());assertNull(user.getPaymentRecoveryId());
        verifyNoInteractions(transactions);verify(users,never()).updateById(any(UserSubscriptionEntity.class));verify(stripe,never()).write(anyString(),anyString(),anyMap(),anyString());
        assertEquals(run.observedBusinessTime,service().localTime(owner,run.subscriptionId,run.customerId));
    }
    @ParameterizedTest @ValueSource(strings={"unpaid","pointer","review","pending","unknown_operation","paused","canceled","advancing","moved","wrong_invoice","no_proof"})
    void nextWindowRefusesUnresolvedOrMismatchedStateWithoutChangingEvidence(String scenario)throws Exception {
        switch(scenario){
            case "unpaid"->stripe.invoice("in_pay").setStatus("open");case "pointer"->user.setPaymentRecoveryId(1L);
            case "review"->root.setReviewState("OPEN");case "pending"->{var a=new SubscriptionRecoveryActionEntity();a.setActionType("SHIFT_RENEWAL");a.setStatus("UNKNOWN");when(actions.selectByRecovery(1L)).thenReturn(List.of(a));}
            case "unknown_operation"->{run.operations.get("first-pay").state="UNKNOWN";save();}
            case "paused"->{var pause=new Subscription.PauseCollection();pause.setBehavior("keep_as_draft");sub.setPauseCollection(pause);}
            case "canceled"->sub.setStatus("canceled");case "advancing"->stripe.clock(run.clockId).setStatus("advancing");case "moved"->user.setStripeSubscriptionId("sub_other");
            case "no_proof"->{run.proof=null;save();}
        }
        var before=Files.readAllBytes(directory.resolve(runId+".json"));
        var error=assertThrows(IllegalStateException.class,()->next(service(),scenario.equals("wrong_invoice")?"in_wrong":"in_pay"));assertTrue(error.getMessage().startsWith("RECOVERY_TEST_CLOCK_"));
        assertArrayEquals(before,Files.readAllBytes(directory.resolve(runId+".json")));verify(stripe,never()).write(anyString(),anyString(),anyMap(),anyString());
    }
    @Test void newWindowUsesDistinctDurableKeysAndStaleFirstWindowCallsCannotDispatch()throws Exception {
        assertDoesNotThrow(()->next(service(),"in_pay"));
        var stale=assertThrows(IllegalStateException.class,()->service().pause(runId,run.subscriptionId));assertTrue(stale.getMessage().contains("WINDOW"));
        when(stripe.write(eq("PAUSE"),eq(run.subscriptionId),anyMap(),anyString())).thenAnswer(call->{
            var stored=json.readTree(Files.readAllBytes(directory.resolve(runId+".json")));
            assertEquals("DISPATCHING",stored.get("operations").get("pause").get("state").asText());
            var pause=new Subscription.PauseCollection();pause.setBehavior("keep_as_draft");sub.setPauseCollection(pause);return sub;
        });
        assertDoesNotThrow(()->ReflectionTestUtils.invokeMethod(service(),"pause",runId,run.subscriptionId,Integer.valueOf(2)));
        verify(stripe).write(eq("PAUSE"),eq(run.subscriptionId),anyMap(),eq("test-clock:"+runId+":window-2:pause"));
    }
    @ParameterizedTest @ValueSource(strings={"pause","firstPay","advance"})
    void staleWindowNumberRejectsEveryCommandBeforeStripeReads(String command)throws Exception {
        next(service(),"in_pay");clearInvocations(stripe);
        var s=service();
        var error=assertThrows(IllegalStateException.class,()->{
            switch(command){case "pause"->s.pause(runId,run.subscriptionId,1);case "firstPay"->s.firstPay(runId,1);case "advance"->s.advance(runId,"D30",1);}
        });
        assertTrue(error.getMessage().contains("WINDOW_MISMATCH"));verifyNoInteractions(stripe);
    }
    @Test void unknownSecondWindowPauseIsNeverResubmittedAfterRestart()throws Exception {
        next(service(),"in_pay");
        when(stripe.write(eq("PAUSE"),eq(run.subscriptionId),anyMap(),anyString())).thenThrow(new java.io.IOException("response lost"));
        service().pause(runId,run.subscriptionId,2);service().pause(runId,run.subscriptionId,2);
        verify(stripe,times(1)).write(eq("PAUSE"),eq(run.subscriptionId),anyMap(),eq("test-clock:"+runId+":window-2:pause"));
        assertEquals("UNKNOWN",new RecoveryTestClockStore(directory).read(runId).operations.get("pause").state);
    }
    @Test void legacyFileWithoutWindowFieldsKeepsItsOriginalOperationKeys()throws Exception {
        var file=directory.resolve(runId+".json");var tree=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(Files.readAllBytes(file));tree.remove(List.of("window","history"));Files.write(file,json.writeValueAsBytes(tree));
        assertEquals(1,service().status(runId).get("window"));assertEquals("test-clock:"+runId+":first-pay",new RecoveryTestClockStore(directory).read(runId).operations.get("first-pay").key);
    }
    @ParameterizedTest @ValueSource(strings={"DISABLE_ADVANCE","RELEASE_SCHEDULE","RELEASE_PAUSE","EXPIRE_CHECKOUT","UNKNOWN_FUTURE_WRITE"})
    void everyUnsettledProviderWritePreventsStartingANewWindow(String type)throws Exception {
        var action=new SubscriptionRecoveryActionEntity();action.setActionType(type);action.setStatus("UNKNOWN");when(actions.selectByRecovery(1L)).thenReturn(List.of(action));
        assertThrows(IllegalStateException.class,()->next(service(),"in_pay"));
        assertTrue(new RecoveryTestClockStore(directory).read(runId).history.isEmpty());
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void lateOldEventsAndNewEventsRetainTheirOwnFirstFailureTime(boolean newer)throws Exception {
        var oldProof=run.proof;next(service(),"in_pay");run=new RecoveryTestClockStore(directory).read(runId);
        var changed=json.writeValueAsString(oldProof).replace("in_pay","in_second").replace("pi_new","pi_second").replace("ch_new","ch_second").replace("evt_new","evt_second").replace("req_pay","req_second")
                .replace(oldProof.beforeClock().frozenTime().toString(),oldProof.beforeClock().frozenTime().plusSeconds(60*86400).toString());
        run.proof=json.readValue(changed,RecoveryOwnedClockEvidence.Frame.class);run.invoiceId="in_second";save();
        stripe.clock(run.clockId).setFrozenTime(run.proof.beforeClock().frozenTime().getEpochSecond());
        var proof=newer?run.proof:oldProof;var f=proof.invoice();var e=proof.failureEvents().get(0);
        var invoice=new Invoice();invoice.setId(f.invoiceId());invoice.setCustomer(run.customerId);invoice.setSubscription(run.subscriptionId);invoice.setLivemode(false);invoice.setPaymentIntent(f.paymentIntentId());
        var pi=new PaymentIntent();pi.setId(f.paymentIntentId());pi.setCustomer(run.customerId);pi.setLivemode(false);pi.setLatestCharge(proof.paymentIntent().latestChargeId());
        var charge=new Charge();charge.setId(pi.getLatestCharge());charge.setCustomer(run.customerId);charge.setInvoice(f.invoiceId());charge.setPaymentIntent(pi.getId());charge.setLivemode(false);
        var payload=Map.of("id",e.eventId(),"type",e.type(),"created",e.created(),"livemode",false,"api_version",e.apiVersion(),"request",Map.of("id",proof.operationRequestId()),"data",Map.of("object",Map.of("id",f.invoiceId(),"customer",run.customerId,"subscription",run.subscriptionId)));
        var event=com.stripe.net.ApiResource.GSON.fromJson(json.writeValueAsString(payload),Event.class);
        var snapshot=new SubscriptionRecoveryStripeGateway.Snapshot(invoice,sub,null,List.of(),pi,charge,run.proof.beforeClock().frozenTime());
        var result=service().firstFailure(snapshot,event);assertEquals(proof.beforeClock().frozenTime(),result.occurredAt());
        assertEquals(oldProof,new RecoveryTestClockStore(directory).read(runId).history.get(0).proof);
    }
    @Test void secondWindowD30UsesItsOwnFailureAndCannotReuseThePriorCheckpoint()throws Exception {
        var old=new RecoveryTestClockStore.Operation();old.type="ADVANCE";old.target=run.clockId;old.key="test-clock:"+runId+":advance-D30";old.state="SUCCEEDED";
        old.parameters=Map.of("frozen_time",run.proof.beforeClock().frozenTime().plusSeconds(30*86400).getEpochSecond());run.operations.put("advance-D30",old);save();
        var first=run.proof.beforeClock().frozenTime();next(service(),"in_pay");run=new RecoveryTestClockStore(directory).read(runId);
        var raw=json.writeValueAsString(run.history.get(0).proof).replace("in_pay","in_second").replace(first.toString(),first.plusSeconds(60*86400).toString());
        run.proof=json.readValue(raw,RecoveryOwnedClockEvidence.Frame.class);run.invoiceId="in_second";save();
        var second=run.proof.beforeClock().frozenTime();root.setFirstFailedAt(DateTimeFormats.fromInstant(second));when(roots.selectByInvoice("in_second")).thenReturn(root);stripe.clock(run.clockId).setFrozenTime(second.getEpochSecond());
        when(stripe.write(eq("ADVANCE"),eq(run.clockId),anyMap(),anyString())).thenAnswer(call->{stripe.clock(run.clockId).setFrozenTime(((Number)((Map<?,?>)call.getArgument(2)).get("frozen_time")).longValue());return stripe.clock(run.clockId);});
        service().advance(runId,"D30",2);service().advance(runId,"D30",2);
        verify(stripe,times(1)).write("ADVANCE",run.clockId,Map.of("frozen_time",second.plusSeconds(30*86400).getEpochSecond()),"test-clock:"+runId+":window-2:advance-D30");
        assertEquals(((Number)old.parameters.get("frozen_time")).longValue(),((Number)new RecoveryTestClockStore(directory).read(runId).history.get(0).operations.get("advance-D30").parameters.get("frozen_time")).longValue());
    }
    @Test void secondWindowUnknownPaymentKeepsItsOwnKeyAndIsNotChargedAgain()throws Exception {
        next(service(),"in_pay");run=new RecoveryTestClockStore(directory).read(runId);run.invoiceId="in_second";
        run.before=new RecoveryTestClockStripe.Capture(null,null,Set.of(),Set.of(),Set.of(),null,Instant.now());save();
        sub.setPauseCollection(new Subscription.PauseCollection());sub.getPauseCollection().setBehavior("keep_as_draft");
        var invoice=new Invoice();invoice.setId("in_second");invoice.setCustomer(run.customerId);invoice.setSubscription(run.subscriptionId);invoice.setLivemode(false);invoice.setStatus("open");when(stripe.invoice("in_second")).thenReturn(invoice);
        when(stripe.write(eq("PAY"),eq("in_second"),anyMap(),anyString())).thenThrow(new java.io.IOException("unknown result"));
        service().firstPay(runId,2);service().firstPay(runId,2);
        verify(stripe,times(1)).write("PAY","in_second",Map.of("off_session",true),"test-clock:"+runId+":window-2:first-pay");
        assertEquals("UNKNOWN",new RecoveryTestClockStore(directory).read(runId).operations.get("first-pay").state);
    }
}
