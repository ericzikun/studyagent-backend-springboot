# 区域定价（方案 A）生产上线计划

- 日期：2026-10-19
- 后端：`release/2.1.0` @ `1bf02958`（运行时代码到 `a95d9ead`，`1bf02958` 仅测试修正）
- 前端：`release` @ `7e34c07f`
- Stripe：live 账号 `acct_1SrJ7Q7GRT6LLkI1`（HK）
- 本文不含任何密钥；所有命令通过环境变量读取，不要把 live key / proxy secret / webhook secret 写进仓库或日志

---

## 0. 结论摘要

| 项 | 状态 |
|---|---|
| Stripe live 价格矩阵 | ✅ 已由同事配好，17 档 × 11 SKU = 187 个绑定 |
| live 金额与测试矩阵 | ✅ 逐条比对 **0 差异**（仅 Price ID 不同） |
| live Webhook | ✅ `https://verla.io/v1/webhook/stripe`（enabled，含 subscription/invoice/checkout 事件） |
| 生产库迁移 | ❌ 尚未执行（`user_subscriptions.pricing_zone` 列不存在） |
| 生产老合同 | 39 个未来续费合同；**最早 2026-10-23 18:30 UTC** |
| Study Pass 合同 | 0 条，无需迁移 |
| 上线阻断项 | 无（前提：严格按本文顺序执行） |

**两个硬时间点：**

1. **新购上线**：迁移 + 后端 + 前端 + preflight 全部通过后，才能执行 `093_enable_zone_pricing_live.sql`。
2. **老合同换价 apply**：必须在 **2026-10-23 18:30 UTC（10-24 02:30 CST）之前**完成，否则最早那批 basic_monthly 会按旧价再续一个月。建议 **2026-10-22** 完成 apply。

---

## 1. 范围与不变量

1. **新购**：国家 → 倍率档 → 对应 Price；区外/无可信国家头默认 **Z100（1.00 档，USD）**。新用户不再看到 $79.99。
2. **老合同**：不考虑原地区，下一周期统一换 **Z100 1.00 档**；换价生效前页面仍展示旧价。
3. **合同动作**（升级/降级/换周期/续费）只认合同的 `pricing_zone / price_version / currency`，切换 IP 不改价。
4. **rollout 是人工闸门**：没有 cron；`apply` 是唯一会写 Stripe Schedule 的动作，之后每个合同到期由 Stripe 自动换价。
5. **不修改 Stripe 价格对象**：live 矩阵已经正确，本次只读使用。

---

## 2. 上线前事实基线（2026-10-19 实测）

### 2.1 Stripe live 盘点

| 产品 | live Product ID | 档位价格数 | legacy active |
|---|---|---|---|
| Verla Basic | `prod_Ui3K6dy7ZVDVg8` | 34（17 档 × 月/年） | 2 |
| Verla Plus | `prod_Ui3NJJK1SIdTPd` | 34 | 2 |
| Verla Pro | `prod_Ui3Q44UdEvgP4o` | 34 | 2 |
| Pro Trial | `prod_V2g1XTzV22Xlej` | 17（周） | 3 |
| Study Pass | `prod_VJrjfJSOd0qGex` | 17（月） | 1 |
| Extra Assignment Pack | `prod_Ui3Uf4hleuWoQG` | 17 | 1 |
| Extra AI Detection Pack | `prod_Ui3VIgRW9nWGN7` | 17 | 1 |
| Extra Humanizer Pack | `prod_Ui3VbCp3yVIY3m` | 17 | 1 |

**Z100（1.00 档）live Price ID：**

| SKU | Price ID | 金额 |
|---|---|---|
| basic_monthly | `price_1USCfI7GRT6LLkI1kWi9EBrn` | $19.99 |
| basic_yearly | `price_1USCfK7GRT6LLkI1BTv470qJ` | $95.88 |
| plus_monthly | `price_1USCfI7GRT6LLkI1bghboO08` | $39.99 |
| plus_yearly | `price_1USCfL7GRT6LLkI1QcrDd5Hm` | $191.88 |
| pro_monthly | `price_1USCfP7GRT6LLkI1i17HGsdU` | $29.99 |
| pro_yearly | `price_1USCfQ7GRT6LLkI1XrxHENlF` | $359.88 |
| pro_trial_to_monthly | `price_1USCfH7GRT6LLkI1M6NjUUlb` | $2.99 / week |
| study_pass_subscription_30d | `price_1USCfR7GRT6LLkI1DLVPsGFl` | $0.99 / month |
| addon_assignment_3 | `price_1USCfM7GRT6LLkI11rmZ1I65` | $9.99 |
| addon_detection_5 | `price_1USCfN7GRT6LLkI1jO7VbQE9` | $4.99 |
| addon_humanizer_3 | `price_1USCfO7GRT6LLkI1POYAIYKd` | $6.99 |

> 说明：live 与测试账号的 **金额、倍率档、114 国昵称清单完全一致**，只有 Price ID/Product ID 不同。生产 seed 必须使用 live ID。

**legacy 别名：12 条**（Basic 2、Plus 2、Pro 2、Trial 周价 2、Study Pass 1、3 个加量包各 1）。live 的 Pro Trial 周价还区分 `pro_trial_to_monthly` 与 `pro_trial_to_yearly`，生成器已按 nickname 分流。

**Webhook：**

- `we_1TBC4l7GRT6LLkI1N0v48kkr` → `https://verla.io/v1/webhook/stripe`
- 状态 enabled
- 事件：`charge.failed`、`checkout.session.completed`、`checkout.session.expired`、`customer.subscription.created`、`customer.subscription.deleted`、`customer.subscription.updated`、`invoice.paid`、`invoice.payment_action_required`、`invoice.payment_failed`、`payment_intent.payment_failed`

上线前在 Stripe Dashboard 再确认该 endpoint 的 signing secret 与生产环境 `STRIPE_WEBHOOK_SECRET` 一致（不要打印值）。

### 2.2 生产库基线（只读查询）

- `user_subscriptions.pricing_zone` 列不存在 → 迁移未执行。
- 未来所有未到期的付费合同（按 `UTC_TIMESTAMP()` 比较，DB 的 `current_period_end` 存 UTC）：

| plan_code | 合同数 | 最早续费（UTC） |
|---|---|---|
| basic_monthly | 3 | **2026-10-23 18:30:04** |
| plus_monthly | 1 | 2026-10-25 07:32:53 |
| pro_monthly | 29 | 2026-10-27 09:48:56 |
| pro_yearly | 3 | 2027-06-30 13:24:57 |
| plus_yearly | 3 | 2027-07-02 06:12:41 |
| **合计** | **39** | — |

- 另有 `pro_monthly` **past_due 8 条**：rollout 分类会判 conflict，不会自动换价，需人工/催付后另行处理。
- `pro_trial_to_monthly` 有 35 条 DB 状态仍是 `trialing`，但 `current_period_end` 已过期（数据漂移）；未来 30 天内没有有效 trial。rollout 扫到它们时会按 Stripe 实际状态判 conflict/skipped，属于预期噪音，建议上线前做一次对账（见 §9）。
- `study_passes` 0 条，无需迁移。
- 所有付费合同都有 `stripe_subscription_id`。
- **时间字段注意**：DB 里 `NOW()` 是服务器本地时间（CST），`UTC_TIMESTAMP()` 是 UTC；判断续费窗口必须用 `UTC_TIMESTAMP()`。

### 2.3 分支与提交

| 仓库 | 分支 | HEAD |
|---|---|---|
| backend | `release/2.1.0` | `1bf02958` |
| backend feat | `feat/billing-zone-pricing` | `9e106aa1` |
| frontend | `release` | `7e34c07f` |

> `1bf02958` 只改了测试断言；运行时行为等价于 `a95d9ead`。

---

## 3. 发布物清单

### 3.1 代码

- 后端 `release/2.1.0`（含区域定价、rollout、webhook 反查、金额格式、Study Pass 区价、Customer 自愈、升级 Checkout 不吞 pending 等全部修复）
- 前端 `release`（区价 SSR 透传、金额符号/零小数修复、Study Pass 区价展示）

### 3.2 SQL（按顺序）

| 文件 | 作用 | 何时执行 |
|---|---|---|
| `sql/091_zone_pricing_tables.sql` | 5 张表 + 合同列；幂等 CREATE/ALTER | T0 第一步 |
| `sql/094_rollout_ledger_nullable.sql` | rollout 台账价列改可空（幂等 ALTER） | T0，紧接 091 |
| `sql/092_zone_pricing_seed_live.sql` | **生产专用 seed**：17 区 / 187 绑定 / 1243 金额 / 249 路由 / 12 legacy 别名；所有开关 **OFF** | 091+094 之后 |
| `sql/093_enable_zone_pricing_live.sql` | 打开全部 zone + country route | preflight 全绿、烟测通过后 |

> **绝对不要在 production 执行 `092_zone_pricing_seed.sql`**——那份是测试账号 Price ID。生产必须用 `_live` 版本。
>
> seed 生成方式：
> ```bash
> python3 scripts/gen_zone_pricing_seed.py \
>   --inventory docs/billing-regional-pricing/stripe-price-inventory-live-20261019.json \
>   --expect-aliases 12 \
>   --note '2026-10-19 live 盘点：批次前旧价，仅存量合同' \
>   --out sql/092_zone_pricing_seed_live.sql \
>   --enable-out sql/093_enable_zone_pricing_live.sql
> ```
> 生成器已断言：17 档、187 绑定、每档 11 SKU、114 国路由、249 总路由、12 别名。

### 3.3 盘点存档

- `docs/billing-regional-pricing/stripe-price-inventory-live-20261019.json`（只读盘点，不含密钥）

---

## 4. 环境变量与边缘配置

### 4.1 后端 env

| 变量 | 值 / 说明 |
|---|---|
| `BILLING_REGIONAL_PRICING_ENABLED` | `true`（代码默认已是 true；显式写下便于回滚） |
| `BILLING_REGIONAL_PRICING_COUNTRY_CURRENCIES` | **置空**。原默认 `BR=brl` 是 legacy 巴西路由；区价启用后保留会形成双路由。 |
| `BILLING_REGION_PROXY_SECRET` | 已有的服务器侧 secret（≥32 字符），nginx 与后端必须一致；不进入 git |
| `BILLING_ZONE_PRICING_INTERNAL_TOKEN` | **新增**：`openssl rand -hex 32`；只放服务器 env，保护 `/v1/internal/billing/zone-pricing/**` |
| `BILLING_ZONE_PRICING_DEFAULT_ZONE` | `Z100`（默认值，可省略） |
| `BILLING_ZONE_PRICING_CATALOG_CACHE_SECONDS` | `60`（默认） |
| `STRIPE_SECRET_KEY` / `STRIPE_PUBLISHABLE_KEY` / `STRIPE_WEBHOOK_SECRET` | 生产 live 值，确认已是 live 且未被测试值覆盖；不要打印 |
| `BILLING_PORTAL_CONFIGURATION_ID` | 若使用 Stripe Portal，确认与 live 账号匹配 |

> env 变更后必须**重建容器**；`restart` 不会重新读取 env_file/compose environment。

### 4.2 边缘（Cloudflare → nginx → 后端）

生产必须使用同事的正式方案，而不是测试服的临时 workaround：

- `docker-compose.regional-pricing.yml` 叠加编排（端口收 127.0.0.1、固定 `https://verla.io`、注入 `BILLING_REGION_PROXY_SECRET`）
- `generated/regional-pricing/main-site.conf`（需从受控位置拷贝到服务器，gitignore）
- Cloudflare 回源加 `X-Verla-Origin-Auth`；nginx 校验通过后才把 `CF-IPCountry` 映射为 `X-Verla-Pricing-Country`，并附带 `X-Verla-Pricing-Proxy-Secret`
- **override 里 `BILLING_REGIONAL_PRICING_ENABLED` 默认 false**，生产 `runtime.env` 必须显式设为 `true`
- 源站安全组/防火墙只允许 Cloudflare 回源 IP；否则国家头可伪造
- `/v1/internal/billing/zone-pricing/**` **不要**暴露到公网；只在服务器本机/VPN 调用，或 nginx 限制内网 CIDR

---

## 5. 发布步骤（T0）

### Phase A：T-1（上线前一天）

1. **备份**：至少 `mysqldump` `user_subscriptions`、`study_passes`、`recharge_orders`，并保留生产当前后端镜像 tag。
2. **确认 live key 模式**：`stripe config --list` 或在服务器容器内确认 `sk_live_`；不要打印完整值。
3. **确认 webhook**：Stripe Dashboard → `https://verla.io/v1/webhook/stripe` enabled，最近 24h 无大量失败。
4. **生成并保存 internal token**：`openssl rand -hex 32`，写入生产 env（不写仓库）。
5. **对账**（建议）：把 DB 中 `status in (active,trialing,past_due,unpaid)` 的合同与 Stripe 逐条比对状态/周期，清理已取消但 DB 仍 active 的行；重点是 35 条过期 trial 和 8 条 past_due。

### Phase B：T0（新购上线）

1. **执行 schema**：`091` → `094`。
2. **执行 seed**：`092_zone_pricing_seed_live.sql`（此时 zones/routes 全部 `is_active=0`/`is_enabled=0`）。
3. **校验 seed**：
   ```sql
   SELECT COUNT(*) FROM pricing_zones;                    -- 17
   SELECT COUNT(*) FROM pricing_zone_prices;              -- 187
   SELECT COUNT(*) FROM pricing_country_routes;           -- 249
   SELECT COUNT(*) FROM stripe_price_aliases;             -- 12
   SELECT COUNT(*) FROM pricing_zone_prices WHERE stripe_price_id IS NULL; -- 0
   ```
4. **部署后端**（release/2.1.0 构建镜像，重建容器）；等待 `/health` healthy。
5. **部署前端**（release 构建；`NEXT_PUBLIC_*` 是构建期内联，必须重新 build，不能只重启）。
6. **preflight（只读）**：
   ```bash
   curl -s -X POST -H "X-Internal-Token: $TOKEN" \
     "http://127.0.0.1:8080/v1/internal/billing/zone-pricing/preflight?fresh=true"
   ```
   期望：`fail=0 / stripeError=0 / ok=true`，`stripeMode=live`。
7. **按国家烟测**（服务器本机直连，带可信头）：
   ```bash
   curl -s -H "X-Verla-Pricing-Country: DE" \
        -H "X-Verla-Pricing-Proxy-Secret: $PROXY_SECRET" \
        "http://127.0.0.1:8080/v1/billing/config" | jq '.data.plans'
   ```
   期望（关键 SKU）：
   - DE：Pro **€20.99**，Basic/Plus 对应 0.80 档
   - IT：Pro **€18.99**（0.70 档）
   - US：Pro **$29.99**
   - CN：Pro **¥89.99**
   - JP：Pro **JPY 2,980**
   - KR：Pro **₩25,900**
   - TW：Pro **NT$629**
   - 无可信国家头 / 区外：Pro **$29.99**（Z100）
8. **打开区价**：执行 `093_enable_zone_pricing_live.sql`。
9. **新购链路烟测**（不产生真实扣费）：
   - `GET /v1/billing/quote?kind=subscription_plan&skuCode=pro_monthly`（带不同国家头）核对 `amountMinor/currency/quoteVersion`
   - 前端未登录页确认不出现 $79.99
   - 可选：用内部测试账号完成一笔最低金额真实购买（需业务批准）
10. **观察**：后端日志无 `STRIPE_ERROR`、`rollout apply failed`；Stripe Dashboard webhook 成功率正常。

### Phase C：T+1（老合同换价，必须在 2026-10-23 18:30 UTC 前）

1. `dry-run`：
   ```bash
   curl -s -X POST -H "X-Internal-Token: $TOKEN" \
     "http://127.0.0.1:8080/v1/internal/billing/zone-pricing/rollout/dry-run?zoneCode=Z100&targetVersion=regional-20261018-v1"
   ```
   - 期望 `scanned ≈ 39 + 8 past_due + 35 stale trial` 的量级
   - **重点核对**：所有 `preview` 都应是 `active/trialing` 且无未来 schedule 的合同；`past_due`、`cancelling_at_period_end`、`user_future_change_pending`、`window_passed` 应为 conflict
   - **若出现意外的 preview（尤其 trial 或 stale 行），先停下处理，不要 apply**
2. 查 ledger（只读）：
   ```sql
   SELECT status, status_reason, COUNT(*) FROM subscription_price_rollouts
   WHERE batch_id='rollout-Z100-20261018-v1-20261019' GROUP BY status, status_reason;
   ```
3. `apply`（唯一写 Stripe 的动作；幂等可重跑）：
   ```bash
   curl -s -X POST -H "X-Internal-Token: $TOKEN" \
     "http://127.0.0.1:8080/v1/internal/billing/zone-pricing/rollout/apply?batchId=<dry-run 返回的 batchId>"
   ```
   - 合同多时从服务器本机直连 8080，避免 nginx 超时
   - `conflict` 合同不会被改动；逐条记录原因
4. `verify`：apply 后立即跑一次，到期窗口后再跑一次：
   ```bash
   curl -s -X POST -H "X-Internal-Token: $TOKEN" \
     "http://127.0.0.1:8080/v1/internal/billing/zone-pricing/rollout/verify?batchId=<batchId>"
   ```
   期望：`applied + scheduled` ≈ apply 成功数；无 `failed`。
5. **到期后核验**每个 applied 合同：
   - Stripe item price = Z100 目标 Price
   - invoice 金额 = $29.99 / $359.88 / 对应 Z100 SKU
   - `recharge_orders` 有 `subscription_renewal` 完成单
   - 合同 `pricing_zone=Z100`、`price_version=regional-20261018-v1`、`currency=usd`
   - account `nextCyclePriceChange=null`

### 回滚（cancel）的时机

- 只能在**生效日之前**回滚：`cancel?batchId=...` 会把该批次所有 `scheduled` 合同恢复到写入前 schedule（旧价），并清掉目标 phase。
- **生效日之后** Stripe 已经按新价出账，`cancel` 不会退钱；需要人工在 Stripe 做退款/改价，并修正合同与订单。
- 同一 batch 内 `applied/cancelled` 行再次 dry-run 会 noop；同一天要重做需要新日期的新 batch（batchId 含日期）或测试环境清行。

---

## 6. 验证清单（Go/No-Go）

| # | 检查 | 通过标准 |
|---|---|---|
| 1 | `091/094` 执行 | 无报错；5 表 + 合同 4 列存在 |
| 2 | `092_live` 执行 | 17/187/249/12 计数正确；`stripe_price_id` 无 NULL |
| 3 | preflight | `ok=true`、`fail=0`、`stripeMode=live` |
| 4 | 国家烟测 | DE/IT/US/CN/JP/KR/TW + 默认档全部正确 |
| 5 | 未登录首屏 | 不出现 $79.99；文案/币种符号正确 |
| 6 | new purchase quote | 国家头切换金额/币种/quoteVersion 正确 |
| 7 | webhook | Dashboard 最近事件全部 2xx |
| 8 | rollout dry-run | preview 集合符合预期；无 trial/stale 误入 |
| 9 | rollout apply | scheduled = preview；failed=0 |
| 10 | 到期核验 | 金额/订单/合同三列/account 全部一致 |

任何一项不通过：**不要执行 093**；先回退到“zone 全关 + 后端旧镜像”。

---

## 7. 回滚预案

### 7.1 新购回滚（尚未 apply 老合同）

1. 关闭区价入口：
   ```sql
   UPDATE pricing_zones SET is_active=0;
   UPDATE pricing_country_routes SET is_enabled=0;
   ```
   注意：区价开关为 true 但没有任何 active zone 时，**新购会 fail-closed**（不会自动回 legacy）。
2. 若要真正回到旧价销售：
   - 后端 env `BILLING_REGIONAL_PRICING_ENABLED=false` → **重建容器**
   - 或回滚到上线前镜像
3. 前端回滚到上线前构建。

### 7.2 老合同回滚

- 生效前：`rollout/cancel`（见 §5）。
- 生效后：逐合同在 Stripe 修正价格/退款，并同步 `user_subscriptions`、`recharge_orders`；没有批量回滚脚本。

### 7.3 数据保留

- 5 张表和合同新增列都可以保留；回滚代码后旧版本会忽略它们。
- 不要 drop 表：已有 zone 合同的 webhook 反查依赖注册表。

---

## 8. 风险与开放项

| # | 风险 | 处置 |
|---|---|---|
| 1 | **10-23 18:30 UTC 最早续费** | 10-22 前完成 apply；若来不及，先接受该合同按旧价续一个月，下一周期再换 |
| 2 | 8 条 past_due + 35 条过期 trial 造成 conflict 噪音 | 上线前对账/催付；dry-run 逐条看原因，不要盲目 apply |
| 3 | Plus 月付 $39.99 > Pro 月付 $29.99 倒挂 | 业务已知情，按促销定价执行；客服话术提前准备 |
| 4 | TN/GH/IQ/JO/OM/KW/VE 本币不在价格 currency_options | 路由回落 USD；seed 已处理 |
| 5 | 生产前端当前跑 main，区价代码在 release | 按团队约定 release → main 晋级（merge commit，禁止 squash），再构建生产 |
| 6 | Cloudflare / nginx 国家头信任 | 必须用官方 origin-auth 方案 + 源站仅 CF 回源；否则国家头可伪造 |
| 7 | internal token 泄露 | 只在服务器 env；nginx 不暴露 `/v1/internal/billing/zone-pricing/**`；轮换后重建容器 |
| 8 | live key / webhook secret 已在对话中出现 | 上线窗口结束后**轮换 Stripe live secret key 与 webhook signing secret**，更新生产 env 并重建 |
| 9 | 前端 `NEXT_PUBLIC_*` 构建期内联 | 每次环境变更都要重新 build，不能只重启容器 |
| 10 | 老合同展示 | 换价生效前不得回填 zone 列；rollout 已按此实现，webhook 在生效后回填 |

---

## 9. 附录

### 9.1 生产库对账 SQL（只读）

```sql
-- 未来续费窗口
SELECT plan_code, COUNT(*), MIN(current_period_end)
FROM user_subscriptions
WHERE status IN ('active','trialing','past_due','unpaid')
  AND current_period_end > UTC_TIMESTAMP()
  AND plan_code IN ('pro_monthly','pro_yearly','basic_monthly','basic_yearly','plus_monthly','plus_yearly')
GROUP BY plan_code ORDER BY MIN(current_period_end);

-- 过期但仍标记 trialing 的行（建议与 Stripe 对账）
SELECT clerk_user_id, plan_code, status, current_period_end, stripe_subscription_id
FROM user_subscriptions
WHERE status='trialing' AND current_period_end <= UTC_TIMESTAMP();

-- past_due
SELECT clerk_user_id, plan_code, current_period_end, stripe_subscription_id
FROM user_subscriptions
WHERE status='past_due';
```

### 9.2 rollout 命令（服务器本机执行）

```bash
TOKEN=$(grep '^BILLING_ZONE_PRICING_INTERNAL_TOKEN=' /path/to/Server_Config/.env | cut -d= -f2-)
BASE=http://127.0.0.1:8080

# 1) dry-run
curl -s -X POST -H "X-Internal-Token: $TOKEN" \
  "$BASE/v1/internal/billing/zone-pricing/rollout/dry-run?zoneCode=Z100&targetVersion=regional-20261018-v1"

# 2) apply（batchId 来自 dry-run）
curl -s -X POST -H "X-Internal-Token: $TOKEN" \
  "$BASE/v1/internal/billing/zone-pricing/rollout/apply?batchId=rollout-Z100-20261018-v1-<日期>"

# 3) verify
curl -s -X POST -H "X-Internal-Token: $TOKEN" \
  "$BASE/v1/internal/billing/zone-pricing/rollout/verify?batchId=<batchId>"

# 4) 回滚（仅生效前）
curl -s -X POST -H "X-Internal-Token: $TOKEN" \
  "$BASE/v1/internal/billing/zone-pricing/rollout/cancel?batchId=<batchId>"
```

### 9.3 Stripe live 只读核对命令

```bash
# 不要打印 key；只读
curl -s -u "$STRIPE_SECRET_KEY:" -H "Stripe-Version: 2024-06-20" \
  "https://api.stripe.com/v1/prices/price_1USCfP7GRT6LLkI1i17HGsdU"
curl -s -u "$STRIPE_SECRET_KEY:" -H "Stripe-Version: 2024-06-20" \
  "https://api.stripe.com/v1/webhook_endpoints?limit=20"
```

### 9.4 生成生产 seed

```bash
python3 scripts/gen_zone_pricing_seed.py \
  --inventory docs/billing-regional-pricing/stripe-price-inventory-live-20261019.json \
  --expect-aliases 12 \
  --note '2026-10-19 live 盘点：批次前旧价，仅存量合同' \
  --out sql/092_zone_pricing_seed_live.sql \
  --enable-out sql/093_enable_zone_pricing_live.sql
```

---

## 10. 发布后监控

- 后端日志：`STRIPE_ERROR`、`rollout apply failed`、`rollout verify failed`、`Unresolvable Stripe subscription Price`
- Stripe Dashboard：webhook 失败率、`invoice.payment_failed`、新购 checkout 完成率
- 关键业务指标：按国家的 checkout 转化、$29.99 vs $79.99 出现次数、取消率
- 到期窗口后：`subscription_price_rollouts` 是否全部 `applied`，`recharge_orders` 金额/币种是否正确
