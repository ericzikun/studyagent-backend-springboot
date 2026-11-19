package com.studyagent.infra.service.billing;

import com.stripe.Stripe;
import com.stripe.model.*;
import com.stripe.model.testhelpers.TestClock;
import com.studyagent.api.controller.internal.RecoveryTestClockController;
import com.studyagent.api.filter.RecoveryTestClockOperatorFilter;
import com.studyagent.infra.mapper.*;
import com.studyagent.common.datetime.DateTimeFormats;
import com.studyagent.infra.service.QuotaRechargeService;
import com.studyagent.common.analytics.AnalyticsService;
import com.studyagent.api.service.robot.RobotNotifyAsyncService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** No injected first-failure proof: deployed operator + gateway must establish it themselves. */
@EnabledIfEnvironmentVariable(named="STRIPE_RECOVERY_DEPLOYED_CLOCK_IT",matches="true")
class StripePaymentRecoveryDeployedClockIT {
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private String token,secret;private int port;
    @Test void coldAdmissionAndExactCheckpointsThroughDeployedOperatorAndNaturalWorker()throws Exception{
        assertEquals("true",System.getenv("STRIPE_RECOVERY_SANDBOX"));assertEquals("true",System.getenv("STRIPE_RECOVERY_APPLICATION_IT"));
        var fixture=new StripePaymentRecoverySandboxIT();fixture.useDeployedClockAcceptance(this::accept);fixture.verifiesProtectedPaymentAndTrialEndCandidate();
    }
    private void accept(RecoverySandboxApplicationHarness fixture)throws Exception{
        var sql=fixture.sessions();var users=sql.getMapper(UserSubscriptionMapper.class);var roots=sql.getMapper(SubscriptionPaymentRecoveryMapper.class);var actions=sql.getMapper(SubscriptionRecoveryActionMapper.class);
        var db=new JdbcTemplate(sql.getSqlSessionFactory().getConfiguration().getEnvironment().getDataSource());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM subscription_payment_recoveries",Integer.class));
        var sub=Subscription.retrieve(fixture.ownedSubscriptionId());var clock=TestClock.retrieve(sub.getTestClock());Instant first=Instant.ofEpochSecond(clock.getFrozenTime());
        var properties=new RecoveryTestClockProperties();properties.setEnabled(true);properties.setDirectory(Files.createTempDirectory(Path.of(System.getProperty("recovery.evidence.dir")),"operator-").toString());
        token=UUID.randomUUID()+"-"+UUID.randomUUID();secret="whsec_"+UUID.randomUUID();properties.setOperatorToken(token);properties.setAllowedUserIds(Set.of(fixture.ownedRunId()));
        var support=new RecoveryTestClockSupport(properties,new MockEnvironment().withProperty("spring.profiles.active","recovery-test-clock"),Stripe.apiKey,new RecoveryTestClockStripe(Stripe.apiKey),users,roots,new DataSourceTransactionManager(db.getDataSource()));
        ReflectionTestUtils.setField(support,"actions",actions);
        var gateway=new SubscriptionRecoveryStripeGateway();ReflectionTestUtils.setField(gateway,"testClockSupport",support);ReflectionTestUtils.setField(fixture.recoveryService(),"stripe",gateway);
        var aspect=new AspectJProxyFactory(fixture.recoveryService());aspect.setProxyTargetClass(true);aspect.addAspect(new RecoveryTestClockCoordination(support));
        var application=(SubscriptionPaymentRecoveryService)aspect.getProxy();
        @SuppressWarnings("unchecked") var provider=(ObjectProvider<SubscriptionPaymentRecoveryService>)mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(application);when(provider.getObject()).thenReturn(application);ReflectionTestUtils.setField(support,"recovery",provider);
        ReflectionTestUtils.setField(fixture.webhookService(),"paymentRecoveryService",application);
        try(var context=new SpringApplicationBuilder(Config.class).profiles("recovery-native-http-it","recovery-test-clock").initializers(app->{
            var beans=app.getBeanFactory();beans.registerSingleton("stripeBillingWebhookService",fixture.webhookService());beans.registerSingleton("paymentRecoveryService",application);
            beans.registerSingleton("paymentRecoveryProperties",fixture.properties());beans.registerSingleton("recoveryMapper",roots);beans.registerSingleton("recoveryActionMapper",actions);
            beans.registerSingleton("recoveryTestClockSupport",support);beans.registerSingleton("recoveryTestClockProperties",properties);
            beans.registerSingleton("quotaRechargeService",mock(QuotaRechargeService.class));beans.registerSingleton("analyticsService",mock(AnalyticsService.class));beans.registerSingleton("robotNotifyAsyncService",mock(RobotNotifyAsyncService.class));
        }).run("--spring.config.location=optional:classpath:/recovery-native-http-isolated.properties","--server.address=127.0.0.1","--server.port=0","--spring.main.banner-mode=off","--logging.level.root=ERROR",
                "--stripe.allow-unsigned-webhooks=false","--stripe.webhook-secret="+secret,"--billing.payment-recovery.test-clock.enabled=true","--billing.webhook-retry.cron=*/1 * * * * ?","--billing.payment-recovery.reconciliation-cron=*/1 * * * * ?","--billing.payment-recovery.review-cron=-")){
            port=((ServletWebServerApplicationContext)context).getWebServer().getPort();
            assertEquals(401,request("GET","/runs/"+fixture.ownedRunId(),null,false).statusCode());
            command("/runs",Map.of("runId",fixture.ownedRunId(),"userId",fixture.ownedRunId(),"customerId",sub.getCustomer(),"clockId",clock.getId()));
            command("/runs/"+fixture.ownedRunId()+"/pause",Map.of("subscriptionId",sub.getId()));
            RecoveryTestClockStore.Run run=null;
            for(int i=0;i<30;i++){
                command("/runs/"+fixture.ownedRunId()+"/first-failure",null);run=new RecoveryTestClockStore(Path.of(properties.getDirectory())).read(fixture.ownedRunId());if(run.proof!=null)break;Thread.sleep(250);
            }
            assertNotNull(run.proof);assertEquals(first,run.proof.beforeClock().frozenTime());assertEquals("DECLINED",run.operations.get("first-pay").state);
            // New instance has no in-memory registry; the durable full proof remains authoritative.
            var restored=new RecoveryTestClockStore(Path.of(properties.getDirectory())).read(fixture.ownedRunId());assertEquals(run.proof,restored.proof);new RecoveryOwnedClockEvidence.Registry().register(restored.proof);
            var firstEvent=Event.retrieve(restored.proof.failureEvents().get(0).eventId());post(firstEvent);post(firstEvent);
            await(()->{var r=roots.selectByInvoice(fixture.ownedInvoiceId());return r!=null&&"ACTIVE".equals(r.getProtectionState());});
            var root=roots.selectByInvoice(fixture.ownedInvoiceId());assertEquals("OPEN_MANUAL",root.getStatus());assertEquals(DateTimeFormats.fromInstant(first),root.getFirstFailedAt());
            assertTrue(root.getContractSnapshot().contains("registered_test_clock_controlled_operation"));
            assertEquals(0,actions.selectByRecovery(root.getId()).stream().filter(a->"RETRY_PAY".equals(a.getActionType())).count());
            var status=request("GET","/runs/"+fixture.ownedRunId(),null,true);assertEquals(200,status.statusCode());var statusJson=json.readTree(status.body());assertEquals("OPEN_MANUAL",statusJson.path("recovery").path("status").asText());assertTrue(statusJson.path("recovery").path("actions").isArray());
            if("true".equals(System.getenv("STRIPE_RECOVERY_DEPLOYED_CLOCK_PAID"))){acceptPaidAnnual(fixture,support,application,root,db);return;}
            Set<String> before=chargeIds(sub.getCustomer());
            for(String point:List.of("D7","D14","D21","D28","D30_BEFORE")){
                command("/runs/"+fixture.ownedRunId()+"/advance",Map.of("checkpoint",point));
                long offset="D30_BEFORE".equals(point)?30*86400L-1:Long.parseLong(point.substring(1))*86400L;
                assertEquals(first.plusSeconds(offset),support.localTime(fixture.ownedRunId(),sub.getId(),sub.getCustomer()));
                assertEquals("OPEN_MANUAL",roots.selectById(root.getId()).getStatus());assertEquals(before,chargeIds(sub.getCustomer()));
                command("/runs/"+fixture.ownedRunId()+"/advance",Map.of("checkpoint",point));
            }
            command("/runs/"+fixture.ownedRunId()+"/advance",Map.of("checkpoint","D30"));
            await(()->"EXPIRED".equals(roots.selectById(root.getId()).getStatus()));
            assertEquals("canceled",Subscription.retrieve(sub.getId()).getStatus());assertEquals("void",Invoice.retrieve(fixture.ownedInvoiceId()).getStatus());
            assertEquals(before,chargeIds(sub.getCustomer()));assertEquals("free",users.selectByUser(fixture.ownedRunId()).getTier());
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM recharge_orders",Integer.class));
            fixture.assertNoLegacySideEffects();
            fixture.evidence("deployed-clock-cold-admission-pass",Map.of("caseKey",fixture.contractFixture().caseKey(),"gateway","PRODUCTION_GATEWAY_NO_TEST_SUBCLASS","proof","NATIVE_CAPTURE_PERSISTED_RESTART_VALIDATED","http","REAL_OPERATOR_FILTER_CONTROLLER_AND_SIGNED_NATIVE_EVENT_REPLAY","scheduler","NATURAL_CRON_NO_DIRECT_ACTION_RUN","checkpoints",List.of("D7","D14","D21","D28","D30_BEFORE","D30"),"decline","generic_decline","fundsFourCharges","NOT_RUN","mailBudget",0,"finalResult","NOT_RUN"));
        }
    }
    private void acceptPaidAnnual(RecoverySandboxApplicationHarness fixture,RecoveryTestClockSupport support,SubscriptionPaymentRecoveryService application,com.studyagent.infra.entity.SubscriptionPaymentRecoveryEntity root,JdbcTemplate db)throws Exception{
        assertEquals("year",fixture.contractFixture().interval());String run=fixture.ownedRunId(),subId=fixture.ownedSubscriptionId(),invoice=fixture.ownedInvoiceId();
        var users=fixture.sessions().getMapper(UserSubscriptionMapper.class);var roots=fixture.sessions().getMapper(SubscriptionPaymentRecoveryMapper.class);
        var user=users.selectByUser(run);String customer=user.getStripeCustomerId();
        command("/runs/"+run+"/advance",Map.of("checkpoint","D7"));Instant paidAt=support.localTime(run,subId,customer);
        var before=chargeIds(customer);var good=PaymentMethod.retrieve("pm_card_visa");
        var attach=Map.<String,Object>of("customer",customer);fixture.evidence("deployed-clock-paid-attach-intent",Map.of("parameters",attach,"key",run+":paid-attach"));good=good.attach(attach,options(run+":paid-attach"));
        var settings=Map.<String,Object>of("invoice_settings",Map.of("default_payment_method",good.getId()));fixture.evidence("deployed-clock-paid-method-intent",Map.of("parameters",settings,"key",run+":paid-default"));Customer.retrieve(customer).update(settings,options(run+":paid-default"));
        var pay=Map.<String,Object>of("off_session",true,"payment_method",good.getId());fixture.evidence("deployed-clock-repay-intent",Map.of("invoice",invoice,"parameters",pay,"key",run+":repay"));var paid=Invoice.retrieve(invoice).pay(pay,options(run+":repay"));assertEquals("paid",paid.getStatus());
        Event paidEvent=null;for(int i=0;i<40&&paidEvent==null;i++){for(var event:Event.list(Map.of("types",List.of("invoice.paid"),"limit",100L)).autoPagingIterable())if(invoice.equals(RecoveryTestClockStripe.id(RecoveryTestClockStripe.raw(event),"id"))){paidEvent=event;break;}if(paidEvent==null)Thread.sleep(250);}
        assertNotNull(paidEvent);post(paidEvent);post(paidEvent);
        long rootId=root.getId();await(()->{var r=roots.selectById(rootId);return "GRANTED".equals(r.getGrantState())&&"CONFIRMED".equals(r.getAnchorSyncState());});root=roots.selectById(root.getId());
        assertEquals(DateTimeFormats.fromInstant(paidAt),root.getEntitlementStartAt());Instant end=paidAt.atZone(ZoneOffset.UTC).plusYears(1).toInstant();assertEquals(DateTimeFormats.fromInstant(end),root.getEntitlementEndAt());assertEquals(end.getEpochSecond(),Subscription.retrieve(subId).getTrialEnd());
        var access=(PaymentRecoveryAccessServiceImpl)ReflectionTestUtils.getField(fixture.recoveryService(),"access");ReflectionTestUtils.setField(access,"testClockSupport",support);assertTrue(access.resolve(run,subId).paidCycleValid());
        var quotas=(com.studyagent.infra.service.PlanQuotaServiceImpl)ReflectionTestUtils.getField(fixture,"quotas");ReflectionTestUtils.setField(quotas,"paymentRecoveryAccessService",access);ReflectionTestUtils.setField(quotas,"recoveryTestClockSupport",support);
        var proxy=new org.springframework.aop.framework.ProxyFactory(quotas);proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(new DataSourceTransactionManager(db.getDataSource()),new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        var quota=(com.studyagent.service.domain.quota.PlanQuotaService)proxy.getProxy();
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM recharge_orders WHERE status='completed'",Integer.class));assertEquals(before.size()+1,chargeIds(customer).size());
        for(String point:List.of("MONTH_1_AFTER","MONTH_2_AFTER")){
            command("/runs/"+run+"/advance",Map.of("checkpoint",point));assertTrue(access.resolve(run,subId).paidCycleValid());quota.refreshAllPlanQuotasIfNeeded(run);quota.refreshAllPlanQuotasIfNeeded(run);
            int month=point.startsWith("MONTH_1")?1:2;assertEquals(3*month,db.queryForObject("SELECT COUNT(*) FROM quota_ledger WHERE ledger_type='plan_refresh'",Integer.class));
            assertEquals(DateTimeFormats.fromInstant(paidAt.atZone(ZoneOffset.UTC).plusMonths(month).toInstant()),db.queryForObject("SELECT MIN(plan_period_start) FROM user_ai_quotas",java.time.LocalDateTime.class));
            assertEquals(DateTimeFormats.fromInstant(end),roots.selectById(root.getId()).getEntitlementEndAt());assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM recharge_orders WHERE status='completed'",Integer.class));
        }
        fixture.evidence("deployed-clock-paid-annual-pass",Map.of("caseKey",fixture.contractFixture().caseKey(),"proof","DEPLOYED_OPERATOR_NATIVE_FIRST_FAILURE","paidPeriod","ACTUAL_CLOCK_PAYMENT_PLUS_FULL_YEAR","anchor","CONFIRMED","monthlyRefresh",List.of("MONTH_1_AFTER","MONTH_2_AFTER"),"quotaRefreshCalls","REAL_TRANSACTIONAL_QUOTA_SERVICE_NO_PRESET_BALANCE","repeatGrant","NONE","mailBudget",0,"finalResult","NOT_RUN"));
    }
    private static com.stripe.net.RequestOptions options(String key){return com.stripe.net.RequestOptions.builder().setIdempotencyKey(key).setMaxNetworkRetries(0).build();}
    private Set<String> chargeIds(String customer)throws Exception{var ids=new HashSet<String>();for(var c:Charge.list(Map.of("customer",customer,"limit",100L)).autoPagingIterable()){assertEquals(Boolean.FALSE,c.getLivemode());ids.add(c.getId());}return ids;}
    private HttpResponse<String> request(String method,String path,Object body,boolean authorized)throws Exception{
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+RecoveryTestClockOperatorFilter.PREFIX+path)).timeout(Duration.ofSeconds(120)).header("Content-Type","application/json");
        if(authorized)request.header(RecoveryTestClockOperatorFilter.HEADER,token);
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return HttpClient.newHttpClient().send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private void command(String path,Object body)throws Exception{var response=request("POST",path,body,true);assertEquals(200,response.statusCode(),response.body());}
    private void post(Event event)throws Exception{
        String body=event.toJson();long at=Instant.now().getEpochSecond();var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/v1/webhook/stripe")).timeout(Duration.ofSeconds(90)).header("Content-Type","application/json").header("stripe-signature","t="+at+",v1="+HexFormat.of().formatHex(mac.doFinal((at+"."+body).getBytes(StandardCharsets.UTF_8)))).POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,response.statusCode(),response.body());
    }
    private static void await(BooleanSupplier ready)throws Exception{long end=System.nanoTime()+Duration.ofMinutes(3).toNanos();while(!ready.getAsBoolean()){if(System.nanoTime()>end)fail("Natural worker did not converge");Thread.sleep(100);}}
    @Configuration(proxyBeanMethods=false)
    @Import({StripePaymentRecoveryNativeHttpSchedulerIT.Config.class,RecoveryTestClockController.class,RecoveryTestClockOperatorFilter.class})
    static class Config{}
}
