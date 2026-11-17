package com.studyagent.infra.service.billing;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.studyagent.infra.mapper.*;
import com.zaxxer.hikari.*;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.*;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** An explicitly supplied empty local test schema only; never loads application datasource configuration. */
@EnabledIfEnvironmentVariable(named="PAYMENT_RECOVERY_DB_IT", matches="true")
class SubscriptionPaymentRecoveryMysqlIT {
    private static HikariDataSource ds;
    private static SqlSessionFactory factory;
    private static String schema;
    private static boolean created;
    private static final LocalDateTime NOW = LocalDateTime.of(2026,11,11,10,0);

    @BeforeAll static void setup() throws Exception {
        String url = System.getenv("PAYMENT_RECOVERY_TEST_JDBC_URL");
        Assumptions.assumeTrue(url != null, "Dedicated test JDBC URL is missing");
        assertTrue(url.matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/payment_recovery_test_[a-z0-9_]+\\?.*serverTimezone=Asia/Shanghai.*"), "Only isolated local test schemas are allowed");
        schema = url.split("/")[3].split("\\?")[0];
        HikariConfig config = new HikariConfig();config.setJdbcUrl(url);
        config.setUsername(System.getenv("PAYMENT_RECOVERY_TEST_DB_USER"));config.setPassword(System.getenv("PAYMENT_RECOVERY_TEST_DB_PASSWORD"));
        config.setMaximumPoolSize(4);ds = new HikariDataSource(config);
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            try (ResultSet tables = s.executeQuery("SHOW TABLES")) { assertFalse(tables.next(), "Test schema must be empty"); }
            created = true;
            String original=Files.readString(Path.of("../sql/057_user_subscriptions_and_webhook_events.sql"));
            int start=original.indexOf("CREATE TABLE IF NOT EXISTS user_subscriptions");
            s.execute(original.substring(start,original.indexOf(";\n",start)));
            int inbox=original.indexOf("CREATE TABLE IF NOT EXISTS stripe_webhook_events");s.execute(original.substring(inbox,original.indexOf(";\n",inbox)));
            String integrity=Files.readString(Path.of("../sql/069_commercialization_integrity.sql"));int inboxAlter=integrity.indexOf("ALTER TABLE stripe_webhook_events");s.execute(integrity.substring(inboxAlter,integrity.indexOf(";\n",inboxAlter)));
            for(String migration:java.util.List.of("066_manual_subscription_upgrade_checkout.sql","069_commercialization_integrity.sql","073_user_subscriptions_intro_trial_fields.sql")){
                String body=Files.readString(Path.of("../sql/"+migration));int alter=body.indexOf("ALTER TABLE user_subscriptions");
                s.execute(body.substring(alter,body.indexOf(";\n",alter)));
            }
            s.execute("ALTER TABLE user_subscriptions ADD COLUMN pricing_zone VARCHAR(8), ADD COLUMN price_version VARCHAR(32), ADD COLUMN currency CHAR(3), ADD COLUMN pricing_country CHAR(2)");
            s.execute("CREATE TABLE subscription_price_rollouts(id BIGINT PRIMARY KEY,contract_kind VARCHAR(32),clerk_user_id VARCHAR(128),stripe_subscription_id VARCHAR(128),status VARCHAR(24),status_reason VARCHAR(255),updated_at DATETIME(3))");
            created = true;
        }
        // Reuse production add-on DDL, including integrity columns read by the real mapper.
        try(Connection c=ds.getConnection();Statement q=c.createStatement()){
            String ddl=Files.readString(Path.of("../sql/059_user_addon_grants.sql"));
            int start=ddl.indexOf("CREATE TABLE IF NOT EXISTS user_addon_grants");
            q.execute(ddl.substring(start,ddl.indexOf(';',start)));
            String integrity=Files.readString(Path.of("../sql/069_commercialization_integrity.sql"));
            int alter=integrity.indexOf("ALTER TABLE user_addon_grants");
            q.execute(integrity.substring(alter,integrity.indexOf(';',alter)));
        }
        // Execute production Study Pass DDL; include every field selected by the real entity mapper.
        try(Connection c=ds.getConnection();Statement q=c.createStatement()){
            String ddl=Files.readString(Path.of("../sql/086_study_passes.sql"));
            for(String table:java.util.List.of("study_pass_products","study_passes")){
                int start=ddl.indexOf("CREATE TABLE IF NOT EXISTS "+table+" (");
                q.execute(ddl.substring(start,ddl.indexOf(';',start)));
            }
            String recurring=Files.readString(Path.of("../sql/087_study_pass_subscriptions.sql")).replaceAll("(?m)^--.*$", "");
            for(String sql:recurring.split(";"))if(!sql.isBlank())q.execute(sql);
            String pricing=Files.readString(Path.of("../sql/091_zone_pricing_tables.sql"));
            var blocks=java.util.regex.Pattern.compile("(?s)SET @zp_ddl = IF\\(.*?DEALLOCATE PREPARE zp_stmt;").matcher(pricing);
            while(blocks.find())if(blocks.group().contains("table_name='study_passes'"))
                for(String sql:blocks.group().split(";"))if(!sql.isBlank())q.execute(sql);
        }
        migrate(); migrate();
        try(Connection c=ds.getConnection();Statement q=c.createStatement()){
            q.execute("CREATE TABLE user_ai_quotas(id BIGINT PRIMARY KEY AUTO_INCREMENT,clerk_user_id VARCHAR(128) NOT NULL,feature_code VARCHAR(64) NOT NULL,free_balance BIGINT,free_period_start DATETIME,free_period_end DATETIME,plan_balance BIGINT,plan_period_start DATETIME,plan_period_end DATETIME,paid_balance BIGINT,version INT NOT NULL DEFAULT 0,created_at DATETIME,updated_at DATETIME,UNIQUE KEY uk_quota_user_feature(clerk_user_id,feature_code))");
            q.execute("CREATE TABLE quota_ledger(id BIGINT PRIMARY KEY AUTO_INCREMENT,ledger_no VARCHAR(128),clerk_user_id VARCHAR(128),feature_code VARCHAR(64),ledger_type VARCHAR(64),amount BIGINT,source_type VARCHAR(64),source_id VARCHAR(128),idempotency_key VARCHAR(255),subscription_id VARCHAR(128),invoice_id VARCHAR(128),free_balance_after BIGINT,plan_balance_after BIGINT,addon_balance_after BIGINT,paid_balance_after BIGINT,biz_context JSON,created_at DATETIME,UNIQUE KEY uk_ledger_idem(feature_code,ledger_type,idempotency_key))");
            q.execute("CREATE TABLE recharge_orders(id BIGINT PRIMARY KEY AUTO_INCREMENT,order_no VARCHAR(128) UNIQUE,order_type VARCHAR(64),clerk_user_id VARCHAR(128),feature_code VARCHAR(64),package_code VARCHAR(64),plan_code VARCHAR(64),target_plan_code VARCHAR(64),addon_code VARCHAR(64),quota_amount BIGINT,validity_months_snapshot INT,price_cents INT,quoted_amount_cents INT,upgrade_charge_type VARCHAR(64),currency VARCHAR(3),stripe_session_id VARCHAR(128),stripe_payment_intent_id VARCHAR(128),stripe_invoice_id VARCHAR(128) UNIQUE,stripe_subscription_id VARCHAR(128),status VARCHAR(64),failure_reason VARCHAR(255),paid_at DATETIME,upgrade_effective_at DATETIME,switch_attempts INT,biz_context JSON,created_at DATETIME,updated_at DATETIME)");
        }
        var bean = new MybatisSqlSessionFactoryBean();bean.setDataSource(ds);
        bean.setPlugins(new com.studyagent.infra.config.MyBatisConfig().mybatisPlusInterceptor());factory = bean.getObject();
        factory.getConfiguration().addMapper(UserSubscriptionMapper.class);
        factory.getConfiguration().addMapper(StripeWebhookEventMapper.class);
        factory.getConfiguration().addMapper(UserAiQuotaMapper.class);
        factory.getConfiguration().addMapper(UserAddonGrantMapper.class);
        factory.getConfiguration().addMapper(StudyPassMapper.class);
        factory.getConfiguration().addMapper(QuotaLedgerMapper.class);
        factory.getConfiguration().addMapper(RechargeOrderMapper.class);
        factory.getConfiguration().addMapper(SubscriptionRecoveryActionMapper.class);
        factory.getConfiguration().addMapper(SubscriptionPaymentRecoveryMapper.class);
        factory.getConfiguration().addMapper(SubscriptionRecoveryReplacementMapper.class);
        factory.getConfiguration().addMapper(SubscriptionPriceRolloutMapper.class);
    }
    private static void migrate() throws Exception {
        Path file = Path.of("../sql/095_subscription_payment_recovery.sql");
        String sql = Files.readString(file).replace("USE studyagent;", "USE " + schema + ";");
        try(Connection c=ds.getConnection();Statement s=c.createStatement()) {
            for(String part:sql.split(";")) if(!part.isBlank()) s.execute(part);
        }
    }
    @BeforeEach void fixtures() throws Exception {
        try(Connection c=ds.getConnection(); Statement s=c.createStatement()) {
            s.executeUpdate("DELETE FROM stripe_webhook_events");
            s.executeUpdate("DELETE FROM study_passes");
            s.executeUpdate("DELETE FROM user_addon_grants");
            s.executeUpdate("DELETE FROM quota_ledger");s.executeUpdate("DELETE FROM user_ai_quotas");s.executeUpdate("DELETE FROM recharge_orders");
            s.executeUpdate("DELETE FROM subscription_price_rollouts");
            s.executeUpdate("DELETE FROM subscription_payment_recovery_actions");
            s.executeUpdate("DELETE FROM subscription_payment_recovery_replacements");
            s.executeUpdate("DELETE FROM subscription_payment_recoveries");
            s.executeUpdate("DELETE FROM user_subscriptions");
            s.executeUpdate("INSERT INTO user_subscriptions(id,clerk_user_id) VALUES(1,'test_user')");
            s.executeUpdate("""
                INSERT INTO subscription_payment_recoveries(id,clerk_user_id,stripe_livemode,stripe_customer_id,stripe_subscription_id,
                stripe_invoice_id,stripe_invoice_status,plan_code,display_tier,billing_interval,contract_snapshot,currency,
                amount_due_minor,amount_remaining_minor,first_failed_at,deadline_at,status)
                VALUES(1,'test_user',0,'cus_test','sub_test','in_test','open','pro_monthly','pro','month','{}','usd',2900,2900,
                '2026-11-04 10:00:00','2026-12-04 10:00:00','OPEN_AUTO')
                """);
        }
    }
    @Test void duplicateFirstFailuresUseTheRealUserTransactionAndCreateOneRecovery() throws Exception {
        clearAdmissionFixture();var stripe=org.mockito.Mockito.mock(SubscriptionRecoveryStripeGateway.class);
        var ready=new CountDownLatch(2);var go=new CountDownLatch(1);var service=admissionService(stripe,null);
        org.mockito.Mockito.when(stripe.readFirstFailure(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenAnswer(call->{
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            ready.countDown();assertTrue(go.await(5,TimeUnit.SECONDS));
            return new SubscriptionRecoveryStripeGateway.FirstFailure(NOW.atZone(com.studyagent.common.datetime.DateTimeFormats.APP_ZONE).toInstant(),"test_invoice_failure_event","evt_admit","ch_admit");
        });
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try{
            Callable<Boolean> admission=()->{try{return service.tryProcessEvent(admissionEvent());}catch(IllegalStateException stale){assertTrue(stale.getMessage().startsWith("RECOVERY_ADMISSION_INCOMPLETE_EVIDENCE"));return false;}};
            var first=pool.submit(admission);var second=pool.submit(admission);assertTrue(ready.await(5,TimeUnit.SECONDS));go.countDown();
            boolean a=first.get(10,TimeUnit.SECONDS),b=second.get(10,TimeUnit.SECONDS);assertTrue(a||b);
            assertTrue(service.tryProcessEvent(admissionEvent()));
            try(Connection c=ds.getConnection();Statement q=c.createStatement()){
                try(ResultSet rows=q.executeQuery("SELECT COUNT(*) FROM subscription_payment_recoveries")){assertTrue(rows.next());assertEquals(1,rows.getInt(1));}
                try(ResultSet rows=q.executeQuery("SELECT COUNT(*) FROM subscription_payment_recovery_actions WHERE action_type='RETRY_PAY'")){assertTrue(rows.next());assertEquals(4,rows.getInt(1));}
                try(ResultSet rows=q.executeQuery("SELECT COUNT(*) FROM subscription_payment_recovery_actions WHERE action_type IN ('CAPTURE_FAILURE','NOTIFY_INTERNAL_FAILURE')")){assertTrue(rows.next());assertEquals(2,rows.getInt(1));}
                try(ResultSet rows=q.executeQuery("SELECT u.payment_recovery_id,u.grace_end_at,r.id FROM user_subscriptions u JOIN subscription_payment_recoveries r ON u.payment_recovery_id=r.id WHERE u.clerk_user_id='test_user'")){assertTrue(rows.next(),"The recovery pointer must commit with its root");assertEquals(rows.getLong(3),rows.getLong(1));assertNull(rows.getObject(2),"Admission must persist SQL NULL for the old grace");}
            }
            org.mockito.Mockito.verify(stripe,org.mockito.Mockito.never()).execute(org.mockito.ArgumentMatchers.any());
        }finally{go.countDown();pool.shutdownNow();}
    }
    @Test void legacyInvoiceSourceEvidenceCommitsWithTheRecoveryPointerInTheRealTransaction() throws Exception {
        clearAdmissionFixture();var stripe=org.mockito.Mockito.mock(SubscriptionRecoveryStripeGateway.class);var service=admissionService(stripe,null);var snapshot=stripe.readInvoice("in_admit");
        snapshot.subscription().setMetadata(java.util.Map.of("purchase_type","subscription_intro_trial","plan_code","pro_trial_to_monthly","conversion_plan_code","pro_monthly"));snapshot.invoice().setBillingReason("subscription_update");
        ((PaymentRecoveryProperties)org.springframework.test.util.ReflectionTestUtils.getField(service,"config")).setVerifiedCohorts(java.util.Set.of("pro_trial_to_monthly:pro_monthly:month_1:NO_SCHEDULE:RENEW"));
        var intro=com.stripe.net.ApiResource.GSON.fromJson(snapshot.invoice().toJson(),com.stripe.model.Invoice.class);intro.setId("in_intro_admit");intro.setCreated(snapshot.invoice().getCreated()-7*86400L);intro.setBillingReason("subscription_create");intro.setStatus("paid");intro.setAmountPaid(299L);intro.setAmountRemaining(0L);intro.setPaidOutOfBand(false);intro.setPaymentIntent("pi_intro_admit");
        var line=com.stripe.net.ApiResource.GSON.fromJson(snapshot.lines().get(0).toJson(),com.stripe.model.InvoiceLineItem.class);line.getPrice().setId("price_intro_admit");line.getPrice().getRecurring().setInterval("week");line.getPeriod().setStart(snapshot.invoice().getCreated()-7*86400L);line.getPeriod().setEnd(snapshot.invoice().getCreated());
        var lines=new com.stripe.model.InvoiceLineItemCollection();lines.setHasMore(false);lines.setData(java.util.List.of(line));intro.setLines(lines);
        org.mockito.Mockito.when(stripe.listInvoices("sub_admit")).thenAnswer(call->{assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());return java.util.List.of(intro,snapshot.invoice());});
        org.mockito.Mockito.when(stripe.listSubscriptionSchedules("cus_admit","sub_admit")).thenAnswer(call->{assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());return java.util.List.of();});
        var plans=(SubscriptionPlanMapper)org.springframework.test.util.ReflectionTestUtils.getField(service,"plans");com.studyagent.infra.testutil.MybatisPlusTableInfoTestHelper.initTableInfo(com.studyagent.infra.entity.SubscriptionPlanEntity.class);
        org.mockito.Mockito.when(plans.selectList(org.mockito.ArgumentMatchers.any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenAnswer(call->{
            var query=(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.studyagent.infra.entity.SubscriptionPlanEntity>)call.getArgument(0);query.getSqlSegment();var plan=new com.studyagent.infra.entity.SubscriptionPlanEntity();plan.setTier("pro");plan.setPlanCode(query.getParamNameValuePairs().containsValue("price_intro_admit")?"pro_trial_to_monthly":"pro_monthly");return java.util.List.of(plan);
        });
        assertTrue(service.tryProcessEvent(admissionEvent()));
        try(var session=factory.openSession(true)){
            var root=session.getMapper(SubscriptionPaymentRecoveryMapper.class).selectByInvoice("in_admit");assertNotNull(root);assertTrue(root.getContractSnapshot().contains("historical_invoice_pair"));assertTrue(root.getContractSnapshot().contains("in_intro_admit"));assertTrue(root.getContractSnapshot().contains("pro_trial_to_monthly:pro_monthly:month_1:NO_SCHEDULE:RENEW"));var user=session.getMapper(UserSubscriptionMapper.class).selectByUser("test_user");assertEquals(root.getId(),user.getPaymentRecoveryId());assertNull(user.getGraceEndAt());assertEquals(4,session.getMapper(SubscriptionRecoveryActionMapper.class).selectByRecovery(root.getId()).stream().filter(a->"RETRY_PAY".equals(a.getActionType())).count());
        }
        org.mockito.Mockito.verify(stripe,org.mockito.Mockito.never()).execute(org.mockito.ArgumentMatchers.any());
    }
    @Test void aConcurrentUserVersionChangeRejectsStaleAdmissionThenRereadsBeforeOneSuccessfulTakeover() throws Exception {
        clearAdmissionFixture();var stripe=org.mockito.Mockito.mock(SubscriptionRecoveryStripeGateway.class);var service=admissionService(stripe,null);
        var snapshot=stripe.readInvoice("in_admit");var proof=stripe.readFirstFailure(snapshot,admissionEvent());var changed=new java.util.concurrent.atomic.AtomicBoolean();
        org.mockito.Mockito.doAnswer(call->{
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            if(changed.compareAndSet(false,true))try(Connection c=ds.getConnection();Statement q=c.createStatement()){assertEquals(1,q.executeUpdate("UPDATE user_subscriptions SET version=version+1 WHERE id=1"));}
            return proof;
        }).when(stripe).readFirstFailure(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.clearInvocations(stripe);
        assertTrue(assertThrows(IllegalStateException.class,()->service.tryProcessEvent(admissionEvent())).getMessage().contains("INCOMPLETE_EVIDENCE:in_admit"));
        assertEquals(0,scalar("SELECT COUNT(*) FROM subscription_payment_recoveries"));assertEquals(0,scalar("SELECT COUNT(*) FROM subscription_payment_recovery_actions"));
        try(var sql=factory.openSession(true)){var user=sql.getMapper(UserSubscriptionMapper.class).selectByUser("test_user");assertNull(user.getPaymentRecoveryId());assertEquals(NOW.plusDays(19),user.getGraceEndAt());}
        assertTrue(service.tryProcessEvent(admissionEvent()));
        assertEquals(1,scalar("SELECT COUNT(*) FROM subscription_payment_recoveries"));assertEquals(4,scalar("SELECT COUNT(*) FROM subscription_payment_recovery_actions WHERE action_type='RETRY_PAY'"));
        try(var sql=factory.openSession(true)){var user=sql.getMapper(UserSubscriptionMapper.class).selectByUser("test_user");var root=sql.getMapper(SubscriptionPaymentRecoveryMapper.class).selectByInvoice("in_admit");assertEquals(root.getId(),user.getPaymentRecoveryId());assertNull(user.getGraceEndAt());assertEquals(NOW,root.getFirstFailedAt());assertEquals(NOW.plusDays(30),root.getDeadlineAt());assertTrue(root.getContractSnapshot().contains("price_admit"));}
        org.mockito.Mockito.verify(stripe,org.mockito.Mockito.times(2)).readInvoice("in_admit");org.mockito.Mockito.verify(stripe,org.mockito.Mockito.times(2)).readFirstFailure(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());org.mockito.Mockito.verify(stripe,org.mockito.Mockito.never()).execute(org.mockito.ArgumentMatchers.any());
    }
    @Test void aFailedOutboxInsertRollsBackRecoveryPointerGraceAndAllPriorActions() throws Exception {
        clearAdmissionFixture();var template=new org.mybatis.spring.SqlSessionTemplate(factory);var actual=template.getMapper(SubscriptionRecoveryActionMapper.class);
        var failing=org.mockito.Mockito.mock(SubscriptionRecoveryActionMapper.class,org.mockito.AdditionalAnswers.delegatesTo(actual));
        var inserts=new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer(call->{if(inserts.incrementAndGet()==3)throw new IllegalStateException("TEST_OUTBOX_INSERT_FAILURE");return actual.insert(call.getArgument(0));}).when(failing).insert(org.mockito.ArgumentMatchers.any(com.studyagent.infra.entity.SubscriptionRecoveryActionEntity.class));
        var stripe=org.mockito.Mockito.mock(SubscriptionRecoveryStripeGateway.class);var service=admissionService(stripe,failing);
        assertEquals("TEST_OUTBOX_INSERT_FAILURE",assertThrows(IllegalStateException.class,()->service.tryProcessEvent(admissionEvent())).getMessage());
        try(Connection c=ds.getConnection();Statement q=c.createStatement()){
            for(String table:java.util.List.of("subscription_payment_recoveries","subscription_payment_recovery_actions"))try(ResultSet rows=q.executeQuery("SELECT COUNT(*) FROM "+table)){assertTrue(rows.next());assertEquals(0,rows.getInt(1));}
            try(ResultSet rows=q.executeQuery("SELECT payment_recovery_id,grace_end_at FROM user_subscriptions WHERE clerk_user_id='test_user'")){assertTrue(rows.next());assertNull(rows.getObject(1));assertNotNull(rows.getObject(2));}
        }
        org.mockito.Mockito.verify(stripe,org.mockito.Mockito.never()).execute(org.mockito.ArgumentMatchers.any());
    }
    @Test void terminalReplacementReleasesItsSlotThroughTheActualEntityUpdate() throws Exception {
        try(Connection c=ds.getConnection();Statement q=c.createStatement()){insertReplacement(q,1,"before_expiry");}
        try(var session=factory.openSession(true)){
            var mapper=session.getMapper(SubscriptionRecoveryReplacementMapper.class);var replacement=mapper.selectByOrder("before_expiry");
            replacement.setState("EXPIRED");replacement.setActiveSubscriptionKey(null);replacement.setClosedAt(NOW);assertEquals(1,mapper.updateById(replacement));
            assertNull(mapper.selectActive("sub_test"),"Expired entity updates must clear the unique active slot in SQL");
        }
        try(Connection c=ds.getConnection();Statement q=c.createStatement()){insertReplacement(q,2,"after_expiry");}
    }
    @Test void stoppedReconciliationPersistsNullThroughEntityUpdates() throws Exception {
        try(Connection c=ds.getConnection();Statement q=c.createStatement()){
            q.executeUpdate("UPDATE subscription_payment_recoveries SET status='PAID',next_reconcile_at='2026-11-11 09:00:00' WHERE id=1");
            insertAction(q,1,"release-lease",1);q.executeUpdate("UPDATE subscription_payment_recovery_actions SET status='DISPATCHING',lease_token='old-worker',lease_until='2026-11-11 09:00:00' WHERE id=1");
        }
        try(var session=factory.openSession(true)){
            var roots=session.getMapper(SubscriptionPaymentRecoveryMapper.class);var root=roots.selectById(1L);root.setNextReconcileAt(null);assertEquals(1,roots.updateById(root));
            assertTrue(roots.selectDueIds(NOW,10).isEmpty(),"A stopped paid recovery cannot keep polling from an old SQL timestamp");
        }
    }
    @Test void releasedLeasesPersistNullThroughEntityUpdates() throws Exception {
        try(Connection c=ds.getConnection();Statement q=c.createStatement()){
            insertAction(q,1,"release-lease",1);q.executeUpdate("UPDATE subscription_payment_recovery_actions SET status='DISPATCHING',lease_token='old-worker',lease_until='2026-11-11 09:00:00' WHERE id=1");
        }
        try(var session=factory.openSession(true)){
            var mapper=session.getMapper(SubscriptionRecoveryActionMapper.class);var action=mapper.selectById(1L);action.setStatus("UNKNOWN");action.setLeaseToken(null);action.setLeaseUntil(null);assertEquals(1,mapper.updateById(action));
            var saved=mapper.selectById(1L);assertNull(saved.getLeaseToken());assertNull(saved.getLeaseUntil());
        }
    }
    private void clearAdmissionFixture() throws Exception {
        try(Connection c=ds.getConnection();Statement q=c.createStatement()){
            q.executeUpdate("DELETE FROM subscription_payment_recoveries");
            q.executeUpdate("UPDATE user_subscriptions SET stripe_customer_id='cus_admit',stripe_subscription_id='sub_admit',tier='pro',plan_code='pro_monthly',status='past_due',grace_end_at='2026-11-30 10:00:00' WHERE id=1");
        }
    }
    private SubscriptionPaymentRecoveryService admissionService(SubscriptionRecoveryStripeGateway stripe,SubscriptionRecoveryActionMapper supplied){
        var sessions=new org.mybatis.spring.SqlSessionTemplate(factory);var config=new PaymentRecoveryProperties();
        config.setAcceptNew(true);config.setAcceptInvoicesCreatedAfter(NOW.minusDays(1).atZone(com.studyagent.common.datetime.DateTimeFormats.APP_ZONE).toInstant());config.setAllowedUserIds(java.util.Set.of("test_user"));config.setVerifiedCohorts(java.util.Set.of("formal:pro_monthly:month_1:NO_SCHEDULE:RENEW"));
        var plans=org.mockito.Mockito.mock(SubscriptionPlanMapper.class);var plan=new com.studyagent.infra.entity.SubscriptionPlanEntity();plan.setPlanCode("pro_monthly");plan.setTier("pro");org.mockito.Mockito.when(plans.selectList(org.mockito.ArgumentMatchers.any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(java.util.List.of(plan));
        var snapshot=admissionSnapshot();
        org.mockito.Mockito.when(stripe.readInvoice(org.mockito.ArgumentMatchers.anyString())).thenAnswer(call->{assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());return snapshot;});
        org.mockito.Mockito.when(stripe.readInvoiceHeader("in_admit")).thenReturn(snapshot.invoice());org.mockito.Mockito.when(stripe.listInvoices("sub_admit")).thenReturn(java.util.List.of(snapshot.invoice()));
        org.mockito.Mockito.when(stripe.readFirstFailure(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenReturn(new SubscriptionRecoveryStripeGateway.FirstFailure(snapshot.businessNow(),"test_invoice_failure_event","evt_admit","ch_admit"));
        return new SubscriptionPaymentRecoveryService(sessions.getMapper(UserSubscriptionMapper.class),sessions.getMapper(SubscriptionPaymentRecoveryMapper.class),supplied==null?sessions.getMapper(SubscriptionRecoveryActionMapper.class):supplied,sessions.getMapper(SubscriptionRecoveryReplacementMapper.class),plans,org.mockito.Mockito.mock(RechargeOrderMapper.class),stripe,config,org.mockito.Mockito.mock(com.studyagent.service.domain.billing.PaymentRecoveryAccessService.class),new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));
    }
    private static com.stripe.model.Event admissionEvent(){return com.stripe.net.ApiResource.GSON.fromJson("{\"id\":\"evt_admit\",\"type\":\"invoice.payment_failed\",\"created\":1794362400,\"data\":{\"object\":{\"id\":\"in_admit\",\"object\":\"invoice\",\"subscription\":\"sub_admit\",\"charge\":\"ch_admit\"}}}",com.stripe.model.Event.class);}
    private static SubscriptionRecoveryStripeGateway.Snapshot admissionSnapshot(){
        var invoice=com.stripe.net.ApiResource.GSON.fromJson("{\"id\":\"in_admit\",\"object\":\"invoice\",\"customer\":\"cus_admit\",\"subscription\":\"sub_admit\",\"status\":\"open\",\"created\":1794362400,\"billing_reason\":\"subscription_cycle\",\"currency\":\"usd\",\"amount_due\":2900,\"amount_paid\":0,\"amount_remaining\":2900,\"livemode\":false,\"auto_advance\":false,\"payment_intent\":\"pi_admit\"}",com.stripe.model.Invoice.class);
        var subscription=com.stripe.net.ApiResource.GSON.fromJson("{\"id\":\"sub_admit\",\"customer\":\"cus_admit\",\"status\":\"past_due\",\"cancel_at_period_end\":false,\"pause_collection\":{\"behavior\":\"keep_as_draft\"},\"items\":{\"data\":[{\"price\":{\"id\":\"price_admit\"},\"quantity\":1}]}}",com.stripe.model.Subscription.class);
        var line=com.stripe.net.ApiResource.GSON.fromJson("{\"id\":\"il_admit\",\"type\":\"subscription\",\"subscription\":\"sub_admit\",\"subscription_item\":\"si_admit\",\"proration\":false,\"currency\":\"usd\",\"price\":{\"id\":\"price_admit\",\"type\":\"recurring\",\"currency\":\"usd\",\"recurring\":{\"interval\":\"month\",\"interval_count\":1}},\"period\":{\"start\":1794362400,\"end\":1796954400},\"quantity\":1}",com.stripe.model.InvoiceLineItem.class);
        var intent=com.stripe.net.ApiResource.GSON.fromJson("{\"id\":\"pi_admit\",\"status\":\"requires_payment_method\",\"latest_charge\":\"ch_admit\",\"last_payment_error\":{\"decline_code\":\"insufficient_funds\"}}",com.stripe.model.PaymentIntent.class);
        var charge=com.stripe.net.ApiResource.GSON.fromJson("{\"object\":\"charge\",\"id\":\"ch_admit\",\"customer\":\"cus_admit\",\"invoice\":\"in_admit\",\"payment_intent\":\"pi_admit\",\"status\":\"failed\"}",com.stripe.model.Charge.class);
        return new SubscriptionRecoveryStripeGateway.Snapshot(invoice,subscription,null,java.util.List.of(line),intent,charge,NOW.atZone(com.studyagent.common.datetime.DateTimeFormats.APP_ZONE).toInstant());
    }
    @Test void unknownScheduleInboxRetriesWithCompleteEvidenceWithoutRebasingItsFirstFailure() throws Exception {
        var fixture=incompleteScheduleInboxFixture();var event=admissionEvent();
        assertTrue(assertThrows(IllegalStateException.class,()->fixture.inbox().process(event)).getMessage().contains("UNKNOWN_SCHEDULE"));
        assertUnknownInboxHasNoRecoveryLock("failed",1);assertNotNull(inboxRow().getNextRetryAt());
        var old=fixture.snapshot().get();var complete=com.stripe.net.ApiResource.GSON.fromJson("{\"id\":\"sched_admit\",\"customer\":\"cus_admit\",\"subscription\":\"sub_admit\",\"status\":\"active\",\"end_behavior\":\"release\",\"default_settings\":{\"collection_method\":\"charge_automatically\"},\"current_phase\":{\"start_date\":1794362400,\"end_date\":1796954400},\"phases\":[{\"start_date\":1794362400,\"end_date\":1796954400,\"items\":[{\"price\":\"price_admit\",\"quantity\":1}]}]}",com.stripe.model.SubscriptionSchedule.class);
        fixture.snapshot().set(new SubscriptionRecoveryStripeGateway.Snapshot(old.invoice(),old.subscription(),complete,old.lines(),old.paymentIntent(),old.charge(),old.businessNow()));
        makeInboxDue();assertEquals(1,fixture.inbox().retryDueEvents(10));assertFalse(fixture.inbox().process(event));assertEquals(0,fixture.inbox().retryDueEvents(10));
        var saved=inboxRow();assertEquals("succeeded",saved.getStatus());assertEquals(2,saved.getAttemptCount());assertNull(saved.getLastError());assertNull(saved.getNextRetryAt());
        assertEquals(1,scalar("SELECT COUNT(*) FROM subscription_payment_recoveries"));assertEquals(4,scalar("SELECT COUNT(*) FROM subscription_payment_recovery_actions WHERE action_type='RETRY_PAY'"));
        try(var session=factory.openSession(true)){
            var user=session.getMapper(UserSubscriptionMapper.class).selectByUser("test_user");var root=session.getMapper(SubscriptionPaymentRecoveryMapper.class).selectById(user.getPaymentRecoveryId());
            assertEquals(NOW,root.getFirstFailedAt());assertEquals(NOW.plusDays(30),root.getDeadlineAt());assertNull(user.getGraceEndAt());
            var retry=session.getMapper(SubscriptionRecoveryActionMapper.class).selectByRecovery(root.getId()).stream().filter(a->"RETRY_PAY".equals(a.getActionType())).map(com.studyagent.infra.entity.SubscriptionRecoveryActionEntity::getScheduledAt).sorted().toList();
            assertEquals(java.util.List.of(NOW.plusDays(7),NOW.plusDays(14),NOW.plusDays(21),NOW.plusDays(28)),retry);
        }
        org.mockito.Mockito.verify(fixture.stripe(),org.mockito.Mockito.never()).execute(org.mockito.ArgumentMatchers.any());org.mockito.Mockito.verifyNoInteractions(fixture.robot());
    }
    @Test void persistentUnknownScheduleUsesTheRealInboxDeadLetterAndNotifiesOnceWithoutLegacyGrant() throws Exception {
        var fixture=incompleteScheduleInboxFixture();assertThrows(IllegalStateException.class,()->fixture.inbox().process(admissionEvent()));
        for(int attempt=2;attempt<=8;attempt++){makeInboxDue();assertEquals(0,fixture.inbox().retryDueEvents(10));}
        assertUnknownInboxHasNoRecoveryLock("dead_letter",8);assertNotNull(inboxRow().getDeadLetteredAt());assertNull(inboxRow().getNextRetryAt());assertFalse(fixture.inbox().process(admissionEvent()));assertEquals(0,fixture.inbox().retryDueEvents(10));
        var request=org.mockito.ArgumentCaptor.forClass(com.studyagent.service.domain.billing.BillingReviewNotifyRequest.class);
        org.mockito.Mockito.verify(fixture.robot(),org.mockito.Mockito.times(1)).notifyBillingReviewRequired(request.capture());assertEquals("dead_letter",request.getValue().getStatus());assertEquals("evt_admit",request.getValue().getStripeEventId());assertEquals(8,request.getValue().getAttemptCount());
        org.mockito.Mockito.verify(fixture.stripe(),org.mockito.Mockito.never()).execute(org.mockito.ArgumentMatchers.any());
    }
    private record InboxFixture(StripeBillingWebhookService inbox,SubscriptionRecoveryStripeGateway stripe,com.studyagent.service.domain.billing.BillingRobotNotifyGateway robot,java.util.concurrent.atomic.AtomicReference<SubscriptionRecoveryStripeGateway.Snapshot> snapshot){}
    private InboxFixture incompleteScheduleInboxFixture() throws Exception {
        clearAdmissionFixture();var stripe=org.mockito.Mockito.mock(SubscriptionRecoveryStripeGateway.class);var recovery=admissionService(stripe,null);var initial=stripe.readInvoice("in_admit");initial.subscription().setSchedule("sched_admit");
        var incomplete=com.stripe.net.ApiResource.GSON.fromJson("{\"id\":\"sched_admit\",\"customer\":\"cus_admit\",\"subscription\":\"sub_admit\",\"status\":\"active\"}",com.stripe.model.SubscriptionSchedule.class);
        var observed=new java.util.concurrent.atomic.AtomicReference<>(new SubscriptionRecoveryStripeGateway.Snapshot(initial.invoice(),initial.subscription(),incomplete,initial.lines(),initial.paymentIntent(),initial.charge(),initial.businessNow().plus(java.time.Duration.ofDays(2))));
        org.mockito.Mockito.when(stripe.readInvoice("in_admit")).thenAnswer(call->{assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());return observed.get();});
        org.mockito.Mockito.when(stripe.readFirstFailure(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenAnswer(call->{assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());return new SubscriptionRecoveryStripeGateway.FirstFailure(initial.businessNow(),"test_invoice_failure_event","evt_first","ch_admit",observed.get().businessNow());});
        ((PaymentRecoveryProperties)org.springframework.test.util.ReflectionTestUtils.getField(recovery,"config")).setVerifiedCohorts(java.util.Set.of("formal:pro_monthly:month_1:EQUIVALENT_SCHEDULE:RENEW"));
        var sessions=new org.mybatis.spring.SqlSessionTemplate(factory);var robot=org.mockito.Mockito.mock(com.studyagent.service.domain.billing.BillingRobotNotifyGateway.class);
        @SuppressWarnings("unchecked") var robots=(org.springframework.beans.factory.ObjectProvider<com.studyagent.service.domain.billing.BillingRobotNotifyGateway>)org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);org.mockito.Mockito.when(robots.getIfAvailable()).thenReturn(robot);
        var pricing=org.mockito.Mockito.mock(BillingPricingService.class);var zone=org.mockito.Mockito.mock(ZonePricingSelectionService.class);
        var inbox=new StripeBillingWebhookService(sessions.getMapper(StripeWebhookEventMapper.class),sessions.getMapper(UserSubscriptionMapper.class),org.mockito.Mockito.mock(SubscriptionPlanMapper.class),org.mockito.Mockito.mock(AddonPackageDefMapper.class),sessions.getMapper(RechargeOrderMapper.class),org.mockito.Mockito.mock(com.studyagent.common.analytics.AnalyticsService.class),null,robots,null,new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds),pricing,zone);
        org.springframework.test.util.ReflectionTestUtils.setField(inbox,"paymentRecoveryService",recovery);
        return new InboxFixture(inbox,stripe,robot,observed);
    }
    private com.studyagent.infra.entity.StripeWebhookEventEntity inboxRow(){try(var session=factory.openSession(true)){return session.getMapper(StripeWebhookEventMapper.class).selectById("evt_admit");}}
    private void makeInboxDue() throws Exception {try(Connection c=ds.getConnection();Statement q=c.createStatement()){assertEquals(1,q.executeUpdate("UPDATE stripe_webhook_events SET next_retry_at=DATE_SUB(NOW(),INTERVAL 1 SECOND) WHERE event_id='evt_admit' AND status='failed'"));}}
    private void assertUnknownInboxHasNoRecoveryLock(String expected,int attempts)throws Exception{
        var row=inboxRow();assertEquals(expected,row.getStatus());assertEquals(attempts,row.getAttemptCount());assertTrue(row.getLastError().contains("UNKNOWN_SCHEDULE"));assertNotNull(row.getPayloadJson());
        for(String table:java.util.List.of("subscription_payment_recoveries","subscription_payment_recovery_actions","subscription_payment_recovery_replacements","recharge_orders","user_ai_quotas","quota_ledger"))assertEquals(0,scalar("SELECT COUNT(*) FROM "+table));
        try(var session=factory.openSession(true)){var user=session.getMapper(UserSubscriptionMapper.class).selectByUser("test_user");assertNull(user.getPaymentRecoveryId());assertEquals(java.time.LocalDateTime.of(2026,11,30,10,0),user.getGraceEndAt());assertNull(user.getCurrentPeriodEnd());}
    }

    @Test void duplicatePaidCallbacksCommitOneOrderOneQuotaGrantAndOneAnchorIntent() throws Exception {
        var fixture=paymentFixture(false);var paid=paidSnapshot("in_test","hkd",18900,false);var service=fixture.service();
        paymentReads(fixture.stripe(),java.util.Map.of("in_test",paid),true);
        concurrently(()->service.tryProcessEvent(paidEvent("in_test")),()->service.tryProcessEvent(paidEvent("in_test")));
        assertGranted("in_test",1,1,false);org.mockito.Mockito.verify(fixture.stripe(),org.mockito.Mockito.never()).execute(org.mockito.ArgumentMatchers.any());
    }
    @Test void simultaneousMainAndChildPaymentsKeepOneSourceAndOneAddonResume() throws Exception {
        var fixture=paymentFixture(false);insertPaidChild();var main=paidSnapshot("in_test","hkd",18900,false);var child=paidSnapshot("in_child","hkd",18900,false);
        paymentReads(fixture.stripe(),java.util.Map.of("in_test",main,"in_child",child),true);
        concurrently(()->fixture.service().tryProcessEvent(paidEvent("in_test")),()->fixture.service().tryProcessEvent(paidEvent("in_child")));
        try(var session=factory.openSession(true)){var root=session.getMapper(SubscriptionPaymentRecoveryMapper.class).selectById(1L);assertTrue(java.util.Set.of("in_test","in_child").contains(root.getEntitlementInvoiceId()));assertGranted(root.getEntitlementInvoiceId(),2,1,false);assertEquals("OPEN",root.getReviewState());}
    }
    @Test void childPaymentSurvivesTheUnpaidMainInvoiceBeingVoided() throws Exception {
        var fixture=paymentFixture(false);insertPaidChild();var main=paidSnapshot("in_test","hkd",18900,false);main.invoice().setStatus("open");main.invoice().setAmountPaid(0L);main.invoice().setAmountRemaining(18900L);var child=paidSnapshot("in_child","hkd",18900,false);
        paymentReads(fixture.stripe(),java.util.Map.of("in_test",main,"in_child",child),false);
        assertTrue(fixture.service().tryProcessEvent(paidEvent("in_child")));assertGranted("in_child",1,1,false);
        main.invoice().setStatus("void");assertTrue(fixture.service().tryProcessEvent(paidEvent("in_test")));assertGranted("in_child",1,1,false);
        try(var session=factory.openSession(true)){assertEquals("void",session.getMapper(SubscriptionPaymentRecoveryMapper.class).selectById(1L).getStripeInvoiceStatus());}
    }
    @Test void annualCashGrantsOneYearButOnlyOneMonthlyQuotaWindow() throws Exception {
        var fixture=paymentFixture(true);paymentReads(fixture.stripe(),java.util.Map.of("in_test",paidSnapshot("in_test","usd",38388,true)),false);
        assertTrue(fixture.service().tryProcessEvent(paidEvent("in_test")));assertGranted("in_test",1,1,true);
    }
    @Test void paidRecoveryUsesTheGatewayBusinessTimeWithoutStripeReadsInsideTheGrantTransaction() throws Exception {
        var fixture=paymentFixture(false);var paidAt=NOW.plusDays(28);
        insertAddon(1,"test_user","paused","addon",700,paidAt.plusDays(4));
        insertAddon(2,"test_user","paused","addon",300,paidAt.minusSeconds(1));
        paymentReads(fixture.stripe(),java.util.Map.of("in_test",paidSnapshot("in_test","hkd",18900,false)),false);
        var oldKey=com.stripe.Stripe.apiKey;
        try(var subscriptions=org.mockito.Mockito.mockStatic(com.stripe.model.Subscription.class)){
            subscriptions.when(()->com.stripe.model.Subscription.retrieve(org.mockito.ArgumentMatchers.anyString())).thenAnswer(call->{
                assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive(),
                        "Add-on lifecycle must use the gateway business time instead of reading Stripe in the grant transaction");
                throw new AssertionError("The gateway snapshot already supplies the add-on business time");
            });
            com.stripe.Stripe.apiKey="sk_test_local_db_fixture_not_a_credential";
            // Deliberately leave the local clock untouched: expiry must follow the gateway snapshot.
            assertTrue(fixture.service().tryProcessEvent(paidEvent("in_test")));
        }finally{com.stripe.Stripe.apiKey=oldKey;}
        assertGranted("in_test",1,1,false);
        assertAddon(1,"active",700,paidAt.plusDays(4),null,1);
        assertAddon(2,"expired",300,paidAt.minusSeconds(1),null,1);
    }
    @Test void aMissingGatewayBusinessTimeRollsBackTheWholeRecoveryGrant() throws Exception {
        var fixture=paymentFixture(false);var paidAt=NOW.plusDays(28);
        insertAddon(1,"test_user","paused","addon",700,paidAt.plusDays(4));
        var paid=paidSnapshot("in_test","hkd",18900,false);
        var snapshot=new SubscriptionRecoveryStripeGateway.Snapshot(paid.invoice(),paid.subscription(),paid.schedule(),paid.lines(),paid.paymentIntent(),paid.charge(),null);
        paymentReads(fixture.stripe(),java.util.Map.of("in_test",snapshot),false);
        assertEquals("RECOVERY_BUSINESS_TIME_MISSING",assertThrows(IllegalStateException.class,()->fixture.service().tryProcessEvent(paidEvent("in_test"))).getMessage());
        assertEquals(0,scalar("SELECT COUNT(*) FROM recharge_orders"));
        assertEquals(0,scalar("SELECT COUNT(*) FROM user_ai_quotas"));
        assertEquals(0,scalar("SELECT COUNT(*) FROM quota_ledger"));
        assertEquals(0,scalar("SELECT COUNT(*) FROM subscription_payment_recovery_actions"));
        assertAddon(1,"paused",700,paidAt.plusDays(4),NOW,0);
        try(var session=factory.openSession(true)){
            var root=session.getMapper(SubscriptionPaymentRecoveryMapper.class).selectById(1L);
            assertEquals("OPEN_AUTO",root.getStatus());assertNull(root.getEntitlementInvoiceId());assertNull(root.getEntitlementEndAt());
            var user=session.getMapper(UserSubscriptionMapper.class).selectByUser("test_user");
            assertEquals("past_due",user.getStatus());assertEquals(1L,user.getPaymentRecoveryId());assertNull(user.getCurrentPeriodEnd());
        }
    }
    @Test void paidRecoveryResumesOnlyEligibleAddonsWithoutExtendingTheirOriginalExpiry() throws Exception {
        var fixture=paymentFixture(false);
        var paidAt=NOW.plusDays(28);
        insertAddon(1,"test_user","paused","addon",700,paidAt.plusDays(4));
        insertAddon(2,"test_user","paused","addon",300,paidAt.minusSeconds(1));
        insertAddon(3,"test_user","paused","addon",200,paidAt);
        insertAddon(4,"test_user","depleted","addon",0,paidAt.plusDays(4));
        insertAddon(5,"test_user","refunded","addon",100,paidAt.plusDays(4));
        insertAddon(6,"test_user","disputed","addon",100,paidAt.plusDays(4));
        insertAddon(7,"other_user","paused","addon",100,paidAt.plusDays(4));
        insertAddon(8,"test_user","paused","compensation",100,paidAt.plusDays(4));
        insertAddon(9,"test_user","paused","addon",50,null);
        paymentReads(fixture.stripe(),java.util.Map.of("in_test",paidSnapshot("in_test","hkd",18900,false)),false);
        try(var clock=addonBusinessClock()){
            assertTrue(fixture.service().tryProcessEvent(paidEvent("in_test")));
            assertTrue(fixture.service().tryProcessEvent(paidEvent("in_test")));
        }
        assertGranted("in_test",1,1,false);
        assertAddon(1,"active",700,paidAt.plusDays(4),null,1);
        assertAddon(2,"expired",300,paidAt.minusSeconds(1),null,1);
        assertAddon(3,"expired",200,paidAt,null,1);
        assertAddon(4,"depleted",0,paidAt.plusDays(4),NOW,0);
        assertAddon(5,"refunded",100,paidAt.plusDays(4),NOW,0);
        assertAddon(6,"disputed",100,paidAt.plusDays(4),NOW,0);
        assertAddon(7,"paused",100,paidAt.plusDays(4),NOW,0);
        assertAddon(8,"paused",100,paidAt.plusDays(4),NOW,0);
        assertAddon(9,"active",50,null,null,1);
        assertEquals(9,scalar("SELECT COUNT(*) FROM user_addon_grants"));
        assertEquals(2,scalar("SELECT COUNT(*) FROM quota_ledger WHERE ledger_type='addon_expired'"));
        assertEquals(-500,scalar("SELECT SUM(amount) FROM quota_ledger WHERE ledger_type='addon_expired'"));
        assertEquals(1,scalar("SELECT COUNT(*) FROM quota_ledger WHERE ledger_type='addon_expired' AND source_id='2' AND idempotency_key='addon-expire:2'"));
        assertEquals(1,scalar("SELECT COUNT(*) FROM quota_ledger WHERE ledger_type='addon_expired' AND source_id='3' AND idempotency_key='addon-expire:3'"));
        assertEquals(1,scalar("SELECT COUNT(*) FROM quota_ledger WHERE ledger_type='addon_resume' AND subscription_id='sub_test' AND source_id='sub_test' AND idempotency_key='recovery:in_test:addons' AND amount=0"));
        org.mockito.Mockito.verify(fixture.stripe(),org.mockito.Mockito.never()).execute(org.mockito.ArgumentMatchers.any());
    }
    @Test void paidRecoverySupersedesOnlyItsOwnActiveAndPendingPassesAndFreezesCleanupEvidence() throws Exception {
        var fixture=paymentFixture(false);var expires=NOW.plusDays(40);
        insertPass(1,"test_user","active",expires);insertPass(2,"test_user","pending",expires.plusDays(1));
        insertPass(3,"other_user","active",expires);insertPass(4,"other_user","pending",expires);
        insertPass(5,"test_user","expired",NOW.minusDays(1));insertPass(6,"test_user","superseded",expires);
        paymentReads(fixture.stripe(),java.util.Map.of("in_test",paidSnapshot("in_test","hkd",18900,false)),false);
        assertTrue(fixture.service().tryProcessEvent(paidEvent("in_test")));
        // A new Pass purchased after the recovery commit is not a target of replay.
        insertPass(7,"test_user","active",expires.plusDays(2));
        assertTrue(fixture.service().tryProcessEvent(paidEvent("in_test")));
        assertGranted("in_test",1,1,false);
        assertPass(1,"superseded",expires);assertPass(2,"superseded",expires.plusDays(1));
        assertPass(3,"active",expires);assertPass(4,"pending",expires);
        assertPass(5,"expired",NOW.minusDays(1));assertPass(6,"superseded",expires);assertPass(7,"active",expires.plusDays(2));
        assertEquals(2,scalar("SELECT COUNT(*) FROM subscription_payment_recovery_actions WHERE action_type='END_STUDY_PASS'"));
        try(var session=factory.openSession(true)){
            var mapper=session.getMapper(SubscriptionRecoveryActionMapper.class);
            for(long id:java.util.List.of(1L,2L)){
                var action=mapper.selectByKey("recovery:1:end-pass:"+id);assertNotNull(action);
                assertEquals(String.valueOf(id),action.getTargetId());assertEquals("READY",action.getStatus());
                var payload=SubscriptionRecoveryStripeGateway.thaw(action.getPayloadJson());
                assertEquals(id,((Number)payload.get("study_pass_id")).longValue());
                assertEquals(id==1?"active":"pending",payload.get("original_status"));
                assertEquals(com.studyagent.common.datetime.DateTimeFormats.formatApi(id==1?expires:expires.plusDays(1)),payload.get("original_expires_at"));
                assertEquals("sub_pass_"+id,payload.get("subscription_id"));assertEquals("cs_pass_"+id,payload.get("checkout_id"));
            }
        }
        org.mockito.Mockito.verify(fixture.stripe(),org.mockito.Mockito.never()).readSubscription(org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.verify(fixture.stripe(),org.mockito.Mockito.never()).readCheckout(org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.verify(fixture.stripe(),org.mockito.Mockito.never()).execute(org.mockito.ArgumentMatchers.any());
    }
    @Test void aFailureAfterRealPassCleanupIntentInsertRollsBackTheGrantAndReplaysOnce() throws Exception {
        var fixture=paymentFixture(false);var expires=NOW.plusDays(40);insertPass(1,"test_user","pending",expires);
        paymentReads(fixture.stripe(),java.util.Map.of("in_test",paidSnapshot("in_test","hkd",18900,false)),false);
        var sessions=new org.mybatis.spring.SqlSessionTemplate(factory);var actual=sessions.getMapper(SubscriptionRecoveryActionMapper.class);
        var failing=org.mockito.Mockito.mock(SubscriptionRecoveryActionMapper.class,org.mockito.AdditionalAnswers.delegatesTo(actual));
        org.mockito.Mockito.doAnswer(call->{
            var action=(com.studyagent.infra.entity.SubscriptionRecoveryActionEntity)call.getArgument(0);int inserted=actual.insert(action);
            if("END_STUDY_PASS".equals(action.getActionType())){
                assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
                assertEquals("superseded",sessions.getMapper(StudyPassMapper.class).selectById(1L).getStatus());
                assertNotNull(actual.selectByKey("recovery:1:end-pass:1"));
                throw new IllegalStateException("TEST_AFTER_REAL_PASS_INTENT_INSERT");
            }
            return inserted;
        }).when(failing).insert(org.mockito.ArgumentMatchers.any(com.studyagent.infra.entity.SubscriptionRecoveryActionEntity.class));
        org.springframework.test.util.ReflectionTestUtils.setField(fixture.service(),"actions",failing);
        assertEquals("TEST_AFTER_REAL_PASS_INTENT_INSERT",assertThrows(IllegalStateException.class,()->fixture.service().tryProcessEvent(paidEvent("in_test"))).getMessage());
        assertPass(1,"pending",expires);
        for(String table:java.util.List.of("recharge_orders","user_ai_quotas","quota_ledger","subscription_payment_recovery_actions"))assertEquals(0,scalar("SELECT COUNT(*) FROM "+table));
        try(var session=factory.openSession(true)){
            var root=session.getMapper(SubscriptionPaymentRecoveryMapper.class).selectById(1L);assertEquals("OPEN_AUTO",root.getStatus());assertNull(root.getEntitlementInvoiceId());assertNull(root.getEntitlementEndAt());
            var user=session.getMapper(UserSubscriptionMapper.class).selectByUser("test_user");assertEquals("past_due",user.getStatus());assertNull(user.getCurrentPeriodEnd());assertEquals(1L,user.getPaymentRecoveryId());
        }
        org.springframework.test.util.ReflectionTestUtils.setField(fixture.service(),"actions",actual);
        assertTrue(fixture.service().tryProcessEvent(paidEvent("in_test")));assertTrue(fixture.service().tryProcessEvent(paidEvent("in_test")));
        assertGranted("in_test",1,1,false);assertPass(1,"superseded",expires);
        assertEquals(1,scalar("SELECT COUNT(*) FROM subscription_payment_recovery_actions WHERE action_type='END_STUDY_PASS'"));
        org.mockito.Mockito.verify(fixture.stripe(),org.mockito.Mockito.never()).execute(org.mockito.ArgumentMatchers.any());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"FAILED","UNKNOWN"})
    void renewalShiftFailureAfterPassCleanupPreservesTheCommittedNativeCashAndFullPaidCycle(String outcome) throws Exception {
        var fixture=paymentFixture(false);var snapshot=paidSnapshot("in_test","hkd",18900,false);paymentReads(fixture.stripe(),java.util.Map.of("in_test",snapshot),false);
        insertPass(1,"test_user","active",NOW.plusDays(40));assertTrue(fixture.service().tryProcessEvent(paidEvent("in_test")));
        var checkout=new com.stripe.model.checkout.Session();checkout.setId("cs_pass_1");checkout.setCustomer("cus_test");checkout.setStatus("expired");checkout.setPaymentStatus("unpaid");
        var passSubscription=new com.stripe.model.Subscription();passSubscription.setId("sub_pass_1");passSubscription.setCustomer("cus_test");passSubscription.setStatus("canceled");
        org.mockito.Mockito.when(fixture.stripe().readCheckout("cs_pass_1")).thenAnswer(call->{assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());return checkout;});
        org.mockito.Mockito.when(fixture.stripe().readSubscription("sub_pass_1")).thenAnswer(call->{assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());return passSubscription;});
        var mapper=new org.mybatis.spring.SqlSessionTemplate(factory).getMapper(SubscriptionRecoveryActionMapper.class);
        var cleanup=mapper.selectByKey("recovery:1:end-pass:1");cleanup.setNextRunAt(com.studyagent.common.datetime.DateTimeFormats.now().minusSeconds(1));mapper.updateById(cleanup);fixture.service().runDueAction(cleanup.getId());assertEquals("SUCCEEDED",mapper.selectById(cleanup.getId()).getStatus());
        var shift=mapper.selectByRecovery(1L).stream().filter(a->"SHIFT_RENEWAL".equals(a.getActionType())).findFirst().orElseThrow();String frozen=shift.getPayloadJson(),key=shift.getProviderIdempotencyKey();shift.setNextRunAt(com.studyagent.common.datetime.DateTimeFormats.now().minusSeconds(1));mapper.updateById(shift);
        org.mockito.Mockito.when(fixture.stripe().execute(org.mockito.ArgumentMatchers.any())).thenAnswer(call->{assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());var action=(com.studyagent.infra.entity.SubscriptionRecoveryActionEntity)call.getArgument(0);assertEquals("SHIFT_RENEWAL",action.getActionType());assertEquals(frozen,action.getPayloadJson());assertEquals(key,action.getProviderIdempotencyKey());return new SubscriptionRecoveryStripeGateway.WriteResult(outcome,"req_shift_"+outcome,"test_shift_receipt",null);});
        fixture.service().runDueAction(shift.getId());restart(fixture.service()).runDueAction(shift.getId());assertTrue(fixture.service().tryProcessEvent(paidEvent("in_test")));
        var stored=mapper.selectById(shift.getId());assertEquals(outcome,stored.getStatus());assertEquals(frozen,stored.getPayloadJson());assertEquals(key,stored.getProviderIdempotencyKey());assertPass(1,"superseded",NOW.plusDays(40));
        try(var sql=factory.openSession(true)){var root=sql.getMapper(SubscriptionPaymentRecoveryMapper.class).selectById(1L);assertEquals("PAID",root.getStatus());assertEquals("GRANTED",root.getGrantState());assertEquals("in_test",root.getEntitlementInvoiceId());assertEquals(NOW.plusDays(28),root.getEntitlementStartAt());assertEquals(NOW.plusDays(28).plusMonths(1),root.getEntitlementEndAt());assertNull(root.getAccountClosedAt());
            var user=sql.getMapper(UserSubscriptionMapper.class).selectByUser("test_user");assertEquals("pro_monthly",user.getPlanCode());assertEquals(root.getEntitlementEndAt(),user.getCurrentPeriodEnd());assertEquals(1L,user.getPaymentRecoveryId());
            for(var quota:sql.getMapper(UserAiQuotaMapper.class).selectList(null)){assertEquals(root.getQuotaPeriodEndAt(),quota.getPlanPeriodEnd());assertEquals("task_create".equals(quota.getFeatureCode())?12L:"ai_detection".equals(quota.getFeatureCode())?20000L:10000L,quota.getPlanBalance());}}
        assertEquals(1,scalar("SELECT COUNT(*) FROM recharge_orders WHERE status='completed' AND stripe_invoice_id='in_test'"));assertEquals(3,scalar("SELECT COUNT(*) FROM quota_ledger WHERE ledger_type='plan_reset' AND invoice_id='in_test'"));org.mockito.Mockito.verify(fixture.stripe(),org.mockito.Mockito.times(1)).execute(org.mockito.ArgumentMatchers.any());
    }
    @Test void aRemotePassCleanupReadFailureKeepsTheCommittedPaidMembershipAndQuota() throws Exception {
        var fixture=paymentFixture(false);var expires=NOW.plusDays(40);insertPass(1,"test_user","active",expires);
        paymentReads(fixture.stripe(),java.util.Map.of("in_test",paidSnapshot("in_test","hkd",18900,false)),false);
        assertTrue(fixture.service().tryProcessEvent(paidEvent("in_test")));
        org.mockito.Mockito.when(fixture.stripe().readCheckout("cs_pass_1")).thenAnswer(call->{
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            throw new IllegalStateException("TEST_PASS_CLEANUP_READ_UNAVAILABLE");
        });
        long cleanupId;
        try(var session=factory.openSession(true)){
            var mapper=session.getMapper(SubscriptionRecoveryActionMapper.class);var cleanup=mapper.selectByKey("recovery:1:end-pass:1");cleanupId=cleanup.getId();
            cleanup.setNextRunAt(NOW.minusDays(1));mapper.updateById(cleanup);
        }
        fixture.service().runDueAction(cleanupId);
        assertGranted("in_test",1,1,false);assertPass(1,"superseded",expires);
        try(var session=factory.openSession(true)){
            var cleanup=session.getMapper(SubscriptionRecoveryActionMapper.class).selectById(cleanupId);
            assertEquals("UNKNOWN",cleanup.getStatus());assertNotNull(cleanup.getFirstDispatchedAt());
            var user=session.getMapper(UserSubscriptionMapper.class).selectByUser("test_user");assertEquals("pro_monthly",user.getPlanCode());assertEquals(NOW.plusDays(28).plusMonths(1),user.getCurrentPeriodEnd());
        }
        org.mockito.Mockito.verify(fixture.stripe()).readCheckout("cs_pass_1");
        org.mockito.Mockito.verify(fixture.stripe(),org.mockito.Mockito.never()).execute(org.mockito.ArgumentMatchers.any());
    }
    @Test void anAddonResumeFailureRollsBackRealOrdersQuotaLedgerAndPaidRightsThenReplaysOnce() throws Exception {
        var fixture=paymentFixture(false);paymentReads(fixture.stripe(),java.util.Map.of("in_test",paidSnapshot("in_test","hkd",18900,false)),false);
        var paidAt=NOW.plusDays(28);
        insertPass(1,"test_user","active",paidAt.plusDays(4));
        insertAddon(1,"test_user","paused","addon",700,paidAt.plusDays(4));
        insertAddon(2,"test_user","paused","addon",300,paidAt.minusSeconds(1));
        org.mockito.Mockito.doAnswer(call->{
            assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            call.callRealMethod();
            // Observe the real mutations inside the same SqlSession/transaction before failing.
            var sessions=new org.mybatis.spring.SqlSessionTemplate(factory);
            assertEquals("active",sessions.getMapper(UserAddonGrantMapper.class).selectById(1L).getStatus());
            assertEquals("expired",sessions.getMapper(UserAddonGrantMapper.class).selectById(2L).getStatus());
            assertEquals(5L,sessions.getMapper(QuotaLedgerMapper.class).selectCount(null));
            assertEquals("superseded",sessions.getMapper(StudyPassMapper.class).selectById(1L).getStatus());
            assertEquals(1,sessions.getMapper(SubscriptionRecoveryActionMapper.class).selectByRecovery(1L).stream().filter(a->"END_STUDY_PASS".equals(a.getActionType())).count());
            throw new IllegalStateException("TEST_AFTER_REAL_ADDON_FAILURE");
        }).when(fixture.addons()).resumeEligibleAt(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any());
        try(var clock=addonBusinessClock()){
            assertEquals("TEST_AFTER_REAL_ADDON_FAILURE",assertThrows(IllegalStateException.class,()->fixture.service().tryProcessEvent(paidEvent("in_test"))).getMessage());
        }
        assertEquals(0,scalar("SELECT COUNT(*) FROM recharge_orders"));assertEquals(0,scalar("SELECT COUNT(*) FROM user_ai_quotas"));assertEquals(0,scalar("SELECT COUNT(*) FROM quota_ledger"));
        assertEquals(0,scalar("SELECT COUNT(*) FROM subscription_payment_recovery_actions"));
        assertPass(1,"active",paidAt.plusDays(4));
        assertAddon(1,"paused",700,paidAt.plusDays(4),NOW,0);
        assertAddon(2,"paused",300,paidAt.minusSeconds(1),NOW,0);
        try(var session=factory.openSession(true)){var root=session.getMapper(SubscriptionPaymentRecoveryMapper.class).selectById(1L);assertEquals("OPEN_AUTO",root.getStatus());assertNull(root.getEntitlementInvoiceId());assertNull(root.getEntitlementEndAt());assertNull(session.getMapper(UserSubscriptionMapper.class).selectByUser("test_user").getCurrentPeriodEnd());}
        org.mockito.Mockito.doCallRealMethod().when(fixture.addons()).resumeEligibleAt(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any());
        try(var clock=addonBusinessClock()){
            assertTrue(fixture.service().tryProcessEvent(paidEvent("in_test")));
            assertTrue(fixture.service().tryProcessEvent(paidEvent("in_test")));
        }
        assertGranted("in_test",1,1,false);
        assertPass(1,"superseded",paidAt.plusDays(4));
        assertEquals(1,scalar("SELECT COUNT(*) FROM subscription_payment_recovery_actions WHERE action_type='END_STUDY_PASS'"));
        assertAddon(1,"active",700,paidAt.plusDays(4),null,1);
        assertAddon(2,"expired",300,paidAt.minusSeconds(1),null,1);
        assertEquals(1,scalar("SELECT COUNT(*) FROM quota_ledger WHERE ledger_type='addon_expired'"));
        assertEquals(1,scalar("SELECT COUNT(*) FROM quota_ledger WHERE ledger_type='addon_resume' AND idempotency_key='recovery:in_test:addons'"));
        org.mockito.Mockito.verify(fixture.stripe(),org.mockito.Mockito.never()).execute(org.mockito.ArgumentMatchers.any());
    }
    @Test void lateOldChildCashAfterANewPaidSubscriptionNeverReplacesItsQuotaOrMembership() throws Exception {
        var fixture=paymentFixture(false);insertPaidChild();var sessions=new org.mybatis.spring.SqlSessionTemplate(factory);var newerStart=NOW.plusDays(27);var newerEnd=newerStart.plusMonths(1);
        insertAddon(1,"test_user","paused","addon",700,NOW.plusDays(60));
        try(var clock=addonBusinessClock()){
        new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds)).executeWithoutResult(tx->{
            var replacement=new com.studyagent.infra.entity.SubscriptionRecoveryReplacementEntity();replacement.setRecoveryId(1L);replacement.setClerkUserId("test_user");replacement.setOldSubscriptionId("sub_test");replacement.setNewSubscriptionId("sub_new");replacement.setOrderNo("new_paid_order");replacement.setTargetPlanCode("pro_monthly");replacement.setState("PAID");replacement.setPausedAt(NOW);replacement.setClosedAt(newerStart);replacement.setQuoteSnapshot("{}");sessions.getMapper(SubscriptionRecoveryReplacementMapper.class).insert(replacement);
            var users=sessions.getMapper(UserSubscriptionMapper.class);var user=users.selectByUserForUpdate("test_user");user.setStripeSubscriptionId("sub_new");user.setPlanCode("pro_monthly");user.setStatus("active");user.setCurrentPeriodStart(newerStart);user.setCurrentPeriodEnd(newerEnd);user.setQuotaPeriodStart(newerStart);user.setQuotaPeriodEnd(newerEnd);
            users.update(user,new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<com.studyagent.infra.entity.UserSubscriptionEntity>().eq(com.studyagent.infra.entity.UserSubscriptionEntity::getId,user.getId()).set(com.studyagent.infra.entity.UserSubscriptionEntity::getPaymentRecoveryId,null));
            fixture.quota().resetFromPaidInvoice("test_user","sub_new","pro_monthly",newerStart.atZone(com.studyagent.common.datetime.DateTimeFormats.APP_ZONE).toInstant(),newerEnd.atZone(com.studyagent.common.datetime.DateTimeFormats.APP_ZONE).toInstant(),"in_new");
            fixture.quota().resumeEligibleAddonsAt("test_user","sub_new","replacement:new_paid_order:addons",newerStart.atZone(com.studyagent.common.datetime.DateTimeFormats.APP_ZONE).toInstant());
            var order=new com.studyagent.infra.entity.RechargeOrderEntity();order.setOrderNo("new_paid_order");order.setOrderType("subscription");order.setClerkUserId("test_user");order.setPlanCode("pro_monthly");order.setCurrency("hkd");order.setPriceCents(18900);order.setStripeInvoiceId("in_new");order.setStripeSubscriptionId("sub_new");order.setStatus("completed");sessions.getMapper(RechargeOrderMapper.class).insert(order);
        });
        assertAddon(1,"active",700,NOW.plusDays(60),null,1);
        var main=paidSnapshot("in_test","hkd",18900,false);main.invoice().setStatus("open");main.invoice().setAmountPaid(0L);main.invoice().setAmountRemaining(18900L);var child=paidSnapshot("in_child","hkd",18900,false);paymentReads(fixture.stripe(),java.util.Map.of("in_test",main,"in_child",child),false);
        assertTrue(fixture.service().tryProcessEvent(paidEvent("in_child")));assertEquals(3,scalar("SELECT COUNT(*) FROM quota_ledger WHERE invoice_id='in_new'"));assertEquals(0,scalar("SELECT COUNT(*) FROM quota_ledger WHERE invoice_id='in_child'"));assertEquals(2,scalar("SELECT COUNT(*) FROM recharge_orders"));assertEquals(0,scalar("SELECT COUNT(*) FROM subscription_payment_recovery_actions WHERE action_type='SHIFT_RENEWAL'"));
        assertAddon(1,"active",700,NOW.plusDays(60),null,1);
        assertEquals(1,scalar("SELECT COUNT(*) FROM quota_ledger WHERE ledger_type='addon_resume' AND idempotency_key='replacement:new_paid_order:addons' AND subscription_id='sub_new'"));
        assertEquals(0,scalar("SELECT COUNT(*) FROM quota_ledger WHERE idempotency_key='recovery:in_child:addons'"));
        var user=sessions.getMapper(UserSubscriptionMapper.class).selectByUser("test_user");assertEquals("sub_new",user.getStripeSubscriptionId());assertEquals("active",user.getStatus());assertNull(user.getPaymentRecoveryId());assertEquals(newerEnd,user.getCurrentPeriodEnd());
        var old=sessions.getMapper(SubscriptionPaymentRecoveryMapper.class).selectByInvoice("in_child");assertEquals("paid",old.getStripeInvoiceStatus());assertEquals(18900L,old.getAmountPaidMinor());assertEquals("HELD",old.getGrantState());assertEquals("OPEN",sessions.getMapper(SubscriptionPaymentRecoveryMapper.class).selectById(1L).getReviewState());
        for(var q:sessions.getMapper(UserAiQuotaMapper.class).selectList(null))assertEquals(newerEnd,q.getPlanPeriodEnd());org.mockito.Mockito.verify(fixture.stripe(),org.mockito.Mockito.never()).execute(org.mockito.ArgumentMatchers.any());
        }
    }
    @Test void checkoutPaidBeforeCreateResultCommitsOneReplacementUnderConcurrentCallbacks() throws Exception {
        var fixture=paymentFixture(false);prepareReplacement(fixture,"checkout_success");assertUnpaidReplacementCreatedDoesNotGrant(fixture,"checkout_success");
        var paid=replacementPayment("checkout_success");replacementReads(fixture,paid,true);
        var event=replacementCheckoutEvent("checkout_success");
        concurrently(()->fixture.service().tryProcessEvent(event),()->fixture.service().tryProcessEvent(event));
        assertReplacementGranted("checkout_success");
        // Replayed callbacks cannot supersede a Pass acquired after the successful switch.
        insertPass(3,"test_user","active",NOW.plusDays(45));
        assertTrue(fixture.service().tryProcessEvent(event));
        assertReplacementGranted("checkout_success");assertPass(3,"active",NOW.plusDays(45));
        org.mockito.Mockito.verify(fixture.stripe(),org.mockito.Mockito.never()).execute(org.mockito.ArgumentMatchers.any());
    }
    @Test void replacementInvoicePaidBeforeCheckoutResultRoutesThroughItsNativeSubscriptionMetadata() throws Exception {
        var fixture=paymentFixture(false);prepareReplacement(fixture,"invoice_first");assertUnpaidReplacementCreatedDoesNotGrant(fixture,"invoice_first");
        var paid=replacementPayment("invoice_first");replacementReads(fixture,paid,false);
        var object=new java.util.LinkedHashMap<String,Object>();object.put("id","in_replacement");object.put("object","invoice");
        object.put("subscription","sub_replacement");object.put("customer","cus_test");
        object.put("subscription_details",java.util.Map.of("metadata",replacementMetadata("invoice_first")));
        var event=com.stripe.net.ApiResource.GSON.fromJson(new com.google.gson.Gson().toJson(java.util.Map.of("id","evt_replacement_invoice","type","invoice.paid","data",java.util.Map.of("object",object))),com.stripe.model.Event.class);
        assertTrue(fixture.service().tryProcessEvent(event));assertReplacementGranted("invoice_first");
        assertTrue(fixture.service().tryProcessEvent(replacementCheckoutEvent("invoice_first")));
        assertTrue(fixture.service().tryProcessEvent(event));assertReplacementGranted("invoice_first");
        org.mockito.Mockito.verify(fixture.stripe(),org.mockito.Mockito.never()).execute(org.mockito.ArgumentMatchers.any());
    }
    @Test void replacementFailureAfterRealAddonAndPassMutationsRollsBackEverythingAndReplaysOnce() throws Exception {
        var fixture=paymentFixture(false);prepareReplacement(fixture,"rollback_checkout");
        var paid=replacementPayment("rollback_checkout");replacementReads(fixture,paid,false);
        var event=replacementCheckoutEvent("rollback_checkout");
        var sessions=new org.mybatis.spring.SqlSessionTemplate(factory);var actual=sessions.getMapper(SubscriptionRecoveryActionMapper.class);
        var failing=org.mockito.Mockito.mock(SubscriptionRecoveryActionMapper.class,org.mockito.AdditionalAnswers.delegatesTo(actual));
        org.mockito.Mockito.doAnswer(call->{
            var action=(com.studyagent.infra.entity.SubscriptionRecoveryActionEntity)call.getArgument(0);int inserted=actual.insert(action);
            if("END_STUDY_PASS".equals(action.getActionType())){
                assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
                assertEquals("PAID",sessions.getMapper(SubscriptionRecoveryReplacementMapper.class).selectByOrder("rollback_checkout").getState());
                assertEquals("sub_replacement",sessions.getMapper(UserSubscriptionMapper.class).selectByUser("test_user").getStripeSubscriptionId());
                assertEquals("active",sessions.getMapper(UserAddonGrantMapper.class).selectById(1L).getStatus());
                assertEquals("expired",sessions.getMapper(UserAddonGrantMapper.class).selectById(2L).getStatus());
                assertEquals("superseded",sessions.getMapper(StudyPassMapper.class).selectById(1L).getStatus());
                assertEquals(5L,sessions.getMapper(QuotaLedgerMapper.class).selectCount(null));
                throw new IllegalStateException("TEST_AFTER_REPLACEMENT_ADDON_PASS_MUTATIONS");
            }
            return inserted;
        }).when(failing).insert(org.mockito.ArgumentMatchers.any(com.studyagent.infra.entity.SubscriptionRecoveryActionEntity.class));
        org.springframework.test.util.ReflectionTestUtils.setField(fixture.service(),"actions",failing);
        assertEquals("TEST_AFTER_REPLACEMENT_ADDON_PASS_MUTATIONS",assertThrows(IllegalStateException.class,()->fixture.service().tryProcessEvent(event)).getMessage());
        assertEquals(0,scalar("SELECT COUNT(*) FROM user_ai_quotas"));assertEquals(0,scalar("SELECT COUNT(*) FROM quota_ledger"));
        assertEquals(1,scalar("SELECT COUNT(*) FROM recharge_orders WHERE order_no='rollback_checkout' AND status='pending' AND stripe_invoice_id IS NULL"));
        assertEquals(0,scalar("SELECT COUNT(*) FROM subscription_payment_recovery_actions WHERE action_type='END_STUDY_PASS'"));
        assertEquals(1,scalar("SELECT COUNT(*) FROM subscription_payment_recovery_actions WHERE action_type='RETRY_PAY' AND status='READY'"));
        try(var session=factory.openSession(true)){
            var replacement=session.getMapper(SubscriptionRecoveryReplacementMapper.class).selectByOrder("rollback_checkout");
            assertEquals("CREATING",replacement.getState());assertEquals("sub_test",replacement.getActiveSubscriptionKey());assertNull(replacement.getNewSubscriptionId());assertNull(replacement.getCheckoutSessionId());
            var root=session.getMapper(SubscriptionPaymentRecoveryMapper.class).selectById(1L);assertEquals("OPEN_AUTO",root.getStatus());assertNull(root.getCloseReason());
            var user=session.getMapper(UserSubscriptionMapper.class).selectByUser("test_user");assertEquals("sub_test",user.getStripeSubscriptionId());assertEquals(1L,user.getPaymentRecoveryId());assertEquals("sched_old",user.getStripeScheduleId());assertEquals(NOW.plusDays(29),user.getGraceEndAt());assertNull(user.getCurrentPeriodEnd());
        }
        assertPass(1,"active",NOW.plusDays(40));assertPass(2,"pending",NOW.plusDays(41));
        assertAddon(1,"paused",700,NOW.plusDays(40),NOW,0);assertAddon(2,"paused",300,NOW.plusDays(27),NOW,0);
        org.springframework.test.util.ReflectionTestUtils.setField(fixture.service(),"actions",actual);
        assertTrue(fixture.service().tryProcessEvent(event));assertTrue(fixture.service().tryProcessEvent(event));assertReplacementGranted("rollback_checkout");
        org.mockito.Mockito.verify(fixture.stripe(),org.mockito.Mockito.never()).execute(org.mockito.ArgumentMatchers.any());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"old_first,UNKNOWN","new_first,UNKNOWN","concurrent,UNKNOWN","old_first,FAILED","new_first,FAILED","concurrent,FAILED"})
    void realOldAndReplacementCashAlwaysKeepTheNewMembershipEvenWhenOldCancellationFailsOrIsUnknown(String order,String cancellationOutcome)throws Exception{
        String orderNo="dual_cash_"+order+"_"+cancellationOutcome;var fixture=paymentFixture(false);prepareReplacement(fixture,orderNo);
        var old=paidSnapshot("in_test","hkd",18900,false);old.invoice().getStatusTransitions().setPaidAt(NOW.plusDays(27).atZone(com.studyagent.common.datetime.DateTimeFormats.APP_ZONE).toEpochSecond());
        var replacement=replacementPayment(orderNo);replacementReads(fixture,replacement,false);
        paymentReads(fixture.stripe(),java.util.Map.of("in_test",old,"in_replacement",replacement),"concurrent".equals(order));
        org.mockito.Mockito.when(fixture.stripe().listInvoices("sub_test")).thenAnswer(call->{assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());return java.util.List.of(old.invoice());});
        var oldEvent=paidEvent("in_test");var newEvent=replacementCheckoutEvent(orderNo);
        if("concurrent".equals(order))concurrently(()->fixture.service().tryProcessEvent(oldEvent),()->fixture.service().tryProcessEvent(newEvent));
        else{assertTrue(fixture.service().tryProcessEvent("old_first".equals(order)?oldEvent:newEvent));assertTrue(fixture.service().tryProcessEvent("old_first".equals(order)?newEvent:oldEvent));}
        assertTrue(fixture.service().tryProcessEvent(oldEvent));assertTrue(fixture.service().tryProcessEvent(newEvent));
        assertEquals(2,scalar("SELECT COUNT(*) FROM recharge_orders WHERE status='completed'"));assertEquals(3,scalar("SELECT COUNT(*) FROM quota_ledger WHERE ledger_type='plan_reset' AND invoice_id='in_replacement' AND subscription_id='sub_replacement'"));
        assertEquals(2,scalar("SELECT COUNT(*) FROM subscription_payment_recovery_actions WHERE action_type='END_STUDY_PASS'"));
        assertDualCashKeptNewMembership(orderNo);
        fixture.service().reconcile(1L);
        long cancel;
        try(var session=factory.openSession(true)){
            var actions=session.getMapper(SubscriptionRecoveryActionMapper.class);var action=actions.selectByRecovery(1L).stream().filter(a->"CANCEL_SUBSCRIPTION".equals(a.getActionType())&&"sub_test".equals(a.getTargetId())).findFirst().orElseThrow();cancel=action.getId();action.setNextRunAt(com.studyagent.common.datetime.DateTimeFormats.now().minusSeconds(1));actions.updateById(action);
        }
        org.mockito.Mockito.when(fixture.stripe().execute(org.mockito.ArgumentMatchers.any())).thenAnswer(call->{assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());var action=(com.studyagent.infra.entity.SubscriptionRecoveryActionEntity)call.getArgument(0);assertEquals("CANCEL_SUBSCRIPTION",action.getActionType());assertEquals("sub_test",action.getTargetId());return new SubscriptionRecoveryStripeGateway.WriteResult(cancellationOutcome,"req_old_cancel_"+cancellationOutcome,"FAILED".equals(cancellationOutcome)?"test_rejected":null,null);});
        fixture.service().runDueAction(cancel);assertDualCashKeptNewMembership(orderNo);
        fixture.service().runDueAction(cancel);assertDualCashKeptNewMembership(orderNo);
        try(var session=factory.openSession(true)){var cancelled=session.getMapper(SubscriptionRecoveryActionMapper.class).selectById(cancel);assertEquals(cancellationOutcome,cancelled.getStatus());assertEquals("req_old_cancel_"+cancellationOutcome,cancelled.getProviderRequestId());if("FAILED".equals(cancellationOutcome))assertTrue(cancelled.getResultJson().contains("test_rejected"));}
        org.mockito.Mockito.verify(fixture.stripe(),org.mockito.Mockito.times(1)).execute(org.mockito.ArgumentMatchers.any());
    }
    private static void assertDualCashKeptNewMembership(String orderNo)throws Exception{
        try(var session=factory.openSession(true)){
            var user=session.getMapper(UserSubscriptionMapper.class).selectByUser("test_user");assertEquals("sub_replacement",user.getStripeSubscriptionId());assertEquals("pro_monthly",user.getPlanCode());assertEquals(NOW.plusDays(28),user.getCurrentPeriodStart());assertEquals(NOW.plusDays(28).plusMonths(1),user.getCurrentPeriodEnd());assertNull(user.getPaymentRecoveryId());
            for(var quota:session.getMapper(UserAiQuotaMapper.class).selectList(null)){assertEquals(user.getCurrentPeriodStart(),quota.getPlanPeriodStart());assertEquals(user.getCurrentPeriodEnd(),quota.getPlanPeriodEnd());assertEquals("task_create".equals(quota.getFeatureCode())?12L:"ai_detection".equals(quota.getFeatureCode())?20000L:10000L,quota.getPlanBalance());}
            var root=session.getMapper(SubscriptionPaymentRecoveryMapper.class).selectById(1L);assertEquals("CLOSING",root.getStatus());assertEquals("REPLACEMENT_PAID",root.getCloseReason());assertEquals("OPEN",root.getReviewState());
            assertTrue(java.util.Set.of("OLD_PAYMENT_AFTER_SWITCH_OR_CLOSE","OLD_PAYMENT_AFTER_REPLACEMENT").contains(root.getReviewReason()));
            assertEquals("PAID",session.getMapper(SubscriptionRecoveryReplacementMapper.class).selectByOrder(orderNo).getState());
        }
        assertPass(1,"superseded",NOW.plusDays(40));assertPass(2,"superseded",NOW.plusDays(41));
    }

    private static void assertUnpaidReplacementCreatedDoesNotGrant(PaymentFixture fixture,String orderNo)throws Exception{
        var subscription=new com.stripe.model.Subscription();subscription.setId("sub_replacement");subscription.setCustomer("cus_test");subscription.setStatus("incomplete");subscription.setMetadata(replacementMetadata(orderNo));
        var event=com.stripe.net.ApiResource.GSON.fromJson("{\"id\":\"evt_unpaid_replacement_created\",\"type\":\"customer.subscription.created\",\"data\":{\"object\":"+subscription.toJson()+"}}",com.stripe.model.Event.class);
        int reads=org.mockito.Mockito.mockingDetails(fixture.stripe()).getInvocations().size();assertTrue(fixture.service().tryProcessEvent(event));assertEquals(reads,org.mockito.Mockito.mockingDetails(fixture.stripe()).getInvocations().size());
        assertEquals(0,scalar("SELECT COUNT(*) FROM user_ai_quotas"));assertEquals(0,scalar("SELECT COUNT(*) FROM quota_ledger"));assertEquals(1,scalar("SELECT COUNT(*) FROM recharge_orders WHERE status='pending'"));
        try(var session=factory.openSession(true)){
            var user=session.getMapper(UserSubscriptionMapper.class).selectByUser("test_user");assertEquals("sub_test",user.getStripeSubscriptionId());assertEquals(1L,user.getPaymentRecoveryId());assertNull(user.getCurrentPeriodEnd());
            var replacement=session.getMapper(SubscriptionRecoveryReplacementMapper.class).selectByOrder(orderNo);assertEquals("CREATING",replacement.getState());assertEquals("sub_test",replacement.getActiveSubscriptionKey());assertNull(replacement.getNewSubscriptionId());assertNull(replacement.getCheckoutSessionId());
        }
        assertPass(1,"active",NOW.plusDays(40));assertPass(2,"pending",NOW.plusDays(41));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"CAPTURE_FAILURE","NOTIFY_INTERNAL_FAILURE"})
    void externalFailurePublicationTimeoutCannotRollbackAPaymentCommittedDuringDispatch(String channel)throws Exception{
        var fixture=paymentFixture(false);var unpaid=unpaidSnapshot();var paid=paidSnapshot("in_test","hkd",18900,false);paymentReads(fixture.stripe(),java.util.Map.of("in_test",unpaid),false);
        var action=new com.studyagent.infra.entity.SubscriptionRecoveryActionEntity();action.setId(10L);action.setRecoveryId(1L);action.setActionKey("failure-channel-"+channel);action.setActionType(channel);action.setTargetId("in_test");action.setStatus("READY");action.setVersion(0);action.setScheduledAt(com.studyagent.common.datetime.DateTimeFormats.now().minusSeconds(2));action.setNextRunAt(action.getScheduledAt());action.setPayloadJson("{\"amount_minor\":18900,\"currency\":\"hkd\",\"decline_code\":\"insufficient_funds\",\"event_type\":\"invoice.payment_failed\"}");
        try(var session=factory.openSession(true)){assertEquals(1,session.getMapper(SubscriptionRecoveryActionMapper.class).insert(action));}
        var analytics=org.mockito.Mockito.mock(com.studyagent.common.analytics.AnalyticsService.class);var internal=org.mockito.Mockito.mock(com.studyagent.service.domain.billing.BillingRobotNotifyGateway.class);
        java.util.concurrent.atomic.AtomicInteger publications=new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.stubbing.Answer<Void> publish=call->{
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());assertEquals(0,scalar("SELECT COUNT(*) FROM recharge_orders"),"Failure publication cannot require an existing paid order");publications.incrementAndGet();
            if("NOTIFY_INTERNAL_FAILURE".equals(channel)){var request=(com.studyagent.service.domain.billing.BillingPaymentFailedNotifyRequest)call.getArgument(0);assertEquals(18900,request.getPriceCents());assertEquals("hkd",request.getCurrency());assertEquals("in_test",request.getInvoiceId());}
            paymentReads(fixture.stripe(),java.util.Map.of("in_test",paid),false);assertTrue(fixture.service().tryProcessEvent(paidEvent("in_test")));
            assertEquals(1,scalar("SELECT COUNT(*) FROM recharge_orders WHERE status='completed'"));throw new IllegalStateException("TEST_EXTERNAL_PUBLICATION_TIMEOUT_AFTER_PAID_COMMIT");
        };
        if("CAPTURE_FAILURE".equals(channel)){org.mockito.Mockito.doAnswer(publish).when(analytics).capture(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyMap());org.springframework.test.util.ReflectionTestUtils.setField(fixture.service(),"analytics",analytics);}
        else{
            @SuppressWarnings("unchecked") var provider=(org.springframework.beans.factory.ObjectProvider<com.studyagent.service.domain.billing.BillingRobotNotifyGateway>)org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(internal);org.springframework.test.util.ReflectionTestUtils.setField(fixture.service(),"internalNotifications",provider);
            org.mockito.Mockito.doAnswer(publish).when(internal).notifyPaymentFailed(org.mockito.ArgumentMatchers.any());
        }
        fixture.service().runDueAction(10L);fixture.service().runDueAction(10L);assertTrue(fixture.service().tryProcessEvent(paidEvent("in_test")));assertEquals(1,publications.get());
        try(var session=factory.openSession(true)){
            var saved=session.getMapper(SubscriptionRecoveryActionMapper.class).selectById(10L);assertEquals("UNKNOWN",saved.getStatus());assertNotNull(saved.getFirstDispatchedAt());
            var root=session.getMapper(SubscriptionPaymentRecoveryMapper.class).selectById(1L);assertEquals("PAID",root.getStatus());assertEquals("GRANTED",root.getGrantState());assertEquals("in_test",root.getEntitlementInvoiceId());assertEquals(NOW.plusDays(28).plusMonths(1),root.getEntitlementEndAt());
            var user=session.getMapper(UserSubscriptionMapper.class).selectByUser("test_user");assertEquals("pro_monthly",user.getPlanCode());assertEquals(root.getEntitlementEndAt(),user.getCurrentPeriodEnd());
        }
        assertEquals(1,scalar("SELECT COUNT(*) FROM recharge_orders WHERE status='completed'"));assertEquals(3,scalar("SELECT COUNT(*) FROM quota_ledger WHERE ledger_type='plan_reset' AND invoice_id='in_test'"));org.mockito.Mockito.verify(fixture.stripe(),org.mockito.Mockito.never()).execute(org.mockito.ArgumentMatchers.any());
    }

    private void prepareReplacement(PaymentFixture fixture,String orderNo)throws Exception{
        paymentReads(fixture.stripe(),java.util.Map.of("in_test",unpaidSnapshot()),false);
        try(Connection c=ds.getConnection();Statement q=c.createStatement()){
            q.executeUpdate("UPDATE subscription_payment_recoveries SET protection_state='ACTIVE' WHERE id=1");
            q.executeUpdate("UPDATE user_subscriptions SET stripe_schedule_id='sched_old',grace_end_at='2026-12-10 10:00:00' WHERE id=1");
            insertAction(q,1,"replacement-retry",4);
        }
        insertPass(1,"test_user","active",NOW.plusDays(40));insertPass(2,"test_user","pending",NOW.plusDays(41));
        insertAddon(1,"test_user","paused","addon",700,NOW.plusDays(40));insertAddon(2,"test_user","paused","addon",300,NOW.plusDays(27));
        var order=new com.studyagent.infra.entity.RechargeOrderEntity();order.setOrderNo(orderNo);order.setOrderType("subscription_replacement");order.setClerkUserId("test_user");order.setTargetPlanCode("pro_monthly");order.setCurrency("hkd");order.setPriceCents(18900);order.setQuotedAmountCents(18900);order.setStatus("pending");
        var expires=java.time.Instant.now().plusSeconds(1860);
        var params=com.stripe.param.checkout.SessionCreateParams.builder().setCustomer("cus_test").setMode(com.stripe.param.checkout.SessionCreateParams.Mode.SUBSCRIPTION).setExpiresAt(expires.getEpochSecond())
                .putAllMetadata(replacementMetadata(orderNo)).setSubscriptionData(com.stripe.param.checkout.SessionCreateParams.SubscriptionData.builder().putAllMetadata(replacementMetadata(orderNo)).build())
                .addLineItem(com.stripe.param.checkout.SessionCreateParams.LineItem.builder().setPrice("price_admit").setQuantity(1L).build()).build();
        fixture.service().reserveReplacement("test_user",1L,order,params,expires);
        assertEquals(1,scalar("SELECT COUNT(*) FROM recharge_orders WHERE status='pending'"));
        assertEquals(1,scalar("SELECT COUNT(*) FROM subscription_payment_recovery_replacements WHERE state='CREATING' AND active_subscription_key='sub_test' AND checkout_session_id IS NULL"));
        assertEquals(1,scalar("SELECT COUNT(*) FROM subscription_payment_recovery_actions WHERE action_type='CREATE_CHECKOUT'"));
    }
    private static java.util.Map<String,String> replacementMetadata(String orderNo){return java.util.Map.of("order_no",orderNo,"purchase_type","subscription_replacement","recovery_id","1","clerk_user_id","test_user","old_subscription_id","sub_test");}
    private static SubscriptionRecoveryStripeGateway.Snapshot replacementPayment(String orderNo){
        var snapshot=paidSnapshot("in_replacement","hkd",18900,false);snapshot.invoice().setSubscription("sub_replacement");snapshot.invoice().setBillingReason("subscription_create");
        snapshot.subscription().setId("sub_replacement");snapshot.subscription().setStatus("active");snapshot.subscription().setPauseCollection(null);snapshot.subscription().setMetadata(replacementMetadata(orderNo));
        long start=NOW.plusDays(28).atZone(com.studyagent.common.datetime.DateTimeFormats.APP_ZONE).toEpochSecond();long end=NOW.plusDays(28).plusMonths(1).atZone(com.studyagent.common.datetime.DateTimeFormats.APP_ZONE).toEpochSecond();
        snapshot.subscription().setCurrentPeriodStart(start);snapshot.subscription().setCurrentPeriodEnd(end);snapshot.lines().get(0).setSubscription("sub_replacement");snapshot.lines().get(0).getPeriod().setStart(start);snapshot.lines().get(0).getPeriod().setEnd(end);return snapshot;
    }
    private static com.stripe.model.checkout.Session replacementSession(String orderNo){
        var session=new com.stripe.model.checkout.Session();session.setObject("checkout.session");session.setId("cs_replacement");session.setCustomer("cus_test");session.setSubscription("sub_replacement");session.setInvoice("in_replacement");session.setStatus("complete");session.setPaymentStatus("paid");session.setCurrency("hkd");session.setAmountTotal(18900L);session.setAmountSubtotal(18900L);session.setMetadata(replacementMetadata(orderNo));return session;
    }
    private static com.stripe.model.Event replacementCheckoutEvent(String orderNo){
        var session=replacementSession(orderNo);
        return com.stripe.net.ApiResource.GSON.fromJson("{\"id\":\"evt_replacement_checkout\",\"type\":\"checkout.session.completed\",\"data\":{\"object\":"+session.toJson()+"}}",com.stripe.model.Event.class);
    }
    private void replacementReads(PaymentFixture fixture,SubscriptionRecoveryStripeGateway.Snapshot paid,boolean synchronize){
        String orderNo=paid.subscription().getMetadata().get("order_no");paymentReads(fixture.stripe(),java.util.Map.of("in_test",unpaidSnapshot(),"in_replacement",paid),false);
        var latch=new CountDownLatch(synchronize?2:0);
        org.mockito.Mockito.when(fixture.stripe().findCheckout("cus_test",orderNo)).thenAnswer(call->{assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());latch.countDown();assertTrue(latch.await(5,TimeUnit.SECONDS));return replacementSession(orderNo);});
        org.mockito.Mockito.when(fixture.stripe().readCheckout("cs_replacement")).thenAnswer(call->{assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());return replacementSession(orderNo);});
    }
    private static void assertReplacementGranted(String orderNo)throws Exception{
        assertEquals(1,scalar("SELECT COUNT(*) FROM recharge_orders"));assertEquals(3,scalar("SELECT COUNT(*) FROM user_ai_quotas"));assertEquals(5,scalar("SELECT COUNT(*) FROM quota_ledger"));
        assertEquals(3,scalar("SELECT COUNT(*) FROM quota_ledger WHERE ledger_type='plan_reset' AND invoice_id='in_replacement' AND subscription_id='sub_replacement' AND source_id='in_replacement'"));
        assertEquals(1,scalar("SELECT COUNT(*) FROM quota_ledger WHERE ledger_type='addon_expired' AND source_id='2'"));
        assertEquals(1,scalar("SELECT COUNT(*) FROM quota_ledger WHERE ledger_type='addon_resume' AND subscription_id='sub_replacement' AND idempotency_key='replacement:"+orderNo+":addons'"));
        assertEquals(2,scalar("SELECT COUNT(*) FROM subscription_payment_recovery_actions WHERE action_type='END_STUDY_PASS'"));
        assertEquals(0,scalar("SELECT COUNT(*) FROM subscription_payment_recovery_actions WHERE action_type='SHIFT_RENEWAL'"));
        assertEquals(1,scalar("SELECT COUNT(*) FROM user_subscriptions WHERE stripe_subscription_id='sub_replacement' AND payment_recovery_id IS NULL AND grace_end_at IS NULL AND stripe_schedule_id IS NULL"));
        try(var session=factory.openSession(true)){
            var replacement=session.getMapper(SubscriptionRecoveryReplacementMapper.class).selectByOrder(orderNo);assertEquals("PAID",replacement.getState());assertNull(replacement.getActiveSubscriptionKey());assertEquals("sub_replacement",replacement.getNewSubscriptionId());assertEquals("cs_replacement",replacement.getCheckoutSessionId());assertNotNull(replacement.getClosedAt());
            var order=session.getMapper(RechargeOrderMapper.class).selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.studyagent.infra.entity.RechargeOrderEntity>().eq(com.studyagent.infra.entity.RechargeOrderEntity::getOrderNo,orderNo));assertEquals("completed",order.getStatus());assertEquals("subscription_replacement",order.getOrderType());assertEquals("in_replacement",order.getStripeInvoiceId());assertEquals("sub_replacement",order.getStripeSubscriptionId());assertEquals("pi_in_replacement",order.getStripePaymentIntentId());assertEquals(NOW.plusDays(28),order.getPaidAt());assertEquals(18900,order.getPriceCents());
            var user=session.getMapper(UserSubscriptionMapper.class).selectByUser("test_user");assertEquals("active",user.getStatus());assertEquals("pro_monthly",user.getPlanCode());assertEquals("pro",user.getTier());assertEquals(NOW.plusDays(28),user.getCurrentPeriodStart());assertEquals(NOW.plusDays(28).plusMonths(1),user.getCurrentPeriodEnd());assertEquals(user.getCurrentPeriodStart(),user.getQuotaPeriodStart());assertEquals(user.getCurrentPeriodEnd(),user.getQuotaPeriodEnd());
            for(var quota:session.getMapper(UserAiQuotaMapper.class).selectList(null)){assertEquals(user.getQuotaPeriodStart(),quota.getPlanPeriodStart());assertEquals(user.getQuotaPeriodEnd(),quota.getPlanPeriodEnd());assertEquals("task_create".equals(quota.getFeatureCode())?12L:"ai_detection".equals(quota.getFeatureCode())?20000L:10000L,quota.getPlanBalance());}
            var root=session.getMapper(SubscriptionPaymentRecoveryMapper.class).selectById(1L);assertEquals("CLOSING",root.getStatus());assertEquals("REPLACEMENT_PAID",root.getCloseReason());assertNull(root.getEntitlementInvoiceId());
            var retry=session.getMapper(SubscriptionRecoveryActionMapper.class).selectByKey("replacement-retry");assertEquals("SKIPPED",retry.getStatus());assertEquals("SWITCHED",retry.getSkipReason());
        }
        assertPass(1,"superseded",NOW.plusDays(40));assertPass(2,"superseded",NOW.plusDays(41));
        assertAddon(1,"active",700,NOW.plusDays(40),null,1);assertAddon(2,"expired",300,NOW.plusDays(27),null,1);
    }
    @Test void concurrentReplacementReservationsCreateOnlyOneCheckoutAndCoverTheRetrySlot() throws Exception {
        var fixture=paymentFixture(false);var unpaid=unpaidSnapshot();paymentReads(fixture.stripe(),java.util.Map.of("in_test",unpaid),true);
        ((PaymentRecoveryProperties)org.springframework.test.util.ReflectionTestUtils.getField(fixture.service(),"config")).setAutomaticPayEnabled(true);
        try(Connection c=ds.getConnection();Statement q=c.createStatement()){q.executeUpdate("UPDATE subscription_payment_recoveries SET protection_state='ACTIVE' WHERE id=1");insertAction(q,1,"retry-at-d28",4);q.executeUpdate("UPDATE subscription_payment_recovery_actions SET scheduled_at='2026-12-09 10:00:00',next_run_at='2026-12-09 10:00:00' WHERE id=1");}
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try{
            Callable<Boolean> first=()->reserveOne(fixture.service(),"replacement_a");Callable<Boolean> second=()->reserveOne(fixture.service(),"replacement_b");var a=pool.submit(first);var b=pool.submit(second);assertNotEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
        }finally{pool.shutdownNow();}
        assertEquals(1,scalar("SELECT COUNT(*) FROM recharge_orders"));assertEquals(1,scalar("SELECT COUNT(*) FROM subscription_payment_recovery_replacements WHERE active_subscription_key='sub_test'"));assertEquals(1,scalar("SELECT COUNT(*) FROM subscription_payment_recovery_actions WHERE action_type='CREATE_CHECKOUT'"));
        fixture.service().runDueAction(1L);
        try(var session=factory.openSession(true)){var retry=session.getMapper(SubscriptionRecoveryActionMapper.class).selectById(1L);assertEquals("SKIPPED",retry.getStatus());assertEquals("SKIPPED_REPLACEMENT",retry.getSkipReason());}
        org.mockito.Mockito.verify(fixture.stripe(),org.mockito.Mockito.never()).execute(org.mockito.ArgumentMatchers.any());
    }
    @Test void anUnknownOrDispatchedRetryBlocksReplacementBeforeAnyOrderIsInserted() throws Exception {
        var fixture=paymentFixture(false);paymentReads(fixture.stripe(),java.util.Map.of("in_test",unpaidSnapshot()),false);
        try(Connection c=ds.getConnection();Statement q=c.createStatement()){q.executeUpdate("UPDATE subscription_payment_recoveries SET protection_state='ACTIVE' WHERE id=1");insertAction(q,1,"inflight-retry",4);q.executeUpdate("UPDATE subscription_payment_recovery_actions SET status='DISPATCHING',lease_token='worker-pay',lease_until=NOW()+INTERVAL 2 MINUTE WHERE id=1");}
        assertFalse(reserveOne(fixture.service(),"must_not_reserve"));assertEquals(0,scalar("SELECT COUNT(*) FROM recharge_orders"));assertEquals(0,scalar("SELECT COUNT(*) FROM subscription_payment_recovery_replacements"));assertEquals(0,scalar("SELECT COUNT(*) FROM subscription_payment_recovery_actions WHERE action_type='CREATE_CHECKOUT'"));org.mockito.Mockito.verify(fixture.stripe(),org.mockito.Mockito.never()).execute(org.mockito.ArgumentMatchers.any());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"SUCCEEDED","UNKNOWN"})
    void apiAndWorkerCompeteForOneFrozenCheckoutAndRestartReadsUnknownBeforeAnyRepeatWrite(String receipt) throws Exception {
        var fixture=paymentFixture(false);String orderNo="restart_checkout_"+receipt;prepareReplacement(fixture,orderNo);
        var sql=new org.mybatis.spring.SqlSessionTemplate(factory);var mapper=sql.getMapper(SubscriptionRecoveryActionMapper.class);
        var persisted=mapper.selectByRecovery(1L).stream().filter(a->"CREATE_CHECKOUT".equals(a.getActionType())).findFirst().orElseThrow();
        String frozen=persisted.getPayloadJson(),key=persisted.getProviderIdempotencyKey();
        var params=SubscriptionRecoveryStripeGateway.thaw(frozen);
        var catalog=(SubscriptionPlanMapper)org.springframework.test.util.ReflectionTestUtils.getField(fixture.service(),"plans");org.mockito.Mockito.clearInvocations(catalog);
        var session=replacementSession(orderNo);session.setMode("subscription");session.setStatus("open");session.setPaymentStatus("unpaid");session.setSubscription((String)null);session.setInvoice((String)null);
        session.setExpiresAt(((Number)params.get("expires_at")).longValue());
        var price=new com.stripe.model.Price();price.setId("price_admit");var line=new com.stripe.model.LineItem();line.setPrice(price);line.setQuantity(1L);
        var lines=new com.stripe.model.LineItemCollection();lines.setData(java.util.List.of(line));lines.setHasMore(false);session.setLineItems(lines);
        org.mockito.Mockito.when(fixture.stripe().findCheckout("cus_test",orderNo)).thenReturn(session);
        org.mockito.Mockito.when(fixture.stripe().readCheckout("cs_replacement")).thenReturn(session);
        // A fresh service instance shares only persisted SQL state and collaborators, not action memory.
        var restarted=restart(fixture.service());assertNotSame(fixture.service(),restarted);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.when(fixture.stripe().execute(org.mockito.ArgumentMatchers.any())).thenAnswer(call->{
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            var dispatched=(com.studyagent.infra.entity.SubscriptionRecoveryActionEntity)call.getArgument(0);
            assertEquals("CREATE_CHECKOUT",dispatched.getActionType());assertEquals(key,dispatched.getProviderIdempotencyKey());assertEquals(frozen,dispatched.getPayloadJson());
            var before=mapper.selectById(persisted.getId());assertEquals("DISPATCHING",before.getStatus());assertNotNull(before.getLeaseToken());assertNotNull(before.getFirstDispatchedAt());assertEquals(frozen,before.getPayloadJson());
            calls.incrementAndGet();return new SubscriptionRecoveryStripeGateway.WriteResult(receipt,"req_create_once",null,"SUCCEEDED".equals(receipt)?session:null);
        });
        paymentReads(fixture.stripe(),java.util.Map.of("in_test",unpaidSnapshot()),true);
        concurrently(()->{fixture.service().runDueAction(persisted.getId());return true;},()->{restarted.runDueAction(persisted.getId());return true;});
        assertEquals(1,calls.get());
        if("UNKNOWN".equals(receipt)){
            assertEquals("UNKNOWN",mapper.selectById(persisted.getId()).getStatus(),"Both candidates read READY before the dispatch barrier; neither may already reconcile UNKNOWN");
            // The ordinary replacement poll may already discover its Session, but the action and order remain unresolved.
            assertEquals(1,scalar("SELECT COUNT(*) FROM recharge_orders WHERE stripe_session_id IS NULL"));
            org.mockito.Mockito.verify(fixture.stripe(),org.mockito.Mockito.never()).readCheckout("cs_replacement");
        }
        paymentReads(fixture.stripe(),java.util.Map.of("in_test",unpaidSnapshot()),false);
        // Restart after an uncertain response must find the owned Session and verify its frozen line.
        restart(fixture.service()).runDueAction(persisted.getId());fixture.service().runDueAction(persisted.getId());
        var completed=mapper.selectById(persisted.getId());assertEquals("SUCCEEDED",completed.getStatus());assertEquals(frozen,completed.getPayloadJson());assertEquals(key,completed.getProviderIdempotencyKey());
        var replacement=sql.getMapper(SubscriptionRecoveryReplacementMapper.class).selectByOrder(orderNo);assertEquals("OPEN",replacement.getState());assertEquals("cs_replacement",replacement.getCheckoutSessionId());assertEquals("sub_test",replacement.getActiveSubscriptionKey());assertEquals(frozen,replacement.getQuoteSnapshot());
        var order=sql.getMapper(RechargeOrderMapper.class).selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.studyagent.infra.entity.RechargeOrderEntity>().eq(com.studyagent.infra.entity.RechargeOrderEntity::getOrderNo,orderNo));
        assertEquals("pending",order.getStatus());assertEquals(18900,order.getQuotedAmountCents());assertEquals(18900,order.getPriceCents());assertEquals("cs_replacement",order.getStripeSessionId());
        assertEquals(0,scalar("SELECT COUNT(*) FROM quota_ledger"));assertEquals(1,calls.get());org.mockito.Mockito.verify(fixture.stripe(),org.mockito.Mockito.times(1)).execute(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verifyNoInteractions(catalog);
    }
    @Test void anOlderUnknownCardUpdateCanSettleWhileTheNewSelectionSynchronizesWithoutImmediatePayment() throws Exception {
        var fixture=paymentFixture(false);var snapshot=unpaidSnapshot();snapshot.subscription().setDefaultPaymentMethod("pm_previous");snapshot.invoice().setDefaultPaymentMethod("pm_previous");
        paymentReads(fixture.stripe(),java.util.Map.of("in_test",snapshot),false);
        var customer=new com.stripe.model.Customer();customer.setId("cus_test");var settings=new com.stripe.model.Customer.InvoiceSettings();settings.setDefaultPaymentMethod("pm_new");customer.setInvoiceSettings(settings);
        org.mockito.Mockito.when(fixture.stripe().readCustomer("cus_test")).thenReturn(customer);
        var method=new com.stripe.model.PaymentMethod();method.setId("pm_new");method.setCustomer("cus_test");org.mockito.Mockito.when(fixture.stripe().readPaymentMethod("pm_new")).thenReturn(method);
        var sql=new org.mybatis.spring.SqlSessionTemplate(factory);var mapper=sql.getMapper(SubscriptionRecoveryActionMapper.class);
        try(Connection c=ds.getConnection();Statement q=c.createStatement()){q.executeUpdate("UPDATE subscription_payment_recoveries SET protection_state='ACTIVE' WHERE id=1");insertAction(q,1,"fixed-d28-card",4);q.executeUpdate("UPDATE subscription_payment_recovery_actions SET scheduled_at='2026-12-09 10:00:00',next_run_at='2026-12-09 10:00:00' WHERE id=1");}
        var old=new com.studyagent.infra.entity.SubscriptionRecoveryActionEntity();old.setRecoveryId(1L);old.setActionType("UPDATE_METHOD");old.setActionKey("recovery:1:method:subscription:pm_previous");old.setProviderIdempotencyKey(old.getActionKey());old.setTargetId("sub_test");old.setStatus("UNKNOWN");old.setPayloadJson("{\"resource\":\"subscription\",\"default_payment_method\":\"pm_previous\"}");old.setFirstDispatchedAt(com.studyagent.common.datetime.DateTimeFormats.now().minusSeconds(10));old.setNextRunAt(com.studyagent.common.datetime.DateTimeFormats.now().minusSeconds(1));old.setScheduledAt(old.getNextRunAt());old.setVersion(0);mapper.insert(old);
        String originalPayload=mapper.selectById(old.getId()).getPayloadJson(),originalKey=old.getProviderIdempotencyKey();
        ((PaymentRecoveryProperties)org.springframework.test.util.ReflectionTestUtils.getField(fixture.service(),"config")).setAutomaticPayEnabled(true);
        fixture.service().reconcile(1L);
        var updates=mapper.selectByRecovery(1L).stream().filter(a->"UPDATE_METHOD".equals(a.getActionType())&&"READY".equals(a.getStatus())).toList();assertEquals(2,updates.size());
        long subscriptionUpdate=updates.stream().filter(a->"sub_test".equals(a.getTargetId())).findFirst().orElseThrow().getId();
        long invoiceUpdate=updates.stream().filter(a->"in_test".equals(a.getTargetId())).findFirst().orElseThrow().getId();
        fixture.service().runDueAction(subscriptionUpdate);fixture.service().runDueAction(1L);
        assertEquals("READY",mapper.selectById(subscriptionUpdate).getStatus());assertEquals("READY",mapper.selectById(1L).getStatus());org.mockito.Mockito.verify(fixture.stripe(),org.mockito.Mockito.never()).execute(org.mockito.ArgumentMatchers.any());
        var writes=new java.util.concurrent.CopyOnWriteArrayList<String>();
        org.mockito.Mockito.when(fixture.stripe().execute(org.mockito.ArgumentMatchers.any())).thenAnswer(call->{
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());var action=(com.studyagent.infra.entity.SubscriptionRecoveryActionEntity)call.getArgument(0);
            assertEquals("UPDATE_METHOD",action.getActionType());assertEquals("pm_new",SubscriptionRecoveryStripeGateway.thaw(action.getPayloadJson()).get("default_payment_method"));assertEquals("DISPATCHING",mapper.selectById(action.getId()).getStatus());writes.add(action.getTargetId());return new SubscriptionRecoveryStripeGateway.WriteResult("SUCCEEDED","req_method_"+action.getTargetId(),null,null);
        });
        paymentReads(fixture.stripe(),java.util.Map.of("in_test",snapshot),true);
        concurrently(()->{fixture.service().runDueAction(old.getId());return true;},()->{restart(fixture.service()).runDueAction(subscriptionUpdate);return true;});
        assertEquals("SUCCEEDED",mapper.selectById(old.getId()).getStatus());assertEquals(originalPayload,mapper.selectById(old.getId()).getPayloadJson());assertEquals(originalKey,mapper.selectById(old.getId()).getProviderIdempotencyKey());
        paymentReads(fixture.stripe(),java.util.Map.of("in_test",snapshot),false);
        fixture.service().runDueAction(subscriptionUpdate);snapshot.subscription().setDefaultPaymentMethod("pm_new");
        fixture.service().runDueAction(1L);assertEquals("READY",mapper.selectById(1L).getStatus(),"Invoice synchronization must still block the fixed retry");
        fixture.service().runDueAction(invoiceUpdate);snapshot.invoice().setDefaultPaymentMethod("pm_new");
        restart(fixture.service()).runDueAction(old.getId());fixture.service().reconcile(1L);
        assertEquals(java.util.List.of("in_test","sub_test"),writes.stream().sorted().toList());assertEquals("pm_new",customer.getInvoiceSettings().getDefaultPaymentMethod());assertEquals("pm_new",snapshot.subscription().getDefaultPaymentMethod());assertEquals("pm_new",snapshot.invoice().getDefaultPaymentMethod());
        assertEquals(3,scalar("SELECT COUNT(*) FROM subscription_payment_recovery_actions WHERE action_type='UPDATE_METHOD' AND status='SUCCEEDED'"));assertEquals(1,scalar("SELECT COUNT(*) FROM subscription_payment_recovery_actions WHERE action_type='RETRY_PAY' AND status='READY' AND scheduled_at='2026-12-09 10:00:00'"));assertEquals(0,scalar("SELECT COUNT(*) FROM recharge_orders"));assertEquals(0,scalar("SELECT COUNT(*) FROM quota_ledger"));
    }
    private static SubscriptionPaymentRecoveryService restart(SubscriptionPaymentRecoveryService original){
        var sql=new org.mybatis.spring.SqlSessionTemplate(factory);
        var service=new SubscriptionPaymentRecoveryService(sql.getMapper(UserSubscriptionMapper.class),sql.getMapper(SubscriptionPaymentRecoveryMapper.class),sql.getMapper(SubscriptionRecoveryActionMapper.class),sql.getMapper(SubscriptionRecoveryReplacementMapper.class),
                (SubscriptionPlanMapper)org.springframework.test.util.ReflectionTestUtils.getField(original,"plans"),sql.getMapper(RechargeOrderMapper.class),
                (SubscriptionRecoveryStripeGateway)org.springframework.test.util.ReflectionTestUtils.getField(original,"stripe"),(PaymentRecoveryProperties)org.springframework.test.util.ReflectionTestUtils.getField(original,"config"),
                (com.studyagent.service.domain.billing.PaymentRecoveryAccessService)org.springframework.test.util.ReflectionTestUtils.getField(original,"access"),new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));
        for(String field:java.util.List.of("passes","quotaProvider","zonePricing","analytics","internalNotifications","emailSender","frontendUrl","contractMutations"))org.springframework.test.util.ReflectionTestUtils.setField(service,field,org.springframework.test.util.ReflectionTestUtils.getField(original,field));
        return service;
    }
    private static SubscriptionRecoveryStripeGateway.Snapshot unpaidSnapshot(){
        var snapshot=paidSnapshot("in_test","hkd",18900,false);snapshot.invoice().setStatus("open");snapshot.invoice().setAmountPaid(0L);snapshot.invoice().setAmountRemaining(18900L);snapshot.paymentIntent().setStatus("requires_payment_method");snapshot.paymentIntent().setAmountReceived(0L);var error=new com.stripe.model.StripeError();error.setDeclineCode("insufficient_funds");snapshot.paymentIntent().setLastPaymentError(error);snapshot.invoice().getStatusTransitions().setPaidAt(null);snapshot.charge().setStatus("failed");snapshot.charge().setPaid(false);snapshot.charge().setCaptured(false);snapshot.charge().setAmountCaptured(0L);return snapshot;
    }
    private static boolean reserveOne(SubscriptionPaymentRecoveryService service,String orderNo){
        var order=new com.studyagent.infra.entity.RechargeOrderEntity();order.setOrderNo(orderNo);order.setOrderType("subscription_replacement");order.setClerkUserId("test_user");order.setTargetPlanCode("pro_monthly");order.setCurrency("hkd");order.setPriceCents(18900);order.setStatus("pending");
        var params=com.stripe.param.checkout.SessionCreateParams.builder().setCustomer("cus_test").setMode(com.stripe.param.checkout.SessionCreateParams.Mode.SUBSCRIPTION).putMetadata("order_no",orderNo).build();
        try{service.reserveReplacement("test_user",1L,order,params,java.time.Instant.now().plusSeconds(1860));return true;}
        catch(com.studyagent.service.domain.billing.BillingDomainException pending){assertEquals("PAYMENT_PROCESSING",pending.getCode());return false;}
    }
    private record PaymentFixture(SubscriptionPaymentRecoveryService service,SubscriptionRecoveryStripeGateway stripe,com.studyagent.service.domain.quota.AddonGrantService addons,com.studyagent.service.domain.billing.BillingQuotaGateway quota){}
    private PaymentFixture paymentFixture(boolean annual)throws Exception{
        var sessions=new org.mybatis.spring.SqlSessionTemplate(factory);var stripe=org.mockito.Mockito.mock(SubscriptionRecoveryStripeGateway.class);var plan=new com.studyagent.infra.entity.SubscriptionPlanEntity();
        plan.setPlanCode(annual?"pro_yearly":"pro_monthly");plan.setTier("pro");plan.setBillingInterval(annual?"year":"month");plan.setAssignmentQuota(12L);plan.setDetectionQuota(20000L);plan.setHumanizerQuota(10000L);
        var plans=org.mockito.Mockito.mock(SubscriptionPlanMapper.class);org.mockito.Mockito.when(plans.selectList(org.mockito.ArgumentMatchers.any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(java.util.List.of(plan));org.mockito.Mockito.when(plans.selectOne(org.mockito.ArgumentMatchers.any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(plan);
        var quotaService=new com.studyagent.infra.service.PlanQuotaServiceImpl(plans,org.mockito.Mockito.mock(AiFeatureDefsMapper.class),sessions.getMapper(UserAiQuotaMapper.class),sessions.getMapper(QuotaLedgerMapper.class),sessions.getMapper(UserSubscriptionMapper.class));
        // resumeEligible does not consult the catalog. All grant/user/ledger access is real SQL.
        assertNull(com.stripe.Stripe.apiKey,"Local DB IT must not initialize Stripe SDK credentials");
        var addons=org.mockito.Mockito.spy(new com.studyagent.infra.service.AddonGrantServiceImpl(
                org.mockito.Mockito.mock(AddonPackageDefMapper.class),sessions.getMapper(UserAddonGrantMapper.class),
                sessions.getMapper(QuotaLedgerMapper.class),sessions.getMapper(UserSubscriptionMapper.class)));
        var quota=new BillingQuotaGatewayImpl(quotaService,addons);
        var service=new SubscriptionPaymentRecoveryService(sessions.getMapper(UserSubscriptionMapper.class),sessions.getMapper(SubscriptionPaymentRecoveryMapper.class),sessions.getMapper(SubscriptionRecoveryActionMapper.class),sessions.getMapper(SubscriptionRecoveryReplacementMapper.class),plans,sessions.getMapper(RechargeOrderMapper.class),stripe,new PaymentRecoveryProperties(),org.mockito.Mockito.mock(com.studyagent.service.domain.billing.PaymentRecoveryAccessService.class),new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));
        org.springframework.test.util.ReflectionTestUtils.setField(service,"passes",sessions.getMapper(StudyPassMapper.class));
        var provider=org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(quota);org.springframework.test.util.ReflectionTestUtils.setField(service,"quotaProvider",provider);
        var snapshot=paidSnapshot("in_test",annual?"usd":"hkd",annual?38388:18900,annual);
        var admission=new java.util.LinkedHashMap<String,Object>();admission.put("sourceKey",annual?"pro_trial_to_yearly":"pro_trial_to_monthly");admission.put("cancelMode","RENEW");admission.put("contractHash",RecoveryContractClassifier.classify(snapshot.subscription(),null,true,"price_admit",1L).contractHash());
        var saved=new java.util.LinkedHashMap<String,Object>();saved.put("price_id","price_admit");saved.put("quantity",1);saved.put("subscription_item_id","si_admit");saved.put("recovery_admission",admission);
        try(var session=factory.openSession(true)){
            var roots=session.getMapper(SubscriptionPaymentRecoveryMapper.class);var r=roots.selectById(1L);r.setPlanCode(plan.getPlanCode());r.setCurrency(annual?"usd":"hkd");r.setBillingInterval(annual?"year":"month");r.setFirstFailedAt(NOW);r.setDeadlineAt(NOW.plusDays(30));r.setAmountDueMinor(annual?38388L:18900L);r.setAmountRemainingMinor(r.getAmountDueMinor());r.setContractSnapshot(new com.google.gson.Gson().toJson(saved));roots.updateById(r);
            var users=session.getMapper(UserSubscriptionMapper.class);var u=users.selectByUser("test_user");u.setStripeCustomerId("cus_test");u.setStripeSubscriptionId("sub_test");u.setPaymentRecoveryId(1L);u.setPlanCode(annual?"pro_trial_to_yearly":"pro_trial_to_monthly");u.setTier("pro");u.setStatus("past_due");users.updateById(u);
        }
        return new PaymentFixture(service,stripe,addons,quota);
    }
    private static org.mockito.MockedStatic<com.studyagent.common.datetime.DateTimeFormats> addonBusinessClock(){
        // Deterministic local processing time at the supplied paid_at; no Stripe Test Clock reads.
        var clock=org.mockito.Mockito.mockStatic(com.studyagent.common.datetime.DateTimeFormats.class,org.mockito.Mockito.CALLS_REAL_METHODS);
        clock.when(com.studyagent.common.datetime.DateTimeFormats::now).thenReturn(NOW.plusDays(28));
        return clock;
    }
    private static void insertPass(long id,String user,String status,LocalDateTime expires){
        try(var session=factory.openSession(true)){
            var pass=new com.studyagent.infra.entity.StudyPassEntity();pass.setId(id);pass.setClerkUserId(user);
            pass.setPassCode("study_pass_subscription_30d");pass.setStatus(status);pass.setStartedAt(NOW.minusDays(3));pass.setExpiresAt(expires);
            pass.setStripeSubscriptionId("sub_pass_"+id);pass.setStripeCheckoutSessionId("cs_pass_"+id);pass.setStripePaymentIntentId("pi_pass_"+id);
            pass.setStripePriceId("price_pass");pass.setPurchaseKey("pass_purchase_"+id);pass.setCancelAtPeriodEnd(false);
            pass.setRenewalPriceCents(99);pass.setRenewalCurrency("usd");pass.setBillingIntervalDays(30);pass.setLastPaidInvoiceId("in_pass_original_"+id);
            pass.setPricingZone("US");pass.setPriceVersion("v1");pass.setPricingCountry("US");pass.setCreatedAt(NOW.minusDays(3));pass.setUpdatedAt(NOW);
            assertEquals(1,session.getMapper(StudyPassMapper.class).insert(pass));
        }
    }
    private static void assertPass(long id,String status,LocalDateTime expires){
        try(var session=factory.openSession(true)){
            var pass=session.getMapper(StudyPassMapper.class).selectById(id);assertNotNull(pass);assertEquals(status,pass.getStatus());
            assertEquals(expires,pass.getExpiresAt(),"Pass supersession must preserve the original expiry");assertEquals(NOW.minusDays(3),pass.getStartedAt());
            assertEquals("sub_pass_"+id,pass.getStripeSubscriptionId());assertEquals("cs_pass_"+id,pass.getStripeCheckoutSessionId());
            assertEquals("pi_pass_"+id,pass.getStripePaymentIntentId());assertEquals("in_pass_original_"+id,pass.getLastPaidInvoiceId());
            assertEquals("pass_purchase_"+id,pass.getPurchaseKey());assertEquals("price_pass",pass.getStripePriceId());
            assertEquals("US",pass.getPricingZone());assertEquals("v1",pass.getPriceVersion());assertEquals("US",pass.getPricingCountry());
            assertEquals(99,pass.getRenewalPriceCents());assertEquals("usd",pass.getRenewalCurrency());assertEquals(30,pass.getBillingIntervalDays());
        }
    }
    private static void insertAddon(long id,String user,String status,String type,long remaining,LocalDateTime expires)throws Exception{
        try(var session=factory.openSession(true)){
            var grant=new com.studyagent.infra.entity.UserAddonGrantEntity();
            grant.setId(id);grant.setClerkUserId(user);grant.setFeatureCode("ai_detection");grant.setGrantType(type);
            grant.setAddonCode("detection_addon");grant.setStatus(status);grant.setInitialAmount(1000L);grant.setRemainingAmount(remaining);
            grant.setPurchasedAt(NOW.minusDays(20));grant.setExpiresAt(expires);grant.setPausedAt(NOW);grant.setVersion(0);
            grant.setCreatedAt(NOW.minusDays(20));grant.setUpdatedAt(NOW);
            session.getMapper(UserAddonGrantMapper.class).insert(grant);
        }
    }
    private static void assertAddon(long id,String status,long remaining,LocalDateTime expires,LocalDateTime paused,int version){
        try(var session=factory.openSession(true)){
            var grant=session.getMapper(UserAddonGrantMapper.class).selectById(id);assertNotNull(grant);
            assertEquals(status,grant.getStatus(),"status for grant "+id);assertEquals(remaining,grant.getRemainingAmount());
            assertEquals(expires,grant.getExpiresAt(),"Original expiration must be preserved for grant "+id);
            assertEquals(paused,grant.getPausedAt());assertEquals(version,grant.getVersion());
            assertEquals(1000L,grant.getInitialAmount());assertEquals(NOW.minusDays(20),grant.getPurchasedAt());
        }
    }
    private static SubscriptionRecoveryStripeGateway.Snapshot paidSnapshot(String invoiceId,String currency,long amount,boolean annual){
        var original=admissionSnapshot();var invoice=original.invoice();invoice.setId(invoiceId);invoice.setCustomer("cus_test");invoice.setSubscription("sub_test");invoice.setCurrency(currency);invoice.setAmountDue(amount);invoice.setAmountPaid(amount);invoice.setAmountRemaining(0L);invoice.setStatus("paid");invoice.setPaymentIntent("pi_"+invoiceId);invoice.setPaidOutOfBand(false);
        var transition=new com.stripe.model.Invoice.StatusTransitions();transition.setPaidAt(NOW.plusDays(28).atZone(com.studyagent.common.datetime.DateTimeFormats.APP_ZONE).toEpochSecond());invoice.setStatusTransitions(transition);
        var sub=original.subscription();sub.setId("sub_test");sub.setCustomer("cus_test");sub.setCurrency(currency);
        var line=original.lines().get(0);line.setSubscription("sub_test");line.setCurrency(currency);line.getPrice().setCurrency(currency);line.getPrice().setUnitAmount(amount);line.getPrice().getRecurring().setInterval(annual?"year":"month");
        var pi=original.paymentIntent();pi.setId(invoice.getPaymentIntent());pi.setStatus("succeeded");pi.setCustomer("cus_test");pi.setCurrency(currency);pi.setAmountReceived(amount);pi.setLatestCharge("ch_"+invoiceId);pi.setLastPaymentError(null);
        var ch=original.charge();ch.setId(pi.getLatestCharge());ch.setCustomer("cus_test");ch.setInvoice(invoiceId);ch.setPaymentIntent(pi.getId());ch.setCurrency(currency);ch.setAmount(amount);ch.setAmountCaptured(amount);ch.setAmountRefunded(0L);ch.setPaid(true);ch.setCaptured(true);ch.setStatus("succeeded");ch.setRefunded(false);ch.setDisputed(false);
        return new SubscriptionRecoveryStripeGateway.Snapshot(invoice,sub,null,original.lines(),pi,ch,NOW.plusDays(28).atZone(com.studyagent.common.datetime.DateTimeFormats.APP_ZONE).toInstant());
    }
    private void insertPaidChild()throws Exception{
        try(var session=factory.openSession(true)){var mapper=session.getMapper(SubscriptionPaymentRecoveryMapper.class);var original=mapper.selectById(1L);original.setId(2L);original.setOwnerRecoveryId(1L);original.setStripeInvoiceId("in_child");original.setVersion(0);mapper.insert(original);}
    }
    private void paymentReads(SubscriptionRecoveryStripeGateway stripe,java.util.Map<String,SubscriptionRecoveryStripeGateway.Snapshot> payments,boolean synchronize){
        var latch=new CountDownLatch(synchronize?2:0);var reads=new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.when(stripe.readInvoice(org.mockito.ArgumentMatchers.anyString())).thenAnswer(call->{assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive(),"No remote read may run in the grant transaction");if(synchronize && reads.incrementAndGet()<=2){latch.countDown();assertTrue(latch.await(5,TimeUnit.SECONDS));}return payments.get(call.getArgument(0));});
        org.mockito.Mockito.when(stripe.listInvoices("sub_test")).thenAnswer(call->{assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());return payments.values().stream().map(SubscriptionRecoveryStripeGateway.Snapshot::invoice).toList();});
    }
    private static com.stripe.model.Event paidEvent(String invoice){return com.stripe.net.ApiResource.GSON.fromJson("{\"id\":\"evt_paid_"+invoice+"\",\"type\":\"invoice.paid\",\"data\":{\"object\":{\"id\":\""+invoice+"\",\"object\":\"invoice\",\"subscription\":\"sub_test\"}}}",com.stripe.model.Event.class);}
    private static void concurrently(Callable<Boolean> first,Callable<Boolean> second)throws Exception{ExecutorService pool=Executors.newFixedThreadPool(2);try{var a=pool.submit(first);var b=pool.submit(second);assertTrue(a.get(10,TimeUnit.SECONDS));assertTrue(b.get(10,TimeUnit.SECONDS));}finally{pool.shutdownNow();}}
    private static long scalar(String sql)throws Exception{try(Connection c=ds.getConnection();Statement q=c.createStatement();ResultSet r=q.executeQuery(sql)){assertTrue(r.next());return r.getLong(1);}}
    private static void assertGranted(String source,int orderCount,int anchorCount,boolean annual)throws Exception{
        assertEquals(1,scalar("SELECT COUNT(*) FROM quota_ledger WHERE ledger_type='addon_resume'"));
        assertEquals(1,scalar("SELECT COUNT(*) FROM quota_ledger WHERE ledger_type='addon_resume' AND source_type='subscription' AND source_id='sub_test' AND subscription_id='sub_test' AND idempotency_key='recovery:"+source+":addons'"));
        assertEquals(orderCount,scalar("SELECT COUNT(*) FROM recharge_orders"));assertEquals(3,scalar("SELECT COUNT(*) FROM quota_ledger WHERE ledger_type='plan_reset'"));assertEquals(3,scalar("SELECT COUNT(*) FROM quota_ledger WHERE invoice_id='"+source+"'"));assertEquals(3,scalar("SELECT COUNT(*) FROM user_ai_quotas"));assertEquals(anchorCount,scalar("SELECT COUNT(*) FROM subscription_payment_recovery_actions WHERE action_type='SHIFT_RENEWAL'"));
        try(var session=factory.openSession(true)){
            var root=session.getMapper(SubscriptionPaymentRecoveryMapper.class).selectById(1L);assertEquals("PAID",root.getStatus());assertEquals("GRANTED",root.getGrantState());assertEquals(source,root.getEntitlementInvoiceId());assertEquals(NOW.plusDays(28),root.getEntitlementStartAt());assertEquals(annual?NOW.plusDays(28).plusYears(1):NOW.plusDays(28).plusMonths(1),root.getEntitlementEndAt());assertEquals(NOW.plusDays(28).plusMonths(1),root.getQuotaPeriodEndAt());
            for(var anchor:session.getMapper(SubscriptionRecoveryActionMapper.class).selectByRecovery(1L))if("SHIFT_RENEWAL".equals(anchor.getActionType())){
                var payload=SubscriptionRecoveryStripeGateway.thaw(anchor.getPayloadJson());assertEquals(source,((java.util.Map<?,?>)payload.get("metadata")).get("payment_recovery_invoice_id"));assertEquals(root.getEntitlementEndAt().atZone(com.studyagent.common.datetime.DateTimeFormats.APP_ZONE).toEpochSecond(),((Number)payload.get("trial_end")).longValue());
            }
            var order=session.getMapper(RechargeOrderMapper.class).selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.studyagent.infra.entity.RechargeOrderEntity>().eq(com.studyagent.infra.entity.RechargeOrderEntity::getStripeInvoiceId,source));assertNotNull(order);assertEquals("sub_test",order.getStripeSubscriptionId());assertEquals("completed",order.getStatus());
            var quotas=session.getMapper(UserAiQuotaMapper.class).selectList(null);for(var q:quotas){assertEquals(root.getQuotaPeriodEndAt(),q.getPlanPeriodEnd());assertEquals("task_create".equals(q.getFeatureCode())?12L:"ai_detection".equals(q.getFeatureCode())?20000L:10000L,q.getPlanBalance());}
        }
    }
    @Test void invoiceAndRetrySlotAreUnique() throws Exception {
        try(Connection c=ds.getConnection();Statement s=c.createStatement()) {
            assertThrows(SQLException.class,()->s.executeUpdate("INSERT INTO subscription_payment_recoveries SELECT 2,clerk_user_id,stripe_livemode,stripe_customer_id,stripe_subscription_id,stripe_invoice_id,owner_recovery_id,stripe_invoice_status,entitlement_invoice_id,plan_code,display_tier,billing_interval,interval_count,contract_snapshot,currency,amount_due_minor,amount_paid_minor,amount_remaining_minor,first_failed_at,deadline_at,status,latest_decline_code,latest_advice_code,failure_evidence,last_stripe_event_created_at,last_stripe_event_id,protection_state,paid_at,payment_evidence,entitlement_start_at,entitlement_end_at,quota_period_end_at,grant_state,anchor_sync_state,technical_invoice_ids,close_reason,refunded_amount_minor,refund_state,dispute_state,last_dispute_id,account_closed_at,review_state,review_reason,review_assignee,review_entered_at,review_due_at,review_last_progress_at,review_next_followup_at,next_reconcile_at,version,created_at,updated_at FROM subscription_payment_recoveries WHERE id=1"));
            insertAction(s,1,"retry-1",1);
            assertThrows(SQLException.class,()->insertAction(s,2,"other-key",1));
            assertThrows(SQLException.class,()->insertAction(s,3,"retry-1",2));
        }
    }
    @Test void activeReplacementOnlyReleasesAfterConfirmedTermination() throws Exception {
        try(Connection c=ds.getConnection();Statement s=c.createStatement()) {
            insertReplacement(s,1,"order1");
            assertThrows(SQLException.class,()->insertReplacement(s,2,"order2"));
            s.executeUpdate("UPDATE subscription_payment_recovery_replacements SET state='EXPIRED',active_subscription_key=NULL,closed_at=NOW(3) WHERE id=1");
            insertReplacement(s,2,"order2");
        }
    }
    @Test void twoRealConnectionsCanOnlyClaimOnceAndStaleWorkerCannotComplete() throws Exception {
        try(Connection c=ds.getConnection();Statement s=c.createStatement()){insertAction(s,1,"retry-1",1);}
        ExecutorService pool=Executors.newFixedThreadPool(2);
        CountDownLatch ready=new CountDownLatch(2),go=new CountDownLatch(1);
        Callable<Integer> claim=()->{try(var session=factory.openSession(true)){
            ready.countDown(); assertTrue(go.await(5,TimeUnit.SECONDS));
            return session.getMapper(SubscriptionRecoveryActionMapper.class).claim(1,0,Thread.currentThread().getName(),NOW.plusSeconds(120),NOW);
        }};
        try {
            Future<Integer>a=pool.submit(claim),b=pool.submit(claim);assertTrue(ready.await(5,TimeUnit.SECONDS));go.countDown();
            assertEquals(1,a.get(5,TimeUnit.SECONDS)+b.get(5,TimeUnit.SECONDS));
            try(var session=factory.openSession(true)) {
                var mapper=session.getMapper(SubscriptionRecoveryActionMapper.class);
                assertEquals(0,mapper.complete(1,1,"lost-lease","SUCCEEDED","{}",NOW));
                var action=mapper.selectByKey("retry-1");
                assertEquals(1,mapper.complete(1,action.getVersion(),action.getLeaseToken(),"UNKNOWN","{}",NOW));
                assertEquals(0,mapper.claim(1,2,"new-worker",NOW.plusSeconds(120),NOW));
            }
        } finally {pool.shutdownNow();}
    }
    @Test void contractMutationReservationSurvivesUnknownResultsAndIsClaimedOnlyOnce() throws Exception {
        try(Connection c=ds.getConnection();Statement s=c.createStatement()){s.executeUpdate("INSERT INTO subscription_price_rollouts(id,contract_kind,clerk_user_id,stripe_subscription_id,status) VALUES(1,'subscription','test_user','sub_test','preview')");}
        try(var session=factory.openSession(true)){
            var mapper=session.getMapper(SubscriptionPriceRolloutMapper.class);assertEquals(1,mapper.claimContractMutation(1,"preview","apply",NOW));assertEquals(0,mapper.claimContractMutation(1,"preview","another",NOW));assertEquals(java.util.List.of(1L),mapper.selectContractMutations("sub_test"));
            try(Statement s=session.getConnection().createStatement()){s.executeUpdate("UPDATE subscription_price_rollouts SET status='recovery_write_unknown' WHERE id=1");}
            assertEquals(java.util.List.of(1L),mapper.selectContractMutationsForUpdate("sub_test"));assertEquals(0,mapper.claimContractMutation(1,"preview","later",NOW.plusDays(2)));assertTrue(mapper.selectContractMutations("sub_foreign").isEmpty());
        }
    }
    @Test void currentMutationReadSeesAnOccupationCommittedAfterTheEarlierSnapshot() throws Exception {
        try(var first=factory.openSession(false);var second=factory.openSession(true)){
            var connection=first.getConnection();connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);connection.setAutoCommit(false);var mapper=first.getMapper(SubscriptionPriceRolloutMapper.class);assertTrue(mapper.selectContractMutations("sub_test").isEmpty());
            try(Statement s=second.getConnection().createStatement()){s.executeUpdate("INSERT INTO subscription_price_rollouts(id,contract_kind,clerk_user_id,stripe_subscription_id,status) VALUES(1,'subscription','test_user','sub_test','recovery_mutating')");}
            first.clearCache();assertTrue(mapper.selectContractMutations("sub_test").isEmpty());try(Statement s=connection.createStatement()){s.executeQuery("SELECT id FROM user_subscriptions WHERE id=1 FOR UPDATE");}
            assertEquals(java.util.List.of(1L),mapper.selectContractMutationsForUpdate("sub_test"));connection.rollback();
        }
    }
    @Test void mailTransportReplayKeepsItsOriginalKeyAndCannotCrossTheProviderWindow() throws Exception {
        var now=com.studyagent.common.datetime.DateTimeFormats.now().withNano(0);var first=now.minusMinutes(10);
        try(Connection c=ds.getConnection();var insert=c.prepareStatement("INSERT INTO subscription_payment_recovery_actions(id,recovery_id,action_type,action_key,target_id,scheduled_at,next_run_at,payload_json,status,first_dispatched_at,updated_at,provider_idempotency_key,transport_attempts) VALUES(1,1,'SEND_EMAIL','original-mail-key','in_test',?,?,'{}','UNKNOWN',?,?,'original-mail-key',1)")){
            insert.setTimestamp(1,Timestamp.valueOf(first));insert.setTimestamp(2,Timestamp.valueOf(first));insert.setTimestamp(3,Timestamp.valueOf(first));insert.setTimestamp(4,Timestamp.valueOf(now.minusMinutes(2)));insert.executeUpdate();
        }
        try(var session=factory.openSession(true)){
            var mapper=session.getMapper(SubscriptionRecoveryActionMapper.class);assertEquals(1,mapper.claimUnknownMail(1,0,"mail-owner",now.plusSeconds(120),now));
            assertEquals(0,mapper.claimUnknownMail(1,0,"another-owner",now.plusSeconds(120),now));assertEquals(1,mapper.markDispatched(1,1,"mail-owner",now,"{}"));
            var claimed=mapper.selectByKey("original-mail-key");assertEquals(first,claimed.getFirstDispatchedAt());assertEquals("original-mail-key",claimed.getProviderIdempotencyKey());assertEquals(2,claimed.getTransportAttempts());
            assertEquals(1,mapper.complete(1,1,"mail-owner","UNKNOWN","{}",now));assertEquals(0,mapper.claimUnknownMail(1,2,"too-soon",now.plusSeconds(120),now));
            assertEquals(0,mapper.claimUnknownMail(1,2,"too-late",first.plusHours(24).plusSeconds(120),first.plusHours(24)));
        }
    }
    @Test void supersededPassOwnershipMatchesOnlyPersistedPassTargets() throws Exception {
        try(Connection c=ds.getConnection();Statement s=c.createStatement()){
            s.executeUpdate("INSERT INTO subscription_payment_recovery_actions(id,recovery_id,action_type,action_key,target_id,scheduled_at,next_run_at,payload_json) VALUES(1,1,'END_STUDY_PASS','end-pass-7','7',NOW(3),NOW(3),'{\"study_pass_id\":7,\"subscription_id\":\"sub_pass\",\"checkout_id\":\"cs_pass\"}')");
        }
        try(var session=factory.openSession(true)){
            var mapper=session.getMapper(SubscriptionRecoveryActionMapper.class);assertEquals(1,mapper.selectStudyPassTargets("sub_pass").size());assertEquals(1,mapper.selectStudyPassTargets("cs_pass").size());assertTrue(mapper.selectStudyPassTargets("sub_foreign").isEmpty());
        }
    }
    @Test void actionClaimAuditUsesRealTimeRatherThanTestClockBusinessTime() throws Exception {
        try(Connection c=ds.getConnection();Statement s=c.createStatement()){insertAction(s,1,"retry-1",1);}
        var real=com.studyagent.common.datetime.DateTimeFormats.now();
        try(var session=factory.openSession(true)){
            var mapper=session.getMapper(SubscriptionRecoveryActionMapper.class);
            assertEquals(1,mapper.claim(1,0,"owner",real.plusSeconds(120),NOW.plusYears(1)));
            var action=mapper.selectByKey("retry-1");assertTrue(action.getUpdatedAt().isBefore(real.plusSeconds(10)));assertTrue(action.getUpdatedAt().isAfter(real.minusSeconds(10)));
        }
    }
    @Test void currentReadObservesAdmissionAfterAnEarlierRepeatableReadSnapshot() throws Exception {
        try(var first=factory.openSession(false);var second=factory.openSession(true)) {
            first.getConnection().setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            first.getConnection().setAutoCommit(false);
            var mapper=first.getMapper(SubscriptionPaymentRecoveryMapper.class);
            assertEquals("OPEN_AUTO",mapper.selectById(1L).getStatus());
            try(var statement=second.getConnection().createStatement()) {
                assertEquals(1,statement.executeUpdate("UPDATE subscription_payment_recoveries SET status='PAID' WHERE id=1"));
            }
            // MyBatis local cache is cleared as a real application transaction may issue other statements.
            first.clearCache();
            assertEquals("OPEN_AUTO",mapper.selectById(1L).getStatus());
            assertEquals("PAID",mapper.selectForUpdate(1L).getStatus());
            first.getConnection().rollback();
        }
    }
    @Test void successfulCasPersistsProviderIdentifiersAndRetainsPriorEvidence() throws Exception {
        try(Connection c=ds.getConnection();Statement s=c.createStatement()){insertAction(s,1,"retry-1",1);}
        try(var session=factory.openSession(true)) {
            var mapper=session.getMapper(SubscriptionRecoveryActionMapper.class);
            assertEquals(1,mapper.claim(1,0,"owner",NOW.plusSeconds(120),NOW));
            assertEquals(1,mapper.markDispatched(1,1,"owner",NOW,"{\"before_charge_id\":\"ch_before\"}"));
            assertEquals(1,mapper.complete(1,1,"owner","SUCCEEDED","{\"provider_request_id\":\"req_test\",\"payment_intent_id\":\"pi_test\",\"charge_id\":\"ch_test\",\"provider_message_id\":\"msg_test\"}",NOW));
            var action=mapper.selectByKey("retry-1");assertEquals("req_test",action.getProviderRequestId());
            assertEquals("pi_test",action.getPaymentIntentId());assertEquals("ch_test",action.getChargeId());assertEquals("msg_test",action.getProviderMessageId());
            assertTrue(action.getResultJson().contains("ch_before"));
        }
    }
    @Test void collectionReadCanBeReclaimedAfterItsLeaseAndBeyondTheWriteIdempotencyWindow() throws Exception {
        try(Connection c=ds.getConnection();Statement s=c.createStatement()){
            insertAction(s,1,"verify-collection",1);insertAction(s,2,"unknown-payment",2);
            s.executeUpdate("UPDATE subscription_payment_recovery_actions SET action_type='VERIFY_COLLECTION',retry_slot=NULL,status='UNKNOWN',first_dispatched_at=DATE_SUB('2026-11-11 10:00:00',INTERVAL 3 DAY) WHERE id=1");s.executeUpdate("UPDATE subscription_payment_recovery_actions SET status='UNKNOWN' WHERE id=2");
        }
        try(var session=factory.openSession(true)){
            var mapper=session.getMapper(SubscriptionRecoveryActionMapper.class);assertEquals(1,mapper.claimCollectionCheck(1,0,"reader",NOW.plusSeconds(120),NOW));assertEquals(0,mapper.claimCollectionCheck(1,0,"stale",NOW.plusSeconds(120),NOW));assertEquals(0,mapper.claimCollectionCheck(2,0,"not-a-read",NOW.plusSeconds(120),NOW));assertEquals(1,mapper.markDispatched(1,1,"reader",NOW,"{}"));assertEquals(1,mapper.complete(1,1,"reader","UNKNOWN","{}",NOW));assertEquals(1,mapper.claimCollectionCheck(1,2,"second-reader",NOW.plusSeconds(180),NOW.plusSeconds(60)));var read=mapper.selectByKey("verify-collection");assertEquals(NOW.minusDays(3),read.getFirstDispatchedAt());assertEquals("second-reader",read.getLeaseToken());
        }
    }
    @Test void failureAliasEnrichmentPreservesAnInflightWorkersLeaseAndProviderEvidence() throws Exception {
        try(Connection c=ds.getConnection();Statement s=c.createStatement()){
            insertAction(s,1,"failure-posthog",1);s.executeUpdate("UPDATE subscription_payment_recovery_actions SET action_type='CAPTURE_FAILURE',retry_slot=NULL WHERE id=1");
        }
        try(var session=factory.openSession(true)){
            var mapper=session.getMapper(SubscriptionRecoveryActionMapper.class);assertEquals(1,mapper.claim(1,0,"publisher",NOW.plusSeconds(120),NOW));assertEquals(1,mapper.markDispatched(1,1,"publisher",NOW,"{\"before_event\":\"evt_first\"}"));
            assertEquals(1,mapper.mergeFailureEvidence(1,"{\"failure_event_ids\":[\"evt_first\",\"evt_alias\"],\"failure_charge_ids\":[\"ch_failed\"]}"));var inflight=mapper.selectByKey("failure-posthog");assertEquals("DISPATCHING",inflight.getStatus());assertEquals("publisher",inflight.getLeaseToken());assertEquals(1,inflight.getVersion());
            assertEquals(1,mapper.complete(1,1,"publisher","UNKNOWN","{\"provider_request_id\":\"req_failure\"}",NOW));var completed=mapper.selectByKey("failure-posthog");assertTrue(completed.getResultJson().contains("evt_alias"));assertTrue(completed.getResultJson().contains("before_event"));assertEquals("req_failure",completed.getProviderRequestId());
        }
    }
    @Test void collectionRestoreKeepsItsSubscriptionBindingAcrossDispatchAndUnknownReadback() throws Exception {
        try(Connection c=ds.getConnection();Statement s=c.createStatement()){
            insertAction(s,1,"restore-owned",1);
            s.executeUpdate("UPDATE subscription_payment_recovery_actions SET action_type='RESTORE_ADVANCE',retry_slot=NULL,result_json=JSON_OBJECT('expected_subscription_id','sub_new','cause_action_id',99) WHERE id=1");
        }
        try(var session=factory.openSession(true)){
            var mapper=session.getMapper(SubscriptionRecoveryActionMapper.class);assertTrue(mapper.selectCollectionRestores("sub_new").isEmpty());
            assertEquals(1,mapper.claim(1,0,"restore-owner",NOW.plusSeconds(120),NOW));assertEquals(java.util.List.of(1L),mapper.selectCollectionRestores("sub_new"));assertTrue(mapper.selectCollectionRestores("sub_other").isEmpty());
            assertEquals(1,mapper.markDispatched(1,1,"restore-owner",NOW,"{\"before_auto_advance\":false}"));
            assertEquals(1,mapper.complete(1,1,"restore-owner","UNKNOWN","{\"provider_request_id\":\"req_restore\"}",NOW));
            assertEquals(java.util.List.of(1L),mapper.selectCollectionRestoresForUpdate("sub_new"));var saved=mapper.selectByKey("restore-owned");assertTrue(saved.getResultJson().contains("sub_new"));assertTrue(saved.getResultJson().contains("cause_action_id"));assertTrue(saved.getResultJson().contains("before_auto_advance"));
        }
    }
    @Test void firstUserRowLockSerializesCompetingReservations() throws Exception {
        try(Connection first=ds.getConnection();Connection second=ds.getConnection()) {
            first.setAutoCommit(false);second.setAutoCommit(false);
            try(Statement a=first.createStatement();Statement b=second.createStatement()) {
                a.executeQuery("SELECT id FROM user_subscriptions WHERE clerk_user_id='test_user' FOR UPDATE");
                b.execute("SET SESSION innodb_lock_wait_timeout=1");
                assertThrows(SQLException.class,()->b.executeUpdate("UPDATE user_subscriptions SET version=version+1 WHERE id=1"));
                first.commit();
                assertEquals(1,b.executeUpdate("UPDATE user_subscriptions SET version=version+1 WHERE id=1"));second.commit();
            }
        }
    }
    @Test void migrationIsRepeatableAndLegacyPointerIsNull() throws Exception {
        migrate();
        try(Connection c=ds.getConnection();Statement s=c.createStatement();ResultSet rs=s.executeQuery("SELECT payment_recovery_id FROM user_subscriptions WHERE id=1")) {
            assertTrue(rs.next());assertNull(rs.getObject(1));
        }
    }
    private static void insertAction(Statement s,int id,String key,int slot)throws SQLException {
        s.executeUpdate("INSERT INTO subscription_payment_recovery_actions(id,recovery_id,action_type,action_key,target_id,retry_slot,scheduled_at,next_run_at,payload_json) VALUES("+id+",1,'RETRY_PAY','"+key+"','in_test',"+slot+",'2026-11-11 10:00:00','2026-11-11 10:00:00','{}')");
    }
    private static void insertReplacement(Statement s,int id,String order)throws SQLException {
        s.executeUpdate("INSERT INTO subscription_payment_recovery_replacements(id,recovery_id,clerk_user_id,old_subscription_id,order_no,target_plan_code,state,active_subscription_key,paused_at,quote_snapshot) VALUES("+id+",1,'test_user','sub_test','"+order+"','pro_monthly','CREATING','sub_test',NOW(3),'{}')");
    }
    @AfterAll static void cleanup() throws Exception {
        if(ds==null)return;
        try {
            if(created)try(Connection c=ds.getConnection();Statement s=c.createStatement()) {
                s.execute("DROP TABLE IF EXISTS stripe_webhook_events,study_passes,study_pass_products,user_addon_grants,quota_ledger,user_ai_quotas,recharge_orders,subscription_payment_recovery_actions,subscription_payment_recovery_replacements,subscription_payment_recoveries,user_subscriptions,subscription_price_rollouts");
            }
        }finally{ds.close();}
    }
}
