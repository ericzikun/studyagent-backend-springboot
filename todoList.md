# 区域定价上线记录与后续 TODO

- 记录日期：2026-10-19
- 生产后端：main `58a670cf`（23:07 CST 部署，healthy）
- 生产前端：main `7e6fee2a`（20:46 CST 镜像重建）
- 上线批次：`rollout-Z100-20261018-v1-20261019`
- 当前台账：**23 scheduled**（15 老合同 + 8 Trial）、18 cancelling、8 past_due、0 failed

---

## 1. 发布结果快照

| 项 | 结果 |
|---|---|
| 数据库迁移 | `091` + `094` + `092_live` 已执行 |
| 区价注册表 | 17 zones / 187 price bindings / 1,243 currency amounts / 249 routes / 12 aliases |
| 开卖开关 | `093` 已执行：zones active=17、routes enabled=249 |
| production preflight | `stripeMode=live`，199/199 pass，fail/draft/stripeError=0 |
| 新购价格 | DE €20.99、IT €18.99、US $29.99、CN ¥89.99、JP JPY 2,980、KR ₩25,900、TW NT$629、默认 $29.99 |
| 页面实测 | HK 出口显示 HK$ 本地价，无 $79.99 |
| 老合同 | 15 条 scheduled（到期自动换 Z100） |
| Trial | 8 条 scheduled，转正 phase2 已改为 Z100 pro_monthly $29.99 |
| 未处理 | 18 条取消中（无需处理）、8 条 past_due（需恢复后再迁移） |

---

## 2. 今天已完成（发布后操作记录）

### 2.1 数据库

- [x] 生产库执行 `sql/091_zone_pricing_tables.sql`
- [x] 生产库执行 `sql/094_rollout_ledger_nullable.sql`
- [x] 生产库执行 `sql/092_zone_pricing_seed_live.sql`（生产专用 live Price ID）
- [x] 校验：187 绑定全部有 Price ID、17 区 × 11 SKU、249 路由、12 别名
- [x] 与 live Stripe 逐条比对：product / USD 默认额 / interval / interval_count / livemode / currency_options 全部一致，0 问题
- [x] `sql/093_enable_zone_pricing_live.sql` 开卖

### 2.2 生产配置与边缘

- [x] `Server_Config/.env` 增加：`BILLING_REGIONAL_PRICING_ENABLED=true`、`BILLING_REGIONAL_PRICING_COUNTRY_CURRENCIES=`（空）、`BILLING_REGION_PROXY_SECRET`、`BILLING_ZONE_PRICING_INTERNAL_TOKEN`、`BILLING_ZONE_PRICING_DEFAULT_ZONE=Z100`、catalog cache=60
- [x] `.env` 权限改为 `600`
- [x] `docker-compose.yml`：springboot-backend 注入全部 billing 变量；frontend-v2 注入 proxy secret
- [x] nginx `main-site.conf`：`location /`（SSR）与 `location /v1` 注入 `X-Verla-Pricing-Proxy-Secret` + `X-Verla-Pricing-Country $http_cf_ipcountry`
- [x] `nginx -t` 通过并 reload
- [x] 配置备份：`/tmp/prod-billing-config-backup-20261019203508/`

### 2.3 代码部署

- [x] 后端 main 从 `c5a3590` 推进到商业化合并 `3a56fdb8`，再修复 Trial 相关提交到 `58a670cf`
- [x] 前端 main 到 `7e6fee2a`（#398 地区差异化 SKU 报价与金额展示），镜像重建
- [x] 后端容器重建、healthy，env 已生效（enabled / proxy secret / internal token 均在容器内）
- [x] 上线后修复并部署：
  - `9f8917eb`：rollout 跳过 Trial，避免误排周价
  - `58a670cf`：Trial 转正改用 Z100 pro_monthly，替换转正 phase2
- [x] 前端金额实现修复（release/feat）：IDR/ISK/UGX 不再被 Intl 放大 100 倍；main 本身已是正确实现

### 2.4 老合同 rollout

- [x] dry-run：scanned 49 / preview 15 / conflict 21 / skipped 13
- [x] 确认 preview 都是付费合同：2 Basic 月、1 Plus 月、10 Pro 月、2 Plus 年
- [x] apply：**scheduled 15 / conflict 0 / failed 0**
- [x] verify：scheduled 15 / failed 0
- [x] 抽查最早合同 Stripe schedule：旧价到 2026-10-23，之后 Z100 Basic 价，`end_behavior=release`

### 2.5 Trial 转正（方案 A）

- [x] 发现 8 条待转正 Trial 的 phase2 仍是旧 $79.99
- [x] trial-aware rollout：目标改为 Z100 pro_monthly `price_1USCfP7…`（$29.99）
- [x] 7 条已有转正 Schedule：只替换 phase2，保留 `change_type=intro_trial_conversion` / `pending_plan_code=pro_monthly`
- [x] 1 条无 Schedule（`sub_1UQLTo…`）：补建「Trial 周价 → Z100 pro_monthly」两段
- [x] apply：**scheduled 8 / conflict 0 / failed 0**
- [x] verify：scheduled 23 / failed 0
- [x] 8 条 Trial 的 Stripe schedule 全部核对为 phase2 = Z100 pro_monthly
- [x] 最早 `sub_1UPxAN…` 将于 **2026-10-20 04:18 UTC / 12:18 CST** 按 $29.99 转正

### 2.6 提交记录

| 内容 | main | release/2.1.0 | feat |
|---|---|---|---|
| Trial 转正改 Z100 价 | `58a670cf` | `b55521be` | `ef291901` |
| TODO 记录更新 | 本次提交 | 本次提交 | 本次提交 |
| 之前的 Trial 跳过修复 | `9f8917eb` | `077c1c93` | `f17b13e2` |
| 商业化主合并 | `3a56fdb8` | 已含 | 已含 |

---

## 3. 到期自动发生，不需要人操作

Stripe 按 Schedule 自动切价、出 invoice、扣款；webhook 自动回填合同三列、落订单。

### 3.1 老合同 15 条

| 到期（UTC） | 合同 | 目标价 |
|---|---|---|
| 2026-10-23 18:30:04 | Basic 月 | Z100 Basic $19.99 |
| 2026-10-25 07:32:53 | Plus 月 | Z100 Plus $39.99 |
| 2026-10-28 23:51:39 | Pro 月 | Z100 Pro $29.99 |
| 2026-11-01 13:05:32 | Pro 月 | $29.99 |
| 2026-11-01 23:36:17 | Pro 月 | $29.99 |
| 2026-11-04 02:58:59 | Basic 月 | $19.99 |
| 2026-11-11 04:51:38 | Pro 月 | $29.99 |
| 2026-11-12 02:53:51 | Pro 月 | $29.99 |
| 2026-11-13 14:22:18 | Pro 月 | $29.99 |
| 2026-11-17 18:02:12 | Pro 月 | $29.99 |
| 2026-11-18 13:29:19 | Pro 月 | $29.99 |
| 2026-11-18 16:32:44 | Pro 月 | $29.99 |
| 2026-11-19 07:15:06 | Pro 月 | $29.99 |
| 2027-07-02 06:12:41 | Plus 年 | Z100 Plus $191.88 |
| 2027-10-04 17:39:34 | Plus 年 | $191.88 |

### 3.2 Trial 8 条

| 转正（UTC） | 用户/订阅 |
|---|---|
| 2026-10-20 04:18:19 | `sub_1UPxAN…` |
| 2026-10-21 06:16:00 | `sub_1UQLTo…`（本无 schedule，已补建） |
| 2026-10-22 07:08:00 | `sub_1UQilg…` |
| 2026-10-22 15:24:48 | `sub_1UQqWS…` |
| 2026-10-24 09:55:45 | `sub_1URUL7…` |
| 2026-10-25 11:06:03 | `sub_1URruh…` |
| 2026-10-25 21:38:16 | `sub_1US1mW…` |
| 2026-10-26 09:10:37 | `sub_1USCaX…` |

全部按 **$29.99** 转正；无需人工 apply。

---

## 4. 后续需要人工 / 人工确认

### 4.1 verify（建议自动化）

`verify` 只读 + 更新台账，不写 Stripe，可重复执行：

```bash
cd /app/study-agent-new-struct/Server_Config
TOKEN=$(grep '^BILLING_ZONE_PRICING_INTERNAL_TOKEN=' .env | cut -d= -f2-)
curl -s -X POST -H "X-Internal-Token: $TOKEN" \
  "http://127.0.0.1:8080/v1/internal/billing/zone-pricing/rollout/verify?batchId=rollout-Z100-20261018-v1-20261019"
```

- `scheduled`：未到生效点，正常
- `applied`：已生效，核对应 invoice、订单、合同三列
- `failed`：需要人工看 Stripe schedule/price 和 `status_reason`

建议加每日 cron：查出所有 `status='scheduled'` 的 batch 逐个 verify，`failed > 0` 告警。`apply` 继续保持人工。

### 4.2 8 条 past_due

- 台账：`conflict / status:past_due`
- 不会自动换价
- 等付款恢复为 `active` 后，用**新日期的新批次** dry-run → apply
- 建议每天看一次 Stripe past_due

### 4.3 18 条 cancelling_at_period_end

- 13 条付费合同 + 5 条 Trial
- 到期自动取消，不需要迁移
- 无需操作

### 4.4 安全与运维（一次性）

- [ ] **轮换 Stripe live secret key 与 webhook signing secret**（值曾在对话出现，建议 24–72h 内）
  - 更新生产 `.env` 后 `docker compose up -d --force-recreate springboot-backend`
  - 在 Stripe Dashboard 更新 webhook endpoint 的 signing secret，确认事件 2xx
- [ ] **限制内部端点**：nginx 对 `/v1/internal/billing/zone-pricing/` 返回 404 或限制内网/VPN
- [ ] **正式 Cloudflare origin-auth**（当前是 proxy-secret workaround）
- [ ] **Trial 文案修复**：卡片显示 HK$19，但说明文案仍写 “$2.99”，随下次前端 release 修
- [ ] **监控**：webhook 失败率、`invoice.payment_failed`、新购转化、`rollout failed`

---

## 5. 下一次需要人参与

| 时间 | 事件 | 是否必须人 |
|---|---|---|
| **2026-10-20 04:18 UTC / 12:18 CST** | 最早 Trial 按 $29.99 转正 | 否；可抽查 |
| **2026-10-23 18:30 UTC / 10-24 02:30 CST** | 首条老合同到期换价 | 否；之后 verify |
| 2026-10-25 ~ 2026-11-19 | 其余老合同到期 | 否；逐次/每日 verify |
| 8 条 past_due 恢复后 | 新批次迁移 | **是（apply 需确认）** |
| 上线后 24–72h | Stripe key / webhook secret 轮换 | **是** |
| 下次前端 release | Trial 文案本地化 | 前端 |

**核心结论：23 条已排期合同不再需要 apply；后续只有 8 条 past_due 需要一次新批次，其余是 verify/监控和一次性安全收尾。**

---

## 6. 快速命令

```bash
# preflight
TOKEN=$(grep '^BILLING_ZONE_PRICING_INTERNAL_TOKEN=' /app/study-agent-new-struct/Server_Config/.env | cut -d= -f2-)
curl -s -X POST -H "X-Internal-Token: $TOKEN" \
  "http://127.0.0.1:8080/v1/internal/billing/zone-pricing/preflight?fresh=true"

# dry-run（新日期会生成新 batchId）
curl -s -X POST -H "X-Internal-Token: $TOKEN" \
  "http://127.0.0.1:8080/v1/internal/billing/zone-pricing/rollout/dry-run?zoneCode=Z100&targetVersion=regional-20261018-v1"

# apply（人工闸门）
curl -s -X POST -H "X-Internal-Token: $TOKEN" \
  "http://127.0.0.1:8080/v1/internal/billing/zone-pricing/rollout/apply?batchId=<batchId>"

# verify
curl -s -X POST -H "X-Internal-Token: $TOKEN" \
  "http://127.0.0.1:8080/v1/internal/billing/zone-pricing/rollout/verify?batchId=<batchId>"

# 国家价格抽查
curl -k -s -H "Host: verla.io" -H "CF-IPCountry: DE" https://127.0.0.1/v1/billing/config
```

---

## 7. 时间线总表

| 时间 | 事件 | 谁做 |
|---|---|---|
| 2026-10-20 04:18 UTC | 最早 Trial 按 $29.99 转正 | Stripe 自动 |
| 2026-10-21 ~ 10-26 | 其余 7 条 Trial 转正 | Stripe 自动 |
| 2026-10-23 18:30 UTC | 首条老合同到期 | Stripe 自动；verify |
| 2026-10-25 ~ 2026-11-19 | 其余 14 条老合同到期 | Stripe 自动；verify |
| 持续 | 8 条 past_due 恢复 | 恢复后新批次 apply |
| 上线后 24–72h | Stripe key / webhook secret 轮换 | 人工 |
| 下次前端 release | Trial 文案 “$2.99” 本地化 | 前端 |
