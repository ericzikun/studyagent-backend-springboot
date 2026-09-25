# Study Pass 订阅迁移与验收

Study Pass 新购改为 USD 0.99 / 自然月自动续费。现有每 30 天订阅继续按原 Stripe Price 续费，旧一次性 Pass 维持原到期日；不会自动转换或更改已购用户的扣款周期。

## 复用现有商品配置切换到月付

1. 确认 Spring 业务库中既有 `sql/087_study_pass_subscriptions.sql` 已执行、`study_pass_subscription_30d` 商品行仍启用且 `stripe_price_id` 指向本环境已改为月付的 Stripe Price。**满足这些条件时无需新 SQL**，也不需要重跑 087。若是全新环境，仍按 086、087 的原始迁移顺序执行。
2. 在与部署 Stripe key 相同的账户中核对 Price：USD 0.99、`interval=month`、`interval_count=1`、active，且属于 Study Pass Product。已成功修改的 Price ID 可继续使用。Price 的名称、描述或 lookup key 仍写“30 days”时同步更正，避免配置和结账文案混淆。
3. 部署本次 Spring 和前端代码，确认 catalog 的 `studyPass.billingInterval=month`。现有 `pass_code` 保留为历史内部标识，不代表实际计费周期。新购买记录以 `billing_interval_days=NULL` 表示月付；历史 30 天订阅保留 `billing_interval_days=30`，各自按对应 Stripe 账单周期验收。无需添加数据库列。

如需只读核对现有商品配置，在 **Spring 业务库**执行：

```sql
SELECT pass_code, billing_type, price_cents, currency, stripe_product_id, stripe_price_id, is_active
FROM study_pass_products
WHERE pass_code = 'study_pass_subscription_30d';
```

仅当这行的 Product / Price ID 与对应环境 Stripe 不一致时，才需要更新现有行；不要为了月付另建一行。

4. 保持现有 Clerk、Stripe webhook 和 Study 销售开关配置；确认 Stripe 把 `checkout.session.completed`、异步付款成功、`invoice.paid` / `invoice.payment_succeeded`、`customer.subscription.created/updated/deleted`、`invoice.payment_failed` 与退款事件发到原有 webhook。无需改爬虫或 Cloudflare 配置。
5. 在 Test/Sandbox 账户用 Stripe Test Clock 从 1 月 10 日推进至 2 月 10 日、3 月 10 日，确认每期成功付款才延长阅读；另用月末日期验证 Stripe 的月末锚点行为。再检查取消、续费失败、会员覆盖，以及旧 30 天订阅仍按旧周期处理。

## 必须执行的真实环境验收

- 用无会员用户购买：Checkout 明确自动续费；支付后阅读放行，会员仍为 free，工具额度不变，账单只出现一笔 Pass。
- 用 Stripe Test Clock 推进一个自然月：实际支付成功后延长一期，产生独立续费账单；重复发送、反序发送旧账单不得延长或缩短最新周期。
- 续费失败：不延长阅读，原已付周期不变；订阅未结束时仍能进入管理并取消，禁止重复创建订阅。
- 取消续费：Stripe `cancel_at_period_end=true`；当前期仍可读，到期后不扣款、不再放行。重复点击无副作用。支付失败或已到期不单独展示状态卡。
- Pass 有效时购买 Basic 或 Pro Trial：会员付款成功才立即结束 Pass 和续费；会员正价，不抵扣。Trial 结束不恢复 Pass。放弃或失败的会员付款不改变 Pass。
- 留着旧 Pass checkout 再购买会员，随后完成旧 checkout：终止冲突 Pass，首笔冲突款幂等退款。正常先购买 Pass 再升级会员不退款剩余时间。
- 全额退款当前已付 Pass 账单：结束 Pass 并停止续费；历史账单退款不能终止更晚的已付周期。争议沿用既有人工审核流程，不影响会员工具额度。
- 旧一次性 Pass：旧日期仍有效，无管理续费按钮；旧已创建会话在商品停售后仍可履约。

## 接口与边界

- catalog 的 `studyPass.billingType` 区分 `subscription` / `one_time`；可售订阅 `billingInterval=month`。
- account 以 `canReadStudyLibrary` 放行；`studyPass` 的 `manageable` / `cancelAtPeriodEnd` 和购买快照只供展示。旧记录续费金额、币种、周期为空。`upgradeCreditAvailable` 恒为 false。
- `POST /v1/subscription/study-pass/cancel` 无请求体，鉴权决定用户。它只取消 Pass 续费，不取消会员。
- 新价格、SQL 执行、真实 MySQL 并发、Stripe Test Clock 与真实 Clerk 回跳不在本地单元测试的证明范围内。当前文档不是这些步骤已完成的声明。
