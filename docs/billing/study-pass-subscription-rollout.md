# Study Pass 订阅迁移与验收

本次改为 USD 0.99 / 每 30 天自动续费。代码与数据库都必须升级；旧一次性 Pass 维持原到期日，不会变成自动扣款订阅。

## 测试服启用顺序

1. 确认 Spring 使用的业务数据库（现有 `086_study_passes.sql` 使用 `studyagent`）。连接测试数据库并选中该库，执行 `sql/087_study_pass_subscriptions.sql`。脚本可重复执行；不需要在前端或 Sanity 数据库执行。脚本会停止旧商品的新销售，保留旧订单。
2. 在与测试服 Stripe key 相同的 **Test/Sandbox 账户**创建新的 recurring Price：USD 0.99，`recurring.interval=day`、`interval_count=30`。不能用 month，也不能复用原来 one-time Price。可复用对应 Product。
3. 部署此 Spring 版本和对应前端版本。先保持新商品 `is_active=0`。部署失败不要把数据库列删除；旧记录仍保留，新代码必须有新增列才可运行。
4. 将实际 Product / Price ID 填入下列环境专属语句后执行，再确认 catalog 返回 `billingType=subscription`。不要原样执行占位 ID，不把真实密钥写入 SQL 或仓库。

```sql
UPDATE study_pass_products
SET stripe_product_id = '<实际测试 Product ID>',
    stripe_price_id = '<实际测试 recurring Price ID>',
    is_active = 1,
    config_version = config_version + 1
WHERE pass_code = 'study_pass_subscription_30d'
  AND billing_type = 'subscription';
```

5. 保持现有 Clerk、Stripe webhook 和 Study 销售开关配置；确认 Stripe 把 `checkout.session.completed`、异步付款成功、`invoice.paid` / `invoice.payment_succeeded`、`customer.subscription.created/updated/deleted`、`invoice.payment_failed` 与退款事件发到原有 webhook。事件重复投递由现有事件表与账单去重处理。无需改爬虫或 Cloudflare 配置。

## 必须执行的真实环境验收

- 用无会员用户购买：Checkout 明确自动续费；支付后阅读放行，会员仍为 free，工具额度不变，账单只出现一笔 Pass。
- 用 Stripe Test Clock 推进 30 天：实际支付成功后延长一期，产生独立续费账单；重复发送、反序发送旧账单不得延长或缩短最新周期。
- 续费失败：不延长阅读，原已付周期不变；订阅未结束时仍能进入管理并取消，禁止重复创建订阅。
- 取消续费：Stripe `cancel_at_period_end=true`；当前期仍可读，到期后不扣款、不再放行。重复点击无副作用。支付失败或已到期不单独展示状态卡。
- Pass 有效时购买 Basic 或 Pro Trial：会员付款成功才立即结束 Pass 和续费；会员正价，不抵扣。Trial 结束不恢复 Pass。放弃或失败的会员付款不改变 Pass。
- 留着旧 Pass checkout 再购买会员，随后完成旧 checkout：终止冲突 Pass，首笔冲突款幂等退款。正常先购买 Pass 再升级会员不退款剩余时间。
- 全额退款当前已付 Pass 账单：结束 Pass 并停止续费；历史账单退款不能终止更晚的已付周期。争议沿用既有人工审核流程，不影响会员工具额度。
- 旧一次性 Pass：旧日期仍有效，无管理续费按钮；旧已创建会话在商品停售后仍可履约。

## 接口与边界

- catalog 的 `studyPass.billingType` 区分 `subscription` / `one_time`。
- account 以 `canReadStudyLibrary` 放行；`studyPass` 的 `manageable` / `cancelAtPeriodEnd` 和购买快照只供展示。旧记录续费金额、币种、周期为空。`upgradeCreditAvailable` 恒为 false。
- `POST /v1/subscription/study-pass/cancel` 无请求体，鉴权决定用户。它只取消 Pass 续费，不取消会员。
- 新价格、SQL 执行、真实 MySQL 并发、Stripe Test Clock 与真实 Clerk 回跳不在本地单元测试的证明范围内。当前文档不是这些步骤已完成的声明。
