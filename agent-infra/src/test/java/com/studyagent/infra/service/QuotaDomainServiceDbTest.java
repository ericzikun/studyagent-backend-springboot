package com.studyagent.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.studyagent.infra.entity.*;
import com.studyagent.infra.mapper.*;
import com.studyagent.service.domain.quota.ConsumeResult;
import com.studyagent.service.domain.quota.PlanQuotaService;
import com.studyagent.service.domain.quota.QuotaBalance;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A 侧额度内核 — 真实 DB 集成测试（每个测试自造数据自清）。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class QuotaDomainServiceDbTest {

    private static final String PREFIX = "dbtest_";

    private static SqlSessionFactory sqlSessionFactory;
    private static SqlSession sqlSession;
    private static HikariDataSource dataSource;

    // --- mappers ---
    private static AiFeatureDefsMapper featureDefMapper;
    private static AiFeaturePackageMapper packageMapper;
    private static UserAiQuotaMapper quotaMapper;
    private static QuotaLedgerMapper ledgerMapper;
    private static UserAddonGrantMapper grantMapper;
    private static QuotaLedgerAllocationMapper allocMapper;
    private static SubscriptionPlanMapper planMapper;
    private static AddonPackageDefMapper addonMapper;

    // --- services ---
    private static QuotaDomainServiceImpl quotaService;
    private static PlanQuotaServiceImpl planService;
    private static AddonGrantServiceImpl addonService;

    @BeforeAll
    static void setUp() throws Exception {
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl("jdbc:mysql://localhost:13306/studyagent?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true");
        hikari.setUsername("studyagent");
        hikari.setPassword("studyagent2024");
        hikari.setMaximumPoolSize(4);
        dataSource = new HikariDataSource(hikari);

        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());

        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setPlugins(interceptor);
        factoryBean.setTypeAliasesPackage("com.studyagent.infra.entity");
        sqlSessionFactory = factoryBean.getObject();

        var config = sqlSessionFactory.getConfiguration();
        config.addMapper(AiFeatureDefsMapper.class);
        config.addMapper(AiFeaturePackageMapper.class);
        config.addMapper(UserAiQuotaMapper.class);
        config.addMapper(QuotaLedgerMapper.class);
        config.addMapper(UserAddonGrantMapper.class);
        config.addMapper(QuotaLedgerAllocationMapper.class);
        config.addMapper(SubscriptionPlanMapper.class);
        config.addMapper(AddonPackageDefMapper.class);

        sqlSession = sqlSessionFactory.openSession();
        featureDefMapper = sqlSession.getMapper(AiFeatureDefsMapper.class);
        packageMapper = sqlSession.getMapper(AiFeaturePackageMapper.class);
        quotaMapper = sqlSession.getMapper(UserAiQuotaMapper.class);
        ledgerMapper = sqlSession.getMapper(QuotaLedgerMapper.class);
        grantMapper = sqlSession.getMapper(UserAddonGrantMapper.class);
        allocMapper = sqlSession.getMapper(QuotaLedgerAllocationMapper.class);
        planMapper = sqlSession.getMapper(SubscriptionPlanMapper.class);
        addonMapper = sqlSession.getMapper(AddonPackageDefMapper.class);

        PlanQuotaService noOpPlanQuotaService = new PlanQuotaService() {
            @Override
            public void refreshPlanQuotaIfNeeded(String clerkUserId, String featureCode) {
            }

            @Override
            public void refreshAllPlanQuotasIfNeeded(String clerkUserId) {
            }

            @Override
            public void resetFromPaidInvoice(String clerkUserId, String subscriptionId, String planCode,
                                             Instant quotaPeriodStart, Instant quotaPeriodEnd, String invoiceId) {
            }

            @Override
            public void addFullPlanForUpgrade(String clerkUserId, String subscriptionId, String planCode,
                                              Instant quotaPeriodStart, Instant quotaPeriodEnd, String invoiceId) {
            }

            @Override
            public void clearPlanQuota(String clerkUserId, String subscriptionId, String idempotencyKey) {
            }
        };
        quotaService = new QuotaDomainServiceImpl(featureDefMapper, packageMapper, quotaMapper,
                ledgerMapper, grantMapper, allocMapper, noOpPlanQuotaService);
        planService = new PlanQuotaServiceImpl(planMapper, featureDefMapper, quotaMapper, ledgerMapper, null);
        addonService = new AddonGrantServiceImpl(addonMapper, grantMapper, ledgerMapper);
    }

    @AfterAll
    static void tearDown() {
        if (sqlSession != null) sqlSession.close();
        if (dataSource != null) dataSource.close();
    }

    @BeforeEach
    void cleanOwnData() {
        // 每个测试前清理自己的测试用户数据
        List<UserAiQuotaEntity> mine = quotaMapper.selectList(
                new LambdaQueryWrapper<UserAiQuotaEntity>().likeRight(UserAiQuotaEntity::getClerkUserId, PREFIX));
        for (UserAiQuotaEntity q : mine) {
            allocMapper.delete(new LambdaQueryWrapper<QuotaLedgerAllocationEntity>()
                    .inSql(QuotaLedgerAllocationEntity::getQuotaLedgerId,
                            "SELECT id FROM quota_ledger WHERE clerk_user_id = '" + q.getClerkUserId() + "'"));
            ledgerMapper.delete(new LambdaQueryWrapper<QuotaLedgerEntity>()
                    .eq(QuotaLedgerEntity::getClerkUserId, q.getClerkUserId()));
            grantMapper.delete(new LambdaQueryWrapper<UserAddonGrantEntity>()
                    .eq(UserAddonGrantEntity::getClerkUserId, q.getClerkUserId()));
            quotaMapper.deleteById(q.getId());
        }
        // 清理只有 grant 没有 quota 的测试用户（如 addon_grant_pause_resume）
        List<UserAddonGrantEntity> orphanGrants = grantMapper.selectList(
                new LambdaQueryWrapper<UserAddonGrantEntity>().likeRight(UserAddonGrantEntity::getClerkUserId, PREFIX));
        for (UserAddonGrantEntity g : orphanGrants) {
            String uid = g.getClerkUserId();
            if (quotaMapper.selectCount(
                    new LambdaQueryWrapper<UserAiQuotaEntity>().eq(UserAiQuotaEntity::getClerkUserId, uid)) == 0) {
                allocMapper.delete(new LambdaQueryWrapper<QuotaLedgerAllocationEntity>()
                        .inSql(QuotaLedgerAllocationEntity::getQuotaLedgerId,
                                "SELECT id FROM quota_ledger WHERE clerk_user_id = '" + uid + "'"));
                ledgerMapper.delete(new LambdaQueryWrapper<QuotaLedgerEntity>()
                        .eq(QuotaLedgerEntity::getClerkUserId, uid));
                grantMapper.delete(new LambdaQueryWrapper<UserAddonGrantEntity>()
                        .eq(UserAddonGrantEntity::getClerkUserId, uid));
            }
        }
    }

    // ═══════════════════════════════════════════════════
    // 场景 1: plan reset + invoice 幂等
    // ═══════════════════════════════════════════════════
    @Test
    @Order(1)
    void resetFromPaidInvoice_idempotent() {
        String uid = PREFIX + "s1";
        seedQuota(uid, "task_create", 1, 0);
        seedQuota(uid, "ai_detection", 1, 0);
        seedQuota(uid, "humanizer", 1, 0);

        Instant start = Instant.parse("2026-06-15T00:00:00Z");
        Instant end = Instant.parse("2026-07-15T00:00:00Z");

        planService.resetFromPaidInvoice(uid, "sub_s1", "basic_monthly", start, end, "inv_s1");
        assertPlan(uid, "task_create", 3L);
        assertPlan(uid, "ai_detection", 3L);
        assertPlan(uid, "humanizer", 2L);

        // 重放 — 不应变化
        planService.resetFromPaidInvoice(uid, "sub_s1", "basic_monthly", start, end, "inv_s1");
        assertPlan(uid, "task_create", 3L);
        assertPlan(uid, "ai_detection", 3L);
        assertPlan(uid, "humanizer", 2L);

        long count = ledgerMapper.selectCount(
                new LambdaQueryWrapper<QuotaLedgerEntity>()
                        .eq(QuotaLedgerEntity::getClerkUserId, uid)
                        .eq(QuotaLedgerEntity::getLedgerType, "plan_reset"));
        assertEquals(3, count, "only 3 plan_reset ledgers, not 6");

        System.out.println("✅ S1 PASS — plan reset idempotent");
    }

    // ═══════════════════════════════════════════════════
    // 场景 2: legacy 余额先迁移进 addon，再按 free → plan → addon 消费
    // ═══════════════════════════════════════════════════
    @Test
    @Order(2)
    void consume_free_plan_addon_with_legacy_migration() {
        String uid = PREFIX + "s2";

        // seed: free=1, plan=2, addon grant=3, legacy(paid)=4
        UserAiQuotaEntity quota = new UserAiQuotaEntity();
        quota.setClerkUserId(uid);
        quota.setFeatureCode("task_create");
        quota.setFreeBalance(1L);
        quota.setPlanBalance(2L);
        quota.setPlanPeriodEnd(LocalDateTime.now().plusDays(20));
        quota.setPaidBalance(4L);
        quota.setVersion(0);
        quota.setCreatedAt(LocalDateTime.now());
        quota.setUpdatedAt(LocalDateTime.now());
        quotaMapper.insert(quota);

        UserAddonGrantEntity grant = new UserAddonGrantEntity();
        grant.setClerkUserId(uid);
        grant.setFeatureCode("task_create");
        grant.setGrantType("addon");
        grant.setAddonCode("addon_assignment_3");
        grant.setStatus("active");
        grant.setInitialAmount(3L);
        grant.setRemainingAmount(3L);
        grant.setStripeSessionId("cs_" + uid);
        grant.setPurchasedAt(LocalDateTime.now());
        grant.setExpiresAt(LocalDateTime.now().plusDays(7));
        grant.setVersion(0);
        grant.setCreatedAt(LocalDateTime.now());
        grant.setUpdatedAt(LocalDateTime.now());
        grantMapper.insert(grant);

        // consume 5: free(1) + plan(2) + addon(2) = 5
        ConsumeResult result = quotaService.consume(uid, "task_create", 5L,
                "verla_session", "session_s2", Map.of("conversation_id", 1L));
        assertTrue(result.ledgerId() > 0);

        // verify quota
        UserAiQuotaEntity q = quotaMapper.selectById(quota.getId());
        assertEquals(0L, q.getFreeBalance());
        assertEquals(0L, q.getPlanBalance());
        assertEquals(0L, q.getPaidBalance(), "legacy migrated into add-on grants");

        // verify original addon grant
        UserAddonGrantEntity g = grantMapper.selectById(grant.getId());
        assertEquals(1L, g.getRemainingAmount(), "3 - 2 = 1 remaining");
        assertEquals("active", g.getStatus());

        List<UserAddonGrantEntity> grants = grantMapper.selectList(
                new LambdaQueryWrapper<UserAddonGrantEntity>()
                        .eq(UserAddonGrantEntity::getClerkUserId, uid)
                        .eq(UserAddonGrantEntity::getFeatureCode, "task_create")
                        .orderByAsc(UserAddonGrantEntity::getId));
        UserAddonGrantEntity migratedGrant = grants.stream()
                .filter(item -> "legacy_migration".equals(item.getGrantType()))
                .findFirst()
                .orElseThrow();
        assertEquals(4L, migratedGrant.getRemainingAmount());
        assertNotNull(migratedGrant.getExpiresAt());

        // verify allocations
        List<QuotaLedgerAllocationEntity> allocs = allocMapper.selectList(
                new LambdaQueryWrapper<QuotaLedgerAllocationEntity>()
                        .eq(QuotaLedgerAllocationEntity::getQuotaLedgerId, result.ledgerId())
                        .orderByAsc(QuotaLedgerAllocationEntity::getId));
        assertEquals(3, allocs.size());
        assertEquals("free", allocs.get(0).getPoolType());
        assertEquals(1L, allocs.get(0).getAmount());
        assertEquals("plan", allocs.get(1).getPoolType());
        assertEquals(2L, allocs.get(1).getAmount());
        assertEquals("addon", allocs.get(2).getPoolType());
        assertEquals(2L, allocs.get(2).getAmount());
        assertEquals(g.getId(), allocs.get(2).getGrantId());

        System.out.println("✅ S2 PASS — legacy migrated into add-on grant before consume");
    }

    // ═══════════════════════════════════════════════════
    // 场景 3: 退款精确恢复
    // ═══════════════════════════════════════════════════
    @Test
    @Order(3)
    void refund_restores_pools() {
        String uid = PREFIX + "s3";
        seedQuota(uid, "task_create", 3, 2);

        ConsumeResult c = quotaService.consume(uid, "task_create", 1L,
                "verla_session", "session_s3", Map.of());
        long lid = c.ledgerId();

        quotaService.refund(lid, "test_refund");

        UserAiQuotaEntity q = quotaMapper.selectOne(
                new LambdaQueryWrapper<UserAiQuotaEntity>()
                        .eq(UserAiQuotaEntity::getClerkUserId, uid)
                        .eq(UserAiQuotaEntity::getFeatureCode, "task_create"));
        assertEquals(3L, q.getFreeBalance(), "free restored to 3");
        assertEquals(2L, q.getPlanBalance(), "plan restored to 2");

        QuotaLedgerEntity refund = ledgerMapper.selectOne(
                new LambdaQueryWrapper<QuotaLedgerEntity>()
                        .eq(QuotaLedgerEntity::getIdempotencyKey, "refund:" + lid));
        assertNotNull(refund);
        assertEquals(1L, refund.getAmount());

        System.out.println("✅ S3 PASS — refund restored");
    }

    // ═══════════════════════════════════════════════════
    // 场景 5: FIFO 消费 — 先过期先扣
    // ═══════════════════════════════════════════════════
    @Test
    @Order(4)
    void fifo_earliest_expiring_first() {
        String uid = PREFIX + "s5";
        seedQuota(uid, "task_create", 0, 0);

        UserAddonGrantEntity early = addonGrant(uid, "cs_fifo_early", 2L, 3);
        UserAddonGrantEntity late = addonGrant(uid, "cs_fifo_late", 3L, 30);

        // consume 4: early(2) + late(2) = 4
        ConsumeResult result = quotaService.consume(uid, "task_create", 4L,
                "verla_session", "session_s5", Map.of());

        UserAddonGrantEntity e = grantMapper.selectById(early.getId());
        assertEquals(0L, e.getRemainingAmount());
        assertEquals("depleted", e.getStatus());

        UserAddonGrantEntity l = grantMapper.selectById(late.getId());
        assertEquals(1L, l.getRemainingAmount());
        assertEquals("active", l.getStatus());

        List<QuotaLedgerAllocationEntity> allocs = allocMapper.selectList(
                new LambdaQueryWrapper<QuotaLedgerAllocationEntity>()
                        .eq(QuotaLedgerAllocationEntity::getQuotaLedgerId, result.ledgerId())
                        .orderByAsc(QuotaLedgerAllocationEntity::getId));
        assertEquals(2, allocs.size());
        assertEquals(early.getId(), allocs.get(0).getGrantId());
        assertEquals(2L, allocs.get(0).getAmount());
        assertEquals(late.getId(), allocs.get(1).getGrantId());
        assertEquals(2L, allocs.get(1).getAmount());

        System.out.println("✅ S5 PASS — FIFO consumption");
    }

    // ═══════════════════════════════════════════════════
    // 场景 4: 过期池退款 → compensation grant
    // ═══════════════════════════════════════════════════
    @Test
    @Order(5)
    void refund_expired_creates_compensation() {
        String uid = PREFIX + "s4";
        seedQuota(uid, "task_create", 0, 0);

        UserAddonGrantEntity expiredGrant = addonGrant(uid, "cs_expired", 0L, -10);
        expiredGrant.setStatus("depleted");
        expiredGrant.setRemainingAmount(0L);
        expiredGrant.setExpiresAt(LocalDateTime.now().minusDays(10));
        grantMapper.updateById(expiredGrant);

        // 手动造 consume ledger + 过期 allocation
        QuotaLedgerEntity consumeLedger = new QuotaLedgerEntity();
        consumeLedger.setLedgerNo("QL_MANUAL_S4");
        consumeLedger.setClerkUserId(uid);
        consumeLedger.setFeatureCode("task_create");
        consumeLedger.setLedgerType("consume");
        consumeLedger.setAmount(-2L);
        consumeLedger.setSourceType("verla_session");
        consumeLedger.setSourceId("session_s4");
        consumeLedger.setFreeBalanceAfter(0L);
        consumeLedger.setPaidBalanceAfter(0L);
        consumeLedger.setCreatedAt(LocalDateTime.now());
        ledgerMapper.insert(consumeLedger);

        QuotaLedgerAllocationEntity expiredAlloc = new QuotaLedgerAllocationEntity();
        expiredAlloc.setQuotaLedgerId(consumeLedger.getId());
        expiredAlloc.setPoolType("addon");
        expiredAlloc.setGrantId(expiredGrant.getId());
        expiredAlloc.setAmount(2L);
        expiredAlloc.setSourcePeriodEnd(LocalDateTime.now().minusDays(5));
        expiredAlloc.setCreatedAt(LocalDateTime.now());
        allocMapper.insert(expiredAlloc);

        // refund
        quotaService.refund(consumeLedger.getId(), "test_expired_refund");

        // 应有 compensation grant
        List<UserAddonGrantEntity> compensations = grantMapper.selectList(
                new LambdaQueryWrapper<UserAddonGrantEntity>()
                        .eq(UserAddonGrantEntity::getClerkUserId, uid)
                        .eq(UserAddonGrantEntity::getGrantType, "compensation"));
        assertEquals(1, compensations.size());
        assertEquals("compensation", compensations.get(0).getGrantType());
        assertEquals("active", compensations.get(0).getStatus());
        assertEquals(2L, compensations.get(0).getInitialAmount());
        assertEquals(2L, compensations.get(0).getRemainingAmount());
        assertNotNull(compensations.get(0).getExpiresAt());

        QuotaLedgerEntity refundLedger = ledgerMapper.selectOne(
                new LambdaQueryWrapper<QuotaLedgerEntity>()
                        .eq(QuotaLedgerEntity::getIdempotencyKey, "refund:" + consumeLedger.getId()));
        assertNotNull(refundLedger);

        System.out.println("✅ S4 PASS — compensation grant created");
    }

    // ═══════════════════════════════════════════════════
    // 场景 6: 并发消费 — 只有一个成功
    // ═══════════════════════════════════════════════════
    @Test
    @Order(6)
    void concurrent_last_unit_only_one_wins() throws Exception {
        String uid = PREFIX + "s6";
        seedQuota(uid, "task_create", 0, 1); // plan=1

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(2);
        var success = new AtomicInteger(0);
        var failure = new AtomicInteger(0);

        Callable<Void> task = () -> {
            latch.countDown();
            latch.await();
            try {
                quotaService.consume(uid, "task_create", 1L,
                        "verla_session", "session_s6", Map.of());
                success.incrementAndGet();
            } catch (Exception ex) {
                failure.incrementAndGet();
            }
            return null;
        };

        executor.invokeAll(List.of(task, task), 10, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertEquals(1, success.get(), "exactly one succeeds");
        assertEquals(1, failure.get(), "exactly one fails");

        UserAiQuotaEntity q = quotaMapper.selectOne(
                new LambdaQueryWrapper<UserAiQuotaEntity>()
                        .eq(UserAiQuotaEntity::getClerkUserId, uid)
                        .eq(UserAiQuotaEntity::getFeatureCode, "task_create"));
        assertEquals(0L, q.getPlanBalance());

        System.out.println("✅ S6 PASS — concurrent: " + success.get() + " success, " + failure.get() + " fail");
    }

    // ═══════════════════════════════════════════════════
    // addon: grant → pause → resume
    // ═══════════════════════════════════════════════════
    @Test
    @Order(7)
    void addon_grant_pause_resume() {
        String uid = PREFIX + "lifecycle";

        addonService.grantFromPaidCheckout(uid, "addon_assignment_3",
                "cs_lifecycle", "pi_lifecycle", Instant.now());

        UserAddonGrantEntity grant = grantMapper.selectOne(
                new LambdaQueryWrapper<UserAddonGrantEntity>()
                        .eq(UserAddonGrantEntity::getStripeSessionId, "cs_lifecycle"));
        assertNotNull(grant);
        assertEquals("addon", grant.getGrantType());
        assertEquals("active", grant.getStatus());
        assertEquals(3L, grant.getRemainingAmount());

        // 幂等
        addonService.grantFromPaidCheckout(uid, "addon_assignment_3",
                "cs_lifecycle", "pi_lifecycle", Instant.now());
        assertEquals(1L, grantMapper.selectCount(
                new LambdaQueryWrapper<UserAddonGrantEntity>()
                        .eq(UserAddonGrantEntity::getStripeSessionId, "cs_lifecycle")));

        // pause
        addonService.pauseAll(uid, "sub_lifecycle", "paused:sub_lifecycle");
        grant = grantMapper.selectById(grant.getId());
        assertEquals("paused", grant.getStatus());

        // resume
        addonService.resumeEligible(uid, "sub_lifecycle", "resume:sub_lifecycle");
        grant = grantMapper.selectById(grant.getId());
        assertEquals("active", grant.getStatus());
        assertNull(grant.getPausedAt(), "pausedAt should be cleared on resume");

        System.out.println("✅ addon lifecycle PASS — grant → pause → resume");
    }

    // ═══════════════════════════════════════════════════
    // balance API 不再显示 legacy，直接并入 addon
    // ═══════════════════════════════════════════════════
    @Test
    @Order(8)
    void balance_api_merges_legacy_into_addon_pool() {
        String uid = PREFIX + "balance";

        UserAiQuotaEntity quota = new UserAiQuotaEntity();
        quota.setClerkUserId(uid);
        quota.setFeatureCode("task_create");
        quota.setFreeBalance(1L);
        quota.setPlanBalance(2L);
        quota.setPlanPeriodEnd(LocalDateTime.now().plusDays(20));
        quota.setPaidBalance(4L);
        quota.setVersion(0);
        quota.setCreatedAt(LocalDateTime.now());
        quota.setUpdatedAt(LocalDateTime.now());
        quotaMapper.insert(quota);

        UserAddonGrantEntity grant = new UserAddonGrantEntity();
        grant.setClerkUserId(uid);
        grant.setFeatureCode("task_create");
        grant.setGrantType("addon");
        grant.setAddonCode("addon_assignment_3");
        grant.setStatus("active");
        grant.setInitialAmount(3L);
        grant.setRemainingAmount(3L);
        grant.setStripeSessionId("cs_" + uid);
        grant.setPurchasedAt(LocalDateTime.now());
        grant.setExpiresAt(LocalDateTime.now().plusDays(7));
        grant.setVersion(0);
        grant.setCreatedAt(LocalDateTime.now());
        grant.setUpdatedAt(LocalDateTime.now());
        grantMapper.insert(grant);

        QuotaBalance balance = quotaService.getUserQuota(uid, "task_create");
        assertEquals(1L, balance.freeBalance());
        assertEquals(2L, balance.planBalance());
        assertEquals(7L, balance.addonBalance());
        assertEquals(0L, balance.legacyBalance());
        assertEquals(10L, balance.totalAvailable());
        assertEquals(2, balance.addonItems().size());

        System.out.println("✅ balance API PASS — legacy merged into addon: free=" + balance.freeBalance()
                + " plan=" + balance.planBalance() + " addon=" + balance.addonBalance()
                + " legacy=" + balance.legacyBalance());
    }

    // ═══════════════════════════════════════════════════
    // legacy 退款不再回写 paid_balance，而是补到 addon grant
    // ═══════════════════════════════════════════════════
    @Test
    @Order(9)
    void refund_legacy_allocation_creates_legacy_migration_refund_grant() {
        String uid = PREFIX + "legacy_refund";
        seedQuota(uid, "task_create", 0, 0);

        QuotaLedgerEntity consumeLedger = new QuotaLedgerEntity();
        consumeLedger.setLedgerNo("QL_MANUAL_LEGACY_REFUND");
        consumeLedger.setClerkUserId(uid);
        consumeLedger.setFeatureCode("task_create");
        consumeLedger.setLedgerType("consume");
        consumeLedger.setAmount(-2L);
        consumeLedger.setSourceType("verla_session");
        consumeLedger.setSourceId("session_legacy_refund");
        consumeLedger.setFreeBalanceAfter(0L);
        consumeLedger.setPlanBalanceAfter(0L);
        consumeLedger.setAddonBalanceAfter(0L);
        consumeLedger.setPaidBalanceAfter(0L);
        consumeLedger.setCreatedAt(LocalDateTime.now());
        ledgerMapper.insert(consumeLedger);

        QuotaLedgerAllocationEntity legacyAlloc = new QuotaLedgerAllocationEntity();
        legacyAlloc.setQuotaLedgerId(consumeLedger.getId());
        legacyAlloc.setPoolType("legacy");
        legacyAlloc.setGrantId(null);
        legacyAlloc.setAmount(2L);
        legacyAlloc.setSourcePeriodEnd(null);
        legacyAlloc.setCreatedAt(LocalDateTime.now());
        allocMapper.insert(legacyAlloc);

        quotaService.refund(consumeLedger.getId(), "legacy_refund");

        UserAiQuotaEntity quota = quotaMapper.selectOne(
                new LambdaQueryWrapper<UserAiQuotaEntity>()
                        .eq(UserAiQuotaEntity::getClerkUserId, uid)
                        .eq(UserAiQuotaEntity::getFeatureCode, "task_create"));
        assertNotNull(quota);
        assertEquals(0L, quota.getPaidBalance(), "refund should not resurrect legacy paid balance");

        List<UserAddonGrantEntity> grants = grantMapper.selectList(
                new LambdaQueryWrapper<UserAddonGrantEntity>()
                        .eq(UserAddonGrantEntity::getClerkUserId, uid)
                        .eq(UserAddonGrantEntity::getFeatureCode, "task_create"));
        UserAddonGrantEntity refundGrant = grants.stream()
                .filter(item -> "legacy_migration_refund".equals(item.getGrantType()))
                .findFirst()
                .orElseThrow();
        assertEquals("active", refundGrant.getStatus());
        assertEquals(2L, refundGrant.getRemainingAmount());
        assertNotNull(refundGrant.getExpiresAt());
    }

    // ═══════════════════════════════════════════════════
    // helpers
    // ═══════════════════════════════════════════════════

    private void seedQuota(String uid, String feature, long free, long plan) {
        UserAiQuotaEntity q = new UserAiQuotaEntity();
        q.setClerkUserId(uid);
        q.setFeatureCode(feature);
        q.setFreeBalance(free);
        q.setPlanBalance(plan);
        q.setPaidBalance(0L);
        q.setVersion(0);
        q.setCreatedAt(LocalDateTime.now());
        q.setUpdatedAt(LocalDateTime.now());
        quotaMapper.insert(q);
    }

    private UserAddonGrantEntity addonGrant(String uid, String sessionId, long remaining, int daysToExpire) {
        UserAddonGrantEntity g = new UserAddonGrantEntity();
        g.setClerkUserId(uid);
        g.setFeatureCode("task_create");
        g.setGrantType("addon");
        g.setAddonCode("addon_assignment_3");
        g.setStatus("active");
        g.setInitialAmount(3L);
        g.setRemainingAmount(remaining);
        g.setStripeSessionId(sessionId);
        g.setPurchasedAt(LocalDateTime.now());
        g.setExpiresAt(LocalDateTime.now().plusDays(daysToExpire));
        g.setVersion(0);
        g.setCreatedAt(LocalDateTime.now());
        g.setUpdatedAt(LocalDateTime.now());
        grantMapper.insert(g);
        return g;
    }

    private void assertPlan(String uid, String feature, long expected) {
        UserAiQuotaEntity q = quotaMapper.selectOne(
                new LambdaQueryWrapper<UserAiQuotaEntity>()
                        .eq(UserAiQuotaEntity::getClerkUserId, uid)
                        .eq(UserAiQuotaEntity::getFeatureCode, feature));
        assertNotNull(q, "quota missing: " + feature);
        assertEquals(expected, q.getPlanBalance(), feature + " plan_balance");
    }
}
