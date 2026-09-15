# Pro Trial 试用期内升级到 Pro —— 测试环境验收清单

适用改动：后端 `feat/pro-trial-manual-upgrade`（已并入 `release/2.1.0`），前端 `Verla-AI/studyagent-fronted-v2` `release`。

## 一、结论：本次不需要执行任何新 SQL

- 无 schema 变更，无新增计划行。`pro_monthly` / `pro_yearly` 与 `pro_trial_to_monthly` / `pro_trial_to_yearly` 都是 Pro Trial 上线时就存在的行（`sql/076`、`sql/077`、`sql/078`）。
- 升级 Checkout 使用 inline `price_data` 建单（见 `BillingDomainServiceImpl.buildManualUpgradeCheckoutParams`），**不需要在 Stripe 后台新建 Product / Price**。
- 无新增环境变量，无新增 webhook 事件类型，无新增 i18n 文案：完全复用既有 `subscription_upgrade_manual` 链路（Basic / Plus → Pro 升级早已在用）。
- 旧代码不会留下脏数据：`rejectUnsupportedPaidPlanTarget` 在**释放 Stripe Schedule 之前**就拦掉了 pro trial → pro / pro_yearly，因此不存在"Schedule 被提前释放"的存量订阅。第二节的 SQL 仍建议跑一次做兜底。

部署前唯一要做的是**确认 sandbox price 绑定**（见第二节），部署后按第四节在网页上验收。

## 二、部署前只读核查（必须）

### 2.1 计划行与 Stripe sandbox price 绑定

测试环境后端使用 Stripe **Sandbox**（见 `Server_Config/docker-compose.yml`，`STRIPE_SECRET_KEY` 的 live 行是注释）。因此以下行的 `stripe_price_id` 必须是 sandbox 价，否则升级会在"切换订阅项"这一步报 Stripe 错误。

```sql
SELECT plan_code, tier, billing_interval, offer_kind, trial_days, converts_to_plan_code,
       price_cents, is_active, stripe_product_id, stripe_price_id
FROM subscription_plans
WHERE plan_code IN ('pro_trial_to_monthly','pro_trial_to_yearly','pro_monthly','pro_yearly','plus_monthly')
ORDER BY plan_code;
```

期望值（sandbox）：

| plan_code | stripe_product_id | stripe_price_id | 来源 |
| --- | --- | --- | --- |
| `pro_trial_to_monthly` | `prod_V2yikGRbHLdZR6` | `price_1U2slM7GRT6LLkI1RYM0atFA` | `sql/077` |
| `pro_trial_to_yearly` | `prod_V2yikGRbHLdZR6` | `price_1U2sli7GRT6LLkI13rZpSxDG` | `sql/077` |
| `pro_monthly` | `prod_V2XRxk7tA14xQr` | `price_1U2SNp7GRT6LLkI17wGltgTq` | `sql/v2_billing_stripe_sandbox_ids.sql` |
| `pro_yearly` | `prod_V2XRxk7tA14xQr` | `price_1U2SNt7GRT6LLkI1NwuJJvcc` | 同上 |

判据：四行的 `stripe_price_id` 与上表一致且 `is_active = 1`。为空或不匹配 → 先补跑对应 SQL 再验收（`sql/077` 仅测试/沙箱环境可用，不要在生产执行）。

### 2.2 存量 Pro Trial 订阅健康度

```sql
SELECT clerk_user_id, plan_code, status, subscription_phase, pending_plan_code,
       pending_effective_at, stripe_schedule_id, current_period_end, updated_at
FROM user_subscriptions
WHERE plan_code LIKE 'pro_trial%'
ORDER BY updated_at DESC;
```

判据：`status IN ('active','trialing')` 且 `subscription_phase = 'intro'` 的行，应当 `stripe_schedule_id` 非空、`pending_plan_code` 为 `pro_monthly` 或 `pro_yearly`。

若发现 `stripe_schedule_id` / `pending_plan_code` 为空：该订阅不会在试用结束时自动转正，且会按**周付 $2.99** 持续续费，需要在 Stripe 侧人工补 Schedule（当前没有对应的 ops 接口，参考 `BillingDomainServiceImpl.ensureIntroTrialConversionSchedule` 的两阶段配置：Phase 1 = 周付试用价到 `current_period_end`，Phase 2 = 对应正式 Pro 价）。

**查询返回空结果**：表示测试环境还没有任何 Pro Trial 订阅，既没有脏数据要修，也说明**Pro Trial 在该环境还没被购买过**——需要按 4.1 / 4.2 全新购买一次（这次购买本身就是"试用下单 + Schedule 建立"的首次验证）。先用下面两条确认是"确实没买过"而不是"查错了库"：

```sql
SELECT COUNT(*) AS total FROM user_subscriptions;
SELECT plan_code, COUNT(*) AS c FROM user_subscriptions GROUP BY plan_code ORDER BY c DESC;
SELECT order_type, COUNT(*) AS c FROM recharge_orders GROUP BY order_type ORDER BY c DESC;
```

判据：`user_subscriptions` 有数据但没有 `pro_trial%`，且 `recharge_orders` 里没有 `subscription_intro_trial` 类型的订单，才可判定"测试环境从未售出 Pro Trial"。

## 三、部署顺序（有依赖）

1. **先部署后端** `release/2.1.0`（含本次改动的合并提交）。
2. **再部署前端** `release`。

前端先行会出现按钮已放开、旧后端仍返回 `SUBSCRIPTION_STATE_INVALID` 的窗口，故不要颠倒。

## 四、网页验收清单

入口：`https://test.verla.io/pricing`

### 4.1 测试账号准备

用 `sql/ops_reset_user_billing_for_retest.sql` 把目标账号重置为"未付费"状态（脚本头部有配置项）。注意它只清本地库，**Stripe Customer 上的 `intro_trial_used` 元数据必须手动清掉或直接删除该测试客户**，否则 Pro Trial 下单会失败（1029）。

### 4.2 A —— 主路径（试用期内手动升级到 Pro 月付）

1. 用测试账号购买 Pro Trial（$2.99 / 7 天），确认定价页 Pro 卡片按钮为 **Upgrade**（改造前是 Not Available）。
2. **购买完成后立即核对转换 Schedule 已建立**（若 2.2 查询为空，这是测试环境首次验证这一步）：

   ```sql
   SELECT plan_code, status, subscription_phase, pending_plan_code, pending_effective_at,
          stripe_schedule_id, stripe_subscription_id, current_period_start, current_period_end
   FROM user_subscriptions WHERE clerk_user_id = '<uid>';
   ```

   期望：`plan_code = 'pro_trial_to_monthly'`、`subscription_phase = 'intro'`、`pending_plan_code = 'pro_monthly'`、`stripe_schedule_id` 非空，且 `current_period_end` ≈ 购买时间 + 7 天。任一为空都要先排查（否则试用结束时不会自动转正），在 Stripe 侧确认 Schedule 的 Phase 1 = 周付 $2.99、Phase 2 = `pro_monthly` 后再继续后续用例。

3. 点击 Upgrade → 进入 Stripe Checkout，金额应为 **Pro 月付全价**（不折抵已付的 $2.99）。
4. 用测试卡 `4242 4242 4242 4242` 支付 → 返回站点后账号计划变为 Pro。
5. 用 5.1 的 SQL 核对落库结果。

### 4.3 B —— 关键回归（放弃支付后仍会自动转正）

1. 试用期内点击 Upgrade 进入 Stripe Checkout，**直接返回/关闭页面，不支付**。
2. 回到定价页：应仍显示 Pro Trial，且提示将在试用结束时转为 Pro（`pending_plan_code` 保留）。
3. 在 Stripe Sandbox 打开该客户的订阅，确认 **Schedule 仍然存在**，Phase 2 为 `pro_monthly`。

第 3 步是本次改动最关键的回归点：若 Schedule 被提前释放，该订阅会一直按周扣 $2.99 且永不转正。

### 4.4 C —— 反向用例（不应放开）

- 试用期内点 Basic / Plus：仍为不可用（不给升级入口）。
- 月付 Pro 试用 → 年付 Pro：可升级。
- 年付 Pro 试用 → 月付 Pro：仍不可用（年→月会按年付策略重算周期却只收月费，故显式拦截）。

### 4.5 D —— 年付路径

年付 Pro 试用 → 年付 Pro：金额为**年费全价**，Stripe 侧 `trial_end` 重算为支付日 + 1 年，`quota_period_end` 按年付规则。

## 五、升级后核对

### 5.1 本地落库核对

```sql
SELECT plan_code, tier, status, subscription_phase, pending_plan_code, pending_effective_at,
       intro_trial_used_at, intro_trial_converted_at, current_period_start, current_period_end,
       quota_period_end, stripe_schedule_id
FROM user_subscriptions WHERE clerk_user_id = '<uid>';
```

期望：`plan_code LIKE 'pro\_%'`、`subscription_phase = 'standard'`、`pending_plan_code IS NULL`、`intro_trial_converted_at` 非空、`current_period_end` ≈ 支付日 + 1 月（年付则 + 1 年）。

```sql
SELECT order_no, order_type, status, plan_code, target_plan_code, upgrade_charge_type,
       quoted_amount_cents, price_cents, upgrade_effective_at, switch_attempts, failure_reason
FROM recharge_orders WHERE clerk_user_id = '<uid>' ORDER BY created_at DESC LIMIT 5;
```

期望：`order_type = 'subscription_upgrade_manual'`、`status = 'completed'`、`plan_code = 'pro_trial_to_monthly'`、`target_plan_code = 'pro_monthly'`、`upgrade_charge_type = 'monthly_full'`（年付目标为 `annual_full`）、`quoted_amount_cents` = 目标套餐全价、`failure_reason IS NULL`。

若 `status = 'switch_failed'`：`failure_reason` 记录原因，`ManualSubscriptionUpgradeRetryScheduler` 会每 10 分钟重试；持续失败且已过 Quote 有效期时会自动退款（`status = 'refunded'`）。

### 5.2 Stripe 侧核对

- 订阅的 item price 已切换为 `pro_monthly` / `pro_yearly`。
- `trial_end` = 切换时间 + 1 月 / + 1 年（即"周期从现在重算"，下次扣费在该时间点）。
- 原试用 Schedule 已释放（`stripe_schedule_id` 置空）。

## 六、"试用到期自动转正"如何验证

- 正常路径要等满 7 天，观察 `subscription_phase` 从 `intro` 变 `standard`、`plan_code` 变 `pro_monthly`。
- 想加速只能用 Stripe **Test Clock**，但时钟不能挂到已有订阅上：需要在该时钟下**新建客户与订阅**，用全新测试账号从购买 Pro Trial 开始走一遍。

## 七、本次不涉及

- 无需执行任何新 SQL 迁移；无需修改 `subscription_plans` 的 price 绑定（除非 2.1 核查不通过）。
- 无需 Stripe 后台新建 Product / Price（升级单用 inline `price_data`）。
- 无需新增环境变量或 webhook 事件。

## 八、未覆盖与风险

- 单元测试使用桩替代 Stripe 调用，**未在本地发起过任何真实 Stripe 请求**，真实 API 行为以测试环境为准。
- 未验证 webhook 重投递（`switch_failed` 后由定时任务重试）在真实 Stripe 下的表现。
- `pro_trial_to_yearly` 的存量订阅数量未知，4.5 的年付路径可能缺少现成账号，需临时购买年付试用。

## 九、相关代码位置

| 行为 | 位置 |
| --- | --- |
| 计划变更分类（试用分支、年→月拦截） | `agent-infra/.../billing/BillingDomainServiceImpl.java` `classifyPlanChange` |
| 试用源保留转换 Schedule / 不清理 pending | 同文件 `releasePendingScheduleStateBeforeManualUpgradeCheckout`、`clearPendingUpgradeStateForRetry`、`hasConversionSchedule` |
| 试用费不参与折抵 | 同文件 `resolveUpgradeCreditBasis` |
| 付费后切换订阅并激活计划 | `agent-infra/.../billing/StripeBillingWebhookService.java` `attemptManualUpgradeSwitch`、`clearPaidTrialPendingConversionTarget` |
| 前端按钮/动作分类（镜像后端规则） | 前端 `src/lib/billing.ts` `resolveSubscriptionPlanChangeAction` |
