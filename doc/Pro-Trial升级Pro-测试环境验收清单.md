# Pro Trial 试用期内购买正式套餐 —— 测试环境验收清单

适用改动：
- 后端 `release/2.1.0`（`feat/pro-trial-manual-upgrade` + `feat/pro-trial-any-plan-purchase`）
- 前端 `Verla-AI/studyagent-fronted-v2` `release`

**行为总览**：Pro Trial 用户可以在试用期内**立即购买 Basic / Plus / Pro 任一套餐**（月付或年付）——当场支付目标套餐全价，计费周期从支付日重算，剩余试用天数不折抵；放弃支付则保留到期自动转 Pro 的兜底。

## 一、结论：本次不需要执行任何新 SQL

- 无 schema 变更，无新增计划行。所需的正式套餐与 Pro Trial SKU 都是既有行（`sql/076`、`sql/077`、`sql/078`）。
- 购买 Checkout 使用 inline `price_data` 建单（见 `BillingDomainServiceImpl.buildManualUpgradeCheckoutParams`），**不需要在 Stripe 后台新建 Product / Price**。
- 无新增环境变量，无新增 webhook 事件类型：完全复用既有 `subscription_upgrade_manual` 链路。
- 历史版本中没有脏数据：旧代码在**释放 Stripe Schedule 之前**就拦掉了不合法的目标套餐，因此不存在"Schedule 被提前释放"的存量订阅。第二节的 SQL 仍建议跑一次做兜底。

部署前唯一要做的是**确认 sandbox price 绑定**（见第二节），部署后按第四节在网页上验收。

> 命名说明：购买更低 tier 时订单仍写 `order_type=subscription_upgrade_manual`、埋点仍是 `subscription_upgrade_checkout`（对降级方向语义偏松，为不动 DB 契约与埋点而沿用）。

## 二、部署前只读核查（必须）

### 2.1 计划行与 Stripe sandbox price 绑定

测试环境后端使用 Stripe **Sandbox**（`STRIPE_SECRET_KEY` 的 live 行是注释）。以下行的 `stripe_price_id` 必须是 sandbox 价，否则购买会在"切换订阅项"这一步报 Stripe 错误。

```sql
SELECT plan_code, tier, billing_interval, offer_kind, trial_days, converts_to_plan_code,
       price_cents, is_active, stripe_product_id, stripe_price_id
FROM subscription_plans
WHERE plan_code IN ('pro_trial_to_monthly','pro_trial_to_yearly','pro_monthly','pro_yearly',
                    'plus_monthly','plus_yearly','basic_monthly','basic_yearly')
ORDER BY plan_code;
```

期望值（sandbox，来源见 `sql/077` 与 `sql/v2_billing_stripe_sandbox_ids.sql`）：

| plan_code | stripe_product_id | stripe_price_id |
| --- | --- | --- |
| `pro_trial_to_monthly` | `prod_V2yikGRbHLdZR6` | `price_1U2slM7GRT6LLkI1RYM0atFA` |
| `pro_trial_to_yearly` | `prod_V2yikGRbHLdZR6` | `price_1U2sli7GRT6LLkI13rZpSxDG` |
| `basic_monthly` | `prod_V2XQKO3FZiPKZI` | `price_1U2SNZ7GRT6LLkI1XgzarCSV` |
| `basic_yearly` | `prod_V2XQKO3FZiPKZI` | `price_1U2SNd7GRT6LLkI1icheuGId` |
| `plus_monthly` | `prod_V2XQrWdt06ryxW` | `price_1U2SNh7GRT6LLkI1xwWeZJn2` |
| `plus_yearly` | `prod_V2XQrWdt06ryxW` | `price_1U2SNl7GRT6LLkI1wICSgCbq` |
| `pro_monthly` | `prod_V2XRxk7tA14xQr` | `price_1U2SNp7GRT6LLkI17wGltgTq` |
| `pro_yearly` | `prod_V2XRxk7tA14xQr` | `price_1U2SNt7GRT6LLkI1NwuJJvcc` |

判据：`stripe_price_id` 与上表一致且 `is_active = 1`。为空或不匹配 → 先补跑对应 SQL 再验收（`sql/077` 仅测试/沙箱环境可用，不要在生产执行）。

### 2.2 存量 Pro Trial 订阅健康度

```sql
SELECT clerk_user_id, plan_code, status, subscription_phase, pending_plan_code,
       pending_effective_at, stripe_schedule_id, current_period_end, updated_at
FROM user_subscriptions
WHERE plan_code LIKE 'pro_trial%'
ORDER BY updated_at DESC;
```

判据：`status IN ('active','trialing')` 且 `subscription_phase = 'intro'` 的行，应当 `stripe_schedule_id` 非空、`pending_plan_code` 为 `pro_monthly` 或 `pro_yearly`。

若发现 `stripe_schedule_id` / `pending_plan_code` 为空：该订阅不会在试用结束时自动转正，且会按**周付 $2.99** 持续续费，需要在 Stripe 侧人工补 Schedule（参考 `BillingDomainServiceImpl.ensureIntroTrialConversionSchedule` 的两阶段配置：Phase 1 = 周付试用价到 `current_period_end`，Phase 2 = 对应正式 Pro 价）。

**查询返回空结果**：表示测试环境还没有任何 Pro Trial 订阅，既没有脏数据要修，也说明 **Pro Trial 在该环境还没被购买过**——需要按 4.1 / 4.2 全新购买一次（这次购买本身就是"试用下单 + Schedule 建立"的首次验证）。先用下面三条确认是"确实没买过"而不是"查错了库"：

```sql
SELECT COUNT(*) AS total FROM user_subscriptions;
SELECT plan_code, COUNT(*) AS c FROM user_subscriptions GROUP BY plan_code ORDER BY c DESC;
SELECT order_type, COUNT(*) AS c FROM recharge_orders GROUP BY order_type ORDER BY c DESC;
```

判据：`user_subscriptions` 有数据但没有 `pro_trial%`，且 `recharge_orders` 里没有 `subscription_intro_trial` 类型的订单，才可判定"测试环境从未售出 Pro Trial"。

## 三、部署顺序（有依赖）

1. **先部署后端** `release/2.1.0`（含本次改动的合并提交）。
2. **再部署前端** `release`。

顺序颠倒会出现按钮已放开、旧后端仍返回 `SUBSCRIPTION_STATE_INVALID` 的窗口。

## 四、网页验收清单

入口：`https://test.verla.io/pricing`

### 4.1 测试账号准备

用 `sql/ops_reset_user_billing_for_retest.sql` 把目标账号重置为"未付费"状态（脚本头部有配置项）。注意它只清本地库，**Stripe Customer 上的 `intro_trial_used` 元数据必须手动清掉或直接删除该测试客户**，否则 Pro Trial 下单会失败（1029）。

若账号在历史 Stripe 账号里还有活跃订阅（如旧 `sub_...71hkxuoncT...`），它属于已被替换的旧 Stripe 账号，当前 webhook secret 验签不会通过，不会把本地行写回；不影响验收。

### 4.2 A —— 试用期购买 Pro（月付）

1. 用测试账号购买 Pro Trial（$2.99 / 7 天）。
2. **购买完成后立即核对转换 Schedule 已建立**（若 2.2 查询为空，这是测试环境首次验证这一步）：

   ```sql
   SELECT plan_code, status, subscription_phase, pending_plan_code, pending_effective_at,
          stripe_schedule_id, stripe_subscription_id, current_period_start, current_period_end
   FROM user_subscriptions WHERE clerk_user_id = '<uid>';
   ```

   期望：`plan_code = 'pro_trial_to_monthly'`、`subscription_phase = 'intro'`、`pending_plan_code = 'pro_monthly'`、`stripe_schedule_id` 非空，且 `current_period_end` ≈ 购买时间 + 7 天。任一为空都要先排查（否则试用结束时不会自动转正），确认 Schedule 的 Phase 1 = 周付 $2.99、Phase 2 = `pro_monthly` 后再继续。

3. 定价页 Pro 卡片按钮应为 **Upgrade** → 点击进入 Stripe Checkout，金额应为 **Pro 月付全价**（$79.99，不折抵已付的 $2.99）。
4. 用测试卡 `4242 4242 4242 4242` 支付 → 返回站点后账号计划变为 Pro。
5. 用 5.1 的 SQL 核对落库结果。

### 4.3 B —— 关键回归（放弃支付后仍会自动转正）

1. 试用期内点 Upgrade 进入 Stripe Checkout，**直接返回/关闭页面，不支付**。
2. 回到定价页：应仍显示 Pro Trial，且提示将在试用结束时转为 Pro（`pending_plan_code` 保留）。
3. 在 Stripe Sandbox 打开该客户的订阅，确认 **Schedule 仍然存在**，Phase 2 为 `pro_monthly`。

第 3 步是最关键的回归点：若 Schedule 被提前释放，该订阅会一直按周扣 $2.99 且永不转正。

### 4.4 C —— 试用期购买 Basic / Plus（本轮新增能力）

1. 试用期内，Basic 与 Plus 卡片的按钮应为 **Downgrade**（不是 Not Available），点击后先弹确认弹窗。
2. 确认弹窗必须是**立即付费语义**：标题下方描述为"You'll switch to Verla {套餐} right away: {金额} is charged now and your new billing period starts today. Remaining trial days are not credited."，套餐摘要的生效日期为 **Today**、单价按目标套餐的计费周期显示。
3. 确认后进入 Stripe Checkout，金额应为目标套餐全价（Basic 月付 $19.99 / Plus 月付 $39.99 / 对应年付价）。
4. 支付成功后核对：`plan_code` 落到目标套餐、`subscription_phase = 'standard'`、`pending_plan_code` 为空、`stripe_schedule_id` 已释放。
5. **额度必须是替换而不是叠加**：`user_ai_quotas` 的 plan 额度应等于目标套餐额度（Basic 3 / Plus 8 次作业），不能出现"试用额度 + 目标套餐额度"累加。

### 4.5 D —— 年付与跨周期组合

- 年付 Pro 试用 → 年付 Pro：金额 = 年费全价，`trial_end` = 支付日 + 1 年。
- 年付 Pro 试用 → Basic/Plus/Pro **月付**：金额 = 目标**月费全价**，`trial_end` = 支付日 + 1 月（这是本轮修正点：旧计价按年付抵扣分支会算成"收月费给一年"）。
- 月付 Pro 试用 → 任一**年付**目标：金额 = 目标年费全价，周期 +1 年。

### 4.6 E —— 反向用例（仍不支持）

- 试用期内点另一个 Pro Trial SKU：仍不可用（不能重复购买试用）。
- 付费 Pro 用户（非试用）降级到 Basic/Plus：仍走原有的"到期后切换"延迟流程（弹窗文案应为"订阅有效到 X，之后自动切换到 Y"），不要变成立即付费。

## 五、购买后核对

### 5.1 本地落库核对

```sql
SELECT plan_code, tier, status, subscription_phase, pending_plan_code, pending_effective_at,
       intro_trial_used_at, intro_trial_converted_at, current_period_start, current_period_end,
       quota_period_end, stripe_schedule_id
FROM user_subscriptions WHERE clerk_user_id = '<uid>';
```

期望：`plan_code` = 目标套餐、`subscription_phase = 'standard'`、`pending_plan_code IS NULL`、`intro_trial_converted_at` 非空、`current_period_end` ≈ 支付日 + 1 月（年付则 + 1 年）。

```sql
SELECT order_no, order_type, status, plan_code, target_plan_code, upgrade_charge_type,
       quoted_amount_cents, price_cents, upgrade_effective_at, switch_attempts, failure_reason
FROM recharge_orders WHERE clerk_user_id = '<uid>' ORDER BY created_at DESC LIMIT 5;
```

期望：`order_type = 'subscription_upgrade_manual'`、`status = 'completed'`、`plan_code` = 试用 SKU、`target_plan_code` = 目标套餐、`upgrade_charge_type` 为 `monthly_full`（月付目标）或 `annual_full`（年付目标）、`quoted_amount_cents` = 目标套餐全价、`failure_reason IS NULL`。

额度核对（购买更低 tier 时必须是替换）：

```sql
SELECT q.feature_code, q.plan_balance, q.plan_period_start, q.plan_period_end
FROM user_ai_quotas q WHERE q.clerk_user_id = '<uid>';
```

若 `status = 'switch_failed'`：`failure_reason` 记录原因，`ManualSubscriptionUpgradeRetryScheduler` 每 10 分钟重试；持续失败且已过 Quote 有效期时会自动退款（`status = 'refunded'`）。

### 5.2 Stripe 侧核对

- 订阅的 item price 已切换为目标套餐价（降级方向即价格下调）。
- `trial_end` = 切换时间 + 1 月 / + 1 年（周期从现在重算）。
- 原试用 Schedule 已释放（`stripe_schedule_id` 置空）。

## 六、"试用到期自动转正"如何验证

- 正常路径要等满 7 天，观察 `subscription_phase` 从 `intro` 变 `standard`、`plan_code` 变 `pro_monthly`。
- 想加速只能用 Stripe **Test Clock**，但时钟不能挂到已有订阅上：需要在该时钟下**新建客户与订阅**，用全新测试账号从购买 Pro Trial 开始走一遍。

## 七、本次不涉及

- 无需执行任何新 SQL 迁移；无需修改 `subscription_plans` 的 price 绑定（除非 2.1 核查不通过）。
- 无需 Stripe 后台新建 Product / Price（购买单用 inline `price_data`）。
- 无需新增环境变量或 webhook 事件。
- **SKU 购买弹窗（`sku-purchase-provider`）未同步**：该表面在试用期购买 Basic/Plus 时不再弹确认（直接进 Stripe Checkout），且它的弹窗文案是"到期后切换"语义，补正确文案需要新增 17 个语言包的 key，本轮未做；定价页（`/pricing`）已完整覆盖。

## 八、未覆盖与风险

- 单元测试使用桩替代 Stripe 调用，**未在本地发起过任何真实 Stripe 请求**，真实 API 行为以测试环境为准。
- 未验证 webhook 重投递（`switch_failed` 后由定时任务重试）在真实 Stripe 下的表现。
- 计价器由"按源周期分支"改为"按月付目标恒为全额月费"，该组合此前在所有付费路径上被年→月守卫挡住，改动对线上现有付费流程无影响，但需在 sandbox 跑通一次年付试用买月付。

## 九、相关代码位置

| 行为 | 位置 |
| --- | --- |
| 计划变更分类（试用放行三种套餐、年→月放开） | `agent-infra/.../billing/BillingDomainServiceImpl.java` `classifyPlanChange` |
| 计价（月付目标恒为全额月费） | `agent-infra/.../billing/UpgradeChargeCalculator.java` |
| 试用源保留转换 Schedule / 不清理 pending | `BillingDomainServiceImpl` `releasePendingScheduleStateBeforeManualUpgradeCheckout`、`clearPendingUpgradeStateForRetry`、`hasConversionSchedule` |
| 试用费不参与折抵 | 同文件 `resolveUpgradeCreditBasis` |
| 付费后切换订阅、激活计划、降级时替换额度 | `agent-infra/.../billing/StripeBillingWebhookService.java` `attemptManualUpgradeSwitch`、`clearPaidTrialPendingConversionTarget` |
| 前端按钮/动作分类与标签（镜像后端规则） | 前端 `src/lib/billing.ts` `resolveSubscriptionPlanChangeAction`、`resolveSubscriptionPlanButtonIntent` |
| 前端确认弹窗（立即付费 vs 到期切换） | 前端 `src/features/pricing/pricing-plans.tsx` `PendingPlanConfirmation.mode`、`getPackageChangeDescription`、`getPackageSummary` |
