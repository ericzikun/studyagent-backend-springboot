package com.studyagent.infra.service.billing;

import com.stripe.Stripe;
import com.stripe.model.*;
import com.stripe.net.ApiResource;
import com.google.gson.JsonParser;
import com.studyagent.infra.entity.*;
import com.studyagent.infra.mapper.*;
import com.studyagent.infra.service.*;
import com.studyagent.service.domain.billing.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Opt-in native Stripe -> real inbox/service -> isolated local SQL; no HTTP signature or real mail claim. */
final class RecoverySandboxApplicationHarness implements AutoCloseable {
    private final RecoverySandboxApplicationDatabase database;
    private final SqlSessionTemplate sql;
    private final SubscriptionPaymentRecoveryService service;
    private final PaymentRecoveryProperties config;
    private final StripeBillingWebhookService inbox;
    private final PaymentRecoveryAccessServiceImpl access;
    private final PlanQuotaServiceImpl quotas;
    private final com.studyagent.service.domain.quota.PlanQuotaService transactionalQuotas;
    private final NativeObservedClock annualClock;
    private String annualClockId;
    private final RecoverySandboxCreationTracker.Journal journal;
    private final String previousKey,user,subscriptionId,invoiceId,introPriceId;
    private final RecoverySandboxFixture fixture;
    private final Event firstEvent;
    private Event nativePaidEvent;
    private final Instant provenFirstFailure;
    private final boolean automaticFunds;
    private final RecoverySandboxShiftResponseLoss shiftResponseLoss;
    private final RecoveryOwnedClockGateway ownedGateway;
    private final com.studyagent.common.analytics.AnalyticsService legacyAnalytics;
    private final BillingRobotNotifyGateway legacyRobot;
    private long rootId;
    private boolean closed;
    private Map<String,Invoice> cancellationInvoices;
    private Set<String> cancellationCharges;
    private Set<String> cancellationIntroDraftIds;
    private Map<String,Invoice> deadlineInvoices;
    private Set<String> deadlineIntroDraftIds;
    private String replacementOrderNo;

    RecoverySandboxApplicationHarness(String run,String key,RecoverySandboxFixture fixture,Event event,
            RecoveryOwnedClockEvidence.Registry registry,String customerId,String subscriptionId,String invoiceId,
            String introPriceId,String formalPriceId,boolean automaticFunds,RecoverySandboxCreationTracker.Journal journal) throws Exception {
        assertTrue(key.startsWith("sk_test_")||key.startsWith("rk_test_"));assertTrue(fixture.priceUpdate()||fixture.explicitFirstPay());assertEquals(Boolean.FALSE,event.getLivemode());
        this.journal=journal;this.fixture=fixture;this.firstEvent=event;this.user=run;this.subscriptionId=subscriptionId;this.invoiceId=invoiceId;this.introPriceId=introPriceId;
        this.automaticFunds=automaticFunds;
        shiftResponseLoss="true".equals(System.getenv("STRIPE_RECOVERY_SHIFT_ACK_LOSS"))?new RecoverySandboxShiftResponseLoss(run,subscriptionId):null;
        ownedGateway=new RecoveryOwnedClockGateway(run,registry,shiftResponseLoss);
        provenFirstFailure=registry.find(run,invoiceId).orElseThrow().occurredAt();
        database=new RecoverySandboxApplicationDatabase();sql=new SqlSessionTemplate(database.sessions());previousKey=Stripe.apiKey;Stripe.apiKey=key;
        try{
            var plans=sql.getMapper(SubscriptionPlanMapper.class);if(fixture.intro())plans.insert(plan(fixture.sourceKey(),introPriceId,true));plans.insert(plan(fixture.targetPlanCode(),formalPriceId,false));
            var account=new UserSubscriptionEntity();account.setClerkUserId(user);account.setStripeCustomerId(customerId);account.setStripeSubscriptionId(subscriptionId);account.setPlanCode(fixture.intro()?fixture.sourceKey():fixture.targetPlanCode());account.setTier(fixture.targetPlanCode().split("_")[0]);account.setStatus("past_due");account.setSubscriptionPhase(fixture.intro()?IntroTrialPlans.PHASE_INTRO:IntroTrialPlans.PHASE_STANDARD);account.setGraceEndAt(com.studyagent.common.datetime.DateTimeFormats.now().plusDays(3));account.setVersion(0);sql.getMapper(UserSubscriptionMapper.class).insert(account);
            config=new PaymentRecoveryProperties();config.setAcceptNew(true);config.setAcceptInvoicesCreatedAfter(Instant.ofEpochSecond(event.getCreated()).minusSeconds(60));config.setAllowedUserIds(Set.of(user));config.setVerifiedCohorts(Set.of(fixture.caseKey()));
            // This is private test wiring only; deployment flags and release allowlists are never edited.
            config.setAutomaticPayEnabled(automaticFunds);config.setEmailEnabled(false);
            var users=sql.getMapper(UserSubscriptionMapper.class);var recoveries=sql.getMapper(SubscriptionPaymentRecoveryMapper.class);var actions=sql.getMapper(SubscriptionRecoveryActionMapper.class);var replacements=sql.getMapper(SubscriptionRecoveryReplacementMapper.class);
            boolean annualRefresh="true".equals(System.getenv("STRIPE_RECOVERY_ANNUAL_REFRESH"));
            annualClock=annualRefresh?new NativeObservedClock():null;
            if(annualRefresh){
                var nativeSub=Subscription.retrieve(subscriptionId);assertEquals(customerId,nativeSub.getCustomer());assertEquals(Boolean.FALSE,nativeSub.getLivemode());assertEquals(run,nativeSub.getMetadata().get("recovery_test_run"));
                annualClockId=nativeSub.getTestClock();
                annualClock.observe(verifiedAnnualClock(com.stripe.model.testhelpers.TestClock.retrieve(annualClockId),annualClockId,run,provenFirstFailure));
            }
            access=annualRefresh?new PaymentRecoveryAccessServiceImpl(users,recoveries,actions,replacements,annualClock,config):new PaymentRecoveryAccessServiceImpl(users,recoveries,actions,replacements,config);
            quotas=new PlanQuotaServiceImpl(plans,mock(AiFeatureDefsMapper.class),sql.getMapper(UserAiQuotaMapper.class),sql.getMapper(QuotaLedgerMapper.class),users);
            var addons=new AddonGrantServiceImpl(mock(AddonPackageDefMapper.class),sql.getMapper(UserAddonGrantMapper.class),sql.getMapper(QuotaLedgerMapper.class),users);
            var quota=new BillingQuotaGatewayImpl(quotas,addons);ObjectProvider<BillingQuotaGateway> provider=provider(quota);
            var tx=new DataSourceTransactionManager(database.dataSource());
            if(annualRefresh){
                ReflectionTestUtils.setField(quotas,"paymentRecoveryAccessService",access);
                var proxy=new org.springframework.aop.framework.ProxyFactory(quotas);
                proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(tx,new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
                transactionalQuotas=(com.studyagent.service.domain.quota.PlanQuotaService)proxy.getProxy();
            }else transactionalQuotas=null;
            service=new SubscriptionPaymentRecoveryService(users,recoveries,actions,replacements,plans,sql.getMapper(RechargeOrderMapper.class),ownedGateway,config,access,tx);
            ReflectionTestUtils.setField(service,"quotaProvider",provider);ReflectionTestUtils.setField(service,"passes",sql.getMapper(StudyPassMapper.class));
            legacyAnalytics=mock(com.studyagent.common.analytics.AnalyticsService.class);legacyRobot=mock(BillingRobotNotifyGateway.class);
            inbox=new StripeBillingWebhookService(sql.getMapper(StripeWebhookEventMapper.class),users,plans,mock(AddonPackageDefMapper.class),sql.getMapper(RechargeOrderMapper.class),legacyAnalytics,provider,provider(legacyRobot),null,tx,mock(BillingPricingService.class),mock(ZonePricingSelectionService.class));
            ReflectionTestUtils.setField(inbox,"paymentRecoveryService",service);
        }catch(Exception|AssertionError e){Stripe.apiKey=previousKey;database.close();throw e;}
    }
    @SuppressWarnings("unchecked") private static <T> ObjectProvider<T> provider(T value){var p=(ObjectProvider<T>)mock(ObjectProvider.class);when(p.getIfAvailable()).thenReturn(value);return p;}
    private SubscriptionPlanEntity plan(String code,String priceId,boolean intro){
        String tier=fixture.targetPlanCode().split("_")[0];var p=new SubscriptionPlanEntity();p.setPlanCode(code);p.setTier(tier);p.setBillingInterval(fixture.interval());p.setOfferKind(intro?("basic".equals(tier)?IntroTrialPlans.OFFER_KIND_BASIC_PAID_TRIAL:IntroTrialPlans.OFFER_KIND_PRO_PAID_TRIAL):IntroTrialPlans.OFFER_KIND_STANDARD);p.setTrialDays(intro?7:null);p.setConvertsToPlanCode(intro?fixture.targetPlanCode():null);p.setStripePriceId(priceId);p.setCurrency(fixture.currency());p.setPriceCents(Math.toIntExact(intro?fixture.introAmountMinor():fixture.unitAmountMinor()));p.setAssignmentQuota(12L);p.setDetectionQuota(20000L);p.setHumanizerQuota(10000L);p.setAllowedOutputTypes("[\"writing\"]");p.setConfigVersion(1);p.setIsActive(true);return p;
    }
    void admitAndProtect() throws Exception {
        assertTrue(inbox.process(firstEvent));assertFalse(inbox.process(firstEvent));
        var root=sql.getMapper(SubscriptionPaymentRecoveryMapper.class).selectByInvoice(invoiceId);assertNotNull(root);rootId=root.getId();assertEquals(automaticFunds?"OPEN_AUTO":"OPEN_MANUAL",root.getStatus());assertEquals(automaticFunds?"insufficient_funds":"generic_decline",root.getLatestDeclineCode());assertOriginalWindow(root);
        var account=sql.getMapper(UserSubscriptionMapper.class).selectByUser(user);assertEquals(rootId,account.getPaymentRecoveryId());assertNull(account.getGraceEndAt());assertTrue(access.resolve(user,subscriptionId).locked());
        if(automaticFunds)assertRetryIntents();else assertEquals(0,actions().stream().filter(a->"RETRY_PAY".equals(a.getActionType())).count());
        for(var action:actions())if(Set.of("DISABLE_ADVANCE","PAUSE_COLLECTION").contains(action.getActionType())){service.runDueAction(action.getId());assertEquals("SUCCEEDED",sql.getMapper(SubscriptionRecoveryActionMapper.class).selectById(action.getId()).getStatus());}
        service.reconcile(rootId);assertEquals("ACTIVE",root().getProtectionState());verifyNoInteractions(legacyAnalytics,legacyRobot);
        journal.record("application-admitted-and-protected",Map.of("rootId",rootId,"invoiceId",invoiceId,"firstFailureEventId",firstEvent.getId(),"nativeEventVersion",firstEvent.getApiVersion(),"status",root().getStatus(),"protection",root().getProtectionState(),"retryActions",automaticFunds?4:0,"database","ISOLATED_LOCAL_SCHEMA","quotaCatalog","ISOLATED_TEST_CATALOG"));
    }
    /** Real owned Checkout payment -> native inbox -> persisted service state. No synthetic paid event. */
    void acceptReplacement(java.nio.file.Path challengePath)throws Exception {
        acceptReplacement(challengePath,false);
    }
    void acceptReplacementDouble(java.nio.file.Path challengePath)throws Exception {
        RecoverySandboxReplacement.requireDoubleScope(fixture,System.getenv(),"generic_decline");
        acceptReplacement(challengePath,true);
    }
    private void acceptReplacement(java.nio.file.Path challengePath,boolean doublePayment)throws Exception {
        RecoverySandboxReplacement.requireScope(fixture,System.getenv(),"generic_decline");
        assertFalse(automaticFunds);assertFalse(config.isEmailEnabled());
        assertNull(ReflectionTestUtils.getField(service,"emailSender"));assertPortalUnpaid();
        var oldInvoice=Invoice.retrieve(invoiceId);var oldSub=Subscription.retrieve(subscriptionId);
        var protectedDrafts=ownedInvoices().values().stream().filter(i->"draft".equals(i.getStatus())).map(Invoice::getId).collect(java.util.stream.Collectors.toSet());
        if(doublePayment)assertNotNull(oldInvoice.getHostedInvoiceUrl(),"Use the already issued link; never sign a new old link after reservation");
        var customer=Customer.retrieve(root().getStripeCustomerId());
        assertEquals(Boolean.FALSE,customer.getLivemode());assertEquals(user,customer.getMetadata().get("recovery_test_run"));
        assertEquals(Boolean.FALSE,oldSub.getLivemode());assertEquals(user,oldSub.getMetadata().get("recovery_test_run"));
        assertEquals(customer.getId(),oldSub.getCustomer());assertEquals(customer.getTestClock(),oldSub.getTestClock());
        String virtualEmail=user+"@example.invalid",mailboxKey=user+":replacement-virtual-email";
        journal.record("application-fixture-customer-email-intent",Map.of("customerId",customer.getId(),"parameters",Map.of("email",virtualEmail),"idempotencyKey",mailboxKey,"purpose","OWNED_TEST_RESERVED_DOMAIN_NO_REAL_INBOX"));
        customer=customer.update(Map.of("email",virtualEmail),com.stripe.net.RequestOptions.builder().setIdempotencyKey(mailboxKey).setMaxNetworkRetries(0).build());
        assertEquals(virtualEmail,customer.getEmail());assertEquals(Boolean.FALSE,customer.getLivemode());assertEquals(user,customer.getMetadata().get("recovery_test_run"));
        var plan=sql.getMapper(SubscriptionPlanMapper.class).selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SubscriptionPlanEntity>().eq(SubscriptionPlanEntity::getPlanCode,fixture.targetPlanCode()));
        var price=Price.retrieve(plan.getStripePriceId());assertEquals(Boolean.FALSE,price.getLivemode());assertEquals(user,price.getMetadata().get("recovery_test_run"));
        assertEquals(7999L,price.getUnitAmount());assertEquals("usd",price.getCurrency());assertEquals("month",price.getRecurring().getInterval());
        var product=Product.retrieve(price.getProduct());assertEquals(Boolean.FALSE,product.getLivemode());assertEquals(user,product.getMetadata().get("recovery_test_run"));
        replacementOrderNo="replacement_"+UUID.randomUUID().toString().replace("-","");
        var order=new RechargeOrderEntity();order.setOrderNo(replacementOrderNo);order.setOrderType("subscription_replacement");order.setClerkUserId(user);
        order.setFeatureCode("subscription");order.setPackageCode(plan.getPlanCode());order.setPlanCode(plan.getPlanCode());order.setTargetPlanCode(plan.getPlanCode());order.setQuotaAmount(0L);
        order.setCurrency("usd");order.setPriceCents(7999);order.setQuotedAmountCents(7999);order.setStatus("pending");
        order.setCreatedAt(com.studyagent.common.datetime.DateTimeFormats.now());order.setUpdatedAt(order.getCreatedAt());
        order.setBizContext(new com.google.gson.Gson().toJson(Map.of("recovery_id",rootId,"old_subscription_id",subscriptionId,"stripe_price_id",price.getId(),"acceptance_quote_scope","OWNED_TEST_CATALOG_NOT_HTTP")));
        var metadata=Map.of("purchase_type","subscription_replacement","recovery_id",String.valueOf(rootId),"old_subscription_id",subscriptionId,
                "order_no",replacementOrderNo,"clerk_user_id",user,"plan_code",plan.getPlanCode(),"recovery_test_run",user);
        var expires=Instant.now().plusSeconds(1860);
        var pricing=mock(BillingPricingService.class);when(pricing.pricingRegion()).thenReturn("TEST_OWNED");
        var builder=mock(BillingDomainServiceImpl.class,CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(builder,"pricingService",pricing);ReflectionTestUtils.setField(builder,"zonePricing",mock(ZonePricingSelectionService.class));
        com.stripe.param.checkout.SessionCreateParams params=ReflectionTestUtils.invokeMethod(builder,"buildSubscriptionCheckoutParams",user,customer.getId(),plan.getPlanCode(),plan,
                "http://127.0.0.1:18788/","http://127.0.0.1:18788/",com.stripe.param.checkout.SessionCreateParams.SubscriptionData.builder().putAllMetadata(metadata).build(),
                "subscription_replacement",null,metadata,expires.getEpochSecond());
        assertNotNull(params);assertEquals("subscription",params.toMap().get("mode"));
        var beforeCharges=ownedCustomerChargeIds();var beforeRights=paidRightsSnapshot();
        var reservation=service.reserveReplacement(user,rootId,order,params,expires);
        var saved=service.replacement(user,replacementOrderNo);var action=sql.getMapper(SubscriptionRecoveryActionMapper.class).selectById(reservation.actionId());
        assertEquals("CREATING",saved.getState());assertEquals(subscriptionId,saved.getActiveSubscriptionKey());assertNotNull(saved.getPausedAt());
        assertEquals("CREATE_CHECKOUT",action.getActionType());assertEquals("READY",action.getStatus());assertEquals(0,action.getTransportAttempts());
        String frozen=action.getPayloadJson(),key=action.getProviderIdempotencyKey();
        assertEquals(params.toMap(),SubscriptionRecoveryStripeGateway.thaw(frozen));assertEquals(frozen,saved.getQuoteSnapshot());
        assertEquals("recovery-replacement:"+replacementOrderNo+":create",action.getActionKey());assertNotNull(key);
        assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
        // A second physical connection proves all three rows are committed before the provider write.
        try(var c=database.dataSource().getConnection();var q=c.prepareStatement("SELECT (SELECT COUNT(*) FROM recharge_orders WHERE order_no=? AND status='pending'),(SELECT COUNT(*) FROM subscription_payment_recovery_replacements WHERE order_no=? AND state='CREATING'),(SELECT COUNT(*) FROM subscription_payment_recovery_actions WHERE id=? AND status='READY')")){
            q.setString(1,replacementOrderNo);q.setString(2,replacementOrderNo);q.setLong(3,reservation.actionId());try(var rows=q.executeQuery()){assertTrue(rows.next());for(int col=1;col<=3;col++)assertEquals(1,rows.getInt(col));}
        }
        assertThrows(BillingDomainException.class,()->service.reserveReplacement(user,rootId,order,params,expires));
        assertThrows(BillingDomainException.class,()->service.createHostedInvoice(user,rootId));
        assertEquals(1,sql.getMapper(RechargeOrderMapper.class).selectList(null).size());assertEquals(1,sql.getMapper(SubscriptionRecoveryReplacementMapper.class).selectByRecovery(rootId).size());
        assertEquals(beforeCharges,ownedCustomerChargeIds());assertEquals(beforeRights.get("quotas"),paidRightsSnapshot().get("quotas"));assertEquals(beforeRights.get("ledgers"),paidRightsSnapshot().get("ledgers"));
        ownedGateway.observeCheckoutBeforeWrite(claimed->{
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals("DISPATCHING",sql.getMapper(SubscriptionRecoveryActionMapper.class).selectById(claimed.getId()).getStatus());
            assertEquals(frozen,claimed.getPayloadJson());assertEquals(key,claimed.getProviderIdempotencyKey());
            assertEquals("CREATING",service.replacement(user,replacementOrderNo).getState());
        });
        journal.record("application-replacement-reserved",Map.of("rootId",rootId,"orderNo",replacementOrderNo,"actionId",action.getId(),"parameters",SubscriptionRecoveryStripeGateway.thaw(frozen),
                "idempotencyKey",key,"threeRowsCommittedBeforeWrite",true,"oldHostedBlocked",true,"duplicateReservationBlocked",true,"automaticRetrySlotsResult","NOT_RUN"));
        service.runDueAction(action.getId());
        action=sql.getMapper(SubscriptionRecoveryActionMapper.class).selectById(action.getId());
        journal.record("application-replacement-create-result",Map.of("rootId",rootId,"actionId",action.getId(),"status",action.getStatus(),"attempts",action.getTransportAttempts(),"providerRequestId",Objects.toString(action.getProviderRequestId(),"NONE")));
        assertEquals("SUCCEEDED",action.getStatus(),"Never resubmit an UNKNOWN Checkout creation");assertEquals(1,action.getTransportAttempts());assertNotNull(action.getProviderRequestId());
        assertEquals(frozen,action.getPayloadJson());assertEquals(key,action.getProviderIdempotencyKey());assertEquals(1,ownedGateway.checkoutNativeExecuteCount());
        saved=service.replacement(user,replacementOrderNo);assertEquals("OPEN",saved.getState());assertNotNull(saved.getCheckoutSessionId());
        var session=com.stripe.model.checkout.Session.retrieve(saved.getCheckoutSessionId());requireReplacementSession(session,customer.getId(),metadata);
        assertEquals("open",session.getStatus());assertEquals("unpaid",session.getPaymentStatus());assertEquals(expires.getEpochSecond(),session.getExpiresAt());assertNull(session.getSubscription());
        assertEquals(7999L,session.getAmountSubtotal());assertEquals(7999L,session.getAmountTotal());
        assertEquals("pending",sql.getMapper(RechargeOrderMapper.class).selectById(order.getId()).getStatus());assertEquals(beforeCharges,ownedCustomerChargeIds());
        Event oldPaid=doublePayment?awaitReplacementOldHostedPayment(challengePath,oldInvoice.getHostedInvoiceUrl(),customer.getId(),paidRightsSnapshot()):null;
        var until=Instant.now().plusSeconds(900);long monotonic=System.nanoTime()+Duration.ofSeconds(900).toNanos();
        Event paid=null,completed=null;
        try(var challenge=RecoverySandboxReplacement.writeChallenge(challengePath,user,rootId,session.getUrl(),until)){
            journal.record("application-replacement-awaiting-payment-page",Map.of("runId",user,"rootId",rootId,"orderNo",replacementOrderNo,"sessionId",session.getId(),"step","CHECKOUT_PAYMENT",
                    "privateChallengePath",challengePath.toString(),"expiresAt",until.toString(),"checkoutOrigin","https://checkout.stripe.com","actualUiEvidence","REQUIRED_SEPARATELY"));
            while(Instant.now().isBefore(until)&&System.nanoTime()-monotonic<0){
                session=com.stripe.model.checkout.Session.retrieve(saved.getCheckoutSessionId());requireReplacementSession(session,customer.getId(),metadata);
                if(!doublePayment)assertEquals(0L,Invoice.retrieve(invoiceId).getAmountPaid(),"Old payment makes this a separate double-payment scenario");
                if("paid".equals(session.getPaymentStatus())&&"complete".equals(session.getStatus())){
                    assertNotNull(session.getSubscription());assertNotNull(session.getInvoice());
                    paid=replacementNativeEvent("invoice.paid",session.getInvoice());completed=replacementNativeEvent("checkout.session.completed",session.getId());
                    if(paid!=null&&completed!=null)break;
                }
                Thread.sleep(2000);
            }
            assertNotNull(paid,"REPLACEMENT_PAYMENT_TIMEOUT_NO_NATIVE_PAID_EVENT");assertNotNull(completed,"REPLACEMENT_PAYMENT_TIMEOUT_NO_NATIVE_CHECKOUT_EVENT");
            var newSub=Subscription.retrieve(session.getSubscription());var invoice=Invoice.retrieve(session.getInvoice());
            assertEquals(Boolean.FALSE,newSub.getLivemode());assertEquals(customer.getId(),newSub.getCustomer());assertEquals(customer.getTestClock(),newSub.getTestClock());
            for(var entry:metadata.entrySet())assertEquals(entry.getValue(),newSub.getMetadata().get(entry.getKey()));
            assertNotEquals(subscriptionId,newSub.getId());assertEquals("active",newSub.getStatus());assertNull(newSub.getTrialEnd());assertNull(newSub.getPauseCollection());assertNull(newSub.getSchedule());
            assertEquals(Boolean.FALSE,invoice.getLivemode());assertEquals("paid",invoice.getStatus());assertEquals(customer.getId(),invoice.getCustomer());assertEquals(newSub.getId(),invoice.getSubscription());
            assertEquals(7999L,invoice.getAmountPaid());assertEquals(0L,invoice.getAmountRemaining());assertEquals(1,invoice.getLines().getData().size());assertEquals(price.getId(),invoice.getLines().getData().get(0).getPrice().getId());
            var pi=PaymentIntent.retrieve(invoice.getPaymentIntent());var charge=Charge.retrieve(pi.getLatestCharge());
            assertEquals(Boolean.FALSE,pi.getLivemode());assertEquals(Boolean.FALSE,charge.getLivemode());assertEquals(customer.getId(),pi.getCustomer());assertEquals(customer.getId(),charge.getCustomer());assertEquals(invoice.getId(),charge.getInvoice());assertEquals(pi.getId(),charge.getPaymentIntent());assertEquals("succeeded",charge.getStatus());assertEquals(7999L,charge.getAmountCaptured());
            if(doublePayment){
                assertEquals(Boolean.FALSE,charge.getRefunded());assertEquals(0L,charge.getAmountRefunded());
                assertTrue(Invoice.retrieve(invoiceId).getStatusTransitions().getPaidAt()<=invoice.getStatusTransitions().getPaidAt());
            }
            assertTrue(inbox.process(paid));assertFalse(inbox.process(paid));assertTrue(inbox.process(completed));assertFalse(inbox.process(completed));
            for(int pass=0;pass<12;pass++){
                service.reconcile(rootId);
                for(var close:actions())if("READY".equals(close.getStatus())&&Set.of("CANCEL_SUBSCRIPTION","DISABLE_ADVANCE","FINALIZE_INVOICE","VOID_INVOICE","RESTORE_ADVANCE").contains(close.getActionType()))service.runDueAction(close.getId());
                if("SWITCHED".equals(root().getStatus()))break;
            }
            service.reconcile(rootId);assertEquals("SWITCHED",root().getStatus());assertEquals("REPLACEMENT_PAID",root().getCloseReason());assertOriginalWindow(root());assertNull(root().getEntitlementInvoiceId());
            assertEquals("canceled",Subscription.retrieve(subscriptionId).getStatus());
            if(!doublePayment){assertEquals("void",Invoice.retrieve(invoiceId).getStatus());assertEquals(0L,Invoice.retrieve(invoiceId).getAmountPaid());}
            for(var close:actions())if(Set.of("CANCEL_SUBSCRIPTION","DISABLE_ADVANCE","FINALIZE_INVOICE","VOID_INVOICE","RESTORE_ADVANCE").contains(close.getActionType())){
                var proof=new LinkedHashMap<String,Object>();proof.put("rootId",rootId);proof.put("actionId",close.getId());proof.put("actionType",close.getActionType());proof.put("targetId",close.getTargetId());proof.put("status",close.getStatus());proof.put("parameters",SubscriptionRecoveryStripeGateway.thaw(close.getPayloadJson()));proof.put("idempotencyKey",close.getProviderIdempotencyKey());proof.put("attempts",close.getTransportAttempts());proof.put("providerRequestId",close.getProviderRequestId());journal.record("application-replacement-close-action",proof);
            }
            var replacement=service.replacement(user,replacementOrderNo);assertEquals("PAID",replacement.getState());assertNull(replacement.getActiveSubscriptionKey());assertEquals(newSub.getId(),replacement.getNewSubscriptionId());
            if(doublePayment){
                finishReplacementDouble(session,newSub,invoice,oldPaid,paid,completed,protectedDrafts,beforeCharges,reservation.actionId(),frozen,key);
                return;
            }
            assertReplacementRights(session,newSub,invoice);
            var rights=paidRightsSnapshot();var charges=ownedCustomerChargeIds();var nativeOld=ownedInvoices();
            assertEquals(beforeCharges.size()+1,charges.size());
            assertFalse(inbox.process(paid));assertFalse(inbox.process(completed));assertFalse(inbox.process(firstEvent));
            var deleted=replacementNativeEvent("customer.subscription.deleted",subscriptionId);assertNotNull(deleted);assertTrue(inbox.process(deleted));assertFalse(inbox.process(deleted));
            var updated=replacementNativeEvent("customer.subscription.updated",subscriptionId);assertNotNull(updated);assertTrue(inbox.process(updated));assertFalse(inbox.process(updated));
            var voided=replacementNativeEvent("invoice.voided",invoiceId);assertNotNull(voided);assertTrue(inbox.process(voided));assertFalse(inbox.process(voided));
            for(var done:actions())if("SUCCEEDED".equals(done.getStatus()))service.runDueAction(done.getId());
            service.reconcile(rootId);assertEquals(rights,paidRightsSnapshot());assertEquals(charges,ownedCustomerChargeIds());assertEquals(nativeOld.keySet(),ownedInvoices().keySet());
            for(var prior:nativeOld.values()){var current=Invoice.retrieve(prior.getId());assertEquals(prior.getStatus(),current.getStatus());assertEquals(prior.getAmountPaid(),current.getAmountPaid());assertEquals(prior.getAmountRemaining(),current.getAmountRemaining());assertEquals(prior.getAutoAdvance(),current.getAutoAdvance());}
            assertReplacementRights(session,Subscription.retrieve(newSub.getId()),Invoice.retrieve(invoice.getId()));
            var replay=sql.getMapper(SubscriptionRecoveryActionMapper.class).selectById(reservation.actionId());assertEquals(1,replay.getTransportAttempts());assertEquals(1,ownedGateway.checkoutNativeExecuteCount());assertEquals(frozen,replay.getPayloadJson());assertEquals(key,replay.getProviderIdempotencyKey());
            var evidence=new LinkedHashMap<String,Object>();evidence.put("rootId",rootId);evidence.put("orderNo",replacementOrderNo);evidence.put("sessionId",session.getId());evidence.put("newSubscriptionId",newSub.getId());evidence.put("newInvoiceId",invoice.getId());evidence.put("paidEventId",paid.getId());evidence.put("checkoutEventId",completed.getId());evidence.put("oldDeletedEventId",deleted.getId());evidence.put("oldInvoiceStatus","void");evidence.put("oldSubscriptionStatus","canceled");evidence.put("orders",1);evidence.put("planResetLedgers",3);evidence.put("replayDidNotWrite",true);evidence.put("result","PASS");evidence.put("actualUiEvidence","REQUIRED_SEPARATELY");evidence.put("trustedQuoteHttpResult","NOT_RUN");evidence.put("httpResult","NOT_RUN");evidence.put("clerkResult","NOT_RUN");evidence.put("quartzResult","NOT_RUN");evidence.put("doublePaymentResult","NOT_RUN");evidence.put("automaticRetrySlotsResult","NOT_RUN");evidence.put("mailResult","NOT_RUN");journal.record("application-replacement-provider-complete",evidence);
        }
    }
    private Event awaitReplacementOldHostedPayment(java.nio.file.Path challengePath,String url,String customer,
            Map<String,Object> beforeRights)throws Exception {
        var until=Instant.now().plusSeconds(900);long monotonic=System.nanoTime()+Duration.ofSeconds(900).toNanos();
        Event paid=null;
        try(var challenge=RecoverySandboxReplacement.writeOldHostedChallenge(challengePath,user,rootId,url,until)){
            journal.record("application-replacement-awaiting-old-payment-page",Map.of("runId",user,"rootId",rootId,
                    "invoiceId",invoiceId,"step","OLD_HOSTED_PAYMENT","privateChallengePath",challengePath.toString(),
                    "expiresAt",until.toString(),"hostedOrigin","https://invoice.stripe.com","linkIssuedBeforeReservation",true));
            while(Instant.now().isBefore(until)&&System.nanoTime()-monotonic<0){
                var invoice=Invoice.retrieve(invoiceId);requireOldInvoiceOwnership(invoice,customer);
                if("paid".equals(invoice.getStatus())&&Long.valueOf(7999).equals(invoice.getAmountPaid())&&Long.valueOf(0).equals(invoice.getAmountRemaining())){
                    requireReplacementCash(invoice,customer);paid=replacementNativeEvent("invoice.paid",invoiceId);if(paid!=null)break;
                }
                Thread.sleep(2000);
            }
            assertNotNull(paid,"REPLACEMENT_OLD_HOSTED_TIMEOUT_NO_NATIVE_PAID_EVENT");
            assertEquals(beforeRights,paidRightsSnapshot(),"Old receipt is deliberately withheld from inbox/reconcile until new receipt");
            assertEquals("OPEN",service.replacement(user,replacementOrderNo).getState());
            var proof=new LinkedHashMap<String,Object>(replacementInvoiceEvidence(Invoice.retrieve(invoiceId),customer));
            proof.put("eventId",paid.getId());proof.put("applicationDelivery","WITHHELD_UNTIL_NEW_PAYMENT");
            journal.record("application-replacement-old-provider-paid",proof);
        }
        return paid;
    }
    private void requireOldInvoiceOwnership(Invoice invoice,String customer)throws Exception {
        assertEquals(invoiceId,invoice.getId());assertEquals(Boolean.FALSE,invoice.getLivemode());
        assertEquals(customer,invoice.getCustomer());assertEquals(subscriptionId,invoice.getSubscription());
        var oldSub=Subscription.retrieve(subscriptionId);assertEquals(Boolean.FALSE,oldSub.getLivemode());
        assertEquals(user,oldSub.getMetadata().get("recovery_test_run"));assertEquals(customer,oldSub.getCustomer());
        assertEquals("usd",invoice.getCurrency());assertEquals(7999L,invoice.getAmountDue());
    }
    private Charge requireReplacementCash(Invoice invoice,String customer)throws Exception {
        assertEquals(Boolean.FALSE,invoice.getLivemode());assertEquals(customer,invoice.getCustomer());assertEquals("paid",invoice.getStatus());
        assertEquals(7999L,invoice.getAmountPaid());assertEquals(0L,invoice.getAmountRemaining());assertNotNull(invoice.getStatusTransitions().getPaidAt());
        var pi=PaymentIntent.retrieve(invoice.getPaymentIntent());var charge=Charge.retrieve(pi.getLatestCharge());
        assertEquals(Boolean.FALSE,pi.getLivemode());assertEquals(Boolean.FALSE,charge.getLivemode());
        assertEquals(customer,pi.getCustomer());assertEquals(customer,charge.getCustomer());assertEquals("succeeded",pi.getStatus());
        assertEquals("usd",pi.getCurrency());assertEquals("usd",charge.getCurrency());assertEquals(7999L,pi.getAmountReceived());
        assertEquals(invoice.getId(),charge.getInvoice());assertEquals(pi.getId(),charge.getPaymentIntent());
        assertEquals("succeeded",charge.getStatus());assertEquals(Boolean.TRUE,charge.getPaid());assertEquals(Boolean.TRUE,charge.getCaptured());
        assertEquals(7999L,charge.getAmount());assertEquals(7999L,charge.getAmountCaptured());
        assertEquals(Boolean.FALSE,charge.getRefunded());assertEquals(0L,charge.getAmountRefunded());return charge;
    }
    private Map<String,Object> replacementInvoiceEvidence(Invoice invoice,String customer)throws Exception {
        var charge=requireReplacementCash(invoice,customer);var proof=new LinkedHashMap<String,Object>();
        proof.put("invoiceId",invoice.getId());proof.put("subscriptionId",invoice.getSubscription());proof.put("customerId",customer);
        proof.put("invoiceStatus",invoice.getStatus());proof.put("amountPaid",invoice.getAmountPaid());proof.put("amountRemaining",invoice.getAmountRemaining());
        proof.put("currency",invoice.getCurrency());proof.put("actualPaidAt",invoice.getStatusTransitions().getPaidAt());
        proof.put("paymentIntentId",invoice.getPaymentIntent());proof.put("chargeId",charge.getId());proof.put("chargeStatus",charge.getStatus());
        proof.put("amountCaptured",charge.getAmountCaptured());proof.put("refunded",charge.getRefunded());proof.put("amountRefunded",charge.getAmountRefunded());return proof;
    }
    private void finishReplacementDouble(com.stripe.model.checkout.Session session,Subscription newSub,Invoice invoice,
            Event oldPaid,Event paid,Event completed,Set<String> protectedDrafts,Set<String> beforeCharges,
            long createActionId,String frozen,String key)throws Exception {
        assertNotNull(oldPaid);assertTrue(inbox.process(oldPaid));assertFalse(inbox.process(oldPaid));
        service.reconcile(rootId);assertReplacementRights(session,newSub,invoice,true);
        var oldInvoice=Invoice.retrieve(invoiceId);requireOldInvoiceOwnership(oldInvoice,session.getCustomer());
        var oldCharge=requireReplacementCash(oldInvoice,session.getCustomer());var newCharge=requireReplacementCash(invoice,session.getCustomer());
        assertNotEquals(oldCharge.getId(),newCharge.getId());assertNotEquals(oldInvoice.getPaymentIntent(),invoice.getPaymentIntent());
        assertEquals("SWITCHED",root().getStatus());assertEquals("REPLACEMENT_PAID",root().getCloseReason());assertNull(root().getEntitlementInvoiceId());assertEquals("HELD",root().getGrantState());
        assertEquals("OPEN",root().getReviewState());assertEquals("OLD_PAYMENT_AFTER_SWITCH_OR_CLOSE",root().getReviewReason(),"Draft-only review cannot prove double-payment handling");
        assertNotNull(root().getReviewEnteredAt());assertNotNull(root().getReviewDueAt());assertNotNull(root().getReviewNextFollowupAt());
        var entered=root().getReviewEnteredAt();var due=entered;int workdays=0;
        while(workdays<2){due=due.plusDays(1);if(due.getDayOfWeek()!=java.time.DayOfWeek.SATURDAY&&due.getDayOfWeek()!=java.time.DayOfWeek.SUNDAY)workdays++;}
        assertEquals(due,root().getReviewDueAt());assertEquals(due,root().getReviewNextFollowupAt());
        assertEquals(7999L,root().getAmountPaidMinor());assertEquals(0L,root().getAmountRemainingMinor());assertEquals("paid",root().getStripeInvoiceStatus());
        var receipt=JsonParser.parseString(root().getPaymentEvidence()).getAsJsonObject();
        assertEquals(invoiceId,receipt.get("invoice_id").getAsString());assertEquals(oldInvoice.getPaymentIntent(),receipt.get("payment_intent_id").getAsString());
        assertEquals(oldCharge.getId(),receipt.get("charge_id").getAsString());assertEquals(7999L,receipt.get("amount_paid").getAsLong());
        assertEquals(oldInvoice.getStatusTransitions().getPaidAt().longValue(),receipt.get("paid_at").getAsLong());assertEquals("invoice.status_transitions.paid_at",receipt.get("paid_at_source").getAsString());
        var orders=sql.getMapper(RechargeOrderMapper.class).selectList(null);var oldOrders=orders.stream().filter(o->invoiceId.equals(o.getStripeInvoiceId())).toList();
        assertEquals(1,oldOrders.size());assertEquals(2,orders.size());var oldOrder=oldOrders.get(0);
        assertEquals("completed",oldOrder.getStatus());assertEquals("subscription",oldOrder.getOrderType());assertEquals(user,oldOrder.getClerkUserId());
        assertEquals(subscriptionId,oldOrder.getStripeSubscriptionId());assertEquals(oldInvoice.getPaymentIntent(),oldOrder.getStripePaymentIntentId());assertEquals(7999,oldOrder.getPriceCents());assertEquals("usd",oldOrder.getCurrency());
        assertEquals(com.studyagent.common.datetime.DateTimeFormats.fromInstant(Instant.ofEpochSecond(oldInvoice.getStatusTransitions().getPaidAt())),oldOrder.getPaidAt());
        assertFalse(actions().stream().anyMatch(a->"VOID_INVOICE".equals(a.getActionType())&&invoiceId.equals(a.getTargetId())));
        assertFalse(actions().stream().anyMatch(a->"SHIFT_RENEWAL".equals(a.getActionType())));
        var cancel=actions().stream().filter(a->"CANCEL_SUBSCRIPTION".equals(a.getActionType())&&subscriptionId.equals(a.getTargetId())).findFirst().orElseThrow();
        assertEquals("SUCCEEDED",cancel.getStatus());assertEquals(SubscriptionRecoveryStripeGateway.cancelParams(),SubscriptionRecoveryStripeGateway.thaw(cancel.getPayloadJson()));
        var rights=paidRightsSnapshot();var charges=ownedCustomerChargeIds();assertEquals(beforeCharges.size()+2,charges.size());
        var originalInvoices=ownedInvoices();var review=Arrays.asList(root().getReviewState(),root().getReviewReason(),entered,due,root().getReviewNextFollowupAt(),root().getPaymentEvidence());
        var deleted=replacementNativeEvent("customer.subscription.deleted",subscriptionId);var updated=replacementNativeEvent("customer.subscription.updated",subscriptionId);
        assertNotNull(deleted);assertNotNull(updated);assertTrue(inbox.process(deleted));assertFalse(inbox.process(deleted));assertTrue(inbox.process(updated));assertFalse(inbox.process(updated));
        assertFalse(inbox.process(oldPaid));assertFalse(inbox.process(paid));assertFalse(inbox.process(completed));assertFalse(inbox.process(firstEvent));
        for(var done:actions())if("SUCCEEDED".equals(done.getStatus()))service.runDueAction(done.getId());
        service.reconcile(rootId);assertEquals(rights,paidRightsSnapshot());assertEquals(charges,ownedCustomerChargeIds());
        assertEquals(review,Arrays.asList(root().getReviewState(),root().getReviewReason(),root().getReviewEnteredAt(),root().getReviewDueAt(),root().getReviewNextFollowupAt(),root().getPaymentEvidence()));
        assertEquals(originalInvoices.keySet(),ownedInvoices().keySet());
        for(var prior:originalInvoices.values()){var current=Invoice.retrieve(prior.getId());assertEquals(prior.getStatus(),current.getStatus());assertEquals(prior.getAmountPaid(),current.getAmountPaid());assertEquals(prior.getAutoAdvance(),current.getAutoAdvance());}
        var draftProof=new ArrayList<Map<String,Object>>();assertFalse(protectedDrafts.isEmpty(),"Owned old draft must remain covered");
        for(String id:protectedDrafts){var draft=Invoice.retrieve(id);assertEquals(subscriptionId,draft.getSubscription());assertEquals("draft",draft.getStatus());assertEquals(Boolean.FALSE,draft.getAutoAdvance());assertEquals(0L,draft.getAmountPaid());draftProof.add(Map.of("invoiceId",id,"status",draft.getStatus(),"autoAdvance",draft.getAutoAdvance(),"amountPaid",draft.getAmountPaid()));}
        assertReplacementRights(session,Subscription.retrieve(newSub.getId()),Invoice.retrieve(invoice.getId()),true);
        var create=sql.getMapper(SubscriptionRecoveryActionMapper.class).selectById(createActionId);assertEquals(1,create.getTransportAttempts());assertEquals(1,ownedGateway.checkoutNativeExecuteCount());assertEquals(frozen,create.getPayloadJson());assertEquals(key,create.getProviderIdempotencyKey());
        var evidence=new LinkedHashMap<String,Object>();evidence.put("rootId",rootId);evidence.put("orderNo",replacementOrderNo);evidence.put("sessionId",session.getId());
        evidence.put("providerPaymentOrder","OLD_THEN_NEW");evidence.put("applicationEventOrder","NEW_INVOICE_CHECKOUT_THEN_OLD_PAID");evidence.put("otherOrderingsResult","NOT_RUN");
        evidence.put("oldPayment",replacementInvoiceEvidence(Invoice.retrieve(invoiceId),session.getCustomer()));evidence.put("newPayment",replacementInvoiceEvidence(Invoice.retrieve(invoice.getId()),session.getCustomer()));
        evidence.put("currentSubscriptionId",sql.getMapper(UserSubscriptionMapper.class).selectByUser(user).getStripeSubscriptionId());evidence.put("newPeriodStart",newSub.getCurrentPeriodStart());evidence.put("newPeriodEnd",newSub.getCurrentPeriodEnd());
        evidence.put("oldSubscriptionStatus",Subscription.retrieve(subscriptionId).getStatus());evidence.put("oldPaidEventId",oldPaid.getId());evidence.put("newPaidEventId",paid.getId());evidence.put("checkoutEventId",completed.getId());
        evidence.put("reviewState",root().getReviewState());evidence.put("reviewReason",root().getReviewReason());evidence.put("reviewEnteredAt",entered.toString());evidence.put("reviewDueAt",due.toString());evidence.put("reviewNextFollowupAt",root().getReviewNextFollowupAt().toString());
        evidence.put("refundHandling","MANUAL_OLD_INVOICE_REFUND_REVIEW_NO_AUTOMATIC_REFUND");evidence.put("oldPaymentEvidence",receipt);
        evidence.put("orders",orders.stream().map(o->{var m=new LinkedHashMap<String,Object>();m.put("orderNo",o.getOrderNo());m.put("orderType",o.getOrderType());m.put("status",o.getStatus());m.put("invoiceId",o.getStripeInvoiceId());m.put("subscriptionId",o.getStripeSubscriptionId());m.put("paymentIntentId",o.getStripePaymentIntentId());m.put("amount",o.getPriceCents());m.put("currency",o.getCurrency());m.put("paidAt",Objects.toString(o.getPaidAt(),"NONE"));return m;}).toList());
        evidence.put("quotaSources",sql.getMapper(QuotaLedgerMapper.class).selectList(null).stream().map(l->{var m=new LinkedHashMap<String,Object>();m.put("type",l.getLedgerType());m.put("feature",l.getFeatureCode());m.put("amount",l.getAmount());m.put("invoiceId",l.getInvoiceId());m.put("sourceId",l.getSourceId());m.put("sourceType",l.getSourceType());m.put("subscriptionId",l.getSubscriptionId());m.put("idempotencyKey",l.getIdempotencyKey());return m;}).toList());
        evidence.put("quotas",sql.getMapper(UserAiQuotaMapper.class).selectList(null).stream().map(q->Map.of("feature",q.getFeatureCode(),"planBalance",q.getPlanBalance(),"periodStart",q.getPlanPeriodStart().toString(),"periodEnd",q.getPlanPeriodEnd().toString())).toList());evidence.put("protectedDrafts",draftProof);
        // Exclude provider result_json: a Checkout response can contain the private payment URL.
        evidence.put("actions",actions().stream().filter(a->Set.of("CREATE_CHECKOUT","CANCEL_SUBSCRIPTION","VOID_INVOICE","FINALIZE_INVOICE","DISABLE_ADVANCE","RESTORE_ADVANCE").contains(a.getActionType())).map(a->{var m=new LinkedHashMap<String,Object>();m.put("id",a.getId());m.put("type",a.getActionType());m.put("targetId",a.getTargetId());m.put("status",a.getStatus());m.put("parameters",SubscriptionRecoveryStripeGateway.thaw(a.getPayloadJson()));m.put("idempotencyKey",a.getProviderIdempotencyKey());m.put("attempts",a.getTransportAttempts());m.put("providerRequestId",a.getProviderRequestId());return m;}).toList());
        evidence.put("replayDidNotWrite",true);evidence.put("result","PASS");evidence.put("doublePaymentResult","PASS");evidence.put("actualUiEvidence","REQUIRED_SEPARATELY");evidence.put("httpResult","NOT_RUN");evidence.put("clerkResult","NOT_RUN");evidence.put("mailResult","NOT_RUN");
        journal.record("application-replacement-double-provider-complete",evidence);
    }
    private void requireReplacementSession(com.stripe.model.checkout.Session session,String customer,Map<String,String> metadata){
        assertEquals(Boolean.FALSE,session.getLivemode());assertEquals(customer,session.getCustomer());assertEquals("subscription",session.getMode());
        for(var item:metadata.entrySet())assertEquals(item.getValue(),session.getMetadata().get(item.getKey()));
        assertEquals(user,session.getClientReferenceId()); // Additional consistency only; server metadata and persisted reservation establish ownership.
    }
    private Event replacementNativeEvent(String type,String target)throws Exception {
        for(var event:Event.list(Map.of("types",List.of(type),"limit",100L)).autoPagingIterable()){
            var raw=JsonParser.parseString(event.getDataObjectDeserializer().getRawJson()).getAsJsonObject();
            if(raw.has("id")&&target.equals(raw.get("id").getAsString())){
                var fetched=Event.retrieve(event.getId());assertEquals(event.getId(),fetched.getId());assertEquals(type,fetched.getType());assertEquals(Boolean.FALSE,fetched.getLivemode());
                var nativeObject=JsonParser.parseString(fetched.getDataObjectDeserializer().getRawJson()).getAsJsonObject();
                assertEquals(target,nativeObject.get("id").getAsString());return fetched;
            }
        }return null;
    }
    private void assertReplacementRights(com.stripe.model.checkout.Session session,Subscription sub,Invoice invoice){
        assertReplacementRights(session,sub,invoice,false);
    }
    private void assertReplacementRights(com.stripe.model.checkout.Session session,Subscription sub,Invoice invoice,boolean doublePayment){
        var account=sql.getMapper(UserSubscriptionMapper.class).selectByUser(user);assertEquals(sub.getId(),account.getStripeSubscriptionId());assertNull(account.getPaymentRecoveryId());assertNull(account.getGraceEndAt());assertEquals(IntroTrialPlans.PHASE_STANDARD,account.getSubscriptionPhase());assertEquals(fixture.targetPlanCode(),account.getPlanCode());assertFalse(access.resolve(user,sub.getId()).locked());
        var paidInstant=Instant.ofEpochSecond(invoice.getStatusTransitions().getPaidAt());
        assertEquals(paidInstant.getEpochSecond(),sub.getCurrentPeriodStart());
        assertEquals(SubscriptionRecoveryPolicy.paidPeriodEnd(paidInstant,"month",1).getEpochSecond(),sub.getCurrentPeriodEnd());
        var paidAt=com.studyagent.common.datetime.DateTimeFormats.fromInstant(paidInstant);
        var end=com.studyagent.common.datetime.DateTimeFormats.fromInstant(Instant.ofEpochSecond(sub.getCurrentPeriodEnd()));assertEquals(paidAt,account.getCurrentPeriodStart());assertEquals(end,account.getCurrentPeriodEnd());
        var orders=sql.getMapper(RechargeOrderMapper.class).selectList(null);
        if(!doublePayment)assertEquals(1,orders.size());
        var newOrders=orders.stream().filter(o->invoice.getId().equals(o.getStripeInvoiceId())).toList();assertEquals(1,newOrders.size());
        var order=newOrders.get(0);assertEquals("completed",order.getStatus());assertEquals("subscription_replacement",order.getOrderType());assertEquals(replacementOrderNo,order.getOrderNo());assertEquals(session.getId(),order.getStripeSessionId());assertEquals(sub.getId(),order.getStripeSubscriptionId());assertEquals(invoice.getId(),order.getStripeInvoiceId());assertEquals(invoice.getPaymentIntent(),order.getStripePaymentIntentId());assertEquals(paidAt,order.getPaidAt());assertEquals(7999,order.getPriceCents());
        var quotas=sql.getMapper(UserAiQuotaMapper.class).selectList(null);assertEquals(3,quotas.size());
        assertEquals(ANNUAL_GRANTS.keySet(),quotas.stream().map(UserAiQuotaEntity::getFeatureCode).collect(java.util.stream.Collectors.toSet()));
        for(var quota:quotas){assertEquals(user,quota.getClerkUserId());assertEquals(ANNUAL_GRANTS.get(quota.getFeatureCode()),quota.getPlanBalance());assertEquals(paidAt,quota.getPlanPeriodStart());assertEquals(end,quota.getPlanPeriodEnd());}
        var resets=sql.getMapper(QuotaLedgerMapper.class).selectList(null).stream().filter(l->"plan_reset".equals(l.getLedgerType())).toList();assertEquals(3,resets.size());
        assertEquals(ANNUAL_GRANTS.keySet(),resets.stream().map(QuotaLedgerEntity::getFeatureCode).collect(java.util.stream.Collectors.toSet()));
        for(var ledger:resets){assertEquals(ANNUAL_GRANTS.get(ledger.getFeatureCode()),ledger.getAmount());assertEquals(ledger.getAmount(),ledger.getPlanBalanceAfter());assertEquals(invoice.getId(),ledger.getInvoiceId());assertEquals(invoice.getId(),ledger.getSourceId());assertEquals("invoice",ledger.getSourceType());assertEquals(sub.getId(),ledger.getSubscriptionId());}
        var extras=sql.getMapper(QuotaLedgerMapper.class).selectList(null).stream().filter(l->!"plan_reset".equals(l.getLedgerType())).toList();
        assertEquals(1,extras.size());var resume=extras.get(0);assertEquals("addon_resume",resume.getLedgerType());assertEquals("replacement:"+replacementOrderNo+":addons",resume.getIdempotencyKey());assertEquals(0L,resume.getAmount());assertEquals(sub.getId(),resume.getSubscriptionId());assertEquals(user,resume.getClerkUserId());
        assertEquals("active",sub.getStatus());assertNull(sub.getPauseCollection());assertFalse(config.isEmailEnabled());assertNull(ReflectionTestUtils.getField(service,"emailSender"));
        for(var reminder:actions())if("SEND_EMAIL".equals(reminder.getActionType()))assertEquals(0,reminder.getTransportAttempts());
    }
    /** Real provider pages perform the TEST method change/payment; this path never calls pay. */
    void acceptPortalHosted(com.stripe.model.billingportal.Configuration portalConfig,java.nio.file.Path challengePath)throws Exception {
        RecoverySandboxPortalHosted.requireScope(fixture,System.getenv(),"generic_decline");assertFalse(automaticFunds);assertFalse(config.isEmailEnabled());
        assertEquals(Boolean.FALSE,portalConfig.getLivemode());assertEquals(user,portalConfig.getMetadata().get("recovery_test_run"));assertEquals("portal_method_only",portalConfig.getMetadata().get("purpose"));assertEquals(Boolean.TRUE,portalConfig.getActive());
        var configJson=JsonParser.parseString(portalConfig.toJson()).getAsJsonObject();
        var expected=(Map<?,?>)RecoverySandboxPortalHosted.configurationParameters(user).get("features");
        for(var feature:expected.keySet())assertEquals("payment_method_update".equals(feature),configJson.getAsJsonObject("features").getAsJsonObject(feature.toString()).get("enabled").getAsBoolean());
        assertFalse(configJson.getAsJsonObject("login_page").get("enabled").getAsBoolean());
        var source=Invoice.retrieve(invoiceId);var customer=Customer.retrieve(source.getCustomer());var subscription=Subscription.retrieve(subscriptionId);
        assertEquals(Boolean.FALSE,customer.getLivemode());assertEquals(user,customer.getMetadata().get("recovery_test_run"));assertEquals(user,subscription.getMetadata().get("recovery_test_run"));assertEquals(Boolean.FALSE,source.getLivemode());assertEquals(subscriptionId,source.getSubscription());
        String oldMethod=customer.getInvoiceSettings().getDefaultPaymentMethod();assertNotNull(oldMethod);assertEquals(oldMethod,subscription.getDefaultPaymentMethod());
        assertNotNull(source.getHostedInvoiceUrl());var charges=ownedCustomerChargeIds();var rights=paidRightsSnapshot();
        config.setPortalMethodOnlyConfigurationId(portalConfig.getId());
        var parameters=Map.<String,Object>of("customer",customer.getId(),"configuration",portalConfig.getId(),"return_url","http://127.0.0.1:18788/","flow_data",Map.of("type","payment_method_update"));
        String portalUrl=service.createPortalSession(user,rootId,"payment_method_update",parameters).getUrl();
        var portal=actions().stream().filter(a->"CREATE_PORTAL".equals(a.getActionType())).findFirst().orElseThrow();
        assertEquals("SUCCEEDED",portal.getStatus());assertEquals(1,portal.getTransportAttempts());assertNotNull(portal.getProviderRequestId());assertEquals(parameters,SubscriptionRecoveryStripeGateway.thaw(portal.getPayloadJson()));
        journal.record("application-portal-session-created",Map.of("rootId",rootId,"actionId",portal.getId(),"idempotencyKey",portal.getProviderIdempotencyKey(),"parameters",parameters,"configurationId",portalConfig.getId(),"providerRequestId",portal.getProviderRequestId(),"portalOrigin","https://billing.stripe.com","portalResult","API_CONFIGURATION_AND_SESSION_ONLY","portalUiEvidence","NOT_OBSERVED"));
        String newMethod=null;Event changed=null;Instant deadline=Instant.now().plusSeconds(900);long monotonicDeadline=System.nanoTime()+Duration.ofSeconds(900).toNanos();
        try(var challenge=RecoverySandboxPortalHosted.writeChallenge(challengePath,user,rootId,"PORTAL_METHOD",portalUrl,source.getHostedInvoiceUrl(),deadline)){
            journal.record("application-portal-awaiting-method-page",Map.of("runId",user,"rootId",rootId,"customerId",customer.getId(),"invoiceId",invoiceId,"privateChallengePath",challengePath.toString(),"step","PORTAL_METHOD","expiresAt",deadline.toString(),"actualUiEvidence","REQUIRED_SEPARATELY"));
            while(Instant.now().isBefore(deadline)&&System.nanoTime()-monotonicDeadline<0){
                var observed=Customer.retrieve(customer.getId());assertEquals(Boolean.FALSE,observed.getLivemode());assertEquals(user,observed.getMetadata().get("recovery_test_run"));
                String candidate=observed.getInvoiceSettings().getDefaultPaymentMethod();
                if(candidate!=null&&!oldMethod.equals(candidate)){
                    var method=PaymentMethod.retrieve(candidate);assertEquals(Boolean.FALSE,method.getLivemode());assertEquals(customer.getId(),method.getCustomer());assertNotNull(method.getCard());assertEquals("4242",method.getCard().getLast4());
                    changed=nativeMethodEvent(customer.getId(),oldMethod,candidate);
                    if(changed!=null){newMethod=candidate;break;}
                }
                Thread.sleep(2000);
            }
            assertNotNull(changed,"PORTAL_METHOD_TIMEOUT_NO_PROVIDER_CHANGE_OR_NATIVE_EVENT");assertNotNull(newMethod);
            assertTrue(inbox.process(changed));assertFalse(inbox.process(changed));service.reconcile(rootId);
            for(int pass=0;pass<6;pass++){
                for(var action:actions())if("UPDATE_METHOD".equals(action.getActionType())&&Set.of("READY","UNKNOWN").contains(action.getStatus()))service.runDueAction(action.getId());
                service.reconcile(rootId);
                if(newMethod.equals(Subscription.retrieve(subscriptionId).getDefaultPaymentMethod())&&newMethod.equals(Invoice.retrieve(invoiceId).getDefaultPaymentMethod()))break;
            }
            String confirmedMethod=newMethod;
            var sync=actions().stream().filter(a->"UPDATE_METHOD".equals(a.getActionType())&&confirmedMethod.equals(SubscriptionRecoveryStripeGateway.thaw(a.getPayloadJson()).get("default_payment_method"))).toList();
            assertEquals(Set.of(subscriptionId,invoiceId),sync.stream().map(SubscriptionRecoveryActionEntity::getTargetId).collect(java.util.stream.Collectors.toSet()));
            for(var action:sync){assertEquals("SUCCEEDED",action.getStatus());assertEquals(1,action.getTransportAttempts());assertNotNull(action.getProviderRequestId());}
            assertEquals(newMethod,Subscription.retrieve(subscriptionId).getDefaultPaymentMethod());assertEquals(newMethod,Invoice.retrieve(invoiceId).getDefaultPaymentMethod());
            assertEquals(charges,ownedCustomerChargeIds());assertEquals(rights,paidRightsSnapshot());assertPortalUnpaid();
            journal.record("application-portal-method-synchronized",Map.of("rootId",rootId,"nativeEventId",changed.getId(),"paymentMethodId",newMethod,"actionIds",sync.stream().map(SubscriptionRecoveryActionEntity::getId).toList(),"originalInvoiceStillOpen",true,"chargesUnchanged",true,"d0D30Unchanged",true,"rightsNotGranted",true,"portalUiEvidence","REQUIRED_SEPARATELY"));
        }
        deadline=Instant.now().plusSeconds(900);monotonicDeadline=System.nanoTime()+Duration.ofSeconds(900).toNanos();boolean paidEventReady=false;
        try(var challenge=RecoverySandboxPortalHosted.writeChallenge(challengePath,user,rootId,"HOSTED_PAYMENT",portalUrl,source.getHostedInvoiceUrl(),deadline)){
            journal.record("application-hosted-awaiting-payment-page",Map.of("runId",user,"rootId",rootId,"invoiceId",invoiceId,"privateChallengePath",challengePath.toString(),"step","HOSTED_PAYMENT","hostedOrigin","https://invoice.stripe.com","expiresAt",deadline.toString(),"actualUiEvidence","REQUIRED_SEPARATELY"));
            while(Instant.now().isBefore(deadline)&&System.nanoTime()-monotonicDeadline<0){
                var observed=Invoice.retrieve(invoiceId);assertEquals(Boolean.FALSE,observed.getLivemode());assertEquals(customer.getId(),observed.getCustomer());assertEquals(subscriptionId,observed.getSubscription());
                if("paid".equals(observed.getStatus())){
                    assertEquals(source.getPaymentIntent(),observed.getPaymentIntent());assertEquals(fixture.unitAmountMinor(),observed.getAmountPaid());assertEquals(0L,observed.getAmountRemaining());
                    var intent=PaymentIntent.retrieve(observed.getPaymentIntent());var charge=Charge.retrieve(intent.getLatestCharge());assertEquals(Boolean.FALSE,charge.getLivemode());assertEquals(invoiceId,charge.getInvoice());assertEquals(customer.getId(),charge.getCustomer());assertEquals(newMethod,charge.getPaymentMethod());assertEquals("succeeded",charge.getStatus());
                    for(var event:Event.list(Map.of("types",List.of("invoice.paid"),"created",Map.of("gte",firstEvent.getCreated()),"limit",100L)).autoPagingIterable()){
                        var raw=JsonParser.parseString(event.getDataObjectDeserializer().getRawJson()).getAsJsonObject();if(raw.has("id")&&invoiceId.equals(raw.get("id").getAsString())){assertEquals(Boolean.FALSE,event.getLivemode());paidEventReady=true;break;}
                    }
                    if(paidEventReady)break;
                }
                Thread.sleep(2000);
            }
            assertTrue(paidEventReady,"HOSTED_PAYMENT_TIMEOUT_NO_NATIVE_ORIGINAL_INVOICE_PAID");
            grantFromNativePaidEvent();
            var actualPaidAt=com.studyagent.common.datetime.DateTimeFormats.fromInstant(Instant.ofEpochSecond(Invoice.retrieve(invoiceId).getStatusTransitions().getPaidAt()));
            assertEquals(actualPaidAt,root().getPaidAt());assertEquals(actualPaidAt,root().getEntitlementStartAt());
            for(var quota:sql.getMapper(UserAiQuotaMapper.class).selectList(null))assertEquals(actualPaidAt,quota.getPlanPeriodStart());
            shiftUsingPersistedAction();
            var afterRights=paidRightsSnapshot();var afterCharges=ownedCustomerChargeIds();var nativeSub=shiftNativeSnapshot(Subscription.retrieve(subscriptionId));
            var shift=actions().stream().filter(a->"SHIFT_RENEWAL".equals(a.getActionType())).findFirst().orElseThrow();String frozen=shift.getPayloadJson(),key=shift.getProviderIdempotencyKey();
            assertFalse(inbox.process(nativePaidEvent));service.runDueAction(shift.getId());service.reconcile(rootId);
            var replay=sql.getMapper(SubscriptionRecoveryActionMapper.class).selectById(shift.getId());assertEquals(1,replay.getTransportAttempts());assertEquals(frozen,replay.getPayloadJson());assertEquals(key,replay.getProviderIdempotencyKey());assertEquals(afterRights,paidRightsSnapshot());assertEquals(afterCharges,ownedCustomerChargeIds());assertEquals(nativeSub,shiftNativeSnapshot(Subscription.retrieve(subscriptionId)));assertOriginalWindow(root());
            journal.record("application-portal-hosted-provider-complete",Map.of("rootId",rootId,"invoiceId",invoiceId,"methodEventId",changed.getId(),"paidEventId",nativePaidEvent.getId(),"grants",1,"orders",1,"shiftActions",1,"replayDidNotWrite",true,"actualUiEvidence","REQUIRED_SEPARATELY","mailResult","NOT_RUN"));
        }
    }
    private Event nativeMethodEvent(String customer,String oldMethod,String method)throws Exception {
        for(var event:Event.list(Map.of("types",List.of("customer.updated"),"limit",100L)).autoPagingIterable()){
            var raw=JsonParser.parseString(event.getDataObjectDeserializer().getRawJson()).getAsJsonObject();
            if(!raw.has("id")||!customer.equals(raw.get("id").getAsString())||!raw.has("invoice_settings"))continue;
            var value=raw.getAsJsonObject("invoice_settings").get("default_payment_method");if(value==null||value.isJsonNull())continue;
            String actual=value.isJsonObject()?value.getAsJsonObject().get("id").getAsString():value.getAsString();if(!method.equals(actual))continue;
            var previous=event.getData().getPreviousAttributes();
            if(previous==null||!(previous.get("invoice_settings") instanceof Map<?,?> settings)||!oldMethod.equals(settings.get("default_payment_method")))continue;
            assertEquals(Boolean.FALSE,event.getLivemode());assertEquals(user,raw.getAsJsonObject("metadata").get("recovery_test_run").getAsString());return event;
        }return null;
    }
    private void assertPortalUnpaid()throws Exception {
        var invoice=Invoice.retrieve(invoiceId);assertEquals("open",invoice.getStatus());assertEquals(0L,invoice.getAmountPaid());assertFalse(invoice.getAutoAdvance());assertEquals("OPEN_MANUAL",root().getStatus());assertEquals("ACTIVE",root().getProtectionState());assertTrue(access.resolve(user,subscriptionId).locked());assertOriginalWindow(root());assertNoGrantOrShift();assertEquals(0,sql.getMapper(UserAiQuotaMapper.class).selectList(null).size());assertEquals(0,actions().stream().filter(a->"RETRY_PAY".equals(a.getActionType())).count());
        var reminders=actions().stream().filter(a->"SEND_EMAIL".equals(a.getActionType())).toList();assertEquals(1,reminders.size());var reminder=reminders.get(0);assertEquals("READY",reminder.getStatus());assertEquals(0,reminder.getTransportAttempts());assertNull(reminder.getLeaseToken());assertNull(reminder.getLeaseUntil());assertNull(reminder.getFirstDispatchedAt());assertNull(reminder.getProviderMessageId());assertFalse(config.isEmailEnabled());verifyNoInteractions(legacyAnalytics,legacyRobot);
    }
    /** One owned reminder, only after a private envelope has received explicit external approval. */
    void acceptControlledMail()throws Exception {
        assertEquals("true",System.getenv("STRIPE_RECOVERY_MAIL_ACCEPTANCE"));
        assertFalse(automaticFunds);assertTrue(fixture.priceUpdate());assertEquals("month",fixture.interval());assertFalse(fixture.keepSchedule());assertFalse(fixture.periodEnd());
        assertFalse(config.isEmailEnabled());assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
        String apiKey=System.getenv("STRIPE_RECOVERY_MAIL_API_KEY"),recipient=System.getenv("STRIPE_RECOVERY_MAIL_TO"),from=System.getenv("STRIPE_RECOVERY_MAIL_FROM");
        assertTrue(apiKey!=null&&!apiKey.isBlank(),"Private provider key is required");assertTrue(recipient!=null&&!recipient.isBlank(),"Owned recipient is required");
        assertTrue("Verla <notifications@send.verla.io>".equals(from),"Only the verified acceptance sender is allowed");config.setEmailFrom(from);
        var reminders=actions().stream().filter(a->"SEND_EMAIL".equals(a.getActionType())).toList();assertEquals(1,reminders.size());var action=reminders.get(0);
        assertEquals("READY",action.getStatus());assertEquals(0,action.getTransportAttempts());assertNull(action.getFirstDispatchedAt());assertNull(action.getLeaseToken());
        assertEquals("recovery:"+invoiceId+":manual-email",action.getProviderIdempotencyKey());assertEquals(action.getProviderIdempotencyKey(),action.getActionKey());
        var nativeInvoice=Invoice.retrieve(invoiceId);assertEquals(Boolean.FALSE,nativeInvoice.getLivemode());assertEquals("open",nativeInvoice.getStatus());assertEquals(0L,nativeInvoice.getAmountPaid());
        assertEquals(root().getStripeCustomerId(),nativeInvoice.getCustomer());assertEquals(subscriptionId,nativeInvoice.getSubscription());
        var charges=ownedCustomerChargeIds();assertMailUnpaidState();
        var clerk=mock(com.studyagent.service.domain.user.ClerkClient.class);when(clerk.getUserEmail(user)).thenReturn(recipient);
        var posts=new java.util.concurrent.atomic.AtomicInteger();
        var web=org.springframework.web.reactive.function.client.WebClient.builder().defaultHeader("User-Agent","StudyAgent-Recovery-Mail-Acceptance/1.0")
                .filter((request,next)->{
                    assertEquals(org.springframework.http.HttpMethod.POST,request.method());assertEquals("https://api.resend.com/emails",request.url().toString());
                    assertEquals(1,posts.incrementAndGet(),"At most one provider POST is authorized");return next.exchange(request);
                }).build();
        var sender=spy(new com.studyagent.infra.notification.BillingPaymentRecoveryEmailSender(clerk,web,config));
        var keyField=com.studyagent.infra.notification.BillingPaymentRecoveryEmailSender.class.getDeclaredField("apiKey");keyField.setAccessible(true);keyField.set(sender,apiKey);
        String localFrontend="http://127.0.0.1:18788";ReflectionTestUtils.setField(service,"frontendUrl",localFrontend);
        String frozen=sender.prepare(root(),localFrontend+"/?payment_recovery_id="+rootId);
        var proposal=new RecoverySandboxMailApproval.Proposal(user,rootId,action.getId(),invoiceId,action.getProviderIdempotencyKey(),frozen,Instant.now().plusSeconds(900));
        var challengePath=java.nio.file.Path.of("/private/tmp/recovery-mail-challenge.json");var approvalPath=java.nio.file.Path.of("/private/tmp/recovery-mail-approval.json");
        assertFalse(java.nio.file.Files.exists(approvalPath,java.nio.file.LinkOption.NOFOLLOW_LINKS),"Approval path must be absent before a fresh challenge");
        long approvalDeadlineNanos=System.nanoTime()+Duration.ofSeconds(900).toNanos();
        try(var challenge=RecoverySandboxMailApproval.writeChallenge(challengePath,proposal)){
            var awaiting=new LinkedHashMap<String,Object>();awaiting.put("runId",user);awaiting.put("rootId",rootId);awaiting.put("actionId",action.getId());awaiting.put("invoiceId",invoiceId);awaiting.put("bodySha256",RecoverySandboxMailApproval.bodySha256(frozen));awaiting.put("sender",from);awaiting.put("amountRemainingMinor",root().getAmountRemainingMinor());awaiting.put("currency",root().getCurrency());awaiting.put("privateChallengePath",challengePath.toString());awaiting.put("expiresAt",proposal.expiresAt().toString());awaiting.put("approvalState","NOT_APPROVED");awaiting.put("mailResult","NOT_RUN");
            journal.record("application-mail-awaiting-approval",awaiting);
            while(!RecoverySandboxMailApproval.approved(approvalPath,proposal,Instant.now())){
                if(!RecoverySandboxMailApproval.withinWindow(proposal,Instant.now(),approvalDeadlineNanos,System.nanoTime()))throw new IllegalStateException("MAIL_APPROVAL_TIMEOUT_NO_EMAIL_SENT");
                var waiting=sql.getMapper(SubscriptionRecoveryActionMapper.class).selectById(action.getId());assertEquals("READY",waiting.getStatus());assertNull(waiting.getLeaseToken());assertEquals(0,waiting.getTransportAttempts());assertFalse(config.isEmailEnabled());
                Thread.sleep(1000);
            }
            if(!RecoverySandboxMailApproval.withinWindow(proposal,Instant.now(),approvalDeadlineNanos,System.nanoTime()))throw new IllegalStateException("MAIL_APPROVAL_TIMEOUT_NO_EMAIL_SENT");
            try(var approval=new RecoverySandboxMailApproval.ChallengeFile(approvalPath,java.nio.file.Files.readAttributes(approvalPath,java.nio.file.attribute.BasicFileAttributes.class,java.nio.file.LinkOption.NOFOLLOW_LINKS).fileKey())){
                journal.record("application-mail-approved",Map.of("runId",user,"rootId",rootId,"actionId",action.getId(),"invoiceId",invoiceId,"bodySha256",RecoverySandboxMailApproval.bodySha256(frozen),"idempotencyKey",proposal.idempotencyKey(),"explicitApproval",true));
                doAnswer(invocation->{
                    assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive(),"Provider send must be outside a transaction");
                    assertTrue(frozen.equals(invocation.getArgument(0)),"Provider body must exactly match approved envelope");assertTrue(proposal.idempotencyKey().equals(invocation.getArgument(1)),"Provider key must exactly match approval");
                    var leased=sql.getMapper(SubscriptionRecoveryActionMapper.class).selectById(action.getId());assertEquals("DISPATCHING",leased.getStatus());assertTrue(RecoverySandboxMailApproval.sameEnvelope(frozen,leased.getPayloadJson()),"Approved body must already be frozen in SQL");assertTrue(proposal.idempotencyKey().equals(leased.getProviderIdempotencyKey()),"Approved key must be retained in SQL");assertNotNull(leased.getLeaseToken());assertNotNull(leased.getLeaseUntil());assertTrue(leased.getLeaseUntil().isAfter(com.studyagent.common.datetime.DateTimeFormats.now()),"Provider lease must still be active");assertNotNull(leased.getFirstDispatchedAt());assertEquals(1,leased.getTransportAttempts());
                    assertTrue(RecoverySandboxMailApproval.withinWindow(proposal,Instant.now(),approvalDeadlineNanos,System.nanoTime()),"Approval must remain within both deadlines at actual dispatch");return invocation.callRealMethod();
                }).when(sender).send(anyString(),anyString());
                ReflectionTestUtils.setField(service,"emailSender",sender);config.setEmailEnabled(true);
                service.runDueAction(action.getId());
                var saved=sql.getMapper(SubscriptionRecoveryActionMapper.class).selectById(action.getId());
                var result=new LinkedHashMap<String,Object>();result.put("rootId",rootId);result.put("invoiceId",invoiceId);result.put("actionId",action.getId());result.put("bodySha256",RecoverySandboxMailApproval.bodySha256(frozen));result.put("idempotencyKey",proposal.idempotencyKey());result.put("status",saved.getStatus());result.put("providerMessageId",Objects.toString(saved.getProviderMessageId(),""));result.put("transportAttempts",saved.getTransportAttempts());result.put("actualPosts",posts.get());result.put("mailResult","SUCCEEDED".equals(saved.getStatus())?"PROVIDER_ACK_ONLY":"NOT_FINAL");result.put("finalResult","NOT_RUN");result.put("inboxDelivery","NOT_RUN");result.put("pageE2E","NOT_RUN");result.put("productionMailGate","NOT_RUN");
                journal.record("application-mail-provider-result",result);
                // An UNKNOWN or FAILED send remains truthful; never replay it to force an acknowledgement.
                assertEquals("SUCCEEDED",saved.getStatus(),"Provider acknowledgement is required; uncertain outcomes are not retried here");assertTrue(saved.getProviderMessageId()!=null&&!saved.getProviderMessageId().isBlank(),"Provider acknowledgement id must be persisted");assertEquals(1,saved.getTransportAttempts());assertEquals(1,posts.get());assertTrue(RecoverySandboxMailApproval.sameEnvelope(frozen,saved.getPayloadJson()),"Frozen approved body must remain unchanged");
                service.runDueAction(action.getId());assertEquals(1,posts.get(),"Completed reminder replay must not POST");
                var replay=sql.getMapper(SubscriptionRecoveryActionMapper.class).selectById(action.getId());assertEquals("SUCCEEDED",replay.getStatus());assertEquals(1,replay.getTransportAttempts());assertEquals(saved.getProviderMessageId(),replay.getProviderMessageId());assertEquals(saved.getVersion(),replay.getVersion());assertTrue(saved.getPayloadJson().equals(replay.getPayloadJson()),"Replay must preserve persisted envelope bytes");
                assertMailUnpaidState();assertEquals(charges,ownedCustomerChargeIds());var after=Invoice.retrieve(invoiceId);assertEquals("open",after.getStatus());assertEquals(0L,after.getAmountPaid());
                var replayEvidence=new LinkedHashMap<String,Object>();replayEvidence.put("rootId",rootId);replayEvidence.put("actionId",action.getId());replayEvidence.put("invoiceId",invoiceId);replayEvidence.put("bodySha256",RecoverySandboxMailApproval.bodySha256(frozen));replayEvidence.put("idempotencyKey",proposal.idempotencyKey());replayEvidence.put("actualPosts",posts.get());replayEvidence.put("transportAttempts",replay.getTransportAttempts());replayEvidence.put("replayDidNotPost",true);replayEvidence.put("mailResult","PROVIDER_ACK_ONLY");replayEvidence.put("finalResult","NOT_RUN");replayEvidence.put("stillUnpaid",true);replayEvidence.put("locked",true);replayEvidence.put("rootStatus",root().getStatus());replayEvidence.put("protection",root().getProtectionState());replayEvidence.put("orders",0);replayEvidence.put("quotaRows",0);replayEvidence.put("ledgerRows",0);replayEvidence.put("shiftActions",0);replayEvidence.put("chargesUnchanged",true);
                journal.record("application-mail-replay",replayEvidence);
            }finally{config.setEmailEnabled(false);}
        }
    }
    private void assertMailUnpaidState(){
        assertEquals("OPEN_MANUAL",root().getStatus());assertEquals("ACTIVE",root().getProtectionState());assertEquals(0L,root().getAmountPaidMinor());assertTrue(access.resolve(user,subscriptionId).locked());
        assertEquals(0,sql.getMapper(RechargeOrderMapper.class).selectList(null).size());assertEquals(0,sql.getMapper(UserAiQuotaMapper.class).selectList(null).size());assertEquals(0,sql.getMapper(QuotaLedgerMapper.class).selectList(null).size());
        assertEquals(0,actions().stream().filter(a->"SHIFT_RENEWAL".equals(a.getActionType())).count());verifyNoInteractions(legacyAnalytics,legacyRobot);
    }
    private void assertRetryIntents(){
        var retries=actions().stream().filter(a->"RETRY_PAY".equals(a.getActionType())).toList();assertEquals(4,retries.size());
        assertEquals(Set.of(1,2,3,4),retries.stream().map(SubscriptionRecoveryActionEntity::getRetrySlot).collect(java.util.stream.Collectors.toSet()));
        for(var action:retries){
            String key="recovery:"+invoiceId+":retry:"+action.getRetrySlot();assertEquals(key,action.getActionKey());assertEquals(key,action.getProviderIdempotencyKey());assertEquals(invoiceId,action.getTargetId());
            assertEquals(Map.of("off_session",true),SubscriptionRecoveryStripeGateway.thaw(action.getPayloadJson()));
            assertEquals(com.studyagent.common.datetime.DateTimeFormats.fromInstant(provenFirstFailure.plus(Duration.ofDays(7L*action.getRetrySlot()))),action.getScheduledAt());
        }
        assertEquals(0,actions().stream().filter(a->"SEND_EMAIL".equals(a.getActionType())).count(),"Funds recovery must remain silent");
    }
    /** Advances are performed by the owned fixture; every payment here is dispatched by the real service. */
    void runAutomaticRetrySlot(int slot)throws Exception {runAutomaticRetrySlot(slot,slot==4);}
    void runAutomaticRetrySlot(int slot,boolean expectedPaid)throws Exception {
        assertFalse(expectedPaid&&slot!=4,"Only the original D28 success path may expect payment");
        assertTrue(automaticFunds);assertTrue(slot>=1&&slot<=4);assertOriginalWindow(root());assertRetryIntents();
        var invoice=Invoice.retrieve(invoiceId);var sub=Subscription.retrieve(subscriptionId);var before=PaymentIntent.retrieve(invoice.getPaymentIntent());
        assertEquals(provenFirstFailure.plus(Duration.ofDays(slot*7L)).getEpochSecond(),com.stripe.model.testhelpers.TestClock.retrieve(sub.getTestClock()).getFrozenTime());
        assertEquals(slot,nativeChargeIds(before.getId()).size(),"Only initial failure and preceding worker attempts may exist");
        var action=actions().stream().filter(a->"RETRY_PAY".equals(a.getActionType())&&Integer.valueOf(slot).equals(a.getRetrySlot())).findFirst().orElseThrow();
        assertEquals("READY",action.getStatus());assertEquals(0,action.getTransportAttempts());assertNull(action.getFirstDispatchedAt());
        for(var planned:actions())if("RETRY_PAY".equals(planned.getActionType())){
            if(planned.getRetrySlot()<slot)assertEquals("SUCCEEDED",planned.getStatus());
            else{assertEquals("READY",planned.getStatus());assertEquals(0,planned.getTransportAttempts());assertNull(planned.getFirstDispatchedAt());}
        }
        String frozen=action.getPayloadJson(),key=action.getProviderIdempotencyKey();
        for(int pass=0;pass<8;pass++){
            service.runDueAction(action.getId());
            var observed=sql.getMapper(SubscriptionRecoveryActionMapper.class).selectById(action.getId());
            if("SUCCEEDED".equals(observed.getStatus()))break;
            assertTrue(Set.of("READY","UNKNOWN").contains(observed.getStatus()),"Unexpected retry outcome: "+observed.getStatus()+" / "+observed.getSkipReason());
            // Settle only real persisted method/protection prerequisites, never another payment or a fabricated status.
            for(var prerequisite:actions())if(Set.of("READY","UNKNOWN").contains(prerequisite.getStatus())&&Set.of("UPDATE_METHOD","DISABLE_ADVANCE","PAUSE_COLLECTION").contains(prerequisite.getActionType()))service.runDueAction(prerequisite.getId());
            service.reconcile(rootId);
        }
        var saved=sql.getMapper(SubscriptionRecoveryActionMapper.class).selectById(action.getId());assertEquals("SUCCEEDED",saved.getStatus());assertEquals(1,saved.getTransportAttempts());assertNotNull(saved.getFirstDispatchedAt());assertEquals(frozen,saved.getPayloadJson());assertEquals(key,saved.getProviderIdempotencyKey());assertNotNull(saved.getProviderRequestId());
        var proof=SubscriptionRecoveryStripeGateway.thaw(saved.getResultJson());assertEquals(expectedPaid?"SUCCEEDED":"DECLINED",proof.get("provider_state"));
        invoice=Invoice.retrieve(invoiceId);var after=PaymentIntent.retrieve(invoice.getPaymentIntent());assertEquals(before.getId(),after.getId());assertNotNull(after.getLatestCharge());assertNotEquals(before.getLatestCharge(),after.getLatestCharge());
        var charge=Charge.retrieve(after.getLatestCharge());assertEquals(Boolean.FALSE,charge.getLivemode());assertEquals(invoiceId,charge.getInvoice());assertEquals(after.getId(),charge.getPaymentIntent());assertEquals(invoice.getCustomer(),charge.getCustomer());
        assertEquals(charge.getId(),proof.get("charge_id"));assertEquals(after.getId(),proof.get("payment_intent_id"));
        var charges=nativeChargeIds(after.getId());assertEquals(slot+1,charges.size());
        if(!expectedPaid){assertEquals("insufficient_funds",proof.get("error"));assertEquals("insufficient_funds",after.getLastPaymentError().getDeclineCode());assertEquals("failed",charge.getStatus());assertEquals(0L,invoice.getAmountPaid());assertEquals("open",invoice.getStatus());assertEquals("OPEN_AUTO",root().getStatus());assertTrue(access.resolve(user,subscriptionId).locked());assertNoGrantOrShift();}
        else{assertEquals("paid",invoice.getStatus());assertEquals("PAID",root().getStatus());assertEquals("GRANTED",root().getGrantState());}
        // Re-delivery of the completed worker item must not dispatch a second provider write.
        service.runDueAction(saved.getId());assertEquals(charges,nativeChargeIds(after.getId()));assertEquals(1,sql.getMapper(SubscriptionRecoveryActionMapper.class).selectById(saved.getId()).getTransportAttempts());
        assertOriginalWindow(root());assertRetryIntents();
        var row=new LinkedHashMap<String,Object>();row.put("slot",slot);row.put("actionId",saved.getId());row.put("idempotencyKey",key);row.put("parameters",SubscriptionRecoveryStripeGateway.thaw(frozen));row.put("scheduledAtBusinessTime",provenFirstFailure.plus(Duration.ofDays(slot*7L)).getEpochSecond());row.put("status",saved.getStatus());row.put("providerRequestId",saved.getProviderRequestId());row.put("providerState",proof.get("provider_state"));row.put("declineCode",proof.get("error"));row.put("chargeId",charge.getId());row.put("transportAttempts",saved.getTransportAttempts());row.put("firstFailureBusinessAt",provenFirstFailure.getEpochSecond());row.put("deadlineBusinessAt",provenFirstFailure.plus(Duration.ofDays(30)).getEpochSecond());row.put("dispatchSource","REAL_SERVICE_PERSISTED_RETRY");row.put("replayDidNotWrite",true);row.put("fixedKeyAndPayload",true);
        journal.record("application-retry-executed",row);
    }
    private Set<String> nativeChargeIds(String intent)throws Exception {
        var ids=new HashSet<String>();for(var charge:Charge.list(Map.of("payment_intent",intent,"limit",100L)).autoPagingIterable()){
            assertEquals(Boolean.FALSE,charge.getLivemode());assertEquals(intent,charge.getPaymentIntent());assertEquals(invoiceId,charge.getInvoice());ids.add(charge.getId());
        }return ids;
    }
    void grantFromNativePaidEvent() throws Exception {
        List<Long> workerShiftIds=List.of();
        if(automaticFunds){
            assertEquals("PAID",root().getStatus());assertEquals("GRANTED",root().getGrantState());assertOriginalWindow(root());assertRetryIntents();
            assertEquals(1,sql.getMapper(RechargeOrderMapper.class).selectList(null).size());
            assertEquals(3,sql.getMapper(QuotaLedgerMapper.class).selectList(null).stream().filter(l->"plan_reset".equals(l.getLedgerType())).count());
            workerShiftIds=actions().stream().filter(a->"SHIFT_RENEWAL".equals(a.getActionType())).map(SubscriptionRecoveryActionEntity::getId).toList();assertEquals(1,workerShiftIds.size());
            journal.record("application-worker-granted-before-native-event",Map.of("rootId",rootId,"grantState",root().getGrantState(),"shiftActionId",workerShiftIds.get(0),"orders",1,"planResetLedgers",3));
        }
        Event paid=null;
        for(int attempt=0;attempt<(automaticFunds?20:1)&&paid==null;attempt++){
            for(var event:Event.list(Map.of("types",List.of("invoice.paid"),"created",Map.of("gte",firstEvent.getCreated()),"limit",100L)).autoPagingIterable()){
                var raw=JsonParser.parseString(event.getDataObjectDeserializer().getRawJson()).getAsJsonObject();
                if(raw.has("id")&&invoiceId.equals(raw.get("id").getAsString())){paid=event;break;}
            }
            if(paid==null&&automaticFunds)Thread.sleep(250);
        }
        assertNotNull(paid,"Native invoice.paid event is required; never synthesize a success payload");assertEquals(Boolean.FALSE,paid.getLivemode());nativePaidEvent=paid;assertTrue(inbox.process(paid));assertFalse(inbox.process(paid));service.reconcile(rootId);
        var root=root();assertOriginalWindow(root);assertEquals("PAID",root.getStatus());assertEquals("GRANTED",root.getGrantState());assertEquals(invoiceId,root.getEntitlementInvoiceId());assertEquals(root.getPaidAt(),root.getEntitlementStartAt());
        var source=Invoice.retrieve(invoiceId);var paidAt=Instant.ofEpochSecond(source.getStatusTransitions().getPaidAt());var end=SubscriptionRecoveryPolicy.paidPeriodEnd(paidAt,fixture.interval(),1);
        assertEquals(com.studyagent.common.datetime.DateTimeFormats.fromInstant(end),root.getEntitlementEndAt());
        if("year".equals(fixture.interval())){
            for(var row:sql.getMapper(UserAiQuotaMapper.class).selectList(null))assertEquals(com.studyagent.common.datetime.DateTimeFormats.fromInstant(paidAt),row.getPlanPeriodStart());
            assertEquals(com.studyagent.common.datetime.DateTimeFormats.fromInstant(SubscriptionRecoveryPolicy.paidPeriodEnd(paidAt,"month",1)),root.getQuotaPeriodEndAt(),"Annual recovery grants its first monthly quota, not a year of quota at once");
            assertTrue(root.getQuotaPeriodEndAt().isBefore(root.getEntitlementEndAt()));
        }
        var account=sql.getMapper(UserSubscriptionMapper.class).selectByUser(user);assertEquals(root.getEntitlementEndAt(),account.getCurrentPeriodEnd());assertFalse(access.resolve(user,subscriptionId).locked());
        var orders=sql.getMapper(RechargeOrderMapper.class).selectList(null);assertEquals(1,orders.size());assertEquals("completed",orders.get(0).getStatus());assertEquals(invoiceId,orders.get(0).getStripeInvoiceId());assertEquals(Math.toIntExact(source.getAmountPaid()),orders.get(0).getPriceCents());
        var quotas=sql.getMapper(UserAiQuotaMapper.class).selectList(null);assertEquals(3,quotas.size());for(var quota:quotas){assertEquals(root.getQuotaPeriodEndAt(),quota.getPlanPeriodEnd());assertEquals("task_create".equals(quota.getFeatureCode())?12L:"ai_detection".equals(quota.getFeatureCode())?20000L:10000L,quota.getPlanBalance());}
        assertEquals(3,sql.getMapper(QuotaLedgerMapper.class).selectList(null).stream().filter(l->"plan_reset".equals(l.getLedgerType())).count());
        var shifts=actions().stream().filter(a->"SHIFT_RENEWAL".equals(a.getActionType())).toList();assertEquals(1,shifts.size());var shift=shifts.get(0);assertEquals("READY",shift.getStatus());
        if(automaticFunds){assertEquals(workerShiftIds,List.of(shift.getId()));assertRetryIntents();}
        var body=SubscriptionRecoveryStripeGateway.thaw(shift.getPayloadJson());assertEquals(end.getEpochSecond(),((Number)body.get("trial_end")).longValue());assertEquals("none",body.get("proration_behavior"));assertEquals("",body.get("pause_collection"));
        verifyNoInteractions(legacyAnalytics,legacyRobot);
        var granted=new LinkedHashMap<String,Object>();granted.put("rootId",rootId);granted.put("paidEventId",paid.getId());granted.put("nativeEventVersion",paid.getApiVersion());granted.put("invoiceId",invoiceId);granted.put("paidAt",paidAt.getEpochSecond());granted.put("entitlementEnd",end.getEpochSecond());granted.put("quotaPeriodEnd",com.studyagent.common.datetime.DateTimeFormats.toEpochSecond(root.getQuotaPeriodEndAt()));granted.put("interval",fixture.interval());granted.put("orders",1);granted.put("quotaRows",3);granted.put("planResetLedgers",3);granted.put("shiftActionId",shift.getId());journal.record("application-native-paid-grant",granted);
    }
    void shiftUsingPersistedAction() throws Exception {
        var shift=actions().stream().filter(a->"SHIFT_RENEWAL".equals(a.getActionType())).findFirst().orElseThrow();String frozen=shift.getPayloadJson(),key=shift.getProviderIdempotencyKey();
        Map<String,Object> paidRights=shiftResponseLoss==null?null:paidRightsSnapshot();
        Set<String> paidCharges=shiftResponseLoss==null?null:ownedCustomerChargeIds();
        List<Object> shiftedNative=null;
        if(shiftResponseLoss!=null)shiftResponseLoss.arm(shift);
        for(int pass=0;pass<8;pass++){
            service.runDueAction(shift.getId());
            var observed=sql.getMapper(SubscriptionRecoveryActionMapper.class).selectById(shift.getId());
            if(shiftResponseLoss!=null && shiftedNative==null && shiftResponseLoss.evidence().isPresent()){
                var receipt=shiftResponseLoss.evidence().orElseThrow();
                assertEquals("UNKNOWN",observed.getStatus(),"Observe persisted UNKNOWN before explicit action reconciliation");
                assertEquals(1,observed.getTransportAttempts());assertEquals(1,ownedGateway.shiftNativeExecuteCount());
                assertEquals(frozen,observed.getPayloadJson());assertEquals(key,observed.getProviderIdempotencyKey());
                assertNull(observed.getProviderRequestId());assertTrue(observed.getResultJson().contains("TEST_INJECTED_SHIFT_ACK_LOSS"));
                assertEquals(paidRights,paidRightsSnapshot());assertFalse(access.resolve(user,subscriptionId).locked());
                var nativeSub=Subscription.retrieve(subscriptionId);assertNull(nativeSub.getPauseCollection());
                assertEquals(com.studyagent.common.datetime.DateTimeFormats.toEpochSecond(root().getEntitlementEndAt()),nativeSub.getTrialEnd());
                assertEquals(nativeSub.getTrialEnd(),nativeSub.getCurrentPeriodEnd());
                shiftedNative=shiftNativeSnapshot(nativeSub);
                assertEquals(paidCharges,ownedCustomerChargeIds());
                var loss=new LinkedHashMap<String,Object>();loss.put("rootId",rootId);loss.put("invoiceId",invoiceId);loss.put("shiftActionId",observed.getId());loss.put("actionKey",observed.getActionKey());loss.put("idempotencyKey",key);loss.put("frozenPayload",frozen);loss.put("parameters",SubscriptionRecoveryStripeGateway.thaw(frozen));loss.put("persistedStatus",observed.getStatus());loss.put("transportAttempts",observed.getTransportAttempts());loss.put("nativeExecuteCount",ownedGateway.shiftNativeExecuteCount());loss.put("nativeRequestId",receipt.nativeRequestId());loss.put("serviceRequestId",observed.getProviderRequestId());loss.put("injection","TEST_AFTER_NATIVE_SUCCESS");loss.put("paidRightsRetained",true);journal.record("application-shift-ack-loss-persisted",loss);
                // The public worker already performs inline invoice reconciliation. This explicit UNKNOWN
                // action reconciliation takes native read-back and must never execute a second write.
                service.runDueAction(shift.getId());
                observed=sql.getMapper(SubscriptionRecoveryActionMapper.class).selectById(shift.getId());
                assertEquals("SUCCEEDED",observed.getStatus(),"Original UNKNOWN action must settle by native read-back");
                assertEquals(1,ownedGateway.shiftNativeExecuteCount());assertEquals(1,observed.getTransportAttempts());
            }
            journal.record("application-sync-progress",Map.of("pass",pass,"shiftStatus",observed.getStatus(),"anchor",root().getAnchorSyncState(),"reviewReason",Objects.toString(root().getReviewReason(),""),
                    "prerequisites",actions().stream().filter(a->Set.of("UPDATE_METHOD","DISABLE_ADVANCE","PAUSE_COLLECTION").contains(a.getActionType())).map(a->Map.of("id",a.getId(),"type",a.getActionType(),"status",a.getStatus(),"target",a.getTargetId())).toList()));
            if("SUCCEEDED".equals(observed.getStatus()))break;
            for(var a:actions())if(Set.of("READY","UNKNOWN").contains(a.getStatus())&&Set.of("UPDATE_METHOD","DISABLE_ADVANCE","PAUSE_COLLECTION").contains(a.getActionType()))service.runDueAction(a.getId());
            service.reconcile(rootId);
        }
        var after=sql.getMapper(SubscriptionRecoveryActionMapper.class).selectById(shift.getId());assertEquals("SUCCEEDED",after.getStatus(),"Pending native prerequisites must settle before shift: "+root().getReviewReason());assertEquals(frozen,after.getPayloadJson());assertEquals(key,after.getProviderIdempotencyKey());
        // Confirm and clean only intents created by the real service, never manually mutate their states.
        for(int pass=0;pass<6&&!"CONFIRMED".equals(root().getAnchorSyncState());pass++){
            service.reconcile(rootId);for(var a:actions())if("READY".equals(a.getStatus())&&Set.of("DISABLE_ADVANCE","FINALIZE_INVOICE","VOID_INVOICE").contains(a.getActionType()))service.runDueAction(a.getId());
        }
        service.reconcile(rootId);
        var remaining=new ArrayList<Map<String,Object>>();
        for(var invoice:Invoice.list(Map.of("subscription",subscriptionId,"limit",100L)).autoPagingIterable()){
            assertEquals(subscriptionId,invoice.getSubscription());assertEquals(Boolean.FALSE,invoice.getLivemode());
            remaining.add(Map.of("invoiceId",invoice.getId(),"status",invoice.getStatus(),"amountPaid",invoice.getAmountPaid(),"amountDue",invoice.getAmountDue(),"autoAdvance",invoice.getAutoAdvance(),"priceIds",invoice.getLines().getData().stream().map(l->l.getPrice()==null?"":l.getPrice().getId()).toList()));
        }
        journal.record("application-sync-evidence",Map.of("rootId",rootId,"anchor",root().getAnchorSyncState(),"reviewState",root().getReviewState(),"reviewReason",Objects.toString(root().getReviewReason(),""),"grantState",root().getGrantState(),"invoices",remaining,
                "cleanupActions",actions().stream().filter(a->Set.of("FINALIZE_INVOICE","VOID_INVOICE").contains(a.getActionType())).map(a->Map.of("id",a.getId(),"type",a.getActionType(),"status",a.getStatus(),"target",a.getTargetId(),"skipReason",Objects.toString(a.getSkipReason(),""))).toList()));
        assertEquals("CONFIRMED",root().getAnchorSyncState(),"Native cleanup target evidence: "+root().getReviewReason());assertEquals("RELEASED",root().getProtectionState());assertEquals("GRANTED",root().getGrantState());
        if(automaticFunds){assertOriginalWindow(root());assertRetryIntents();}
        var nativeSub=Subscription.retrieve(subscriptionId);assertNull(nativeSub.getPauseCollection());assertEquals(com.studyagent.common.datetime.DateTimeFormats.toEpochSecond(root().getEntitlementEndAt()),nativeSub.getTrialEnd());
        if(shiftResponseLoss!=null){
            assertNotNull(shiftedNative,"Must observe one actual native successful SHIFT with lost acknowledgement");
            for(int replay=0;replay<2;replay++){service.runDueAction(shift.getId());service.reconcile(rootId);}
            var saved=sql.getMapper(SubscriptionRecoveryActionMapper.class).selectById(shift.getId());
            assertEquals("SUCCEEDED",saved.getStatus());assertEquals(frozen,saved.getPayloadJson());assertEquals(key,saved.getProviderIdempotencyKey());assertEquals(shift.getActionKey(),saved.getActionKey());
            assertEquals(1,saved.getTransportAttempts());assertEquals(1,ownedGateway.shiftNativeExecuteCount());
            assertEquals(List.of(shift.getId()),actions().stream().filter(a->"SHIFT_RENEWAL".equals(a.getActionType())).map(SubscriptionRecoveryActionEntity::getId).toList());
            assertEquals(shiftedNative,shiftNativeSnapshot(Subscription.retrieve(subscriptionId)));assertEquals(paidRights,paidRightsSnapshot());
            assertEquals(paidCharges,ownedCustomerChargeIds());assertFalse(access.resolve(user,subscriptionId).locked());
            assertEquals("CONFIRMED",root().getAnchorSyncState());assertOriginalWindow(root());verifyNoInteractions(legacyAnalytics,legacyRobot);
            var confirmed=new LinkedHashMap<String,Object>();confirmed.put("rootId",rootId);confirmed.put("invoiceId",invoiceId);confirmed.put("shiftActionId",saved.getId());confirmed.put("status",saved.getStatus());confirmed.put("anchorSyncState",root().getAnchorSyncState());confirmed.put("transportAttempts",saved.getTransportAttempts());confirmed.put("nativeExecuteCount",ownedGateway.shiftNativeExecuteCount());confirmed.put("fixedKeyAndPayload",true);confirmed.put("paidRightsRetained",true);confirmed.put("chargesUnchanged",true);confirmed.put("replayDidNotWrite",true);journal.record("application-shift-ack-loss-readback-confirmed",confirmed);
        }
        if(fixture.periodEnd()){
            assertEquals(Boolean.TRUE,nativeSub.getCancelAtPeriodEnd());assertEquals(nativeSub.getTrialEnd(),nativeSub.getCancelAt());
        }
        journal.record("application-shift-confirmed",Map.of("rootId",rootId,"shiftActionId",shift.getId(),"providerRequestId",Objects.toString(after.getProviderRequestId(),""),"anchorSyncState",root().getAnchorSyncState(),"protection",root().getProtectionState(),"technicalInvoiceIds",Objects.toString(root().getTechnicalInvoiceIds(),""),"httpWebhookResult","NOT_RUN","mailResult","NOT_RUN","finalResult","NOT_RUN"));
    }
    private Map<String,Object> paidRightsSnapshot(){
        var r=root();var u=sql.getMapper(UserSubscriptionMapper.class).selectByUser(user);
        return Map.of("root",Arrays.asList(r.getStatus(),r.getGrantState(),r.getEntitlementInvoiceId(),r.getPaidAt(),r.getEntitlementStartAt(),r.getEntitlementEndAt(),r.getQuotaPeriodEndAt()),
                "account",Arrays.asList(u.getCurrentPeriodEnd(),u.getPlanCode(),u.getTier()),
                "orders",sql.getMapper(RechargeOrderMapper.class).selectList(null).stream().sorted(Comparator.comparing(RechargeOrderEntity::getId)).toList(),
                "quotas",sql.getMapper(UserAiQuotaMapper.class).selectList(null).stream().sorted(Comparator.comparing(UserAiQuotaEntity::getId)).toList(),
                "ledgers",sql.getMapper(QuotaLedgerMapper.class).selectList(null).stream().sorted(Comparator.comparing(QuotaLedgerEntity::getId)).toList());
    }
    private static List<Object> shiftNativeSnapshot(Subscription sub){
        return Arrays.asList(sub.getId(),sub.getCustomer(),sub.getTrialEnd(),sub.getCurrentPeriodStart(),sub.getCurrentPeriodEnd(),
                sub.getPauseCollection(),sub.getCancelAtPeriodEnd(),sub.getCancelAt(),sub.getSchedule(),sub.getDefaultPaymentMethod());
    }
    void assertBeforeOriginalDeadline()throws Exception {
        assertNativeBusinessTime(provenFirstFailure.plus(Duration.ofDays(30)).minusSeconds(1));
        service.reconcile(rootId);assertOriginalWindow(root());assertEquals(automaticFunds?"OPEN_AUTO":"OPEN_MANUAL",root().getStatus());assertNull(root().getCloseReason());assertNull(root().getAccountClosedAt());assertTrue(access.resolve(user,subscriptionId).locked());
        assertEquals(0,actions().stream().filter(a->Set.of("CANCEL_SUBSCRIPTION","CANCEL_SCHEDULE","FINALIZE_INVOICE","VOID_INVOICE").contains(a.getActionType())).count());
        deadlineInvoices=ownedInvoices();deadlineIntroDraftIds=requiredIntroDraftIds(deadlineInvoices,introPriceId);
        journal.record("application-before-d30",Map.of("rootId",rootId,"businessTime",provenFirstFailure.plus(Duration.ofDays(30)).minusSeconds(1).getEpochSecond(),"status",root().getStatus(),"closeActions",0));
    }
    void closeAtOriginalDeadline()throws Exception {
        var businessTime=provenFirstFailure.plus(Duration.ofDays(30));assertNativeBusinessTime(businessTime);assertOriginalWindow(root());
        assertNotNull(deadlineInvoices,"Capture protected original intro drafts before the deadline");assertNotNull(deadlineIntroDraftIds);
        // The clock may naturally create a formal-cycle draft at D30. Preserve the intro
        // identities captured before the deadline, but audit cancellation against invoices
        // present immediately before its write, including that legitimate new cycle bill.
        finishUnpaidClosure("EXPIRED","DEADLINE",businessTime,ownedInvoices(),deadlineIntroDraftIds,ownedCustomerChargeIds(),null);
    }
    void acceptCancellationReservation(Event event)throws Exception {
        assertFalse(automaticFunds);assertNativeBusinessTime(provenFirstFailure.plus(Duration.ofDays(10)));
        assertNativeSubscriptionEvent(event,"customer.subscription.updated");
        assertTrue(inbox.process(event));assertFalse(inbox.process(event));service.reconcile(rootId);
        var reserved=root();assertOriginalWindow(reserved);assertEquals("OPEN_MANUAL",reserved.getStatus());assertNull(reserved.getCloseReason());assertNull(reserved.getAccountClosedAt());
        assertTrue(access.resolve(user,subscriptionId).locked());var nativeSub=Subscription.retrieve(subscriptionId);assertTrue(nativeSub.getCancelAtPeriodEnd());assertNotEquals("canceled",nativeSub.getStatus());
        assertEquals(subscriptionId,sql.getMapper(UserSubscriptionMapper.class).selectByUser(user).getStripeSubscriptionId());
        assertEquals(0,actions().stream().filter(a->Set.of("CANCEL_SUBSCRIPTION","CANCEL_SCHEDULE","FINALIZE_INVOICE","VOID_INVOICE").contains(a.getActionType())).count());assertNoGrantOrShift();
        cancellationInvoices=ownedInvoices();cancellationIntroDraftIds=requiredIntroDraftIds(cancellationInvoices,introPriceId);cancellationCharges=ownedCustomerChargeIds();assertEquals(2,cancellationCharges.size());
        journal.record("application-cancel-reserved",Map.of("result","PASS","rootId",rootId,"status",reserved.getStatus(),"accountClosed",false,"cancelAtPeriodEnd",true,"eventId",event.getId(),"businessTime",provenFirstFailure.plus(Duration.ofDays(10)).getEpochSecond()));
    }
    void closeFromNativeCancellation(Event event)throws Exception {
        assertFalse(automaticFunds);assertNotNull(cancellationInvoices);assertNotNull(cancellationCharges);
        var businessTime=provenFirstFailure.plus(Duration.ofDays(10));assertNativeBusinessTime(businessTime);assertTrue(businessTime.isBefore(provenFirstFailure.plus(Duration.ofDays(30))));
        assertNativeSubscriptionEvent(event,"customer.subscription.deleted");assertEquals("canceled",Subscription.retrieve(subscriptionId).getStatus());
        assertTrue(inbox.process(event));assertFalse(inbox.process(event));
        finishUnpaidClosure("CANCELED","REMOTE_CANCELED",businessTime,cancellationInvoices,cancellationIntroDraftIds,cancellationCharges,event);
    }
    private void assertNativeSubscriptionEvent(Event event,String type){
        assertEquals(type,event.getType());assertEquals(Boolean.FALSE,event.getLivemode());assertEquals(firstEvent.getApiVersion(),event.getApiVersion());
        var raw=JsonParser.parseString(event.getDataObjectDeserializer().getRawJson()).getAsJsonObject();assertEquals(subscriptionId,raw.get("id").getAsString());assertEquals(root().getStripeCustomerId(),raw.get("customer").getAsString());assertEquals(user,raw.getAsJsonObject("metadata").get("recovery_test_run").getAsString());
    }
    private void assertNoGrantOrShift(){
        assertEquals(0,sql.getMapper(RechargeOrderMapper.class).selectList(null).size());assertEquals(0,sql.getMapper(QuotaLedgerMapper.class).selectList(null).stream().filter(l->"plan_reset".equals(l.getLedgerType())).count());
        assertEquals(0,actions().stream().filter(a->"SHIFT_RENEWAL".equals(a.getActionType())).count());
    }
    private void finishUnpaidClosure(String expectedStatus,String reason,Instant businessTime,Map<String,Invoice> before,Set<String> requiredIntroDraftIds,Set<String> charges,Event canceledEvent)throws Exception {
        assertEquals(automaticFunds?6:2,charges.size(),"Only paid intro, original failure and the four opted-in funds worker attempts may exist");
        service.reconcile(rootId);assertEquals("CLOSING",root().getStatus());assertEquals(reason,root().getCloseReason());assertNull(root().getAccountClosedAt());
        var types=Set.of("DISABLE_ADVANCE","CANCEL_SUBSCRIPTION","CANCEL_SCHEDULE","FINALIZE_INVOICE","VOID_INVOICE");
        for(int pass=0;pass<12&&!expectedStatus.equals(root().getStatus());pass++){
            for(var action:actions())if(types.contains(action.getActionType())&&Set.of("READY","UNKNOWN").contains(action.getStatus())){
                var payload=SubscriptionRecoveryStripeGateway.thaw(action.getPayloadJson());assertNotNull(action.getProviderIdempotencyKey());
                if(canceledEvent!=null)assertFalse(Set.of("CANCEL_SUBSCRIPTION","CANCEL_SCHEDULE").contains(action.getActionType()),"User canceled remotely; only protection/finalize/void closure actions may run");
                switch(action.getActionType()){
                    case "CANCEL_SUBSCRIPTION" -> {assertEquals(subscriptionId,action.getTargetId());assertEquals(Map.of("invoice_now",false,"prorate",false),payload);}
                    case "CANCEL_SCHEDULE" -> {assertEquals(Subscription.retrieve(subscriptionId).getSchedule(),action.getTargetId());assertEquals(Map.of("invoice_now",false,"prorate",false),payload);}
                    case "FINALIZE_INVOICE" -> {assertTrue(before.containsKey(action.getTargetId()));assertEquals(Map.of("auto_advance",false),payload);}
                    case "VOID_INVOICE" -> {assertTrue(before.containsKey(action.getTargetId()));assertEquals(Map.of(),payload);}
                    case "DISABLE_ADVANCE" -> {assertTrue(before.containsKey(action.getTargetId()));assertEquals(Map.of("auto_advance",false),payload);}
                    default -> throw new IllegalStateException("Unexpected close action");
                }
                String frozen=action.getPayloadJson(),key=action.getProviderIdempotencyKey();service.runDueAction(action.getId());
                var saved=sql.getMapper(SubscriptionRecoveryActionMapper.class).selectById(action.getId());assertEquals(frozen,saved.getPayloadJson());assertEquals(key,saved.getProviderIdempotencyKey());
                journal.record(canceledEvent==null?"application-d30-action":"application-active-cancel-action",Map.of("actionId",saved.getId(),"type",saved.getActionType(),"targetId",saved.getTargetId(),"status",saved.getStatus(),"idempotencyKey",key,"parameters",payload,"providerRequestId",Objects.toString(saved.getProviderRequestId(),""),"skipReason",Objects.toString(saved.getSkipReason(),"")));
            }
            service.reconcile(rootId);
        }
        var closed=root();assertEquals(expectedStatus,closed.getStatus(),"Account closure must finish while protected manual drafts remain separately truthful");assertEquals(reason,closed.getCloseReason());assertNotNull(closed.getAccountClosedAt());assertOriginalWindow(closed);
        assertNull(closed.getEntitlementInvoiceId());assertNull(closed.getEntitlementStartAt());assertNull(closed.getEntitlementEndAt());assertNotEquals("GRANTED",closed.getGrantState());
        var account=sql.getMapper(UserSubscriptionMapper.class).selectByUser(user);assertEquals("free",account.getTier());assertEquals("free",account.getPlanCode());assertEquals("canceled",account.getStatus());assertNull(account.getStripeSubscriptionId());assertNull(account.getGraceEndAt());
        assertEquals(0,sql.getMapper(RechargeOrderMapper.class).selectList(null).size());assertEquals(0,sql.getMapper(QuotaLedgerMapper.class).selectList(null).stream().filter(l->"plan_reset".equals(l.getLedgerType())).count());
        assertNoGrantOrShift();
        if(automaticFunds){
            assertRetryIntents();for(var retry:actions())if("RETRY_PAY".equals(retry.getActionType())){assertEquals("SUCCEEDED",retry.getStatus());assertEquals(1,retry.getTransportAttempts());assertEquals("DECLINED",SubscriptionRecoveryStripeGateway.thaw(retry.getResultJson()).get("provider_state"));assertEquals("insufficient_funds",SubscriptionRecoveryStripeGateway.thaw(retry.getResultJson()).get("error"));}
        }else assertEquals(0,actions().stream().filter(a->"RETRY_PAY".equals(a.getActionType())).count());
        if(canceledEvent!=null)assertEquals(0,actions().stream().filter(a->Set.of("CANCEL_SUBSCRIPTION","CANCEL_SCHEDULE").contains(a.getActionType())).count(),"Native user cancellation must not dispatch an application cancellation");
        for(var quota:sql.getMapper(UserAiQuotaMapper.class).selectList(null))assertEquals(0L,quota.getPlanBalance());
        var nativeSub=Subscription.retrieve(subscriptionId);assertEquals("canceled",nativeSub.getStatus());assertEquals("void",Invoice.retrieve(invoiceId).getStatus());
        var after=ownedInvoices();assertEquals(before.keySet(),after.keySet(),"Cancellation must not create a final invoice");assertEquals(charges,ownedCustomerChargeIds(),"Closing must not create a payment attempt");
        var residuals=closureResiduals(before,after,requiredIntroDraftIds,closed.getReviewState(),actions());
        var actionStates=actions().stream().map(a->Map.of("id",a.getId(),"status",a.getStatus(),"attempts",a.getTransportAttempts())).toList();
        for(var action:actions())if(types.contains(action.getActionType())&&"SUCCEEDED".equals(action.getStatus()))service.runDueAction(action.getId());
        if(canceledEvent!=null){assertFalse(inbox.process(canceledEvent));assertFalse(inbox.process(firstEvent));}
        for(int replay=0;replay<2;replay++)service.reconcile(rootId);
        assertEquals(expectedStatus,root().getStatus());assertEquals(reason,root().getCloseReason());assertEquals(closed.getAccountClosedAt(),root().getAccountClosedAt());assertOriginalWindow(root());
        assertEquals(closed.getReviewEnteredAt(),root().getReviewEnteredAt());assertEquals(closed.getReviewDueAt(),root().getReviewDueAt());
        assertEquals(actionStates,actions().stream().map(a->Map.of("id",a.getId(),"status",a.getStatus(),"attempts",a.getTransportAttempts())).toList());assertEquals(after.keySet(),ownedInvoices().keySet());assertEquals(charges,ownedCustomerChargeIds());
        assertEquals(residuals,closureResiduals(before,ownedInvoices(),requiredIntroDraftIds,root().getReviewState(),actions()),"Required original drafts must remain protected after all replays");
        verifyNoInteractions(legacyAnalytics,legacyRobot);
        var evidence=new LinkedHashMap<String,Object>();evidence.put("result","PASS");evidence.put("rootId",rootId);evidence.put("status",root().getStatus());evidence.put("closeReason",root().getCloseReason());evidence.put("firstFailureBusinessAt",provenFirstFailure.getEpochSecond());evidence.put("closedAtBusinessTime",businessTime.getEpochSecond());evidence.put("residualDrafts",residuals);evidence.put("requiredProtectedDraftIds",new TreeSet<>(requiredIntroDraftIds));evidence.put("reviewState",root().getReviewState());evidence.put("replayDidNotWrite",true);evidence.put("httpWebhookResult","NOT_RUN");evidence.put("mailResult","NOT_RUN");evidence.put("portalResult","NOT_RUN");evidence.put("quartzResult","NOT_RUN");evidence.put("customerCharges",charges.size());evidence.put("retryActions",automaticFunds?4:0);
        if(canceledEvent!=null)evidence.put("nativeCanceledEventId",canceledEvent.getId());
        journal.record(canceledEvent==null?"application-d30-closure":"application-active-cancel",evidence);
    }
    private static Set<String> requiredIntroDraftIds(Map<String,Invoice> before,String introPriceId){
        var required=new TreeSet<String>();
        for(var invoice:before.values())if("draft".equals(invoice.getStatus())){
            assertNotNull(invoice.getLines());assertEquals(Boolean.FALSE,invoice.getLines().getHasMore(),"Complete original draft lines are required");
            if(invoice.getLines().getData().stream().anyMatch(line->line.getPrice()!=null&&introPriceId.equals(line.getPrice().getId())))required.add(invoice.getId());
        }
        assertFalse(required.isEmpty(),"Original trial closure fixture must include its protected old intro draft");
        return Set.copyOf(required);
    }
    /** Shared closure acceptance seam; synthetic invoice tests exercise the same native evidence inspection. */
    static List<Map<String,Object>> closureResiduals(Map<String,Invoice> before,Map<String,Invoice> after,
            Set<String> requiredIntroDraftIds,String reviewState,List<SubscriptionRecoveryActionEntity> actions){
        assertFalse(requiredIntroDraftIds.isEmpty(),"Original intro draft identity must be captured before closure");
        for(String id:requiredIntroDraftIds){
            var original=before.get(id);assertNotNull(original,"Required intro draft missing from preclosure evidence: "+id);assertEquals("draft",original.getStatus());
            assertEquals(Boolean.FALSE,original.getAutoAdvance());assertEquals(0L,original.getAmountPaid());
            var retained=after.get(id);assertNotNull(retained,"Required original intro draft disappeared: "+id);
            assertEquals("draft",retained.getStatus(),"Protected original intro draft must remain a draft: "+id);assertEquals(Boolean.FALSE,retained.getAutoAdvance());assertEquals(0L,retained.getAmountPaid());assertEquals("OPEN",reviewState);
            var finalizations=actions.stream().filter(a->id.equals(a.getTargetId())&&"FINALIZE_INVOICE".equals(a.getActionType())).toList();
            assertFalse(finalizations.isEmpty(),"Required intro draft must have truthful rejected finalize evidence: "+id);
            for(var action:finalizations){assertEquals("SKIPPED",action.getStatus(),"Protected intro draft finalization must never succeed");assertEquals("SKIPPED_UNVERIFIED_TARGET",action.getSkipReason());}
        }
        var residuals=new ArrayList<Map<String,Object>>();
        for(var invoice:after.values()){
            if(Set.of("paid","void").contains(invoice.getStatus()))continue;
            assertEquals("draft",invoice.getStatus());assertEquals(Boolean.FALSE,invoice.getAutoAdvance());assertEquals(0L,invoice.getAmountPaid());assertEquals("OPEN",reviewState);
            assertEquals("draft",before.get(invoice.getId()).getStatus(),"A retained draft must not be silently finalized by the worker");
            residuals.add(Map.of("invoiceId",invoice.getId(),"status",invoice.getStatus(),"autoAdvance",invoice.getAutoAdvance(),"amountPaid",invoice.getAmountPaid(),"processing","MANUAL_SEPARATE_FROM_ACCOUNT"));
        }
        return residuals;
    }
    @FunctionalInterface interface NativeAdvance {void advance(String clockId,long time)throws Exception;}
    void followAnnualMonthlyRefresh(String clockId,NativeAdvance advance)throws Exception {
        assertNotNull(annualClock);assertNotNull(transactionalQuotas);assertEquals(annualClockId,clockId);
        assertEquals("year",fixture.interval());assertTrue(fixture.priceUpdate());assertTrue(fixture.periodEnd());assertFalse(automaticFunds);
        var paidAt=Instant.ofEpochSecond(Invoice.retrieve(invoiceId).getStatusTransitions().getPaidAt());
        var entitlementEnd=SubscriptionRecoveryPolicy.paidPeriodEnd(paidAt,"year",1);
        var charges=ownedCustomerChargeIds();var invoices=annualInvoiceState();var orders=sql.getMapper(RechargeOrderMapper.class).selectList(null);var actionState=annualActionState();
        assertEquals(1,orders.size());assertEquals(1,actions().stream().filter(a->"SHIFT_RENEWAL".equals(a.getActionType())).count());
        assertAnnualQuotaEvidence(sql.getMapper(UserAiQuotaMapper.class).selectList(null),sql.getMapper(QuotaLedgerMapper.class).selectList(null),user,subscriptionId,invoiceId,paidAt,0);
        long before=com.studyagent.common.datetime.DateTimeFormats.toEpochSecond(annualMonth(paidAt,1))-1;
        advance.advance(clockId,before);observeAnnualNativeTime(Instant.ofEpochSecond(before));
        var initialQuotas=sql.getMapper(UserAiQuotaMapper.class).selectList(null);var initialLedgers=sql.getMapper(QuotaLedgerMapper.class).selectList(null);
        transactionalQuotas.refreshAllPlanQuotasIfNeeded(user);transactionalQuotas.refreshAllPlanQuotasIfNeeded(user);
        assertEquals(initialQuotas,sql.getMapper(UserAiQuotaMapper.class).selectList(null));assertEquals(initialLedgers,sql.getMapper(QuotaLedgerMapper.class).selectList(null));
        assertAnnualFixedState(paidAt,entitlementEnd,charges,invoices,orders,actionState);
        journal.record("application-annual-before-refresh",Map.of("rootId",rootId,"invoiceId",invoiceId,"result","PASS","businessTime",before,"paidAt",paidAt.getEpochSecond(),"entitlementEnd",entitlementEnd.getEpochSecond(),"planRefreshLedgers",0,"noNewCharge",true));
        for(int index=1;index<=2;index++){
            long start=com.studyagent.common.datetime.DateTimeFormats.toEpochSecond(annualMonth(paidAt,index));
            long end=com.studyagent.common.datetime.DateTimeFormats.toEpochSecond(annualMonth(paidAt,index+1));
            advance.advance(clockId,start);observeAnnualNativeTime(Instant.ofEpochSecond(start));
            var beforeExactQuotas=sql.getMapper(UserAiQuotaMapper.class).selectList(null);var beforeExactLedgers=sql.getMapper(QuotaLedgerMapper.class).selectList(null);
            transactionalQuotas.refreshAllPlanQuotasIfNeeded(user);
            assertEquals(beforeExactQuotas,sql.getMapper(UserAiQuotaMapper.class).selectList(null));assertEquals(beforeExactLedgers,sql.getMapper(QuotaLedgerMapper.class).selectList(null));
            assertAnnualFixedState(paidAt,entitlementEnd,charges,invoices,orders,actionState);
            journal.record("application-annual-exact-boundary",Map.of("rootId",rootId,"invoiceId",invoiceId,"result","OBSERVED","observation","NO_REFRESH_STRICT_AFTER","businessTime",start,"monthIndex",index,"planRefreshLedgers",3*(index-1),"quartzResult","NOT_RUN"));
            advance.advance(clockId,start+1);observeAnnualNativeTime(Instant.ofEpochSecond(start+1));
            transactionalQuotas.refreshAllPlanQuotasIfNeeded(user);
            var refreshed=sql.getMapper(UserAiQuotaMapper.class).selectList(null);var ledgers=sql.getMapper(QuotaLedgerMapper.class).selectList(null);
            assertAnnualQuotaEvidence(refreshed,ledgers,user,subscriptionId,invoiceId,paidAt,index);
            var account=sql.getMapper(UserSubscriptionMapper.class).selectByUser(user);
            assertEquals(annualMonth(paidAt,index),account.getQuotaPeriodStart());assertEquals(annualMonth(paidAt,index+1),account.getQuotaPeriodEnd());
            // The real inbox and service replay completed events/actions; never fabricate rows or windows.
            for(int replay=0;replay<2;replay++){
                transactionalQuotas.refreshAllPlanQuotasIfNeeded(user);assertFalse(inbox.process(nativePaidEvent));assertFalse(inbox.process(firstEvent));
                for(var action:actions())if("SHIFT_RENEWAL".equals(action.getActionType()))service.runDueAction(action.getId());
                service.reconcile(rootId);
            }
            assertEquals(refreshed,sql.getMapper(UserAiQuotaMapper.class).selectList(null));assertEquals(ledgers,sql.getMapper(QuotaLedgerMapper.class).selectList(null));
            assertAnnualFixedState(paidAt,entitlementEnd,charges,invoices,orders,actionState);
            var evidence=new LinkedHashMap<String,Object>();evidence.put("result","PASS");evidence.put("rootId",rootId);evidence.put("invoiceId",invoiceId);evidence.put("monthIndex",index);evidence.put("paidAt",paidAt.getEpochSecond());evidence.put("entitlementEnd",entitlementEnd.getEpochSecond());evidence.put("businessTime",start+1);evidence.put("windowStart",start);evidence.put("windowEnd",end);evidence.put("planRefreshLedgers",3*index);evidence.put("planResetLedgers",3);evidence.put("orders",1);evidence.put("shiftActions",1);evidence.put("noNewCharge",true);evidence.put("replayDidNotWrite",true);evidence.put("paidCycleValid",true);evidence.put("quotaFeatures",ANNUAL_GRANTS);evidence.put("transactionScope","PUBLIC_SERVICE_PROXY");evidence.put("stripeReadInsideTransaction",true);evidence.put("quartzResult","NOT_RUN");evidence.put("schedulerScanResult","NOT_RUN");
            journal.record("application-annual-monthly-refresh",evidence);
        }
        advance.advance(clockId,entitlementEnd.getEpochSecond());observeAnnualNativeTime(entitlementEnd);
        assertFalse(access.resolve(user,subscriptionId).paidCycleValid());
        var beforeExpiryQuotas=sql.getMapper(UserAiQuotaMapper.class).selectList(null);var beforeExpiryLedgers=sql.getMapper(QuotaLedgerMapper.class).selectList(null);
        transactionalQuotas.refreshAllPlanQuotasIfNeeded(user);transactionalQuotas.refreshAllPlanQuotasIfNeeded(user);
        assertEquals(beforeExpiryQuotas,sql.getMapper(UserAiQuotaMapper.class).selectList(null));assertEquals(beforeExpiryLedgers,sql.getMapper(QuotaLedgerMapper.class).selectList(null));
        assertEquals(orders,sql.getMapper(RechargeOrderMapper.class).selectList(null));assertEquals(charges,ownedCustomerChargeIds());assertEquals(actionState,annualActionState());
        journal.record("application-annual-expired-boundary",Map.of("rootId",rootId,"invoiceId",invoiceId,"result","PASS","businessTime",entitlementEnd.getEpochSecond(),"noGrant",true,"paidCycleValid",false,"noNewCharge",true,"planRefreshLedgers",6,"orders",1,"quartzResult","NOT_RUN"));
    }
    private void observeAnnualNativeTime(Instant expected)throws Exception {
        var sub=Subscription.retrieve(subscriptionId);assertEquals(Boolean.FALSE,sub.getLivemode());assertEquals(user,sub.getMetadata().get("recovery_test_run"));assertEquals(root().getStripeCustomerId(),sub.getCustomer());assertEquals(annualClockId,sub.getTestClock());
        var customer=Customer.retrieve(sub.getCustomer());assertEquals(Boolean.FALSE,customer.getLivemode());assertEquals(user,customer.getMetadata().get("recovery_test_run"));assertEquals(annualClockId,customer.getTestClock());
        var source=Invoice.retrieve(invoiceId);assertEquals(Boolean.FALSE,source.getLivemode());assertEquals(sub.getCustomer(),source.getCustomer());assertEquals(subscriptionId,source.getSubscription());assertEquals("paid",source.getStatus());
        annualClock.observe(verifiedAnnualClock(com.stripe.model.testhelpers.TestClock.retrieve(annualClockId),annualClockId,user,expected));
    }
    private Map<String,String> annualInvoiceState()throws Exception {
        var state=new TreeMap<String,String>();for(var invoice:ownedInvoices().values())state.put(invoice.getId(),List.of(invoice.getStatus(),Objects.toString(invoice.getAutoAdvance()),Objects.toString(invoice.getAmountPaid()),Objects.toString(invoice.getAmountRemaining())).toString());return state;
    }
    private List<String> annualActionState(){return actions().stream().map(a->List.of(a.getId(),a.getActionType(),a.getStatus(),a.getTransportAttempts(),Objects.toString(a.getProviderRequestId()),Objects.toString(a.getPayloadJson())).toString()).toList();}
    private void assertAnnualFixedState(Instant paidAt,Instant end,Set<String> charges,Map<String,String> invoices,List<RechargeOrderEntity> orders,List<String> actionState)throws Exception {
        var saved=root();assertEquals("PAID",saved.getStatus());assertEquals("GRANTED",saved.getGrantState());assertEquals("CONFIRMED",saved.getAnchorSyncState());assertEquals("RELEASED",saved.getProtectionState());assertEquals(invoiceId,saved.getEntitlementInvoiceId());assertEquals(com.studyagent.common.datetime.DateTimeFormats.fromInstant(paidAt),saved.getEntitlementStartAt());assertEquals(com.studyagent.common.datetime.DateTimeFormats.fromInstant(end),saved.getEntitlementEndAt());
        var account=sql.getMapper(UserSubscriptionMapper.class).selectByUser(user);assertEquals(saved.getEntitlementEndAt(),account.getCurrentPeriodEnd());
        var resolved=access.resolve(user,subscriptionId);assertTrue(resolved.paidCycleValid());assertFalse(resolved.locked());assertEquals(paidAt,resolved.paidPeriodStart());assertEquals(end,resolved.paidPeriodEnd());
        var sub=Subscription.retrieve(subscriptionId);assertEquals(end.getEpochSecond(),sub.getTrialEnd());assertEquals(end.getEpochSecond(),sub.getCurrentPeriodEnd());assertEquals(Boolean.TRUE,sub.getCancelAtPeriodEnd());assertEquals(end.getEpochSecond(),sub.getCancelAt());assertNull(sub.getPauseCollection());
        assertEquals(charges,ownedCustomerChargeIds());assertEquals(invoices,annualInvoiceState());assertEquals(orders,sql.getMapper(RechargeOrderMapper.class).selectList(null));assertEquals(actionState,annualActionState());
    }
    static Instant verifiedAnnualClock(com.stripe.model.testhelpers.TestClock clock,String id,String run,Instant expected){
        assertNotNull(id);assertNotNull(clock);assertEquals(id,clock.getId());assertEquals(run,clock.getName());
        assertEquals(Boolean.FALSE,clock.getLivemode());assertEquals("ready",clock.getStatus());assertEquals(expected.getEpochSecond(),clock.getFrozenTime());
        return Instant.ofEpochSecond(clock.getFrozenTime());
    }
    private static final Map<String,Long> ANNUAL_GRANTS=Map.of("task_create",12L,"ai_detection",20000L,"humanizer",10000L);
    private static LocalDateTime annualMonth(Instant paidAt,int index){
        var month=paidAt.atZone(ZoneOffset.UTC);for(int i=0;i<index;i++)month=month.plusMonths(1);return com.studyagent.common.datetime.DateTimeFormats.fromInstant(month.toInstant());
    }
    static void assertAnnualQuotaEvidence(List<UserAiQuotaEntity> quotas,List<QuotaLedgerEntity> ledgers,
            String user,String subscription,String expectedInvoice,Instant paidAt,int monthIndex){
        assertTrue(monthIndex>=0&&monthIndex<=2);assertEquals(3,quotas.size());
        assertEquals(ANNUAL_GRANTS.keySet(),quotas.stream().map(UserAiQuotaEntity::getFeatureCode).collect(java.util.stream.Collectors.toSet()));
        for(var quota:quotas){assertEquals(user,quota.getClerkUserId());assertEquals(ANNUAL_GRANTS.get(quota.getFeatureCode()),quota.getPlanBalance());assertEquals(annualMonth(paidAt,monthIndex),quota.getPlanPeriodStart());assertEquals(annualMonth(paidAt,monthIndex+1),quota.getPlanPeriodEnd());}
        assertEquals(ledgers.size(),ledgers.stream().map(QuotaLedgerEntity::getId).distinct().count());
        assertEquals(ledgers.size(),ledgers.stream().map(QuotaLedgerEntity::getIdempotencyKey).distinct().count());
        for(var ledger:ledgers){assertNotNull(ledger.getId());assertNotNull(ledger.getLedgerType());assertNotNull(ledger.getIdempotencyKey());assertEquals(user,ledger.getClerkUserId());assertEquals(subscription,ledger.getSubscriptionId());}
        var planLedgers=ledgers.stream().filter(l->Set.of("plan_reset","plan_refresh").contains(l.getLedgerType())).toList();
        assertEquals(3*(monthIndex+1),planLedgers.size());
        var resets=planLedgers.stream().filter(l->"plan_reset".equals(l.getLedgerType())).toList();assertEquals(3,resets.size());
        var invoiceIds=resets.stream().map(QuotaLedgerEntity::getInvoiceId).collect(java.util.stream.Collectors.toSet());assertEquals(1,invoiceIds.size());String invoice=invoiceIds.iterator().next();assertNotNull(invoice);assertFalse(invoice.isBlank());assertEquals(expectedInvoice,invoice,"Plan reset invoice must match the owned recovery invoice");
        for(var reset:resets){assertEquals("invoice",reset.getSourceType());assertEquals(invoice,reset.getSourceId());}
        // Recovery always resumes eligible add-ons once, even when no add-on grant exists.
        // Accept only that known zero lifecycle row, never arbitrary non-plan evidence.
        var lifecycle=ledgers.stream().filter(l->!Set.of("plan_reset","plan_refresh").contains(l.getLedgerType())).toList();assertEquals(1,lifecycle.size());
        var resumed=lifecycle.get(0);assertEquals("addon_resume",resumed.getLedgerType());assertEquals("addon",resumed.getFeatureCode());assertEquals(0L,resumed.getAmount());assertEquals("subscription",resumed.getSourceType());assertEquals(subscription,resumed.getSourceId());assertNull(resumed.getInvoiceId());assertEquals("recovery:"+invoice+":addons",resumed.getIdempotencyKey());assertNull(resumed.getPlanBalanceAfter());
        for(var ledger:planLedgers){assertTrue(ANNUAL_GRANTS.containsKey(ledger.getFeatureCode()));assertEquals(ANNUAL_GRANTS.get(ledger.getFeatureCode()),ledger.getAmount());assertEquals(ledger.getAmount(),ledger.getPlanBalanceAfter());}
        for(String feature:ANNUAL_GRANTS.keySet()){
            assertEquals(1,planLedgers.stream().filter(l->feature.equals(l.getFeatureCode())&&"plan_reset".equals(l.getLedgerType())).count());
            var refreshed=planLedgers.stream().filter(l->feature.equals(l.getFeatureCode())&&"plan_refresh".equals(l.getLedgerType())).toList();assertEquals(monthIndex,refreshed.size());
            for(int month=1;month<=monthIndex;month++){
                String key="plan-refresh:"+user+":"+feature+":"+annualMonth(paidAt,month);
                var matching=refreshed.stream().filter(l->key.equals(l.getIdempotencyKey())).toList();assertEquals(1,matching.size());var ledger=matching.get(0);
                assertEquals("subscription",ledger.getSourceType());assertEquals(subscription,ledger.getSourceId());assertNull(ledger.getInvoiceId());
                var context=JsonParser.parseString(ledger.getBizContext()).getAsJsonObject();assertEquals(annualMonth(paidAt,month).toString(),context.get("quota_period_start").getAsString());assertEquals(annualMonth(paidAt,month+1).toString(),context.get("quota_period_end").getAsString());
            }
        }
    }
    /** Clock can move only after a fresh scoped native ready-state read. No DB time is changed. */
    private static final class NativeObservedClock extends Clock {
        private Instant observed;
        void observe(Instant nativeTime){assertTrue(observed==null||!nativeTime.isBefore(observed));observed=nativeTime;}
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone){return Clock.fixed(instant(),zone);}
        @Override public Instant instant(){return Objects.requireNonNull(observed,"Owned native time must be observed first");}
    }
    private void assertNativeBusinessTime(Instant expected)throws Exception {
        var sub=Subscription.retrieve(subscriptionId);assertEquals(Boolean.FALSE,sub.getLivemode());assertEquals(root().getStripeCustomerId(),sub.getCustomer());
        assertEquals(expected.getEpochSecond(),com.stripe.model.testhelpers.TestClock.retrieve(sub.getTestClock()).getFrozenTime());
    }
    private Map<String,Invoice> ownedInvoices()throws Exception {
        var invoices=new LinkedHashMap<String,Invoice>();for(var invoice:Invoice.list(Map.of("subscription",subscriptionId,"limit",100L)).autoPagingIterable()){
            assertEquals(Boolean.FALSE,invoice.getLivemode());assertEquals(subscriptionId,invoice.getSubscription());assertEquals(root().getStripeCustomerId(),invoice.getCustomer());invoices.put(invoice.getId(),invoice);
        }return invoices;
    }
    private Set<String> ownedCustomerChargeIds()throws Exception {
        var ids=new HashSet<String>();for(var charge:Charge.list(Map.of("customer",root().getStripeCustomerId(),"limit",100L)).autoPagingIterable()){
            assertEquals(Boolean.FALSE,charge.getLivemode());assertEquals(root().getStripeCustomerId(),charge.getCustomer());ids.add(charge.getId());
        }return ids;
    }
    private void assertOriginalWindow(SubscriptionPaymentRecoveryEntity root){
        assertEquals(com.studyagent.common.datetime.DateTimeFormats.fromInstant(provenFirstFailure),root.getFirstFailedAt());
        assertEquals(com.studyagent.common.datetime.DateTimeFormats.fromInstant(provenFirstFailure.plus(Duration.ofDays(30))),root.getDeadlineAt());
    }
    private SubscriptionPaymentRecoveryEntity root(){return sql.getMapper(SubscriptionPaymentRecoveryMapper.class).selectById(rootId);}
    private List<SubscriptionRecoveryActionEntity> actions(){return sql.getMapper(SubscriptionRecoveryActionMapper.class).selectByRecovery(rootId);}
    private void cleanupReplacement()throws Exception {
        if(replacementOrderNo==null)return;
        var row=sql.getMapper(SubscriptionRecoveryReplacementMapper.class).selectByOrder(replacementOrderNo);if(row==null)return;
        try{
            var customer=Customer.retrieve(root().getStripeCustomerId());assertEquals(Boolean.FALSE,customer.getLivemode());assertEquals(user,customer.getMetadata().get("recovery_test_run"));
            var session=row.getCheckoutSessionId()==null?ownedGateway.findCheckout(customer.getId(),replacementOrderNo):com.stripe.model.checkout.Session.retrieve(row.getCheckoutSessionId());
            var create=actions().stream().filter(a->"CREATE_CHECKOUT".equals(a.getActionType())).findFirst().orElseThrow();
            if(session==null){
                if(create.getFirstDispatchedAt()==null&&"READY".equals(create.getStatus()))return;
                throw new IllegalStateException("REPLACEMENT_CLEANUP_UNKNOWN_CREATE");
            }
            var expected=Map.of("recovery_test_run",user,"order_no",replacementOrderNo,"purchase_type","subscription_replacement","recovery_id",String.valueOf(rootId),"old_subscription_id",subscriptionId,"clerk_user_id",user);
            requireReplacementSession(session,customer.getId(),expected);
            if("open".equals(session.getStatus())){
                // Cleanup also uses a persisted EXPIRE_CHECKOUT action, never a second CREATE.
                if(row.getCheckoutSessionId()==null){service.reconcile(rootId);row=service.replacement(user,replacementOrderNo);}
                assertEquals(session.getId(),row.getCheckoutSessionId());service.requestReplacementExit(user,rootId,replacementOrderNo);
                for(var expire:actions())if("EXPIRE_CHECKOUT".equals(expire.getActionType())&&session.getId().equals(expire.getTargetId())&&"READY".equals(expire.getStatus()))service.runDueAction(expire.getId());
                session=com.stripe.model.checkout.Session.retrieve(session.getId());assertEquals("expired",session.getStatus());
            }
            if(session.getSubscription()!=null){
                var sub=Subscription.retrieve(session.getSubscription());assertEquals(Boolean.FALSE,sub.getLivemode());assertEquals(customer.getId(),sub.getCustomer());assertEquals(customer.getTestClock(),sub.getTestClock());
                for(var item:expected.entrySet())assertEquals(item.getValue(),sub.getMetadata().get(item.getKey()));
                if(!"canceled".equals(sub.getStatus())){
                    var body=SubscriptionRecoveryStripeGateway.cancelParams();String cleanupKey=user+":cleanup-replacement-sub:"+sub.getId();
                    journal.record("cleanup-replacement-sub-intent",Map.of("subscriptionId",sub.getId(),"customerId",customer.getId(),"parameters",body,"idempotencyKey",cleanupKey));
                    sub=sub.cancel(body,com.stripe.net.RequestOptions.builder().setIdempotencyKey(cleanupKey).setMaxNetworkRetries(0).build());assertEquals("canceled",sub.getStatus());
                }
                journal.record("cleanup-replacement-sub-confirmed",Map.of("subscriptionId",sub.getId(),"status",sub.getStatus(),"paidHistoryRetained",true));
            }
            if(!Set.of("expired","complete").contains(session.getStatus()))throw new IllegalStateException("REPLACEMENT_CLEANUP_SESSION_NOT_TERMINAL");
            journal.record("cleanup-replacement",Map.of("orderNo",replacementOrderNo,"sessionId",session.getId(),"sessionStatus",session.getStatus(),"result","PASS","onlyOwnedNewObjects",true));
        }catch(Exception|AssertionError failure){
            journal.record("cleanup-replacement",Map.of("orderNo",replacementOrderNo,"replacementId",row.getId(),
                    "sessionId",Objects.toString(row.getCheckoutSessionId(),"UNKNOWN_FOLLOW_CREATE_INTENT"),
                    "newSubscriptionId",Objects.toString(row.getNewSubscriptionId(),"UNKNOWN_CHECK_NATIVE_SESSION"),
                    "result","UNKNOWN","errorType",failure.getClass().getSimpleName(),"runId",user,"rootId",rootId));
            throw new IllegalStateException("REPLACEMENT_CLEANUP_UNKNOWN_FOLLOW_JOURNAL");
        }
    }
    @Override public void close()throws Exception {if(closed)return;closed=true;try{cleanupReplacement();}finally{try{database.close();}finally{Stripe.apiKey=previousKey;}}}
}
