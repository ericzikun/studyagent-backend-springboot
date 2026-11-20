package com.studyagent.infra.service.billing;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.studyagent.infra.mapper.*;
import com.zaxxer.hikari.*;
import org.apache.ibatis.session.SqlSessionFactory;
import java.nio.file.*;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;

/** Test-only empty local schema. No application datasource or remote test-server credentials are loaded. */
final class RecoverySandboxApplicationDatabase implements AutoCloseable {
    private HikariDataSource ds;
    private SqlSessionFactory factory;
    private String schema;
    private boolean created;
    RecoverySandboxApplicationDatabase() throws Exception {
        try {setup();catalog();} catch(Exception|AssertionError e){close();throw e;}
    }
    static String requireLocalSchema(String url){
        if(url==null||!url.matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):13306/payment_recovery_test_[a-z0-9_]+\\?.*serverTimezone=Asia/Shanghai.*"))
            throw new IllegalArgumentException("Only an explicitly named isolated local recovery test schema is allowed");
        return url.split("/")[3].split("\\?")[0];
    }
    HikariDataSource dataSource(){return ds;}
    SqlSessionFactory sessions(){return factory;}
    private void setup() throws Exception {
        String url = System.getenv("PAYMENT_RECOVERY_TEST_JDBC_URL");
        schema=requireLocalSchema(url);
        assertTrue(url.matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):13306/payment_recovery_test_[a-z0-9_]+\\?.*serverTimezone=Asia/Shanghai.*"), "Only isolated local test schemas are allowed");
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
    private void migrate() throws Exception {
        for(String name:java.util.List.of("097_subscription_payment_recovery.sql","098_subscription_period_time_zones.sql")){
            String sql=Files.readString(Path.of("../sql/"+name)).replace("USE studyagent;", "USE " + schema + ";");
            try(Connection c=ds.getConnection();Statement s=c.createStatement()) {
                for(String part:sql.split(";")) if(!part.isBlank()) s.execute(part);
            }
        }
    }
    private void catalog() throws Exception {
        try(Connection c=ds.getConnection();Statement q=c.createStatement()){
            String ddl=Files.readString(Path.of("../sql/056_subscription_catalog.sql"));int at=ddl.indexOf("CREATE TABLE IF NOT EXISTS subscription_plans (");q.execute(ddl.substring(at,ddl.indexOf(';',at)));
            q.execute("ALTER TABLE subscription_plans ADD COLUMN offer_kind VARCHAR(32),ADD COLUMN trial_days INT,ADD COLUMN converts_to_plan_code VARCHAR(64)");
        }
        factory.getConfiguration().addMapper(SubscriptionPlanMapper.class);
    }
    @Override public void close() throws Exception {
        if(ds==null)return;
        try{
            if(created)try(Connection c=ds.getConnection();Statement q=c.createStatement()){
                q.execute("DROP TABLE IF EXISTS stripe_webhook_events,study_passes,study_pass_products,user_addon_grants,quota_ledger,user_ai_quotas,recharge_orders,subscription_payment_recovery_actions,subscription_payment_recovery_replacements,subscription_payment_recoveries,user_subscriptions,subscription_price_rollouts,subscription_plans");
            }
        }finally{ds.close();}
    }
}
