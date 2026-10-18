# 地区差异化定价：方案 A 数据库化实施定稿

版本：2026-10-18 定稿（r1）。状态：用户已确认按完整版实施；本文是 [regional-expansion-plan-a.md](regional-expansion-plan-a.md) 的落地形态定稿，与其冲突时以本文的数据载体设计为准，业务规则以 plan-a 与 specs 为准。

## 0. 对 plan-a 的唯一修订

plan-a §4 设计用 JSON 配置文件（`BILLING_ZONE_PRICING_CONFIG_PATH`）承载地区目录与 Price 注册表。**用户已确认允许新增数据库表列，本定稿改为数据库承载**：

| plan-a 原设计 | 本定稿 | 理由 |
| --- | --- | --- |
| JSON 配置文件 + `config-path` 入口 | 5 张新表 + 2 处加列，迁移 SQL 管理 | 与 056/085/086 商品目录管理模式一致，数据库为唯一真值；webhook 反查走唯一索引而非内存 map，多实例天然一致；换价执行记录持久化（plan-a 自己要求「执行记录必须持久保存并可回读 Stripe 对账」） |
| 文件内 `zonePricingEnabled` 开关 | 表内开关列（区级 `is_active` + 国家级 `is_enabled`），沿用既有 `BILLING_REGIONAL_PRICING_ENABLED` 总开关 | 开关语义不变，载体从文件变表；不引入 `BILLING_ZONE_PRICING_CONFIG_PATH` |
| 批次结果文件（换价执行记录） | `subscription_price_rollouts` 表 | 可查询、可重试、可对账 |

其余全部按 plan-a 与 specs 执行：7 定价区 × 11 国家 × 9 币种；复用 Product、每「区+SKU」一条 Price（同区多币种放 `currency_options`）；不新建 `pro_new` SKU；存量合同本期不动、下周期同区同币种换价；quote 确认流与指纹升级；KRW 修复；credit packs 保留 legacy 路由。

## 1. 需求映射

| 需求来源 | 落法 |
| --- | --- |
| 各国 priceId 不同 | 定价区粒度：7 区 × 11 SKU = 77 条新 Price；同区同价（DE/AT 共用 Z080 EUR），跨区异价（DE €13.99 / IT €11.99）；priceId 差异只在服务端注册表，前端购买契约不变（仍传 planCode + 报价确认三元组） |
| 国家判定 | 复用巴西分支 `BillingRegionFilter`（签名可信头）+ `BillingPricingContext`；边缘接线（CF→nginx 签名头 + secret）是部署前置，见 plan-a tasks 4.4 |
| config 按国家返回 | 未登录/新用户：可信国家→区价；老用户：合同区 + 实际币种，展示价=账单价 |
| 新 Pro $29.9 | 首批 11 地区新购用「新 Pro」列（USD 技术默认 29.99/359.88；Z080 EUR 20.99/月、251.88/年）；**非首批地区维持旧价 $79.99（spec 口径）**；SKU/权益不变 |
| 存量合同 | 本期不动，下周期同区同币种切新价（换价执行器） |
| credit packs（10 种旧独立额度包） | 完全不动，走 legacy 路由 |

## 2. 总体架构

```
                      ┌─ 新购（未登录/无有效合同）──────────────────┐
CF ─签名头─> nginx ──> BillingRegionFilter > BillingPricingContext(可信国家)
                      │                      │
                      │                      ▼
                      │         pricing_country_routes(启用名单)
                      │                      ▼
                      │         pricing_zones(active_version)
                      │                      ▼
                      │         pricing_zone_prices(区×版本×SKU → Price+金额基准)
                      │                      │   ↕ Stripe 实时核验（livemode/product/金额/周期）
                      │                      ▼
                      │              PricingSelection（唯一选价结果）
                      │
                      ├─ 合同动作（升降级/额外包/转正/续费）──────────┤
                      │   Subscription 当前 Price ──注册表反查──> 区/版本/SKU
                      │   币种 = Subscription.currency（落库列）
                      │
                      └─ 存量下周期换价 ──> subscription_price_rollouts
                              dry-run → apply(合并 Schedule) → verify(回读三态确认)
```

不可变原则：`pricing_zone_prices` 行只增不改语义——新版本 INSERT 新行；`stripe_price_id` 只允许从 NULL 回填一次；旧行永不删除。历史版本永久保留，供合同解析与延迟事件处理。

## 3. 数据模型（5 表 + 2 加列）

### 3.1 五张表各答一个问题

| 表 | 回答的问题 | 为什么现有表干不了 |
| --- | --- | --- |
| `pricing_zone_prices` | 这个 Price ID 是哪个区/版本/SKU？ | `subscription_plans` 有 `UNIQUE(stripe_price_id)`；一个 SKU 现在对应 8 条价格（7 区 + legacy USD），塞回去必须复制套餐行，污染权益/目录/转正目标（plan-a 明令禁止） |
| `pricing_country_routes` | DE 请求用哪个区哪个币种？哪些国家已开放？ | 国家路由与启用名单是独立运营数据，逐国开、可挪区 |
| `pricing_zones` | 这个区当前卖哪个版本？ | 区级版本指针 + 一条 UPDATE 全区止血 |
| `stripe_price_aliases` | 商品表之外的旧 Price ID 属于谁？ | 保险层，初始为空；仅当盘点发现孤儿 ID 才填 |
| `subscription_price_rollouts` | 这单合同换价执行到哪步？ | 执行器状态机，spec 要求持久化可重试可对账 |

### 3.2 DDL（迁移 091）

**① 定价区**

```sql
CREATE TABLE IF NOT EXISTS pricing_zones (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  zone_code VARCHAR(8) NOT NULL COMMENT '如 Z080',
  label VARCHAR(64) NOT NULL,
  reference_factor DECIMAL(4,2) NOT NULL COMMENT '参考倍率，仅审计展示，运行时不参与计算',
  active_version VARCHAR(32) NOT NULL COMMENT '当前新购销售版本，如 regional-20261018-v1',
  is_active TINYINT(1) NOT NULL DEFAULT 0 COMMENT '区级销售开关',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_pz_zone (zone_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='区域定价：定价区定义与当前销售版本';
```

**② 国家路由**

```sql
CREATE TABLE IF NOT EXISTS pricing_country_routes (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  country_code CHAR(2) NOT NULL COMMENT 'ISO alpha-2 大写，与可信头同格式',
  zone_code VARCHAR(8) NOT NULL,
  currency CHAR(3) NOT NULL COMMENT '本币小写，如 eur/hkd',
  label VARCHAR(64) NOT NULL,
  is_enabled TINYINT(1) NOT NULL DEFAULT 0 COMMENT '启用前置：该区 11 SKU 齐备且预检通过',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_pcr_country (country_code),
  KEY idx_pcr_zone (zone_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='区域定价：国家→区/币种路由，is_enabled=启用名单';
```

**③ Price 注册表（核心）**

```sql
CREATE TABLE IF NOT EXISTS pricing_zone_prices (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  zone_code VARCHAR(8) NOT NULL,
  price_version VARCHAR(32) NOT NULL,
  sku_code VARCHAR(64) NOT NULL COMMENT 'plan_code / addon_code / study_pass SKU',
  sku_kind VARCHAR(16) NOT NULL COMMENT 'subscription_plan | addon | study_pass',
  stripe_price_id VARCHAR(128) NULL COMMENT 'NULL=未配置不可售；回填后永不改指向（MySQL 唯一索引允许多个 NULL，草稿行不冲突）',
  stripe_product_id VARCHAR(128) NULL COMMENT '必须等于该 SKU 现有 Product',
  price_type VARCHAR(16) NOT NULL COMMENT 'recurring | one_time',
  billing_interval VARCHAR(8) NULL COMMENT 'week | month | year；one_time 为 NULL',
  interval_count INT NOT NULL DEFAULT 1,
  usd_default_amount_minor INT NOT NULL COMMENT 'USD 技术默认金额，非销售入口',
  currency_amounts JSON NOT NULL COMMENT '{"eur":{"amountMinor":1399}} 预检核对基准；实际金额仍从 Stripe 实读比对',
  renewal_sku_code VARCHAR(64) NULL COMMENT '试用 SKU 的转正目标',
  is_active TINYINT(1) NOT NULL DEFAULT 1 COMMENT '0=绑定作废（以停用代替删除）',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_pzp_binding (zone_code, price_version, sku_code),
  UNIQUE KEY uk_pzp_price (stripe_price_id),
  KEY idx_pzp_sku (sku_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='区域定价：不可变 Price 注册表，正查选价、反查解析合同';
```

`uk_pzp_price` 使 webhook 反查成为一次索引查询；「同一 Price 绑定多 SKU/区/版本」由 DB 直接拒绝。

**④ 历史 Price 别名（保险层，初始为空）**

```sql
CREATE TABLE IF NOT EXISTS stripe_price_aliases (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  stripe_price_id VARCHAR(128) NOT NULL,
  sku_code VARCHAR(64) NOT NULL,
  sku_kind VARCHAR(16) NOT NULL,
  note VARCHAR(255) NULL COMMENT '来源：盘点批次等',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_spa_price (stripe_price_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='历史 Price 别名：三大商品表之外的旧 Price 反查兜底';
```

**⑤ 换价执行记录**

```sql
CREATE TABLE IF NOT EXISTS subscription_price_rollouts (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  batch_id VARCHAR(64) NOT NULL COMMENT '如 rollout-Z080-v2-20261101',
  contract_kind VARCHAR(16) NOT NULL COMMENT 'subscription | study_pass',
  clerk_user_id VARCHAR(64) NOT NULL,
  stripe_subscription_id VARCHAR(128) NOT NULL,
  zone_code VARCHAR(8) NOT NULL,
  currency CHAR(3) NOT NULL,
  source_price_id VARCHAR(128) NOT NULL,
  source_version VARCHAR(32) NOT NULL,
  target_price_id VARCHAR(128) NOT NULL,
  target_version VARCHAR(32) NOT NULL,
  effective_at DATETIME NOT NULL COMMENT '计划生效点=当前付费周期结束',
  status VARCHAR(24) NOT NULL COMMENT 'preview|pending|scheduled|conflict|skipped|failed|superseded|cancelled|applied',
  status_reason VARCHAR(512) NULL COMMENT '跳过/冲突原因：已取消、已出账、归属不明、币种无目标…',
  snapshot_json JSON NULL COMMENT '写入前合同快照：schedule_id、cancel_at_period_end、period_end、发票状态',
  idempotency_key VARCHAR(180) NOT NULL COMMENT 'rollout:{batch}:{sub}:{targetPrice}:{effectiveAt}',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_spr_idem (idempotency_key),
  UNIQUE KEY uk_spr_batch_contract (batch_id, stripe_subscription_id),
  KEY idx_spr_status (status),
  KEY idx_spr_user (clerk_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='存量合同下周期换价：批次与逐合同执行记录（dry-run 亦落库）';
```

### 3.3 两处加列（合同持区）

```sql
-- user_subscriptions: pricing_zone VARCHAR(8) NULL / price_version VARCHAR(32) NULL / currency CHAR(3) NULL
-- study_passes:       pricing_zone VARCHAR(8) NULL / price_version VARCHAR(32) NULL
```

收益：`getCatalog`/`getPurchaseQuote` 判会员币种不再每次实时调 Stripe 读 Subscription（巴西分支现状是每请求一次 retrieve，落库后零成本读列）；rollout dry-run 枚举合同变成一条 SQL；满足 spec「合同保留定价区」的持久化要求。历史行为 NULL（=legacy），webhook 触碰时渐进回填 + dry-run 批量补齐，无需一次性存量迁移。

### 3.4 不动的既有结构

- 三大商品表（`subscription_plans`/`addon_package_defs`/`study_pass_products`）：保留为 legacy 定义 + legacy 价格；77 条区价绝不塞入。
- `recharge_orders.biz_context`：继续合并写 `pricing` 快照（区、版本、当前/目标/转正 Price ID、币种、金额最小单位），旧 JSON 无字段兼容。
- 权益/配额引擎、plan_code、前端购买契约：零变化。

## 4. 开关与门控链

```
新购选价门控（三级全满足才走区价）：
  ① billing.regional-pricing.enabled（既有 env 总开关，默认 false）
  ② pricing_zones.is_active（区级）
  ③ pricing_country_routes.is_enabled（国家级，默认 0）
任一不满足 → legacy 路由（USD 目录 / legacy country-currencies 规则）

关键不变量：开关只门控【新购选价】。
合同解析（webhook 反查、会员动作、rollout、延迟事件）【永远】查注册表，不看任何开关——
否则关开关会导致已售 BRL/EUR 合同解析失败（spec「回滚保留已售合同」要求）。
```

启动时离线结构校验（只查 DB）：启用国家必须 11 SKU 齐备且 `stripe_price_id` 非空、Price ID 三表间全局无重复、金额正整数、周期与 price_type 自洽；草稿行（price_id NULL）未启用时合法。远程预检（Stripe 交叉核对）走受控端点按需执行，不在启动时跑。

## 5. 统一选价 `PricingSelection`

内部值对象（不暴露前端）：`{zone, version, skuCode, skuKind, priceId, productId, currency, amountMinor, renewalPriceId?, renewalAmountMinor?}`。

- **① 独立新购**：总开关关→legacy；路由缺失/未启用→legacy；区未启用→不可售+告警；绑定缺/作废/price_id NULL→`PRICING_CURRENCY_UNAVAILABLE`（禁止回退 USD）；Stripe 实读校验与 `currency_amounts` 比对，不一致→不可售。
- **② 会员合同动作**：当前 Price 三段反查（zone_prices→subscription_plans→aliases）得区/版本；币种=合同 currency 列；本周期动作用合同当前版本，周期边界动作用已安排下期目标（Schedule 内），无则当前版；zone 为 NULL→legacy 目录现价（既有行为原样）。
- **③ Study Pass**：独立合同。新购走①；已有 Pass 按自身 Price 反查 + 自身 `renewalCurrency`，绝不被商品表现价或会员区价覆盖。
- **④ credit pack**：不进本链路，走 `/v1/payment/config` legacy 路由。
- 试用转正：trial Selection 携带同区同版本 `renewal_price_id`，进指纹与 Schedule 第二段。

## 6. 代码接入点清单（基线=feat/billing-brazil-pricing）

| 位置 | 改动 |
| --- | --- |
| `BillingRegionalPricingProperties` | 保留 legacy 规则；新增三表装载（`ZonePricingCatalog` + Caffeine 短缓存）与结构校验 |
| `BillingPricingService` | 注册表正反查 + `PricingSelection` 构建；既有 `!enabled` 返回 DB 基准分支不得作用于已绑定区合同；Stripe 校验/缓存复用 |
| `getCatalog` | 匿名：区价覆写 amount/currency，缺币种隐藏 SKU；会员：读 `user_subscriptions.currency` 列 |
| `getPurchaseQuote` | 走 Selection；指纹 v2：加绑区、版本、当前/目标/转正 Price、生效时间——DE/IT Trial 同为 €1.99 指纹必须不同（转正 20.99 vs 18.99） |
| 四个 checkout 入口 | line item 用 Selection priceId；Session 显式 currency；metadata 增写 `pricing_zone`/`pricing_version`/`sku_code`（trial 加 `renewal_price_id`），保留 `pricing_region` 审计 |
| Trial Schedule | 两段 phases 显式 currency；转正段用区价 `renewal_price_id` |
| `UpgradeChargeCalculator` 调用方 | 算法不动；目标价/已付净额传 Selection 同币种金额（整数最小单位）；回归：Plus €27.99 升级更便宜的 Pro €20.99 仍按 monthly_full 收全额 |
| `requirePlanBySubscription` | 唯一 Price 查表→三段注册表反查，解析 SKU 后回 `subscription_plans` 取权益；未登记 ID 拒绝变更+告警（不猜） |
| `syncSubscription` 等 | 反查成功后回填合同三列 |
| `biz_context` 读写 | 合并 `pricing` 快照，旧 JSON 兼容 |
| `formatBillingMoney` | 修 KRW：零小数币种集合 {krw}，₩2,490 不再显示 ₩25（含 Trial Checkout 文案） |
| `application.yml` | 仅缓存/开关项；无 JSON path、无密钥 |
| 前端 | 契约零变化；`getStudyPassOffers` SSR 透传可信国家头；确认全部金额走 `billing-money` |
| 账号展示 | account 响应补下一期金额/生效日期（Schedule + rollout 表），前端显示「下期将按新价续费」 |

## 7. 换价执行器

挂既有 `/v1/internal/**`（IP 白名单 + Token + HMAC，`VerlaInternalAuthFilter`）：

```
POST /v1/internal/billing/zone-pricing/preflight        # 77 绑定对 Stripe 逐项交叉核对
POST /v1/internal/billing/zone-pricing/rollout/dry-run  {zoneCode, targetVersion}
POST /v1/internal/billing/zone-pricing/rollout/apply    {batchId}
POST /v1/internal/billing/zone-pricing/rollout/verify   {batchId}
POST /v1/internal/billing/zone-pricing/rollout/cancel   {batchId}  # 撤销未生效安排，独立操作
```

- **dry-run**：枚举该区合同（zone 列 + 巴西老合同按订单 `pricing_region` 证据归属），逐合同读 Stripe 现状，落库分类：`preview`（可迁）/ `conflict`（已出账/窗口已过/欠款/取消中）/ `skipped`（归属不明/同币种无目标/USD 合同不许借技术默认值），各带 reason；顺带回填合同空列。
- **apply**：逐 `pending` 行先比对快照（用户期间升级/取消→`conflict` 重解析）；无 Schedule 从 Subscription 建、有的只合并更新（保留当前段与用户未来段：转正/降级/取消语义/日期/税设置，仅在原周期结束点替换价格 phase，无 prorations）；写入前重读；幂等键 `rollout:{batch}:{sub}:{targetPrice}:{effectiveAt}`；超时重试先读实际 Schedule 防重复；同合同已安排 v3 时旧 v2 任务标 `superseded`。成功→`scheduled`。
- **verify**：回读区分三态——「已安排」（Schedule 写入）/「已生效」（phase 到期切换）/「已付款」（发票支付），分别推进到 `applied`。
- **回滚边界**（spec 原样）：关新购开关/停任务不动已写入 Schedule；`cancel` 仅撤销本批次价格替换、保留用户其他安排；已生效周期不追溯退款。

## 8. 迁移与种子

| 文件 | 内容 |
| --- | --- |
| `sql/091_zone_pricing_tables.sql` | 5 表 DDL + 2 处 ALTER（幂等守卫，仓库惯例） |
| `sql/092_zone_pricing_seed.sql` | 由脚本从 `regional-expansion-prices.json` 生成：7 区 + 11 路由（`is_enabled=0`）+ 77 绑定（price_id=NULL，金额/周期/renewal 全带入，KRW 2490/TWD 5900/IDR 1390000 最小单位）；`ON DUPLICATE KEY UPDATE` 幂等 |
| `scripts/gen_zone_pricing_seed.py` | 生成器，含 77/110/77 计数断言 |
| （授权后）`093_zone_price_backfill.sql` | 沙盒建价后回填 77 Price ID + aliases 盘点结果 + 首批 `is_enabled=1`，逐条可回滚 |

上线顺序：091+092 部署即生效（全关，行为零变化）→ preflight 绿 → 093 回填 → 逐国启用。

## 9. 测试与验收

- 本地单测（fixture 三表 + mock Stripe）：扩展 plan-a 点名的 6 个测试类（`BillingRegionalPricingPropertiesTest`、`BillingPricingServiceTest`、`BillingDomainServiceImplTest`、`StripeBillingWebhookServiceTest`、`IntroTrialBillingTest`、`StudyPassBillingTest`）+ 新增 `ZonePricingCatalogTest`（种子校验/反查/门控）、`SubscriptionPriceRolloutServiceTest`（dry-run 分类、Schedule 合并、幂等/乱序/快照失效、supersede）。
- 必测：A02 单位（KRW 不乘 100）、A03 同币种异价指纹（DE/IT Trial 同额不同指纹）、A08 降价升级算法（monthly_full 全额）。
- 验收矩阵 A01–A16 按 plan-a §7 执行；A01 由 preflight + 种子计数断言承接；A04/A05/A13 真实沙盒项待授权。
- 巴西分支遗留前置：边缘接线（tasks 4.4）、真实沙盒验收（tasks 5.2–5.5）。

## 10. 授权边界

现在可做：全部代码 + 本地 fixture 测试 + 种子脚本 + 091/092 迁移文件。
需逐项授权：Stripe 只读盘点（7.2）、沙盒写 77 Price（7.7）、测试/生产执行迁移、边缘链路部署、生产 rollout apply、commit/push。

## 11. 已定稿默认值

1. 非首批地区 Pro 维持 $79.99（spec 口径）；若要全球 $29.9 另立变更。
2. 启用 BR 路由的同一变更里清掉 `country-currencies` 的 `BR=brl`，避免双路由歧义。
3. rollout 入口挂 `/v1/internal`；执行记录只落库不出结果文件。
4. 表名列名按 §3 定稿。

## 12. 实施顺序（约 9–13 人日）

| 步骤 | 内容 | 估时 | 对应 tasks |
| --- | --- | --- | --- |
| 1 | 091/092 + 种子脚本 + `ZonePricingCatalog` 装载/校验/缓存 + preflight | 1.5–2 d | 7.3 |
| 2 | `PricingSelection` 四路径 + 全入口接入 + 指纹 v2 | 2–3 d | 7.4 |
| 3 | webhook 反查 + 合同三列回填 + biz_context 快照 | 1–1.5 d | 7.5 |
| 4 | KRW 修复 + 前端 SSR 透传/展示核对 | 0.5–1 d | 7.6 |
| 5 | rollout 四端点 + Schedule 合并 + 幂等/并发 + verify | 2–3 d | 8.2–8.4 |
| 6 | 账号下一期金额展示 | 0.5 d | 8.5 |
| 7 | 本地自动化测试补齐（A01–A16 可本地化部分） | 1.5–2 d | 7.8/8.6 |

开发分支：`feat/billing-zone-pricing`（基于 `feat/billing-brazil-pricing` 0adf475b，worktree `worktrees/be-zone-pricing`）。

## 13. 2026-10-18 实盘对齐（覆盖 §11.1 与 7 区假设）

测试 Stripe 主账号（`51SrJ7Q7GRT6LLkI1`）已按 **17 个倍率档 × 全 SKU** 建好价格，共 187 条倍率价（Basic/Plus/Pro 各 34 条月+年、Trial 17、Study Pass 17、3 个加装包各 17），另有 15 条批次前旧价仍 active。完整只读盘点见
`stripe-price-inventory-20261018.json`；档位 → 国家清单以 Price nickname 为唯一真值（114 国/地区，互不重叠），仓库内没有其它源文件。

用户拍板的执行口径：

1. **新购全部按倍率矩阵**：国家 → `pricing_country_routes` → 档位 → 该档 SKU 的 `stripe_price_id`。名单外国以及无可信国家头一律走默认 1.00 档（`Z100`，USD $29.99），新用户永远不再看到 $79.99。
2. **本币缺口**：TND/GHS/IQD/JOD/OMR/KWD/VES 不在对应档的 `currency_options` 里，这 7 国路由币种用 USD（所有档位都有 usd 选项）。
3. **存量合同到期换价**：旧 $79.99 合同按币种归属目标档（**usd→Z100、brl→Z040、krw→Z065**），由 rollout 在周期结束合并 Schedule；dry-run **不再回填** `pricing_zone/price_version`，保证到期前会员目录仍显示旧价，新价生效后由 webhook 反查回填、展示自动切换。
4. **旧价不下线**：15 条 legacy 价保持 active 服务存量续费，并登记进 `stripe_price_aliases` 保证反查永久可解析；区外/未知国家的兜底改由默认档承担，不再依赖 legacy 目录。
5. **购买地区落库**：`user_subscriptions` / `study_passes` 增加 `pricing_country`，checkout 的 session 与 subscription metadata 同时写入；合同动作只认 `pricing_zone/price_version/currency`，换 IP 不会改价。
6. 迁移序号：**091** 建表+加列、**092** 种子（17 区/187 绑定/249 路由/15 别名，全部开关 OFF）、**093** 启用脚本（preflight 绿后执行）。

> 注：旧 trial 转正合同（Schedule 里已有 intro_conversion 未来段）会被 rollout 判为 `user_future_change_pending` 冲突，留人工处理；Study Pass 旧价 $0.99 与 1.00 档同额，本期不做换价。
