-- ============================================================
-- A 侧商业化额度 — 集成测试数据准备
-- 用法：docker exec -i studyagent-mysql-local mysql -ustudyagent -pstudyagent2024 studyagent < this_file.sql
-- ============================================================

-- 清理旧测试数据
DELETE FROM quota_ledger_allocations WHERE quota_ledger_id IN (
    SELECT id FROM quota_ledger WHERE clerk_user_id LIKE 'test_%'
);
DELETE FROM quota_ledger WHERE clerk_user_id LIKE 'test_%';
DELETE FROM user_addon_grants WHERE clerk_user_id LIKE 'test_%';
DELETE FROM user_ai_quotas WHERE clerk_user_id LIKE 'test_%';

-- ============================================================
-- 场景 1：plan 发放 + invoice 幂等
-- 预期：resetFromPaidInvoice 后 plan_balance = 3/3/2，重放不变
-- ============================================================
INSERT INTO user_ai_quotas (clerk_user_id, feature_code, free_balance, plan_balance, paid_balance, version, created_at, updated_at)
VALUES
    ('test_scenario_1', 'task_create',    1, 0, 0, 0, NOW(), NOW()),
    ('test_scenario_1', 'ai_detection',   1, 0, 0, 0, NOW(), NOW()),
    ('test_scenario_1', 'humanizer',      1, 0, 0, 0, NOW(), NOW());

-- ============================================================
-- 场景 2：消费顺序 free → plan → addon → legacy
-- 初始: free=1, plan=2, addon grant=3, legacy(paid)=4 — consume 5
-- 预期分配: free:1, plan:2, addon:2 → addon grant remaining=1
-- ============================================================
INSERT INTO user_ai_quotas (clerk_user_id, feature_code, free_balance, plan_balance, plan_period_end, paid_balance, version, created_at, updated_at)
VALUES
    ('test_scenario_2', 'task_create', 1, 2, DATE_ADD(NOW(), INTERVAL 20 DAY), 4, 0, NOW(), NOW());

INSERT INTO user_addon_grants (clerk_user_id, feature_code, grant_type, addon_code, status, initial_amount, remaining_amount, stripe_session_id, purchased_at, expires_at, version, created_at, updated_at)
VALUES
    ('test_scenario_2', 'task_create', 'addon', 'addon_assignment_3', 'active', 3, 3, 'cs_test_scenario_2_addon', NOW(), DATE_ADD(NOW(), INTERVAL 7 DAY), 0, NOW(), NOW());

-- ============================================================
-- 场景 3：退款精确恢复
-- 对场景 2 的 consume ledger 执行 refund
-- 预期：free→1, plan→2, addon grant remaining→3
-- ============================================================

-- ============================================================
-- 场景 4：过期池退款 → compensation grant
-- addon grant expires_at 已过期，refund 应创建 30 天 compensation grant
-- ============================================================
INSERT INTO user_ai_quotas (clerk_user_id, feature_code, free_balance, plan_balance, paid_balance, version, created_at, updated_at)
VALUES
    ('test_scenario_4', 'task_create', 0, 0, 0, 0, NOW(), NOW());

INSERT INTO user_addon_grants (clerk_user_id, feature_code, grant_type, addon_code, status, initial_amount, remaining_amount, stripe_session_id, purchased_at, expires_at, version, created_at, updated_at)
VALUES
    ('test_scenario_4', 'task_create', 'addon', 'addon_assignment_3', 'depleted', 3, 0, 'cs_test_scenario_4_expired', DATE_SUB(NOW(), INTERVAL 40 DAY), DATE_SUB(NOW(), INTERVAL 10 DAY), 0, NOW(), NOW());

-- ============================================================
-- 场景 5：多个 addon grant — FIFO 消费（先过期先扣）
-- grant_early: expires +3天, remaining=2
-- grant_late:  expires +30天, remaining=3
-- consume 4 → 预期: grant_early 扣 2 (depleted), grant_late 扣 2 (remaining→1)
-- ============================================================
INSERT INTO user_ai_quotas (clerk_user_id, feature_code, free_balance, plan_balance, paid_balance, version, created_at, updated_at)
VALUES
    ('test_scenario_5', 'task_create', 0, 0, 0, 0, NOW(), NOW());

INSERT INTO user_addon_grants (clerk_user_id, feature_code, grant_type, addon_code, status, initial_amount, remaining_amount, stripe_session_id, purchased_at, expires_at, version, created_at, updated_at)
VALUES
    ('test_scenario_5', 'task_create', 'addon', 'addon_assignment_3', 'active', 3, 2, 'cs_test_fifo_early', NOW(), DATE_ADD(NOW(), INTERVAL 3 DAY),  0, NOW(), NOW()),
    ('test_scenario_5', 'task_create', 'addon', 'addon_assignment_3', 'active', 3, 3, 'cs_test_fifo_late',  NOW(), DATE_ADD(NOW(), INTERVAL 30 DAY), 0, NOW(), NOW());

-- ============================================================
-- 场景 6：并发消费最后 1 次 — 只有一个成功
-- plan_balance = 1, 两个请求同时 consume 1
-- 预期：一个成功（affected rows=1），一个失败（affected rows=0，抛 IllegalStateException）
-- ============================================================
INSERT INTO user_ai_quotas (clerk_user_id, feature_code, free_balance, plan_balance, paid_balance, version, created_at, updated_at)
VALUES
    ('test_scenario_6', 'task_create', 0, 1, 0, 0, NOW(), NOW());

-- ============================================================
-- 验证查询（执行后运行下面的 SELECT 确认数据就位）
-- ============================================================
SELECT '=== 测试数据概览 ===' AS '';
SELECT clerk_user_id, feature_code, free_balance, plan_balance, paid_balance, version
FROM user_ai_quotas WHERE clerk_user_id LIKE 'test_%'
ORDER BY clerk_user_id, feature_code;

SELECT '' AS '';
SELECT clerk_user_id, feature_code, grant_type, status, initial_amount, remaining_amount,
       DATEDIFF(expires_at, NOW()) AS days_to_expire, stripe_session_id
FROM user_addon_grants WHERE clerk_user_id LIKE 'test_%'
ORDER BY clerk_user_id, expires_at;
