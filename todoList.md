# 区域定价上线后 TODO / 人工检查点

- 日期：2026-10-19
- 生产版本：backend main `9f8917eb`、frontend main `7e6fee2a`
- 已上线：新购 17 档多币种区价（093 已执行，zones=17 / routes=249）
- 已排期：老合同 Z100 换价 15 条（apply 完成，到期由 Stripe 自动切换）
- 台账批次：`rollout-Z100-20261018-v1-20261019`

---

## 0. 下一次需要人参与：**2026-10-20 12:18 CST（04:18 UTC）之前**

### 原因：待迁移 Trial 的转正价仍是旧 $79.99

dry-run 里 13 条 Trial 被标记为 `intro_trial_contract` 跳过。进一步核对 Stripe：

- **8 条 Trial 会转成正式 pro_monthly**（另外 5 条是取消中的 Trial，到期直接取消，无需处理）
- 其中 **7 条** 已有转正 Schedule，phase2 价格是 **旧 pro_monthly `price_1TmUvv…` = $79.99**
- **1 条**（`sub_1UQLTo…`）没有 Schedule，由应用侧转正流程处理，预计也会用旧价
- 最早一条 **`sub_1UPxAN…`（user_3Kclq…）在 2026-10-20 04:18:19 UTC / 12:18 CST 转正**

### 需要拍板的三选一

| 方案 | 做法 | 影响 | 建议 |
|---|---|---|---|
| **A. Trial-aware rollout（推荐）** | 让 rollout 对 `pro_trial_to_monthly` 的目标改为 **Z100 pro_monthly `price_1USCfP7…`**，并把已有转正 Schedule 的 phase2 价格替换掉；无 schedule 的那条单独处理 | 转正即按 $29.99，符合“下周期换新价” | 需要代码+测试+部署+apply，必须在 10-20 04:18 UTC 前完成 |
| B. 手工改 Schedule | 直接对 7 条 Stripe Schedule 的 phase2 价格批量改为 Z100 pro_monthly；无 schedule 的一条单独建 | 快，但绕过 rollout 台账，审计性差 | 时间不够时的备选 |
| C. 接受首月旧价 | 不处理，Trial 转正先按 $79.99 收一个月；转正后用新批次在**下一次续费**迁到 $29.99 | 8 个用户首月多付 $50；可能需要退款/沟通 | 不推荐 |

> **如果选 C，就不需要在 10-20 前操作；下一个人工检查点顺延到 10-23/24 的首次到期 verify。**

---

## 1. 已完成（无需再操作）

- [x] 生产库 `091/094/092_live` 迁移
- [x] production preflight：199/199 pass，`stripeMode=live`
- [x] `093` 开卖：17 区 / 249 路由
- [x] 国家价格烟测：DE €20.99、IT €18.99、US $29.99、CN ¥89.99、JP JPY 2,980、KR ₩25,900、TW NT$629、默认 $29.99
- [x] 页面实测：HK 出口显示 HK$ 本地价，无 $79.99
- [x] 老合同 rollout apply：15 条 scheduled、0 failed
- [x] 修复 Trial 误排期：`intro_trial_contract` 跳过（main `9f8917eb`）

---

## 2. 到期自动发生，**不需要人操作**

Stripe 会按已写入的 Schedule 自动切换价格、出 invoice、扣款；后端 webhook 自动回填合同三列并落订单。

| 到期时间（UTC） | 到期时间（CST） | 合同 | 目标价格 |
|---|---|---|---|
| 2026-10-23 18:30:04 | 10-24 02:30 | Basic 月付 | Z100 Basic `price_1USCfI7…kWi9` $19.99 |
| 2026-10-25 07:32:53 | 10-25 15:32 | Plus 月付 | Z100 Plus `price_1USCfI7…bghboO08` $39.99 |
| 2026-10-28 23:51:39 | 10-29 07:51 | Pro 月付 | Z100 Pro `price_1USCfP7…i17HGsdU` $29.99 |
| 2026-11-01 13:05:32 | 11-01 21:05 | Pro 月付 | 同上 |
| 2026-11-01 23:36:17 | 11-02 07:36 | Pro 月付 | 同上 |
| 2026-11-04 02:58:59 | 11-04 10:58 | Basic 月付 | Z100 Basic $19.99 |
| 2026-11-11 04:51:38 | 11-11 12:51 | Pro 月付 | $29.99 |
| 2026-11-12 02:53:51 | 11-12 10:53 | Pro 月付 | $29.99 |
| 2026-11-13 14:22:18 | 11-13 22:22 | Pro 月付 | $29.99 |
| 2026-11-17 18:02:12 | 11-18 02:02 | Pro 月付 | $29.99 |
| 2026-11-18 13:29:19 | 11-18 21:29 | Pro 月付 | $29.99 |
| 2026-11-18 16:32:44 | 11-19 00:32 | Pro 月付 | $29.99 |
| 2026-11-19 07:15:06 | 11-19 15:15 | Pro 月付 | $29.99 |
| 2027-07-02 06:12:41 | 2027-07-02 14:12 | Plus 年付 | Z100 Plus `price_1USCfL7…QcrDd5Hm` $191.88 |
| 2027-10-04 17:39:34 | 2027-10-05 01:39 | Plus 年付 | 同上 |

---

## 3. 到期后的核对（建议自动化）

`verify` 是**只读 + 台账更新**，不写 Stripe，可安全重复执行：

```bash
cd /app/study-agent-new-struct/Server_Config
TOKEN=$(grep '^BILLING_ZONE_PRICING_INTERNAL_TOKEN=' .env | cut -d= -f2-)
curl -s -X POST -H "X-Internal-Token: $TOKEN" \
  "http://127.0.0.1:8080/v1/internal/billing/zone-pricing/rollout/verify?batchId=rollout-Z100-20261018-v1-20261019"
```

判定：

- `scheduled`：还没到生效点，正常
- `applied`：已生效；同时核对应 invoice 金额、`recharge_orders`、合同 `pricing_zone/price_version/currency`
- `failed`：**需要人工处理**（查 Stripe schedule/price、`status_reason`）

建议：加一个服务器每日任务，查出所有 `status='scheduled'` 的 batch 逐个 verify；`failed > 0` 告警。`apply` 仍保持人工。

---

## 4. 待处理合同

### 4.1 8 条 past_due

- 台账状态：`conflict / status:past_due`
- 不会自动换价
- 处理：等 Stripe 重试/客户付款恢复为 `active` 后，用**新日期的新批次**再跑 dry-run → apply
- 建议每天看一次 Stripe Dashboard 的 past_due 列表

### 4.2 13 条 cancelling_at_period_end

- 到期自动取消，不需要迁移
- 无需操作

### 4.3 13 条 Trial（`intro_trial_contract`）

- 8 条会转正：见 §0，**10-20 前决策是否在转正时就用新价**
- 5 条是取消中的 Trial：到期取消，无需处理
- 如果选方案 C（首月旧价），转正后的**下一次续费**用新日期新批次迁移（2026-11 月中下旬）

---

## 5. 安全与运维 TODO

- [ ] **轮换 Stripe live secret key 与 webhook signing secret**
  - 两个值曾在对话中出现过，建议上线后 24–72h 内在 Stripe Dashboard 轮换
  - 更新生产 `.env`：`STRIPE_SECRET_KEY` / `STRIPE_WEBHOOK_SECRET`
  - `docker compose up -d --force-recreate springboot-backend`
  - 在 Stripe Dashboard 更新 webhook endpoint 的 signing secret，确认近期事件 2xx
- [ ] **限制内部端点**
  - 当前 `/v1/internal/billing/zone-pricing/**` 随 `/v1` 暴露在公网（有 token 保护）
  - 建议 nginx 加 `location ^~ /v1/internal/billing/zone-pricing/ { return 404; }`，或只允许服务器本机/VPN
- [ ] **正式 Cloudflare origin-auth**
  - 当前是 `X-Verla-Pricing-Proxy-Secret` workaround；正式方案需要 `X-Verla-Origin-Auth` + 源站仅 CF 回源
- [ ] **Trial 文案修复**
  - 定价页 Trial 卡片显示 HK$19/7天，但说明文案仍写 “$2.99”
  - 前端文案未本地化，随下一次前端 release 修复
- [ ] **监控**
  - Stripe webhook 失败率、`invoice.payment_failed`
  - 新购 checkout 转化、按国家 `config/quote` 抽样
  - `subscription_price_rollouts` 的 `failed`

---

## 6. 快速命令

```bash
# preflight（只读）
TOKEN=$(grep '^BILLING_ZONE_PRICING_INTERNAL_TOKEN=' /app/study-agent-new-struct/Server_Config/.env | cut -d= -f2-)
curl -s -X POST -H "X-Internal-Token: $TOKEN" \
  "http://127.0.0.1:8080/v1/internal/billing/zone-pricing/preflight?fresh=true"

# 新批次 dry-run（换 targetVersion 或等新日期生成新 batchId）
curl -s -X POST -H "X-Internal-Token: $TOKEN" \
  "http://127.0.0.1:8080/v1/internal/billing/zone-pricing/rollout/dry-run?zoneCode=Z100&targetVersion=regional-20261018-v1"

# apply（唯一写 Stripe，人工闸门）
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
| **2026-10-20 04:18 UTC（12:18 CST）** | **最早 Trial 转正；决定是否在转正时用 $29.99** | **人拍板；可能需要立即改 schedule/rollout** |
| 2026-10-21 ~ 10-26 | 其余 7 条 Trial 转正 | 同上；若选 C 则无需操作 |
| 2026-10-23 18:30 UTC（10-24 02:30 CST） | 首条老合同到期换价 | Stripe 自动；之后跑 verify |
| 2026-10-25 ~ 2026-11-19 | 其余 14 条老合同到期 | Stripe 自动；逐次/每日 verify |
| 持续 | 8 条 past_due 恢复 | 业务/付款恢复后新批次迁移 |
| 上线后 24–72h | Stripe key / webhook secret 轮换 | 人工 |
| 下一次前端 release | Trial 文案 “$2.99” 本地化 | 前端 |
