package com.studyagent.infra.service.billing;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.stripe.model.*;
import com.stripe.net.RequestOptions;
import com.studyagent.api.controller.WebhookController;
import com.studyagent.api.service.robot.RobotNotifyAsyncService;
import com.studyagent.common.analytics.AnalyticsService;
import com.studyagent.common.datetime.DateTimeFormats;
import com.studyagent.infra.entity.*;
import com.studyagent.infra.job.*;
import com.studyagent.infra.mapper.*;
import com.studyagent.infra.service.QuotaRechargeService;
import com.studyagent.service.domain.billing.BillingRobotNotifyGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Native TEST events replayed unchanged with a local HMAC through the production HTTP controller.
 * Real inbox, recovery service, MySQL, Stripe gateway and naturally scheduled jobs.
 * This is not Stripe-to-public-endpoint delivery, Clerk authentication or production mail acceptance.
 */
@EnabledIfEnvironmentVariable(named="STRIPE_RECOVERY_NATIVE_HTTP_IT", matches="true")
class StripePaymentRecoveryNativeHttpSchedulerIT {
    private ThreadPoolTaskScheduler naturalScheduler;
    private final boolean funds="true".equals(System.getenv("STRIPE_RECOVERY_FUNDS_SETUP"));
    private final boolean deadline="true".equals(System.getenv("STRIPE_RECOVERY_D30_CLOSE"));
    private final boolean publicDelivery="true".equals(System.getenv("STRIPE_RECOVERY_PUBLIC_HTTP"));
    @Test void nativeFailureAndRepaymentThroughHttpAndNaturalScheduler() throws Exception {
        assertEquals("true", System.getenv("STRIPE_RECOVERY_SANDBOX"));
        assertEquals("true", System.getenv("STRIPE_RECOVERY_APPLICATION_IT"));
        assertEquals(funds?"insufficient_funds":"generic_decline", System.getenv("STRIPE_RECOVERY_FAILURE_FIXTURE"));
        assertFalse(publicDelivery&&(funds||deadline),"Public delivery fixture has its own non-3DS payment scope");
        for (String mode : List.of("ACTIVE_CANCEL", "ANNUAL_REFRESH",
                "MAIL_ACCEPTANCE", "SHIFT_ACK_LOSS", "PORTAL_HOSTED", "REPLACEMENT", "REPLACEMENT_DOUBLE"))
            assertNotEquals("true", System.getenv("STRIPE_RECOVERY_" + mode), "Explicit HTTP scope only");
        var sandbox=new StripePaymentRecoverySandboxIT();
        sandbox.useHttpAcceptance(this::acceptNativeHttp);
        sandbox.verifiesProtectedPaymentAndTrialEndCandidate();
    }

    private void acceptNativeHttp(RecoverySandboxApplicationHarness fixture) throws Exception {
        String secret="whsec_" + UUID.randomUUID();
        var sql=fixture.sessions();
        var roots=sql.getMapper(SubscriptionPaymentRecoveryMapper.class);
        var actions=sql.getMapper(SubscriptionRecoveryActionMapper.class);
        var users=sql.getMapper(UserSubscriptionMapper.class);
        var jdbc=new JdbcTemplate(sql.getSqlSessionFactory().getConfiguration().getEnvironment().getDataSource());
        var analytics=mock(AnalyticsService.class);
        var notifications=mock(BillingRobotNotifyGateway.class);
        @SuppressWarnings("unchecked")
        var notificationProvider=(org.springframework.beans.factory.ObjectProvider<BillingRobotNotifyGateway>)mock(org.springframework.beans.factory.ObjectProvider.class);
        when(notificationProvider.getIfAvailable()).thenReturn(notifications);
        ReflectionTestUtils.setField(fixture.recoveryService(),"analytics",analytics);
        ReflectionTestUtils.setField(fixture.recoveryService(),"internalNotifications",notificationProvider);
        ReflectionTestUtils.setField(fixture.recoveryService(),"frontendUrl","https://recovery-test.example.invalid");
        fixture.evidence("native-http-scope", Map.of("caseKey",fixture.contractFixture().caseKey(),
                "transport","UNCHANGED_NATIVE_EVENT_LOCAL_SIGNED_HTTP_REPLAY","stripeGateway","REAL_TEST_API",
                "scheduler","PRODUCTION_SCHEDULED_BEANS_ONE_SECOND_CRON","database","ISOLATED_LOCAL_SCHEMA",
                "clerkResult","NOT_RUN","stripePublicDeliveryResult","NOT_RUN","mailResult","NOT_RUN"));
        try(var diagnostics=new RecoveryNativeStripeDiagnostics(fixture);
            var context=new SpringApplicationBuilder(Config.class)
                .profiles("recovery-native-http-it")
                .initializers(app -> {
                    var beans=app.getBeanFactory();
                    // Prebuilt real services retain fixture wiring; Spring must not reinject unrelated adapters.
                    beans.registerSingleton("stripeBillingWebhookService",fixture.webhookService());
                    beans.registerSingleton("paymentRecoveryService",fixture.recoveryService());
                    beans.registerSingleton("paymentRecoveryProperties",fixture.properties());
                    beans.registerSingleton("recoveryMapper",roots);
                    beans.registerSingleton("recoveryActionMapper",actions);
                    beans.registerSingleton("quotaRechargeService",mock(QuotaRechargeService.class));
                    beans.registerSingleton("analyticsService",mock(AnalyticsService.class));
                    beans.registerSingleton("robotNotifyAsyncService",mock(RobotNotifyAsyncService.class));
                }).run("--spring.config.location=optional:classpath:/recovery-native-http-isolated.properties",
                        "--server.address=127.0.0.1","--server.port=0","--spring.main.banner-mode=off",
                        "--logging.level.root=ERROR","--stripe.allow-unsigned-webhooks=false",
                        "--stripe.webhook-secret="+secret,"--billing.webhook-retry.cron=*/1 * * * * ?",
                        "--billing.payment-recovery.reconciliation-cron=*/1 * * * * ?",
                        "--billing.payment-recovery.review-cron=-")) {
            int port=((ServletWebServerApplicationContext)context).getWebServer().getPort();
            var scheduler=context.getBean(ThreadPoolTaskScheduler.class);
            naturalScheduler=scheduler;
            assertTrue(scheduler.getScheduledThreadPoolExecutor().getQueue().size()>=2);
            String invoiceId=fixture.ownedInvoiceId();
            Event first=fixture.initialFailureEvent();
            post(port,secret,first); post(port,secret,first);
            var root=roots.selectByInvoice(invoiceId); assertNotNull(root);
            long rootId=root.getId();
            assertEquals(funds?"OPEN_AUTO":"OPEN_MANUAL",root.getStatus());
            assertEquals(funds?"insufficient_funds":"generic_decline",root.getLatestDeclineCode());
            assertEquals(rootId,users.selectByUser(fixture.ownedRunId()).getPaymentRecoveryId());
            assertNull(users.selectByUser(fixture.ownedRunId()).getGraceEndAt());
            assertEquals(funds?4:0,actions.selectByRecovery(rootId).stream().filter(a->"RETRY_PAY".equals(a.getActionType())).count());
            await(() -> "ACTIVE".equals(roots.selectById(rootId).getProtectionState())
                    && actions.selectByRecovery(rootId).stream()
                    .filter(a->Set.of("DISABLE_ADVANCE","PAUSE_COLLECTION").contains(a.getActionType()))
                    .allMatch(a->"SUCCEEDED".equals(a.getStatus())),jdbc);
            Invoice unpaid=Invoice.retrieve(invoiceId);
            Subscription old=Subscription.retrieve(fixture.ownedSubscriptionId());
            assertEquals(Boolean.FALSE,unpaid.getLivemode()); assertEquals(Boolean.FALSE,old.getLivemode());
            assertEquals(fixture.ownedRunId(),old.getMetadata().get("recovery_test_run"));
            assertEquals(unpaid.getCustomer(),old.getCustomer());
            assertEquals("open",unpaid.getStatus()); assertFalse(unpaid.getAutoAdvance());
            assertEquals("keep_as_draft",old.getPauseCollection().getBehavior());
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM recharge_orders",Integer.class));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM user_ai_quotas",Integer.class));
            assertInboxOnce(jdbc,first);
            fixture.evidence("native-http-protected",Map.of("rootId",rootId,"firstEventId",first.getId(),
                    "firstEventApiVersion",first.getApiVersion(),"inboxAttempts",1,"protection","ACTIVE",
                    "naturalTicks",scheduler.getScheduledThreadPoolExecutor().getCompletedTaskCount(),
                    "actions",actionEvidence(actions.selectByRecovery(rootId))));

            try(var publicIngress=publicDelivery?new RecoveryNativePublicWebhook(fixture,port,unpaid.getCustomer()):null){
            if(publicIngress!=null){
                ReflectionTestUtils.setField(context.getBean(WebhookController.class),"webhookSecret",publicIngress.signingSecret());
                var failed=publicIngress.causeOwnedFailure(invoiceId);
                assertInboxOnce(jdbc,com.stripe.net.ApiResource.GSON.fromJson(new String(failed.body(),StandardCharsets.UTF_8),Event.class));
                publicIngress.replay(failed,port);
                assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM recharge_orders",Integer.class));
            }

            if(deadline&&!funds){
                if(fixture.contractFixture().keepSchedule())closeScheduleAtDeadlineNaturally(fixture,root,jdbc,port,secret);
                else closeAtDeadlineNaturally(fixture,root,jdbc,port,secret);
                return;
            }

            var cards=PaymentMethod.list(Map.of("customer",unpaid.getCustomer(),"type","card","limit",100L));
            assertFalse(Boolean.TRUE.equals(cards.getHasMore()));
            var good=cards.getData().stream().filter(p->p.getCard()!=null&&"4242".equals(p.getCard().getLast4())).findFirst().orElseThrow();
            assertEquals(unpaid.getCustomer(),good.getCustomer()); assertEquals(Boolean.FALSE,good.getLivemode());
            long paymentTime;
            Invoice paid;
            if(funds){
                paymentTime=DateTimeFormats.toEpochSecond(root.getFirstFailedAt())+Duration.ofDays(28).toSeconds();
                runFourNaturalSlots(fixture,root,jdbc,port,secret,good);
                if(deadline){closeAtDeadlineNaturally(fixture,root,jdbc,port,secret);return;}
                paid=Invoice.retrieve(invoiceId);
            }else{
                // Independent TEST repayment delayed beyond the original failed renewal boundary.
                var clock=com.stripe.model.testhelpers.TestClock.retrieve(old.getTestClock());
                paymentTime=clock.getFrozenTime()+Duration.ofDays(2).toSeconds();
                advanceClock(fixture,clock.getId(),paymentTime,"delayed-payment",jdbc);
                var payBody=Map.<String,Object>of("payment_method",good.getId(),"off_session",true);
                String payKey=fixture.ownedRunId()+":native-http-original-repayment";
                fixture.evidence("native-http-repayment-intent",Map.of("invoiceId",invoiceId,"parameters",payBody,
                        "idempotencyKey",payKey,"purpose","OWNED_TEST_ORIGINAL_INVOICE_PAYMENT"));
                paid=unpaid.pay(payBody,RequestOptions.builder().setIdempotencyKey(payKey).setMaxNetworkRetries(0).build());
            }
            assertEquals("paid",paid.getStatus());
            Event paidEvent;
            RecoveryNativePublicWebhook.Delivery deliveredPaid=null;
            if(publicIngress!=null){
                deliveredPaid=publicIngress.awaitDelivery("invoice.paid",invoiceId);
                publicIngress.recordDelivery(deliveredPaid,null);
                paidEvent=com.stripe.net.ApiResource.GSON.fromJson(new String(deliveredPaid.body(),StandardCharsets.UTF_8),Event.class);
                publicIngress.replay(deliveredPaid,port);
            }else{
                paidEvent=findPaidEvent(invoiceId,first.getCreated());
                assertNotNull(paidEvent,"Actual Stripe invoice.paid event required");
                post(port,secret,paidEvent); post(port,secret,paidEvent);
            }
            await(() -> "CONFIRMED".equals(roots.selectById(rootId).getAnchorSyncState()),jdbc);
            var granted=roots.selectById(rootId);
            assertEquals("PAID",granted.getStatus()); assertEquals("GRANTED",granted.getGrantState());
            assertEquals("RELEASED",granted.getProtectionState()); assertEquals(invoiceId,granted.getEntitlementInvoiceId());
            Instant paidAt=Instant.ofEpochSecond(paid.getStatusTransitions().getPaidAt());
            assertEquals(paymentTime,paidAt.getEpochSecond());
            Instant end=SubscriptionRecoveryPolicy.paidPeriodEnd(paidAt,fixture.contractFixture().interval(),1);
            assertEquals(DateTimeFormats.fromInstant(paidAt),granted.getEntitlementStartAt());
            assertEquals(DateTimeFormats.fromInstant(end),granted.getEntitlementEndAt());
            assertEquals(root.getFirstFailedAt(),granted.getFirstFailedAt());assertEquals(root.getDeadlineAt(),granted.getDeadlineAt());
            assertEquals(DateTimeFormats.fromInstant(SubscriptionRecoveryPolicy.quotaPeriodEnd(paidAt,fixture.contractFixture().interval(),1)),granted.getQuotaPeriodEndAt());
            assertEquals(granted.getEntitlementEndAt(),users.selectByUser(fixture.ownedRunId()).getCurrentPeriodEnd());
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM recharge_orders WHERE stripe_invoice_id=? AND status='completed'",Integer.class,invoiceId));
            assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM quota_ledger WHERE invoice_id=? AND ledger_type='plan_reset'",Integer.class,invoiceId));
            var quotaRows=sql.getMapper(UserAiQuotaMapper.class).selectList(null); assertEquals(3,quotaRows.size());
            for(var quota:quotaRows){
                assertEquals(granted.getQuotaPeriodEndAt(),quota.getPlanPeriodEnd());
                assertEquals("task_create".equals(quota.getFeatureCode())?12L:"ai_detection".equals(quota.getFeatureCode())?20000L:10000L,quota.getPlanBalance());
            }
            var shifts=actions.selectByRecovery(rootId).stream().filter(a->"SHIFT_RENEWAL".equals(a.getActionType())).toList();
            assertEquals(1,shifts.size()); var shift=shifts.get(0);
            assertEquals("SUCCEEDED",shift.getStatus()); assertEquals(1,shift.getTransportAttempts());
            var frozen=SubscriptionRecoveryStripeGateway.thaw(shift.getPayloadJson());
            assertEquals(end.getEpochSecond(),((Number)frozen.get("trial_end")).longValue());
            assertEquals("none",frozen.get("proration_behavior")); assertEquals("",frozen.get("pause_collection"));
            var renewed=Subscription.retrieve(old.getId()); assertNull(renewed.getPauseCollection());
            assertEquals(end.getEpochSecond(),renewed.getTrialEnd()); assertEquals(end.getEpochSecond(),renewed.getCurrentPeriodEnd());
            if(fixture.contractFixture().keepSchedule()){
                assertNotNull(old.getSchedule());assertNull(renewed.getSchedule());
                var releases=actions.selectByRecovery(rootId).stream().filter(a->"RELEASE_SCHEDULE".equals(a.getActionType())).toList();
                assertEquals(1,releases.size());assertEquals("SUCCEEDED",releases.get(0).getStatus());assertEquals(1,releases.get(0).getTransportAttempts());
                assertEquals(Map.of("preserve_cancel_date",true),SubscriptionRecoveryStripeGateway.thaw(releases.get(0).getPayloadJson()));
                var released=SubscriptionSchedule.retrieve(old.getSchedule());assertEquals(Boolean.FALSE,released.getLivemode());
                assertEquals("released",released.getStatus());assertEquals(old.getId(),released.getReleasedSubscription());
            }
            if(fixture.contractFixture().periodEnd()){
                assertEquals(Boolean.TRUE,renewed.getCancelAtPeriodEnd());assertEquals(end.getEpochSecond(),renewed.getCancelAt());
            }
            for(var invoice:Invoice.list(Map.of("subscription",old.getId(),"limit",100L)).autoPagingIterable())
                if(Set.of("draft","open","uncollectible").contains(invoice.getStatus()))assertFalse(invoice.getAutoAdvance());
            assertInboxOnce(jdbc,paidEvent);
            if(publicIngress!=null){
                var technicalIds=com.google.gson.JsonParser.parseString(roots.selectById(rootId).getTechnicalInvoiceIds()).getAsJsonArray();
                assertFalse(technicalIds.isEmpty(),"The native trial shift must produce a tracked technical invoice");
                for(var id:technicalIds){
                    String technicalId=id.getAsString();
                    await(()->publicIngress.proxy.deliveries().stream().anyMatch(d->technicalId.equals(d.objectId())&&"invoice.paid".equals(d.type())),jdbc);
                    var technical=publicIngress.proxy.deliveries().stream().filter(d->technicalId.equals(d.objectId())&&"invoice.paid".equals(d.type())).findFirst().orElseThrow();
                    publicIngress.recordDelivery(technical,shift.getProviderRequestId());
                    var beforeRetry=jdbc.queryForMap("SELECT status,attempt_count,last_error FROM stripe_webhook_events WHERE event_id=?",technical.eventId());
                    fixture.evidence("public-technical-inbox-before-retry",Map.of("eventId",technical.eventId(),"invoiceId",technicalId,"firstHttpStatus",technical.status(),"inbox",beforeRetry));
                    // Wait for the real retry scheduler. Do not force an HTTP retry or change SQL due times.
                    await(()->"succeeded".equals(jdbc.queryForObject("SELECT status FROM stripe_webhook_events WHERE event_id=?",String.class,technical.eventId())),jdbc);
                    int attempts=jdbc.queryForObject("SELECT attempt_count FROM stripe_webhook_events WHERE event_id=?",Integer.class,technical.eventId());
                    publicIngress.replay(technical,port);
                    assertEquals(attempts,jdbc.queryForObject("SELECT attempt_count FROM stripe_webhook_events WHERE event_id=?",Integer.class,technical.eventId()));
                    assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM recharge_orders WHERE stripe_invoice_id=?",Integer.class,technicalId));
                    fixture.evidence("public-technical-inbox-result",Map.of("result","PASS","eventId",technical.eventId(),"invoiceId",technicalId,"attempts",attempts,"status","succeeded","technicalOrders",0,"dispatchSource","NATURAL_RETRY_SCHEDULER","signedReplayIdempotent",true));
                }
            }
            long ticks=scheduler.getScheduledThreadPoolExecutor().getCompletedTaskCount();
            await(() -> scheduler.getScheduledThreadPoolExecutor().getCompletedTaskCount()>=ticks+4,jdbc);
            if(publicIngress!=null)publicIngress.replay(deliveredPaid,port);
            else{post(port,secret,first);post(port,secret,paidEvent);}
            assertInboxOnce(jdbc,first); assertInboxOnce(jdbc,paidEvent);
            assertEquals(1,actions.selectById(shift.getId()).getTransportAttempts());
            if(publicIngress!=null)fixture.evidence("native-public-order-diagnostic",Map.of(
                    "orders",jdbc.queryForList("SELECT order_type,stripe_invoice_id,stripe_subscription_id,status,price_cents FROM recharge_orders"),
                    "technicalInvoiceIds",Objects.toString(roots.selectById(rootId).getTechnicalInvoiceIds(),""),
                    "deliveries",publicIngress.proxy.deliveries().stream().map(d->Map.of("eventId",d.eventId(),"type",d.type(),"invoiceId",d.objectId(),"status",d.status())).toList()));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM recharge_orders",Integer.class));
            assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM quota_ledger WHERE ledger_type='plan_reset'",Integer.class));
            assertTrue(actions.selectByRecovery(rootId).stream().filter(a->"SEND_EMAIL".equals(a.getActionType()))
                    .allMatch(a->a.getTransportAttempts()==null||a.getTransportAttempts()==0));
            if(funds)assertEquals(0,actions.selectByRecovery(rootId).stream().filter(a->"SEND_EMAIL".equals(a.getActionType())).count());
            fixture.assertNoLegacySideEffects();
            fixture.evidence("native-http-scheduler-result",Map.ofEntries(
                    Map.entry("caseKey",fixture.contractFixture().caseKey()),Map.entry("result","PASS"),
                    Map.entry("firstEventId",first.getId()),Map.entry("paidEventId",paidEvent.getId()),
                    Map.entry("rootId",rootId),Map.entry("paidAt",paidAt.getEpochSecond()),Map.entry("entitlementEnd",end.getEpochSecond()),
                    Map.entry("anchor","CONFIRMED"),Map.entry("orders",1),Map.entry("planResetLedgers",3),
                    Map.entry("paymentDelayDays",funds?28:2),Map.entry("failureFixture",funds?"insufficient_funds":"generic_decline"),
                    Map.entry("scheduleRelease",fixture.contractFixture().keepSchedule()?"PERSISTED_RELEASE_CONFIRMED":"NOT_RUN"),
                    Map.entry("automaticRetrySlots",funds?"FOUR_NATURAL_CRON_SLOTS_PASS":"NOT_RUN"),Map.entry("externalNotificationDelivery","TEST_DOUBLES"),
                    Map.entry("shiftAttempts",1),Map.entry("actions",actionEvidence(actions.selectByRecovery(rootId))),
                    Map.entry("directRunDueActionCalls",0),Map.entry("directReconcileCalls",0),
                    Map.entry("stripePublicDeliveryResult",publicIngress==null?"NOT_RUN":"NATIVE_FAILURE_AND_PAID_PUBLIC_PASS"),Map.entry("mailResult","NOT_RUN"),Map.entry("finalResult","NOT_RUN")));
            }
        }
    }

    /** Separate Schedule cancellation scenario: it does not claim the legacy intro-draft fixture. */
    private void closeScheduleAtDeadlineNaturally(RecoverySandboxApplicationHarness fixture,SubscriptionPaymentRecoveryEntity original,
            JdbcTemplate db,int port,String secret)throws Exception {
        assertTrue(fixture.contractFixture().scheduleFirstPay());assertTrue(fixture.contractFixture().keepSchedule());
        assertFalse(fixture.contractFixture().periodEnd());assertFalse(funds);
        var roots=fixture.sessions().getMapper(SubscriptionPaymentRecoveryMapper.class);
        var actions=fixture.sessions().getMapper(SubscriptionRecoveryActionMapper.class);
        String invoice=fixture.ownedInvoiceId(),subscription=fixture.ownedSubscriptionId();
        var sub=Subscription.retrieve(subscription);String scheduleId=sub.getSchedule();assertNotNull(scheduleId);
        var schedule=com.stripe.model.SubscriptionSchedule.retrieve(scheduleId);
        assertEquals(Boolean.FALSE,schedule.getLivemode());assertEquals(subscription,schedule.getSubscription());
        assertEquals(fixture.ownedRunId(),schedule.getMetadata().get("recovery_test_run"));assertEquals("active",schedule.getStatus());
        long at=DateTimeFormats.toEpochSecond(original.getDeadlineAt());
        var observed=new java.util.concurrent.atomic.AtomicLong();
        fixture.observeNativeReads(snapshot->{if(invoice.equals(snapshot.invoice().getId())){
            assertTrue(Thread.currentThread().getName().startsWith("recovery-native-cron-"));observed.set(snapshot.businessNow().getEpochSecond());
        }});
        advanceClock(fixture,sub.getTestClock(),at-1,"schedule-before-d30",db);
        int version=roots.selectById(original.getId()).getVersion();
        await(()->observed.get()==at-1&&roots.selectById(original.getId()).getVersion()>version,db);
        assertEquals("OPEN_MANUAL",roots.selectById(original.getId()).getStatus());assertNull(roots.selectById(original.getId()).getAccountClosedAt());
        assertEquals(0,actions.selectByRecovery(original.getId()).stream().filter(a->Set.of("CANCEL_SCHEDULE","CANCEL_SUBSCRIPTION").contains(a.getActionType())).count());
        var before=new java.util.concurrent.atomic.AtomicReference<Map<String,Invoice>>();
        var charges=new java.util.concurrent.atomic.AtomicReference<Set<String>>();var calls=new java.util.concurrent.atomic.AtomicInteger();
        fixture.observeNativeWrites(action->{
            if(!"CANCEL_SCHEDULE".equals(action.getActionType()))return;
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());assertEquals(1,calls.incrementAndGet());
            var persisted=actions.selectById(action.getId());assertEquals("DISPATCHING",persisted.getStatus());assertEquals(1,persisted.getTransportAttempts());
            assertEquals(scheduleId,persisted.getTargetId());assertEquals(Map.of("invoice_now",false,"prorate",false),SubscriptionRecoveryStripeGateway.thaw(persisted.getPayloadJson()));
            try{
                before.set(ownedInvoices(fixture));charges.set(customerCharges(sub.getCustomer()));assertEquals(2,charges.get().size());
                for(var i:before.get().values())if(Set.of("draft","open","uncollectible").contains(i.getStatus()))assertEquals(Boolean.FALSE,i.getAutoAdvance());
                fixture.evidence("native-http-schedule-cancel-dispatch",Map.of("actionId",action.getId(),"scheduleId",scheduleId,"payload",persisted.getPayloadJson(),
                        "providerIdempotencyKey",persisted.getProviderIdempotencyKey(),"invoiceIds",before.get().keySet(),"chargeIds",charges.get(),"outsideTransaction",true));
            }catch(Exception e){throw new IllegalStateException("Schedule cancellation evidence failed",e);}
        });
        advanceClock(fixture,sub.getTestClock(),at,"schedule-d30",db);
        await(()->"EXPIRED".equals(roots.selectById(original.getId()).getStatus()),db);
        var closed=roots.selectById(original.getId());assertEquals("DEADLINE",closed.getCloseReason());assertNotNull(closed.getAccountClosedAt());
        assertEquals(original.getFirstFailedAt(),closed.getFirstFailedAt());assertEquals(original.getDeadlineAt(),closed.getDeadlineAt());
        assertNull(closed.getEntitlementInvoiceId());assertNotEquals("GRANTED",closed.getGrantState());
        assertEquals("canceled",com.stripe.model.SubscriptionSchedule.retrieve(scheduleId).getStatus());assertEquals("canceled",Subscription.retrieve(subscription).getStatus());
        var canceledInvoice=Invoice.retrieve(invoice);assertEquals("void",canceledInvoice.getStatus());assertEquals(0L,canceledInvoice.getAmountPaid());assertEquals(original.getAmountDueMinor(),canceledInvoice.getAmountDue());
        var account=fixture.sessions().getMapper(UserSubscriptionMapper.class).selectByUser(fixture.ownedRunId());
        assertEquals("free",account.getTier());assertEquals("free",account.getPlanCode());assertNull(account.getStripeSubscriptionId());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM recharge_orders",Integer.class));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM quota_ledger WHERE ledger_type='plan_reset'",Integer.class));
        var cancellations=actions.selectByRecovery(original.getId()).stream().filter(a->"CANCEL_SCHEDULE".equals(a.getActionType())).toList();
        assertEquals(1,cancellations.size());assertEquals("SUCCEEDED",cancellations.get(0).getStatus());assertEquals(1,cancellations.get(0).getTransportAttempts());assertEquals(1,calls.get());
        var after=ownedInvoices(fixture);assertEquals(before.get().keySet(),after.keySet());assertEquals(charges.get(),customerCharges(sub.getCustomer()));
        for(var i:after.values()){
            var prior=before.get().get(i.getId());assertEquals(prior.getAmountPaid(),i.getAmountPaid());
            if("paid".equals(prior.getStatus()))assertEquals("paid",i.getStatus());
            else if(!"void".equals(i.getStatus())){assertEquals("draft",i.getStatus());assertEquals(Boolean.FALSE,i.getAutoAdvance());assertEquals("OPEN",closed.getReviewState());}
        }
        post(port,secret,fixture.initialFailureEvent());Thread.sleep(2500);
        assertEquals("EXPIRED",roots.selectById(original.getId()).getStatus());assertEquals(closed.getAccountClosedAt(),roots.selectById(original.getId()).getAccountClosedAt());
        assertEquals(before.get().keySet(),ownedInvoices(fixture).keySet());assertEquals(charges.get(),customerCharges(sub.getCustomer()));assertEquals(1,calls.get());
        assertEquals(1,actions.selectById(cancellations.get(0).getId()).getTransportAttempts());fixture.assertNoLegacySideEffects();
        fixture.evidence("native-http-schedule-d30-result",Map.ofEntries(Map.entry("result","PASS"),Map.entry("caseKey",fixture.contractFixture().caseKey()),Map.entry("scheduleId",scheduleId),
                Map.entry("status","EXPIRED"),Map.entry("closeReason","DEADLINE"),Map.entry("cancelType","CANCEL_SCHEDULE"),Map.entry("cancelAttempts",1),
                Map.entry("noFinalInvoiceCreated",true),Map.entry("noNewCharges",true),Map.entry("historyRetained",true),Map.entry("legacyIntroDraftVariant","NOT_RUN"),
                Map.entry("actions",actionEvidence(actions.selectByRecovery(original.getId()))),Map.entry("mailResult","NOT_RUN"),Map.entry("stripePublicDeliveryResult","NOT_RUN"),Map.entry("finalResult","NOT_RUN")));
    }

    private void closeAtDeadlineNaturally(RecoverySandboxApplicationHarness fixture,SubscriptionPaymentRecoveryEntity original,
            JdbcTemplate db,int port,String secret)throws Exception {
        var roots=fixture.sessions().getMapper(SubscriptionPaymentRecoveryMapper.class);
        var actions=fixture.sessions().getMapper(SubscriptionRecoveryActionMapper.class);
        assertTrue(fixture.contractFixture().priceUpdate());assertFalse(fixture.contractFixture().periodEnd());
        String subscription=fixture.ownedSubscriptionId(),invoice=fixture.ownedInvoiceId();
        var sub=Subscription.retrieve(subscription);
        long at=DateTimeFormats.toEpochSecond(original.getDeadlineAt());
        // Observe an actual natural reconciliation one second before D30, without changing SQL times.
        var observedBusinessTime=new java.util.concurrent.atomic.AtomicLong();
        fixture.observeNativeReads(snapshot -> {
            if(invoice.equals(snapshot.invoice().getId())){
                assertTrue(Thread.currentThread().getName().startsWith("recovery-native-cron-"));
                observedBusinessTime.set(snapshot.businessNow().getEpochSecond());
            }
        });
        advanceClock(fixture,sub.getTestClock(),at-1,"before-d30",db);
        int version=roots.selectById(original.getId()).getVersion();
        await(() -> observedBusinessTime.get()==at-1&&roots.selectById(original.getId()).getVersion()>version,db);
        var before=roots.selectById(original.getId());assertEquals(funds?"OPEN_AUTO":"OPEN_MANUAL",before.getStatus());assertNull(before.getCloseReason());assertNull(before.getAccountClosedAt());
        assertEquals(0,actions.selectByRecovery(original.getId()).stream().filter(a->Set.of("CANCEL_SUBSCRIPTION","FINALIZE_INVOICE","VOID_INVOICE").contains(a.getActionType())).count());
        var protectedInvoices=ownedInvoices(fixture);
        var requiredDrafts=protectedInvoices.values().stream().filter(i->"draft".equals(i.getStatus())
                &&i.getLines().getData().stream().anyMatch(l->l.getPrice()!=null&&fixture.ownedIntroPriceId().equals(l.getPrice().getId())))
                .map(Invoice::getId).collect(java.util.stream.Collectors.toSet());
        assertFalse(requiredDrafts.isEmpty(),"Capture actual old trial draft identities before D30");
        fixture.evidence("native-http-before-d30",Map.of("businessTime",at-1,"status",before.getStatus(),"requiredProtectedDraftIds",requiredDrafts));
        var beforeCancel=new java.util.concurrent.atomic.AtomicReference<Map<String,Invoice>>();
        var chargesBeforeCancel=new java.util.concurrent.atomic.AtomicReference<Set<String>>();
        var cancelCalls=new java.util.concurrent.atomic.AtomicInteger();
        fixture.observeNativeWrites(action -> {
            if(!"CANCEL_SUBSCRIPTION".equals(action.getActionType()))return;
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(1,cancelCalls.incrementAndGet());
            var persisted=actions.selectById(action.getId());assertEquals("DISPATCHING",persisted.getStatus());assertEquals(1,persisted.getTransportAttempts());
            assertEquals(Map.of("invoice_now",false,"prorate",false),SubscriptionRecoveryStripeGateway.thaw(persisted.getPayloadJson()));
            try{
                beforeCancel.set(ownedInvoices(fixture));chargesBeforeCancel.set(customerCharges(sub.getCustomer()));
                fixture.evidence("native-http-d30-cancel-dispatch",Map.of("actionId",action.getId(),"idempotencyKey",action.getProviderIdempotencyKey(),
                        "payload",action.getPayloadJson(),"invoiceIds",beforeCancel.get().keySet(),"charges",chargesBeforeCancel.get(),"outsideTransaction",true));
            }catch(Exception e){throw new IllegalStateException("D30 pre-dispatch evidence failed",e);}
        });
        advanceClock(fixture,sub.getTestClock(),at,"d30",db);
        await(() -> "EXPIRED".equals(roots.selectById(original.getId()).getStatus()),db);
        var closed=roots.selectById(original.getId());assertEquals("DEADLINE",closed.getCloseReason());assertNotNull(closed.getAccountClosedAt());
        assertEquals(original.getFirstFailedAt(),closed.getFirstFailedAt());assertEquals(original.getDeadlineAt(),closed.getDeadlineAt());
        assertNull(closed.getEntitlementInvoiceId());assertNull(closed.getEntitlementEndAt());assertNotEquals("GRANTED",closed.getGrantState());
        var account=fixture.sessions().getMapper(UserSubscriptionMapper.class).selectByUser(fixture.ownedRunId());
        assertEquals("free",account.getPlanCode());assertEquals("free",account.getTier());assertEquals("canceled",account.getStatus());assertNull(account.getStripeSubscriptionId());
        assertEquals("canceled",Subscription.retrieve(subscription).getStatus());assertEquals("void",Invoice.retrieve(invoice).getStatus());
        assertEquals(0L,Invoice.retrieve(invoice).getAmountPaid());assertEquals(original.getAmountDueMinor(),Invoice.retrieve(invoice).getAmountDue());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM recharge_orders",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM quota_ledger WHERE ledger_type='plan_reset'",Integer.class));
        var cancellations=actions.selectByRecovery(original.getId()).stream().filter(a->"CANCEL_SUBSCRIPTION".equals(a.getActionType())).toList();
        assertEquals(1,cancellations.size());assertEquals("SUCCEEDED",cancellations.get(0).getStatus());assertEquals(1,cancellations.get(0).getTransportAttempts());
        assertNotNull(beforeCancel.get());assertNotNull(chargesBeforeCancel.get());assertEquals(funds?6:2,chargesBeforeCancel.get().size());
        var after=ownedInvoices(fixture);assertEquals(beforeCancel.get().keySet(),after.keySet());assertEquals(chargesBeforeCancel.get(),customerCharges(sub.getCustomer()));
        var residuals=RecoverySandboxApplicationHarness.closureResiduals(beforeCancel.get(),after,requiredDrafts,closed.getReviewState(),actions.selectByRecovery(original.getId()));
        post(port,secret,fixture.initialFailureEvent());Thread.sleep(2500);
        assertEquals("EXPIRED",roots.selectById(original.getId()).getStatus());assertEquals(closed.getAccountClosedAt(),roots.selectById(original.getId()).getAccountClosedAt());
        assertEquals(beforeCancel.get().keySet(),ownedInvoices(fixture).keySet());assertEquals(chargesBeforeCancel.get(),customerCharges(sub.getCustomer()));
        assertEquals(residuals,RecoverySandboxApplicationHarness.closureResiduals(beforeCancel.get(),ownedInvoices(fixture),requiredDrafts,roots.selectById(original.getId()).getReviewState(),actions.selectByRecovery(original.getId())));
        assertEquals(1,cancelCalls.get());assertEquals(1,actions.selectById(cancellations.get(0).getId()).getTransportAttempts());fixture.assertNoLegacySideEffects();
        if(funds){
            var retries=actions.selectByRecovery(original.getId()).stream().filter(a->"RETRY_PAY".equals(a.getActionType())).toList();assertEquals(4,retries.size());
            for(var retry:retries){assertEquals("SUCCEEDED",retry.getStatus());assertEquals(1,retry.getTransportAttempts());assertEquals("DECLINED",SubscriptionRecoveryStripeGateway.thaw(retry.getResultJson()).get("provider_state"));}
            assertEquals(0,actions.selectByRecovery(original.getId()).stream().filter(a->"SEND_EMAIL".equals(a.getActionType())).count());
        }
        fixture.evidence("native-http-d30-result",Map.ofEntries(Map.entry("result","PASS"),Map.entry("caseKey",fixture.contractFixture().caseKey()),
                Map.entry("status","EXPIRED"),Map.entry("closeReason","DEADLINE"),Map.entry("deadlineBusinessTime",at),Map.entry("rootId",original.getId()),
                Map.entry("requiredProtectedDraftIds",requiredDrafts),Map.entry("residualDrafts",residuals),Map.entry("cancelAttempts",1),
                Map.entry("noFinalInvoiceCreated",true),Map.entry("noNewCharges",true),Map.entry("historyRetained",true),
                Map.entry("actions",actionEvidence(actions.selectByRecovery(original.getId()))),Map.entry("directRunDueActionCalls",0),Map.entry("directReconcileCalls",0),
                Map.entry("stripePublicDeliveryResult","NOT_RUN"),Map.entry("mailResult","NOT_RUN"),Map.entry("fundsD30Result",funds?"PASS":"NOT_RUN"),Map.entry("finalResult","NOT_RUN")));
    }
    private static Map<String,Invoice> ownedInvoices(RecoverySandboxApplicationHarness fixture)throws Exception{
        var rows=new LinkedHashMap<String,Invoice>();
        for(var invoice:Invoice.list(Map.of("subscription",fixture.ownedSubscriptionId(),"limit",100L)).autoPagingIterable()){
            assertEquals(Boolean.FALSE,invoice.getLivemode());assertEquals(fixture.ownedSubscriptionId(),invoice.getSubscription());rows.put(invoice.getId(),invoice);
        }
        return rows;
    }
    private static Set<String> customerCharges(String customer)throws Exception{
        var ids=new HashSet<String>();for(var charge:Charge.list(Map.of("customer",customer,"limit",100L)).autoPagingIterable()){
            assertEquals(Boolean.FALSE,charge.getLivemode());assertEquals(customer,charge.getCustomer());ids.add(charge.getId());
        }return ids;
    }
    private void runFourNaturalSlots(RecoverySandboxApplicationHarness fixture,SubscriptionPaymentRecoveryEntity original,
            JdbcTemplate db,int port,String secret,PaymentMethod good)throws Exception {
        var actions=fixture.sessions().getMapper(SubscriptionRecoveryActionMapper.class);
        var roots=fixture.sessions().getMapper(SubscriptionPaymentRecoveryMapper.class);
        String invoice=fixture.ownedInvoiceId(),subscription=fixture.ownedSubscriptionId();
        var nativeSub=Subscription.retrieve(subscription);
        long first=DateTimeFormats.toEpochSecond(original.getFirstFailedAt());
        var planned=actions.selectByRecovery(original.getId()).stream().filter(a->"RETRY_PAY".equals(a.getActionType())).toList();
        assertEquals(4,planned.size());assertEquals(Set.of(1,2,3,4),planned.stream().map(SubscriptionRecoveryActionEntity::getRetrySlot).collect(java.util.stream.Collectors.toSet()));
        for(var action:planned){
            String key="recovery:"+invoice+":retry:"+action.getRetrySlot();
            assertEquals(key,action.getActionKey());assertEquals(key,action.getProviderIdempotencyKey());
            assertEquals(Map.of("off_session",true),SubscriptionRecoveryStripeGateway.thaw(action.getPayloadJson()));
            assertEquals(first+Duration.ofDays(7L*action.getRetrySlot()).toSeconds(),DateTimeFormats.toEpochSecond(action.getScheduledAt()));
        }
        var seenEvents=new HashSet<String>();seenEvents.add(fixture.initialFailureEvent().getId());
        for(int slot=1;slot<=4;slot++){
            int current=slot;
            var action=planned.stream().filter(a->Integer.valueOf(current).equals(a.getRetrySlot())).findFirst().orElseThrow();
            var before=actions.selectById(action.getId());assertEquals("READY",before.getStatus());assertEquals(0,before.getTransportAttempts());
            var oldInvoice=Invoice.retrieve(invoice);var oldIntent=PaymentIntent.retrieve(oldInvoice.getPaymentIntent());
            assertEquals(slot,chargeIds(oldIntent.getId(),invoice).size());
            if(slot==4&&!deadline){
                // Only user card preference is changed. The real service must synchronize invoice/subscription.
                String key=fixture.ownedRunId()+":native-http-d28-customer-good";
                var body=Map.<String,Object>of("invoice_settings",Map.of("default_payment_method",good.getId()));
                long changedAfter=Instant.now().minusSeconds(1).getEpochSecond();
                fixture.evidence("native-http-funds-method-intent",Map.of("customerId",oldInvoice.getCustomer(),"parameters",body,"idempotencyKey",key));
                Customer updated=Customer.retrieve(oldInvoice.getCustomer()).update(body,RequestOptions.builder().setIdempotencyKey(key).setMaxNetworkRetries(0).build());
                assertEquals(Boolean.FALSE,updated.getLivemode());assertEquals(fixture.ownedRunId(),updated.getMetadata().get("recovery_test_run"));
                assertEquals(good.getId(),updated.getInvoiceSettings().getDefaultPaymentMethod());
                var changed=findEvent("customer.updated",updated.getId(),changedAfter,Set.of());assertNotNull(changed);
                post(port,secret,changed);post(port,secret,changed);assertInboxOnce(db,changed);
                await(() -> {
                    var methods=actions.selectByRecovery(original.getId()).stream().filter(a->"UPDATE_METHOD".equals(a.getActionType())&&a.getPayloadJson().contains(good.getId())).toList();
                    return methods.size()==2&&methods.stream().allMatch(a->"SUCCEEDED".equals(a.getStatus()));
                },db);
                assertEquals(good.getId(),Subscription.retrieve(subscription).getDefaultPaymentMethod());
                assertEquals(good.getId(),Invoice.retrieve(invoice).getDefaultPaymentMethod());
                assertEquals("open",Invoice.retrieve(invoice).getStatus());
                assertEquals(slot,chargeIds(oldIntent.getId(),invoice).size(),"Card update must not collect");
            }
            long at=first+Duration.ofDays(7L*slot).toSeconds();
            advanceClock(fixture,nativeSub.getTestClock(),at,"slot-"+slot,db);
            await(() -> "SUCCEEDED".equals(actions.selectById(action.getId()).getStatus()),db);
            var done=actions.selectById(action.getId());
            assertEquals(1,done.getTransportAttempts());assertNotNull(done.getFirstDispatchedAt());assertNotNull(done.getProviderRequestId());
            assertEquals(before.getPayloadJson(),done.getPayloadJson());assertEquals(before.getProviderIdempotencyKey(),done.getProviderIdempotencyKey());
            var receipt=SubscriptionRecoveryStripeGateway.thaw(done.getResultJson());
            assertEquals(slot==4&&!deadline?"SUCCEEDED":"DECLINED",receipt.get("provider_state"));
            var afterInvoice=Invoice.retrieve(invoice);var afterIntent=PaymentIntent.retrieve(afterInvoice.getPaymentIntent());
            assertEquals(oldIntent.getId(),afterIntent.getId());assertNotEquals(oldIntent.getLatestCharge(),afterIntent.getLatestCharge());
            var charges=chargeIds(afterIntent.getId(),invoice);assertEquals(slot+1,charges.size());
            var charge=Charge.retrieve(afterIntent.getLatestCharge());
            assertEquals(afterIntent.getId(),receipt.get("payment_intent_id"));assertEquals(charge.getId(),receipt.get("charge_id"));
            if(slot<4||deadline){
                assertEquals("insufficient_funds",receipt.get("error"));assertEquals("insufficient_funds",afterIntent.getLastPaymentError().getDeclineCode());
                assertEquals("failed",charge.getStatus());assertEquals("open",afterInvoice.getStatus());assertEquals(0L,afterInvoice.getAmountPaid());
                assertEquals("OPEN_AUTO",roots.selectById(original.getId()).getStatus());
                assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM recharge_orders",Integer.class));
                assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM user_ai_quotas",Integer.class));
                var failed=findEvent("invoice.payment_failed",invoice,fixture.initialFailureEvent().getCreated(),seenEvents);assertNotNull(failed);
                seenEvents.add(failed.getId());post(port,secret,failed);post(port,secret,failed);assertInboxOnce(db,failed);
            }else{
                assertEquals("paid",afterInvoice.getStatus());assertEquals("succeeded",charge.getStatus());
                assertEquals(good.getId(),charge.getPaymentMethod());
            }
            post(port,secret,fixture.initialFailureEvent());Thread.sleep(2200);
            assertEquals(charges,chargeIds(afterIntent.getId(),invoice),"Replay/natural scans must not charge twice");
            assertEquals(1,actions.selectById(action.getId()).getTransportAttempts());
            assertEquals(0,actions.selectByRecovery(original.getId()).stream().filter(a->"SEND_EMAIL".equals(a.getActionType())).count());
            var root=roots.selectById(original.getId());assertEquals(original.getFirstFailedAt(),root.getFirstFailedAt());assertEquals(original.getDeadlineAt(),root.getDeadlineAt());
            for(var future:planned)if(future.getRetrySlot()>slot){var pending=actions.selectById(future.getId());assertEquals("READY",pending.getStatus());assertEquals(0,pending.getTransportAttempts());}
            fixture.evidence("native-http-funds-slot-result",Map.ofEntries(Map.entry("slot",slot),Map.entry("result","PASS"),
                    Map.entry("actionId",done.getId()),Map.entry("scheduledBusinessTime",at),Map.entry("status",done.getStatus()),
                    Map.entry("providerState",receipt.get("provider_state")),Map.entry("providerRequestId",done.getProviderRequestId()),
                    Map.entry("idempotencyKey",done.getProviderIdempotencyKey()),Map.entry("payload",done.getPayloadJson()),
                    Map.entry("chargeId",charge.getId()),Map.entry("chargeCount",charges.size()),Map.entry("transportAttempts",1),
                    Map.entry("replayDidNotWrite",true),Map.entry("dispatchSource","NATURAL_SCHEDULED_BEAN")));
        }
    }
    private static Set<String> chargeIds(String intent,String invoice)throws Exception{
        var ids=new HashSet<String>();
        for(var charge:Charge.list(Map.of("payment_intent",intent,"limit",100L)).autoPagingIterable()){
            assertEquals(Boolean.FALSE,charge.getLivemode());assertEquals(intent,charge.getPaymentIntent());assertEquals(invoice,charge.getInvoice());ids.add(charge.getId());
        }
        return ids;
    }
    private void advanceClock(RecoverySandboxApplicationHarness fixture,String id,long at,String reason,JdbcTemplate db)throws Exception{
        // Stripe rejects mutations while a Test Clock is advancing. Serialize only this fixture operation
        // on the one-thread scheduler; production cron methods still run naturally after native ready-state.
        naturalScheduler.submit(()->{advanceOwnedClock(fixture,id,at,reason,db);return null;}).get(3,java.util.concurrent.TimeUnit.MINUTES);
    }
    private static void advanceOwnedClock(RecoverySandboxApplicationHarness fixture,String id,long at,String reason,JdbcTemplate db)throws Exception{
        var clock=com.stripe.model.testhelpers.TestClock.retrieve(id);
        assertEquals(Boolean.FALSE,clock.getLivemode());assertEquals(fixture.ownedRunId(),clock.getName());assertEquals("ready",clock.getStatus());
        assertTrue(at>clock.getFrozenTime());String key=fixture.ownedRunId()+":native-http-clock:"+reason;
        fixture.evidence("native-http-clock-advance-intent",Map.of("clockId",id,"parameters",Map.of("frozen_time",at),"idempotencyKey",key));
        clock.advance(Map.of("frozen_time",at),RequestOptions.builder().setIdempotencyKey(key).setMaxNetworkRetries(0).build());
        await(() -> {
            try{return "ready".equals(com.stripe.model.testhelpers.TestClock.retrieve(id).getStatus());}
            catch(Exception e){throw new IllegalStateException("Owned test clock read failed",e);}
        },db);
        assertEquals(at,com.stripe.model.testhelpers.TestClock.retrieve(id).getFrozenTime());
    }
    private static List<Map<String,Object>> actionEvidence(List<SubscriptionRecoveryActionEntity> rows){
        return rows.stream().map(a->Map.<String,Object>of("id",a.getId(),"type",a.getActionType(),
                "status",a.getStatus(),"attempts",a.getTransportAttempts()==null?0:a.getTransportAttempts(),
                "idempotencyKey",a.getProviderIdempotencyKey(),"payload",a.getPayloadJson())).toList();
    }
    private static void assertInboxOnce(JdbcTemplate db,Event event){
        var row=db.queryForMap("SELECT status,attempt_count FROM stripe_webhook_events WHERE event_id=?",event.getId());
        assertEquals("succeeded",row.get("status"));assertEquals(1,((Number)row.get("attempt_count")).intValue());
    }
    private static Event findPaidEvent(String invoice,long created)throws Exception{
        return findEvent("invoice.paid",invoice,created,Set.of());
    }
    private static Event findEvent(String type,String objectId,long created,Set<String> seen)throws Exception{
        for(int attempt=0;attempt<30;attempt++){
            for(var event:Event.list(Map.of("types",List.of(type),"created",Map.of("gte",created),"limit",100L)).autoPagingIterable()){
                var raw=com.google.gson.JsonParser.parseString(event.getDataObjectDeserializer().getRawJson()).getAsJsonObject();
                if(objectId.equals(raw.get("id").getAsString())&&!seen.contains(event.getId())){assertEquals(Boolean.FALSE,event.getLivemode());return event;}
            }
            Thread.sleep(250);
        }
        return null;
    }
    private static void post(int port,String secret,Event event)throws Exception{
        String body=event.toJson();long timestamp=Instant.now().getEpochSecond();
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        String signature="t="+timestamp+",v1="+HexFormat.of().formatHex(mac.doFinal((timestamp+"."+body).getBytes(StandardCharsets.UTF_8)));
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/v1/webhook/stripe"))
                .timeout(Duration.ofSeconds(90)).header("Content-Type","application/json").header("stripe-signature",signature)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        assertEquals(200,HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString()).statusCode());
    }
    private static void await(BooleanSupplier ready,JdbcTemplate db)throws Exception{
        long deadline=System.nanoTime()+Duration.ofMinutes(3).toNanos();
        while(!ready.getAsBoolean()){
            if(System.nanoTime()>deadline)fail("Native HTTP/cron deadline; roots="+db.queryForList("SELECT status,protection_state,anchor_sync_state,review_reason FROM subscription_payment_recoveries")
                    +"; actions="+db.queryForList("SELECT action_type,status,transport_attempts,last_error FROM subscription_payment_recovery_actions"));
            Thread.sleep(100);
        }
    }

    @Configuration(proxyBeanMethods=false)
    @Profile("recovery-native-http-it")
    @EnableAutoConfiguration(exclude={DataSourceAutoConfiguration.class,RabbitAutoConfiguration.class,MybatisPlusAutoConfiguration.class})
    @EnableScheduling
    @Import({WebhookController.class,StripeWebhookRetryScheduler.class,SubscriptionPaymentRecoveryScheduler.class})
    static class Config {
        @Bean ThreadPoolTaskScheduler taskScheduler(){
            var scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(1);
            scheduler.setThreadNamePrefix("recovery-native-cron-");
            scheduler.setWaitForTasksToCompleteOnShutdown(true);scheduler.setAwaitTerminationSeconds(120);
            return scheduler;
        }
    }
}
