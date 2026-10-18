# 通用地区计费实现（当前仅巴西）

跨工程需求与可售 SKU 以 Verla 根 OpenSpec `add-brazil-regional-pricing` 为准。没有数据库 schema 或 migration 变更；基准 USD catalog 保留，新订单与既有字段记录选币后的真实金额。

独立新购使用可信地区 BR → BRL、其他/未知 → USD；已有会员的附属购买、升降级和 Trial 转正以实际 Stripe Subscription.currency 为准。新 Pass 独立选币，续费保留自己的币种。多币种 resolver 只读 Price.currency_options，核验环境、映射和周期；缺币种隐藏 SKU 并拒绝购买。Price 缓存 60 秒，带确认快照的付款请求强制重新读取。

`GET /v1/billing/quote?kind=subscription|addon|study_pass|credit_pack&skuCode=...` 验证 Clerk 身份并返回 currency、amountCents、quoteVersion、trialDays、续费金额/币种和生效日期。Checkout/change/downgrade 接收 expectedCurrency、expectedAmountCents、quoteVersion。开关开启时必须确认；开关关闭时，未带报价的旧客户端保持兼容，已经带报价的请求仍比较快照，防止确认期间回滚导致静默切换金额。

服务端配置为 `BILLING_REGIONAL_PRICING_ENABLED=false`、`BILLING_REGIONAL_PRICING_COUNTRY_CURRENCIES=BR=brl`、`BILLING_REGION_PROXY_SECRET`、`BILLING_PORTAL_CONFIGURATION_ID`。local/test 可使用 `BILLING_REGION_TEST_OVERRIDE=<ISO 国家或 unknown>`；其他及混合 profile 拒绝 override。启用前必须完成 `Server_Config/docs/brazil-pricing.md` 的自有 Zone 来源认证及沙盒检查。Portal 配置必须关闭 subscription_update，以保留站内报价校验。

Customer 使用已验证 JWT 邮箱或 Clerk Backend API 的已验证 primary_email_address_id，已有 Customer 校验归属并补齐/同步 email。输入 DTO 的邮箱不作为权威来源。后台事件不使用地区模拟或请求价格，Schedule 两段显式指定实际合同币种，订单保存真实付款金额。

本地 Maven tests 使用模拟 Stripe 对象，不产生付款。`billing-brazil-pricing-reconciliation.sql` 是可选的只读运维查询，不得直接用于正式环境；本地沙盒账号清理已在备份后另行执行。已有 webhook 重试流程继续处理 Schedule 失败，必须完成实际 Phase 更新才视为已配置转正；查询结果中的对象需分别核验后才能另行修复。

国家→币种映射由 BillingRegionalPricingProperties 绑定并验证，格式为逗号分隔 COUNTRY=currency；当前仅 BR=brl，未匹配/unknown 或关闭时的新购均使用 USD。规则整体替换默认映射，空字符串移除所有地区规则；非法国家、币种、格式和重复国家在启动时被拒绝。旧 BILLING_BRAZIL_PRICING_ENABLED 不再生效，Server_Config 使用通用 docker-compose.regional-pricing.yml；生产配置没有增加其他国家或 SKU 金额；本地目标 Stripe 沙盒已单独配置 BRL 测试金额，也没有数据库结构变更。新购 metadata 记录实际可信 ISO 国家或 UNKNOWN，历史 OTHER 不重写。

## 地区扩展方案（待实施，2026-10-18 r5）

第一组10地区、第二组53地区全部使用新金额，共63地区；Pro统一使用“新 Pro”月付／年付价格。唯一当期保旧价例外：本次需求确认时线上已购买Pro的用户，当前已付周期保留旧金额，下个付费周期切新价。

上文为当前巴西基础实现记录，不能作为新价格范围或已上线证据。完整新金额、线上Pro资格及任务见[后端交接入口](billing-regional-pricing/README.md)。Study Pass全部62个区币种组合建议月价已补齐，见[测算明细](billing-regional-pricing/study-pass-pricing.md)，目标账户最低扣款及真实续费仍待验证。本轮未改代码或Stripe。
