# 地区差异化定价：方案 A 后端交接文档

版本：2026-10-18 r3（发布新价后，适用存量订阅下周期切换）。状态：用户已选择方案 A，并确认首批新购采用价格表的“新 Pro”列；本轮交付技术方案，尚未实施代码、Stripe 配置或部署。

本文是现有 `add-brazil-regional-pricing` 的扩展方案。发生范围冲突时，本文覆盖旧设计中“仅 BR”“每个 SKU 唯一销售 Price”的限制；原有鉴权、权益、计费周期、支付幂等和报价确认规则继续适用。不要另建一套支付系统。

## 1. 已确认的交付范围

- 基于前后端的 `feat/billing-brazil-pricing`，第 1 组十地区加巴西，共 11 地区：香港、台湾、印尼、韩国、土耳其、德国、意大利、瑞典、泰国、奥地利、巴西。
- 权益不变，固定地区售价与本币收费。新 Pro 用于首批地区新购及适用存量订阅下周期价格，不新建权益等级，不另建 `pro_new` SKU。
- 德国新购：Trial €1.99/7 天，随后 Pro €20.99/月；直接年付 €251.88。适用旧订阅当前周期不变，下一个付费周期切换同区同币种新价。
- 泰国倍率 0.3、奥地利 0.8；使用本次价格附件中的固定金额，运行时不计算倍率或汇率。
- 会员月/年付、当前可售月付转正 Trial、三个会员 extra packs、Study Pass 纳入地区定价。停售 Trial 不重新出售。
- Study Pass 新购按所在地区倍率计算固定本币月价；用户已确认低于扣款下限时上调。续费保持自身区和币种，发布新版本后于自身下一个付费周期切价，不跟随会员合同或当前所在地改价。用户已明确旧独立额度包（credit packs）本次不动，价格、入口和权益保持原样，仍走原 legacy 路由。
- 巴西加入 Z040，会员/Trial/extra packs 使用用户新表的 BRL 价格，Pro 采用新 Pro 列，Study Pass 使用本次补价。美国、印度等其他地区不切换；印度专项及其余第 2 组钱包不在范围内。
- 不追溯改历史账单、不迁移币种、不新增钱包集成、不做国家支付方式选择器、不改税费/优惠政策，不做动态汇率或运营管理后台。

“新购”指既有领域规则认定可以建立的新合同；取消但仍在有效期、欠款恢复、待完成变更均不自动视为新购。旧合同真正结束后重新购买，才按当时开放地区规则选择新版本。

## 2. 基线与已有能力

| 工程 | 基线分支 | 本次审查提交 |
| --- | --- | --- |
| Spring Boot | `feat/billing-brazil-pricing` | `0ac6a333684078d73a545f279e01d9019a0baf5e` |
| 前端 V2 | `feat/billing-brazil-pricing` | `dbe97d575af06731749cbda0b2420666a87b5a19` |

本机对应 `worktrees/studyagent-backend-springboot-brazil-pricing/` 和 `worktrees/studyagent-fronted-v2-brazil-pricing/`，审查时均无未提交改动。接手者应先核对分支的新进展，不把上述提交当作要求回退的目标。

已有可信地区解析、国家→币种配置、Stripe Price 查询缓存、报价确认、Checkout 显式 currency、历史订阅保币种、Schedule 处理和支付回调幂等。本次扩展共同 resolver，复用这些能力。

关键缺口：当前 `pricedPlan/pricedAddon` 仅改变金额和币种，不改变 Price ID；回调按商品表唯一 Price ID 查 SKU；仅保留 currency 无法区分德国 EUR 和意大利 EUR。

## 3. 配置负责人需要做什么

本版替代初版“Study Pass 不参与”和“巴西不改价”两项范围约定。用户已明确要求补入，其他国家未因此自动开放。

### 3.1 固定国家、定价区和币种

| 定价区 | 参考倍率 | 首批国家/地区代码 | 实际收费币种 |
| --- | ---: | --- | --- |
| Z025 | 0.25 | ID 印尼 | idr |
| Z030 | 0.30 | TH 泰国 | thb |
| Z040 | 0.40 | TR 土耳其、BR 巴西 | try、brl |
| Z065 | 0.65 | TW 台湾、KR 韩国 | twd、krw |
| Z070 | 0.70 | IT 意大利 | eur |
| Z080 | 0.80 | HK 香港、DE 德国、AT 奥地利 | hkd、eur |
| Z085 | 0.85 | SE 瑞典 | sek |

共有 7 个区、9 种本币、10 个“区＋币种”组合。德国与奥地利共用 Z080/EUR；意大利使用 Z070/EUR。区名只是标识，不是计算公式；以后同倍率同币种出现不同售价，应拆区，不覆盖现有价格。

### 3.2 Stripe Product 与 Price

继续使用各 SKU 现有的 Stripe Product。每个“定价区＋SKU”创建一条新 Price，同区的不同币种放入该 Price 的 `currency_options`。

| SKU | Stripe 类型/周期 | 表格金额来源 |
| --- | --- | --- |
| `pro_trial_to_monthly` | recurring，week × 1，仅首段 7 天 | 试用7天 |
| `basic_monthly` / `basic_yearly` | recurring，month/year × 1 | Basic月/年 |
| `plus_monthly` / `plus_yearly` | recurring，month/year × 1 | Plus月/年 |
| `pro_monthly` / `pro_yearly` | recurring，month/year × 1 | **新Pro月/年** |
| `addon_assignment_3` | one_time | 作业包 |
| `addon_detection_5` | one_time | 检测包 |
| `addon_humanizer_3` | one_time | Humanizer包 |
| `study_pass_subscription_30d` | recurring，month × 1，自然月 | $0.99 基价按倍率折算并应用低额上调规则 |

合计 **77 条新 Price，110 个本币金额**。每条 Price 再配置一个统一的 USD 默认金额，作为 Stripe 多币种 Price 的默认值；共 77 个 USD 默认金额。它们不是新增的地区美元销售入口。

USD 默认金额使用本附件 `usdDefaultAmounts`：Basic/Plus/Trial/额外包沿用表格美国金额，Pro 使用新 Pro 的 29.99/月、359.88/年，Study Pass 为 0.99/月。它们只属于新建地区 Price，**不得写回旧默认销售 Price 或旧商品表金额**。非首批地区仍选旧销售 Price。

所有新增 Price 统一默认 USD，避免同一 Checkout 的 Price/固定额优惠等默认币种不一致。后端必须显式传选中的本币；即使 Price 有 USD 默认值，也不能在缺本币时自动卖 USD。

示例：在现有 Basic Product 下创建 `Z080 / Basic monthly / regional-20261018-v1`：默认 USD 19.99；额外添加 EUR 13.99、HKD 129。同产品再创建 Z070 Price：默认 USD 19.99、EUR 11.99。Stripe 不配置国家列表，国家路由在后端。

Dashboard 操作顺序：选择正确沙盒 → 产品目录 → 原 Product → 添加价格 → 设置周期和 USD 默认金额 → 添加对应本币金额 → 保持既有税务策略 → 保存并记录 Price ID。年付和额外包分别按表格周期配置。**不是先给每个国家复制一个 Product。**

命名建议：`regional-20261018-v1/Z080/basic_monthly`。Price metadata 记录 `sku_code`、`pricing_zone`、`pricing_version`，仅便于检查；不能依靠客户端 metadata 决定收费。

已售 Price 和已有 currency option 不改价、不转移归属。发布该区新版本时保留旧 Price 绑定，并逐合同安排下一付费周期换到新 Price；只修改目录映射不会自动更新 Stripe 订阅。历史快照保留不等于永久锁住续费价格。

### 3.3 金额与环境检查

配置金额详见同目录 [regional-expansion-prices.json](regional-expansion-prices.json)：包括 11 地区映射、77 个待填 Price ID、110 个本币金额及最小单位值；Study Pass 金额为已按上调策略生成的建议价，仍有扣款核验门槛。它是交付配置草案，**当前应用不会自动读取**，后端需要实现绑定和验证。

- Dashboard 输入主单位：KRW 2,490、TWD 59、IDR 13,900。
- API/后端保存最小单位：KRW 2490、TWD 5900、IDR 1390000。KRW 不乘 100；TWD、IDR 仍乘 100，不能因为标价没有小数就当成零小数币种。
- HKD、EUR、SEK、THB、TRY、BRL 同样使用两位最小单位。所有金额转换使用十进制精确计算，不使用浮点数四舍五入猜测。
- 沙盒与生产分别填写 Price ID、Product ID、Stripe account ID；不得把沙盒 ID 直接复制到生产。
- 检查 Price active、Product 归属、币种、金额、固定单价、recurring interval/count、税行为；核对现有优惠券是否支持这些币种。税行为不在这次擅自改成含税或未税。
- 最低扣款、支付方式可用性、实际结算币种以目标账户核验。Price 能保存不等于每次扣款都可成功，尤其要测优惠后低额和零额账单。

### 3.4 支付方式和部署配置

- 保持现有银行卡和已批准的支付方式配置，韩国本地卡、Kakao Pay、Naver Pay 分别验收首次支付、保存授权和后续订阅扣款。测试环境“已启用”不代表生产已开通。
- 后端继续使用 Checkout 的动态支付方式，不添加按国家硬编码的 `payment_method_types`。订阅为 `mode=subscription`，一次性额外包为 `mode=payment`。
- 本次不要求启用微信、支付宝、PromptPay、PayNow，也不以换成 USD 作为让不支持自动续费的支付方式获得该能力的方案。
- 不新增多币种结算银行账户要求；向客户收本币和向商户银行结算是两件事，结算方式沿用账户配置。
- 保留已有 `BILLING_REGIONAL_PRICING_ENABLED`、可信代理密钥、Webhook 签名、Portal 配置。新增地区销售开关与映射文件，先部署关闭状态，检查通过后按国家开放。
- Portal 继续禁止绕过后端的订阅改价/换套餐入口，保留已批准的账单、付款方式与取消等管理能力。检查 Dashboard 的 upsell/cross-sell 不得把客户带到另一区 Price。

### 3.5 Study Pass 月价与低额处理

基价为 USD 0.99/月，SKU `study_pass_subscription_30d`，按自然月自动续费，不改成每 30 天，也不恢复旧一次性 Pass。

原表没有汇率列。本次用各币种已有 Basic 月价除以 `19.99 × 该地区倍率` 得到固定折算参考；EUR 统一取德国作为参照，泰国沿用此前补价时的 33.5 THB/USD。它们是从已批准标价推导的估算口径，**不是查到的实时汇率，也不是 Stripe 实际结算汇率**。

计算：`原始月价 = 0.99 × 地区倍率 × 固定折算参考`。用户已同意不足最低扣款时上调。当前建议价先采用 USD 0.60 等值作为缓冲参考，再按币种步长向上取整。**0.60 不是 Stripe 的统一最低扣款要求。** 最终应核对目标账户的结算币种、实际支付方式和实测结果；不足则在上线配置阶段继续上调固定价，不能在用户付款时临时加价。

| 地区 | 倍率 | 按倍率原始月价，约 | 建议配置月价 | 本次采用参考下限上调 |
| --- | ---: | ---: | ---: | --- |
| 香港 | 0.8 | HKD 6.39 | **HKD 6.40** | 否，仅向上取整 |
| 台湾 | 0.65 | TWD 20.75 | **TWD 21** | 否，仅向上取整 |
| 印尼 | 0.25 | IDR 4452.28 | **IDR 10800** | 是 |
| 韩国 | 0.65 | KRW 836.97 | **KRW 840** | 否，仅向上取整 |
| 土耳其 | 0.4 | TRY 19.76 | **TRY 30** | 是 |
| 德国、奥地利 | 0.8 | EUR 0.69 | **EUR 0.70** | 否，仅向上取整 |
| 意大利 | 0.7 | EUR 0.61 | **EUR 0.61** | 否，仅向上取整 |
| 瑞典 | 0.85 | SEK 8.32 | **SEK 8.40** | 否，仅向上取整 |
| 泰国 | 0.3 | THB 9.95 | **THB 21** | 是 |
| 巴西 | 0.4 | BRL 2.03 | **BRL 3.10** | 是 |

取整步长：HKD/SEK/BRL 为 0.1，TWD/TRY/THB 为 1，IDR 为 100，KRW 为 10，EUR 为 0.01。JSON 的 `studyPassPricing` 保存参考汇率、原始计算值、缓冲值、步长和建议金额，可复算。所有地区建议价都保持待扣款核验状态，不能把“未触发参考下限”当成已经通过真实门槛。

[Stripe 最低金额规则](https://docs.stripe.com/currencies#minimum-and-maximum-charge-amounts) 取决于结算币种，跨币种收费需满足换算后的门槛；例如 HKD 结算的基础下限是 HKD 4。本轮连接器只列出了正式账户，未取得该账户实际结算币种及结算账户配置，也未创建支付验证。因此本表是可审阅建议配置，不是扣款成功保证。上线前同时检查首付、次月扣款和优惠后低额，核验未通过则不启用该地区的这一批销售。

### 3.6 巴西新购价格

巴西使用用户表格中的 0.4 区价格，不再使用原沙盒“美元数值 ×5”的测试价。巴西与土耳其共用 Z040 的区 Price，分别增加 BRL 和 TRY option。

| SKU | 新购 BRL 价格 |
| --- | ---: |
| Trial 7 天 | 5.99，之后转 Pro 60.99/月 |
| Basic 月/年 | 40.99 / 194.99 |
| Plus 月/年 | 80.99 / 390.99 |
| 新 Pro 月/年 | 60.99 / 731.88 |
| 作业包 / 检测包 / Humanizer包 | 19.99 / 9.99 / 13.99 |
| Study Pass 月 | 3.10，待最低扣款实测 |

上述价格进入新地区 Price，原 BRL Price 不覆盖。已付 Trial、老会员和老 Pass 若归属已明确、目标已验证，则保留当前周期，在下一付费周期换新价。例如已核实 Z040/BRL 的原沙盒 Pro 399.90/月，本期不变，下次续费切为 60.99；已有 Trial 的下一期 Pro 转正目标须合并更新。旧独立额度包暂未重定价，因此巴西该类旧商品仍走原有价格，不能宣称“所有旧商品都已改价”。

### 3.7 “旧独立额度包”与三个额外包的区别

这里的 credit packs 不是新加的积分系统，而是代码里原有的独立额度商品：

| 类型 | 代码中的套餐 | 与本次价格表的关系 |
| --- | --- | --- |
| 作业独立额度 | `assignment_1/5/10/50`：1/5/10/50 次 | 不等于表中 `addon_assignment_3` |
| 检测独立额度 | `detection_10k/50k/200k`：1万/5万/20万字 | 不等于表中 `addon_detection_5` |
| Humanizer 独立额度 | `humanizer_10k/50k/200k`：1万/5万/20万字 | 不等于表中 `addon_humanizer_3` |

前者属于旧 `/v1/payment/config`、credit_pack 目录；后者是当前会员 extra packs。基线的 `start-mock.sh`、`sql/023_humanizer_tasks_add_quota_ledger_id.sql`、`price-matrix.md` 可确认这些定义，但不能据此断言它们当前在生产页面仍全部可见。用户已明确这些十种旧商品本次不动，不改价、不下架、不删除，继续保留原规则。

## 4. 后端配置模型

新增 `billing.regional-pricing.zone-pricing` 配置块；建议由独立 JSON 配置文件绑定为类型化对象，应用配置只提供文件路径和开关，避免在环境变量中放 77 个 ID。按项目现有配置加载模式实现，无需引入配置中心。

配置入口约定为 `billing.regional-pricing.zone-pricing.config-path`，环境变量 `BILLING_ZONE_PRICING_CONFIG_PATH`；这是**待实现**的新入口，不是现有代码已经支持的参数。路径为空表示不加载新增目录；加载后由文件内 `zonePricingEnabled` 与 `enabledCountries` 控制新购，仍受原地区总开关约束。开关变更通过受控配置发布/重启生效，不额外实现热更新 API。部署必须把含历史绑定的配置文件保留并挂载；已有地区合同后不能用清空路径的方式回滚。

结构采用附件 JSON：

```text
zonePricingEnabled: false
enabledCountries: []
countryRoutes[DE] = { zone: Z080, currency: eur }
activeVersions[Z080] = regional-20261018-v1
catalogs[version].zones[Z080].prices[basic_monthly]
  = { stripePriceId, stripeProductId, expectedDefaultAmountMinor,
      currencyAmounts: { eur: {amountMinor:1399}, hkd:{amountMinor:12900} }, ... }
legacyPriceAliases[历史PriceId] = { skuCode, expectedProductId }
legacyTargetPrices[skuCode] = { stripePriceId, stripeProductId }
```

`countryRoutes` 表示候选路由，只有 `enabledCountries` 里的国家才生效。总开关关闭时新购仍走旧规则。单个国家启用前，该国家涉及的全部 11 SKU 必须验证通过；Study Pass 额外要求目标账户最低扣款核验通过。

金额附件用于配置预检与核对；**实际报价仍从 Stripe 读取，并与版本中预期金额核对**。配置和 Stripe 不一致时禁止该销售报价，不偷偷以一方覆盖另一方。不能让前端维护另一份金额。

结构验证不依赖联网：拒绝重复国家、非法币种/区、缺目标、同一 Price ID 绑定多个 SKU/区/版本、占位 ID、非整数/越界金额、错误周期。关闭的草案允许留空 Price ID；开启某国家前必须全部补齐。

远程预检核验账户、livemode、Product、金额和周期，输出每项通过/失败，不在生产每次启动自动创建 Stripe 对象。运行时按现有短缓存/确认时刷新规则再校验，缓存 key 至少隔离账户环境、版本、Price ID、currency。停用新购不能删除历史解析需要的版本。

## 5. 选价算法与合同绑定

### 5.1 统一上下文

引入内部 `PricingSelection`，包含 `catalogVersion`、`zone`、`currency`、`skuCode`、`priceId`、`productId`、`amountMinor`。Trial 另外持有转正目标 `renewalPriceId` 与金额。仅在内部使用，不为了展示给前端增加区/Price ID 字段。

```text
resolve(user, kind, sku, action):
  校验原有商品可售、登录、权益、试用资格、并发购买规则
  若是现有会员的动作或依附会员的额外包：
    读取实际 Subscription 和其当前 Price
    由已登记 Price ID 解析原合同区/版本，币种以 Subscription.currency 为准
    在同一区、当前生效版本中选择本期动作目标；下周期目标读取已安排 Schedule，未归属旧合同走 legacy 并列入待核对
  否则若是已有 Study Pass 的续费/恢复：
    使用该 Pass 自身实际 Subscription/Price 解析当前区、版本和币种，下周期目标读取其已安排 Schedule
  否则若 kind 为 credit_pack：
    沿用原巴西分支的旧独立额度包规则
  否则若地区总开关及 zone 开关开启，且可信国家在启用名单：
    countryRoutes → zone/currency → activeVersion → SKU Price
  否则：
    沿用原默认/巴西新购目录，不修改原 BRL/USD 金额
  从 Stripe 校验选中的 Price、本币金额及 Trial 转正目标
  返回唯一的 PricingSelection
```

匹配到已启用地区但配置不完整时，返回不可售，不回退 USD。未开放地区属于明确的 legacy 路由，两者不可混为一谈。

### 5.2 保存历史快照与当前版本，不永久锁价

方案 A 不新增数据库表/列，采用 **不可变 Price 注册表＋现有订单 JSON 快照＋Stripe 合同对象**：

1. 每条新 Price ID 唯一对应 `version/zone/sku`；德国、奥地利共享同一绑定，不算冲突。不复用该 ID 到另一个区版本。
2. 新 Checkout 同时写 Session metadata 和 `subscription_data.metadata`：`pricing_zone`、`pricing_version`、`sku_code`，Trial 增加 `renewal_price_id`。保留原 `pricing_region` 作为购买时国家审计字段，不拿它代替合同区。
3. 当前与未来版本分开：已生效版本以实际 Subscription Price 为准，未来目标保存在 Schedule 与迁移清单，不能提前替换当前镜像。在 `recharge_orders.biz_context` 合并 `pricing` 对象：区、版本、当前/目标/转正 Price ID、币种、报价最小单位值。**合并而非覆盖**原有升级抵扣、Pass 等字段。待支付订单创建时写入，事件重试也能恢复。
4. Subscription 当前 Price ID 和实际 currency 为合同解析依据；metadata、订单快照用于一致性核对和恢复。只信任注册表中已验证的 ID，不能仅凭 metadata 声称 `plan_code=pro` 发权益。
5. 续费、Trial 转正、升级、降级、额外包和故障恢复不重新按访问国家定价。Webhook 请求的 IP 不参与任何选价。
6. 配置版本不设定时自动淘汰。只要可能收到旧订阅、退款、重试或延迟事件，相关绑定都必须保留。

Study Pass 独立维护当前生效版本与下一期目标：复用 `StudyPassEntity.stripePriceId`、`stripeSubscriptionId`、`renewalCurrency`、`renewalPriceCents` 及订单快照。购买时 `pricedProduct` 必须替换实际 Price ID，续费/对账不得再拿商品表当前销售 Price 校验旧 Pass。Pass 与会员是不同合同，不共享定价区；由会员覆盖 Pass 时保留既有取消、并发和退款规则，不增加跨币种抵扣。Pass 的 Session/Subscription metadata 与会员一样记录区版本，但使用 pass SKU。

例：德国用户购买 Z080/v1 后赴意大利，本期仍用 Z080/v1/EUR；发布 v2 并安排成功后，下期转为 Z080/v2/EUR，不转意大利区。旧 USD/KRW/BRL 先通过历史 Price alias 识别商品，再用可靠购买证据或经核对的逐合同归属映射确定区；不能按当前 IP 或单凭币种猜。无法确认区或缺同币种可售目标时列入待核对，不自动换币，也不借用新 Price 的技术性 USD 默认金额。

旧合同的当前价格必须读取其实际 Price；未迁移期间的本期动作仍按 legacy 目标映射解析，已明确的下周期迁移目标则读取已安排的新版本 Price。不要先找到 SKU 再拿当前销售 Price 重算旧合同。部署前只读收集当前销售和历史仍可能出现的 Price ID，生成 legacy alias；未登记历史 Price 拒绝收费变更并告警，不能猜成新地区价，也不能提前将订单置为成功。

`legacyTargetPrices` 在首次上线前从原销售目录生成冻结快照，至少覆盖原合同可变更到的会员套餐、Trial 转正目标和依附会员的额外包；对应 ID 同时进入 legacy alias。非首批新购仍走现有商品表目录；本次不得改其映射，因此首次发布二者一致。两个 legacy 字段在附件中留空，表示必须盘点补齐，不能理解为空字典即可上线。配置文件的账户/模式声明也必须与实际 API 凭据匹配，不依赖 ID 外观区分沙盒/生产。

### 5.3 试用、升级与降级

- Trial 首段沿用 week × 1 的付费订阅与 Schedule，不改成 Stripe 免费 trial。创建前校验同区同版本 Pro 月付 Price，7 天后转入该 Price，所有 phases 显式带原 currency。
- 首笔已付但 Schedule 创建失败，保留可重试状态及现有告警，不能标记“转正安排成功”。重试须核对最新有效安排；若已由新价发布更新目标，旧任务不得把目标写回旧版本。
- 升降级分类仍按原 tier/plan/interval 规则，金额由该合同定价上下文提供。保留现有立即/到期生效、周期重置、抵扣及取消逻辑。
- **新 Pro 比 Plus 月价低**：现有 `classifyPlanChange` 按权益等级判断；现有 `UpgradeChargeCalculator` 对目标月付收取完整目标月费。因此德国 Z080/v1 的 Plus 月付升级 Pro，按既有规则本次收 €20.99、切换权益和周期；不是收负差价，也不是自动退 €7。这是保留既有算法的结果，UI 必须显示真实本次应付。
- 年付抵扣仍以原实际净支付依据和既有算法计算，不能用当前国家价或最新目录价代替。零额、优惠和税造成的实际金额差异按原付款事实处理，不用“等于标价”判断支付是否真实成功。
- 同 SKU 存量换价通过新价发布入口安排下一付费周期生效，不要求用户取消重订；它不属于立即升降级，不产生本期差额。

### 5.4 发布新价与下周期切换

规则详见 [subscription-price-rollout spec](specs/subscription-price-rollout/spec.md)。本次已批准此业务规则；当前仍只交付规格，不执行真实迁移。

1. 提供服务端受控的 dry-run/apply 入口，不新建运营后台。发布绑定明确版本；按账户、合同、源/目标 Price、生效时间记录清单与逐项结果，保留可复查执行记录。历史归属映射属于经核对的发布输入，不从当前 IP 推断。
2. 无 Schedule 的合同从实际订阅创建；有 Schedule 的只合并更新原计划，保留用户试用转正、降级、取消及所有仍需保留的 phase 设置。当前阶段不改价、不重置周期、不补扣；下一付费边界切目标 Price。年付按年度续费边界，不按月度额度刷新。
3. 用发布批次/合同/目标/生效时间构成幂等上下文，写入前重读合同、发票和计划。取消、暂停、欠款冲突、已出账、窗口已过、归属不明或缺目标均逐项输出原因，不强行改价；后续解决后重新确定可用窗口，不能追溯重写账单。
4. 用户升级/降级改变目标或周期时须重新合并迁移目标；新版本发布淘汰旧任务，超时重试先读实际 Schedule。不依赖 invoice.paid 时临时改价，也不新增每次扣款前计算实时汇率的逻辑。
5. 分别确认“已安排”“目标阶段已生效”“本次发票已付”。合同当前版本在实际生效后更新，金额与权益按实际支付处理，旧事件不回退新状态。
6. 新购开关不负责撤销已写入的换价；撤销未生效目标是独立受控操作，只撤销本批次修改，不清空其他未来安排。已生效周期不追溯退款。

复用 Stripe Schedule、metadata、现有 webhook 事件记录、订单快照与受控批次结果文件进行恢复；无需为了版本单独建表。仅靠进程内变量或一次性控制台输出不足以支持重试，执行记录必须持久保存并可回读 Stripe 对账。

## 6. 需要改到哪些代码

以下路径相对 Spring Boot 工程；类名为本次审查基线，接手后先定位调用方，再做小范围改造。

| 位置 | 具体修改 |
| --- | --- |
| `agent-infra/.../billing/BillingRegionalPricingProperties.java` | 保留 legacy 国家币种配置；增加地区目录与启用名单绑定/验证 |
| `agent-infra/.../billing/BillingPricingService.java` | 增加上下文选区、Price 注册表正反查；保留 Stripe 校验与缓存；既有 `!enabled` 返回数据库基准金额的快捷分支不得作用于已绑定地区合同 |
| `agent-infra/.../billing/BillingDomainServiceImpl.java` | `pricedPlan/pricedAddon` 必须同时替换 Price ID、amount、currency；所有 quote、checkout、change、downgrade、Trial 说明和额外包入口使用同一上下文 |
| `agent-infra/.../billing/StripeBillingWebhookService.java` | 将 `requirePlanBySubscription` 的唯一 Price 查表换成注册表反查 SKU，再取原权益定义；更新当前/目标 Price 比较、Schedule 目标和延迟事件处理 |
| 新价发布执行入口与 Schedule 共用处理 | 输出/保存逐合同清单，合并 future phases，重试/并发校验，回读确认安排与实际生效 |
| `agent-infra/.../billing/UpgradeChargeCalculator.java` 及调用方 | 保留收费算法，传入正确区版本价格与实际净支付；核对整数计算不溢出 |
| `RechargeOrderEntity.bizContext` 的写入与读取 | 合并 pricing 快照，支持旧 JSON 无 pricing 字段；不更改旧记录的实际金额 |
| Study Pass 的 `pricedProduct`、购买、续费与对账路径 | 同时选择 Price ID/金额/币种，复用 Pass 自身 stripePriceId/renewalCurrency 快照，按其实际合同解析，兼容旧 Pass |
| 旧 payment config/credit pack 调用方 | 显式保留 legacy 路由，本轮未要求旧独立额度包重定价 |
| `agent-start/.../application.yml` 与部署样例 | 新配置文件路径与开关，不提交真实密钥，不在启动时写 Stripe |

新增一个 Price registry/helper 即可，不拆无实际用途的服务层。新地区目录与 legacy 商品表并存：不能通过给 `subscription_plans` 复制几十行套餐来绕过唯一 Price 问题，否则会污染权益、页面目录和 Trial 目标。

### 6.1 API 与报价兼容

保持现有 `/v1/billing/config`、`/v1/billing/quote` 和购买/变更请求形状。继续使用 `expectedCurrency`、`expectedAmountCents`、`quoteVersion`，不接受客户端传 zone、Price ID 或倍率作为选价依据。

报价指纹升级内部版本，至少包含：用户、动作、SKU、区、目录版本、当前/目标 Price、币种、应付金额、Trial 转正 Price/金额、相关合同/生效时间。即使德国和意大利 Trial 都是 €1.99，也必须得到不同的报价指纹，因为续费金额不同。

复用 `PRICING_QUOTE_CHANGED`、`PRICING_QUOTE_REQUIRED`、`PRICING_CURRENCY_UNAVAILABLE` 等现有错误协议；区域绑定缺失作为该商品不可售并记录内部原因，不新增无前端消费者的业务码。旧 Session 仅在所属用户、商品、上下文、Price 和金额全部匹配时复用。新旧接口报价不得绕过后端重算。

Checkout/Subscription 创建、Schedule 每个 phase、inline price_data、Invoice item 均显式使用真实币种。人工升级的一次性 inline Price 不纳入永久目录反查，用原订单快照核验；升级后正式订阅目标 Price 必须来自注册表。

### 6.2 前端和后端金额显示

保留 `amountCents/priceCents` 字段名以兼容现有消费者，但文档及内部命名按“币种最小单位”理解。

- 前端已有 `src/lib/billing-money.ts` 支持 KRW，不要重复实现；检查所有购买确认、账单与弹层是否一致使用它。
- **后端现有 `BillingDomainServiceImpl.formatBillingMoney` 固定按两位缩放，必须修正 KRW**，包括试用 Checkout 文案。KRW 2490 应显示 ₩2,490，不能显示 ₩25。
- 本次首批只有 KRW 为零小数扣款币种，其余八种为两位；不为了第 2 组预先增加所有货币特殊规则。后续扩币种按 Stripe charge 规则增加，不能直接用 Java ISO 默认小数位代替 Stripe 规则。
- 公开未登录卡片、登录后会员价、Checkout 说明和账单必须区分新购价与现合同价。页面不自行按当前 IP 重新计算旧会员价格。
- 前端负责显示和确认，后端负责选价。新 Pro 较低售价不改变权益文案或排序规则。

## 7. 验收矩阵

全量检查配置，代表性执行支付生命周期。每条记录保存使用的 account/env、SKU、Price ID、currency、金额、Session/Subscription/Schedule/Invoice 和本地订单结果；不保存密钥。

| 编号 | 检查 | 通过标准 |
| --- | --- | --- |
| A01 | 11 地区、7 区、77 Price、110 本币金额 | 与附件完全匹配；DE/AT 共用，IT 独立；新 Pro 列正确 |
| A02 | TWD/IDR/KRW 金额单位 | TWD 59→5900；IDR 13900→1390000；KRW 2490→2490；前后端/Checkout 一致 |
| A03 | DE/IT 同币种不同价 | Basic 13.99/11.99；Trial 同为1.99但 Pro 转正20.99/18.99；错区报价被拒绝 |
| A04 | 9 种本币首次支付 | 每种至少一个实际沙盒 Checkout 支付；不能只模拟 webhook 或看 Price 能保存 |
| A05 | DE、IT Trial 与 KRW Trial | 首笔、7天转正及随后正常月续费按本区新 Pro Price；Schedule phases 正确；首次取消/转正后取消可用 |
| A06 | 旅行/发布新版本/关闭新购开关 | 原区币种不变；本期旧价，下期按已安排目标换价；关闭新购不自动撤销已写入 Schedule |
| A07 | 旧 USD/BRL/KRW、巴西新价及范围外地区 | 归属明确且存在同币种目标的存量下期切价；归属不明列待核对；巴西新购 Trial 5.99 后转 Pro 60.99/月；其他非首批地区不变 |
| A08 | 升降级与额外包 | 本期使用当前版本，预约下期目标与新价合并；Plus→新 Pro 月付按原 monthly_full 逻辑；年付抵扣、到期降级、额外包权益各发一次 |
| A09 | 重试/乱序/并发 | 首付重复回调、Schedule失败重试、旧付款晚到、变更中更新配置均不重发权益、不取错目标、不回退新合同版本 |
| A10 | 缺 Price/币种/错误环境/未登记 ID | 预检失败或商品不可售；不回退 USD，不猜 SKU，不提前确认成功 |
| A11 | Quote/Session 一致性 | 当前金额相同但转正 Price 不同也拒绝旧报价；Session 不能跨区复用；取消销售后旧已完成支付仍按快照处理 |
| A12 | Study Pass/旧独立额度包/余额 | Pass 新购按地区、续费按自身周期切换已安排新价；首付及第二次月扣款都成功；旧 Pass 本期不改价，旧独立额度包路由不变；Pass与会员不同币种不跨币种抵扣；覆盖、取消和并发处理保留 |
| A13 | 韩国已启用支付方式 | 分别验收首付、授权、续费与失败恢复；可用性不从截图推断 |
| A14 | 最低金额、优惠、税与零额 | 按目标账户和实际Invoice构成核验，合法零额不误判；不自行发明补扣/加价规则 |
| A15 | 来源认证/Portal/缓存 | 伪造国家不能改价；生产override禁用；Portal不绕过选价；跨用户缓存不串价 |
| A16 | 回滚演练 | 区分停新购、停止待执行迁移、撤销未生效 phase；已生效价格不追溯回滚，历史映射保留，不能退回不识别新 Price 的旧二进制 |

后端按仓库规则运行 `mvn test`，重点扩展现有 BillingRegionalPricingPropertiesTest、BillingPricingServiceTest、BillingDomainServiceImplTest、StripeBillingWebhookServiceTest、IntroTrialBillingTest 和 StudyPassBillingTest。前端按本分支 `docs/verification-strategy.md`，运行相关金额/报价 runtime 测试及标准交付检查。

真实时间推进仅在目标账户/对象支持的沙盒方式下执行；若 Test Clock 无法覆盖该 Checkout 链路，记录实测等待，不以本地 mock 替代真实试用转正验收。巴西分支原 `tasks.md` 第 4.4、5.2–5.5 未完成项目继续作为基础验收，不能因“已有分支”而跳过。

## 8. 配置、实现、发布顺序

1. 后端读取本方案、现有 design/test-plan 和子工程规则，核对基线及真实可售 SKU。第一阶段只读盘点 Product、现有 Price、legacy alias 和账号设置。
2. 配置负责人按 JSON 金额创建沙盒 Price，回填绑定并执行预检；这一步须有针对沙盒的配置执行授权，不能把本文当作已完成配置。
3. 后端实现注册表、合同解析、报价/Checkout/Schedule/回调接入；前端修正必要展示；两端消费同一金额语义。使用 fixture 可先完成不依赖真实 Price ID 的代码与测试。
4. 在沙盒执行上述验收；部署人员完成巴西分支遗留的可信来源链路验证。
5. 生产配置单独生成并核对账户、真实价格、税行为、支付方式和 legacy alias。先部署兼容代码与完整历史注册表，保持新地区开关关闭。
6. 按国家名单逐步启用首批 11 地区；观察首付成功、未知Price、报价不一致、已付Trial无Schedule、续费失败和权益重复等既有日志/告警。
7. 存量迁移先生成 dry-run 清单，再在实际执行获授权后按合同安排并回读。失败/待核对条目单独列出；不得把销售启用等同于迁移完成。
8. 出问题可以停止新购或尚未执行的迁移；已经写入的未来 phase 若需撤销须单独核对并处理，不能覆盖用户安排。已生效周期不追溯改价，不删除历史映射，不退回不支持新 Price 的旧代码。

金额配置、Stripe 写入、生产启用和代码实现是不同动作。本次文档交付不代表 Stripe 或生产已配置，不自动授权后续 AI 修改真实账户、提交推送或部署。

77 条 Price 可以由独立配置脚本批量创建，降低人工录入错误；脚本先输出 dry-run 清单，按账户/版本/区/SKU 做幂等与已有对象核对，写入后重新读取金额和周期，并导出实际 ID。不要让业务应用在启动时建价，不用“发现同名就复用”代替逐字段核验。没有写入授权时仅生成 dry-run。

## 9. 给后端 AI 的任务说明

可将以下内容连同本文件及 JSON 交给后端开发者，再由开发者授权其 AI 实施：

> 请在前后端 `feat/billing-brazil-pricing` 基线上实现本方案 A。先读取仓库 AGENTS、现有 `add-brazil-regional-pricing` 设计、本文件和价格 JSON，检查当前分支及未提交改动。新增七区 11 地区定价，首批 Pro 使用“新 Pro”，适用存量订阅当前周期不变、下个付费周期切新版本，区和币种不变；非首批销售沿用原规则。优先复用 BillingPricingService、报价确认、Checkout、Schedule 与 webhook 幂等，不复制地区 Product 或权益 SKU。通过不可变 Price 注册表反查合同区版本，订单 biz_context 合并快照；覆盖所有付款、变更、转正及延迟回调路径，不只改首购。Study Pass 新购纳入倍率价格，低于最低扣款时在固定配置中上调，启用前验证；已购 Pass 按自身周期安排换价，不继承会员区价。旧独立额度包保留旧路由。巴西新购切到新表价格，归属明确且适用的旧 BRL 合同下周期切价。修复后端韩元文案缩放，保留原升级收费算法，新增 Plus 月付升级至更便宜 Pro 的回归。先用 fixture 和沙盒完成规定测试，逐项更新 tasks；不得将模拟测试当作真实支付验收。新增服务端新价发布入口及 Schedule 合并、幂等、并发检查，按 subscription-price-rollout spec 实现。没有对应执行授权时，不写 Stripe、改生产、执行真实存量迁移、commit 或 push。交付列明实际代码改动、配置清单、测试证据与未完成项。

## 10. 本次证据与官方参考

本次完成：只读审查上述两端提交、生成配置草案和技术规格、文档/结构校验。未执行应用测试、沙盒支付、Stripe写入或生产部署。

- [现有巴西价格与旧映射](price-matrix.md)、[原验收计划](test-plan.md)、[实施任务](tasks.md)。
- [Stripe 多币种 Price、显式 currency 与 Schedule phases](https://docs.stripe.com/products-prices/manage-prices#multi-currency-prices)：作为 Price 组织及币种参数依据。
- [Stripe 托管 Checkout 固定本币价格](https://docs.stripe.com/payments/checkout/localize-prices/manual-currency-prices?payment-ui=stripe-hosted)：作为 Checkout 币种选择和默认币种一致性依据。
- [Stripe 金额单位与币种规则](https://docs.stripe.com/currencies)：TWD 扣款两位与 payout 特例须区分；账户可用性另验。

原十地区方案估算 6–9 工程人日；本次增加 Pass 的独立合同定价及低额验证、巴西新旧价回归，粗估再增加 1–2 人日，总计约 7–11 人日，不是承诺工期；不包含真实账期等待或新发现的基础支付缺陷。本次确认新 Pro 后，月付与权益等级不单调的场景已经纳入必测范围。

新增下周期存量换价需要单独估算发布执行器、Schedule 冲突合并与回归成本；上面的 7–11 人日是新增本规则前的估算，不作为当前完整范围工期承诺。
