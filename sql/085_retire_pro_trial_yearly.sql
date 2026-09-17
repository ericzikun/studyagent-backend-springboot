-- 停售 Pro Trial 年付 SKU（pro_trial_to_yearly）。
--
-- 1) is_active = 0
--    目录（getCatalog 只出 is_active=1）不再返回该 SKU；
--    下单/切换目标走 requirePlan（带 is_active 过滤），直接失败 INVALID_PLAN。
-- 2) converts_to_plan_code = 'pro_monthly'
--    新购买已不可能，此项用于让任何"转换目标再推导"都得到月付（存量重建/展示一致）。
--
-- 存量订阅不受影响：权益与续费走 requireRuntimePlan、webhook 按 stripe_price_id 反查套餐，
-- 都不看 is_active（仓库既有的"下架但继续履约"模式）。
--
-- 注意：本脚本**不改写 Stripe 侧已建立的转换 Schedule**。存量年付试用（到期会转 pro_yearly）
-- 由一次性任务 billing.pro-trial-yearly-repair 重指为 pro_monthly，
-- 见 doc/Pro-Trial升级Pro-测试环境验收清单.md。
--
-- 保留 billing_interval = 'year' 不动：它描述 SKU 自身的计费节奏，改动会影响存量用户在
-- 账号接口看到的当前周期；计价已按目标套餐周期计算，无需依赖该字段。
--
-- 幂等，可重复执行。Sandbox / 生产各执行一次。

USE studyagent;

START TRANSACTION;

UPDATE subscription_plans
SET is_active = 0,
    updated_at = CURRENT_TIMESTAMP
WHERE plan_code = 'pro_trial_to_yearly';

UPDATE subscription_plans
SET converts_to_plan_code = 'pro_monthly',
    updated_at = CURRENT_TIMESTAMP
WHERE plan_code = 'pro_trial_to_yearly';

COMMIT;

-- 验证
--   pro_trial_to_yearly  : is_active = 0，converts_to_plan_code = 'pro_monthly'
--   pro_trial_to_monthly : is_active = 1，converts_to_plan_code = 'pro_monthly'（唯一可售 Pro Trial）
SELECT plan_code, tier, billing_interval, offer_kind, trial_days, converts_to_plan_code,
       price_cents, is_active, stripe_product_id, stripe_price_id
FROM subscription_plans
WHERE plan_code IN ('pro_trial_to_monthly', 'pro_trial_to_yearly')
ORDER BY plan_code;
