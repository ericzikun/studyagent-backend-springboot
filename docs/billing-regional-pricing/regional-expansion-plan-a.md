> 2026-10-18 r8：用户已采纳三组审阅表全部44项尾数建议（6地区、40条Price），当前金额以完整价格表和approved-price-rounding.md为准；Pro年价仍为月价×12，Study Pass金额及冻结参考不变。文档已先推送，随后沙盒新建40条替代Price并回读全部187条；不改业务代码。

# 地区差异化定价：方案 A 后端交接文档

版本：2026-10-18 r7（三组新价与Study Pass全量补价，仅线上已购Pro当期保旧价）。本次用户已授权并完成三组187条当前绑定Price配置及回读（本轮新建33条）；[配置记录](sandbox-configuration.md)及[环境绑定](regional-expansion-prices.sandbox.json)可复核。扩展业务代码、真实支付、订阅迁移及生产部署尚未执行。

本版覆盖r3中“仅第一组加巴西”“其余第二组不开放”以及“所有存量订阅本期保旧价”的范围和价格规则。旧设计、历史沙盒价格及实施记录仅供追溯，不覆盖本文。跨工程权威来源仍为Verla根`openspec/changes/add-brazil-regional-pricing/`。

## 1. 已确认范围与唯一例外

第一组10地区、第二组53地区、第三组51地区全部使用新金额，共114地区；Pro统一使用“新 Pro”月付／年付价格。唯一当期保旧价例外：本次需求确认时线上已购买Pro的用户，当前已付周期保留旧金额，下个付费周期切新价。

- 第一组：香港、台湾、印尼、土耳其、韩国、德国、意大利、瑞典、泰国、奥地利，共10地区。
- 第三组：原表51地区全部纳入，包括伊拉克、约旦、西班牙等；五地USD替代经用户确认，见currency-replacements.md。
- 第二组：原表53地区全部纳入，包括美国、印度、巴西、日本等。巴西保留第二组身份，不是单独准入特例。完整名单及每项金额见[三组新价矩阵](regional-price-matrix.md)。
- 表内Basic／Plus月年付、7天付费Trial、三个会员额外包使用对应新金额；Pro月年付与Trial转正只用“新Pro”列。权益、SKU、排序和计费周期不变，不建立`pro_new`。
- Study Pass仍纳入三组，按既有USD0.99基价、倍率及固定折算参考补价、低额上调政策准备固定售价。原63地区的61组合保持，本轮新增42组合，全部103组合固定建议月价已齐；真实最低扣款验证仍须完成，不形成继续卖旧价或排除国家的特例。
- 只有本次需求确认时**线上已经购买Pro**且仍处于当前已付周期的合同可保留该期旧金额。资格须使用生产合同与付款记录固化，记录确认时间、合同、当前周期和依据；不能仅凭用户曾购买过Pro、Price创建时间、Trial的Pro权益或沙盒Pro判断。
- Basic、Plus、付费Trial、Study Pass和额外包没有本期保旧价资格。上线后新报价和后续应付使用新金额，已支付账单仍保留真实历史事实，不追溯补扣或退款；不能以“已有合同”为由继续选旧销售目录。
- 旧独立额度包与会员extra packs不是同一SKU；本表未给对应旧包新金额，不擅自套用addon价格。仍有销售入口的逐项核对金额和映射，缺项列配置待办，不能写成额外的保旧价例外。
- 不主动迁移币种，不改变税／优惠／退款政策，不新增钱包集成或国家支付方式选择器，不做实时汇率和运营后台。支付渠道的验证工作不能缩小三组全部采用新金额的业务范围。

## 2. 基线与已有能力

| 工程 | 工作分支 | 审查基线 |
| --- | --- | --- |
| Spring Boot | `feat/billing-brazil-pricing` | `0adf475bba576afad3df8e268c46d0a753d46b05` |
| 前端V2 | `feat/billing-brazil-pricing` | `dbe97d575af06731749cbda0b2420666a87b5a19` |

复用本任务既有worktree，不回退提交。已有可信地区解析、报价确认、Checkout显式币种、Schedule及webhook幂等；主要缺口是一个SKU主要只有一个销售Price、回调按唯一Price查SKU、仅currency无法区分同币种不同地区。

## 3. 国家、Price与金额配置

### 3.1 三组完整路由

| 定价区 | 参考倍率 | 国家／地区代码 | 销售币种 |
| --- | ---: | --- | --- |
| Z020 | 0.2 | IN、PK、EG、NG、BD、ET、MW、ZW、PS、RW、TZ、UG、VE、ZM、AF、CM、LA、LR、MM、NP、UZ | INR、PKR、EGP、NGN、BDT、ETB、MWK、USD、ILS、RWF、TZS、UGX、ZMW、AFN、XAF、LAK、LRD、MMK、NPR、UZS |
| Z025 | 0.25 | VN、ID、KE、TN、GH、UA、BO、LK、KH、CI | VND、IDR、KES、USD、UAH、BOB、LKR、KHR、XOF |
| Z030 | 0.3 | PH、DZ、MA、NA、AZ、TH、HN、MN | PHP、DZD、MAD、NAD、AZN、THB、HNL、MNT |
| Z035 | 0.35 | MY、CO、ZA、EC、BW、GT、IQ、JO、SV、GA、GE、LB、MD | MYR、COP、ZAR、USD、BWP、GTQ、XAF、GEL、LBP、MDL |
| Z040 | 0.4 | BR、AR、TR、JM、PE、BZ、AL、KZ、ME、PG、RS | BRL、ARS、TRY、JMD、PEN、BZD、ALL、KZT、EUR、PGK、RSD |
| Z045 | 0.45 | MX、CN、RO、LC、CL、MV | MXN、CNY、RON、XCD、CLP、MVR |
| Z050 | 0.5 | PA、CR、HR、HU、OM、PL、SC、TT | USD、CRC、EUR、HUF、PLN、SCR、TTD |
| Z055 | 0.55 | GY、GR、BB、BN、LV、PR、SK | GYD、EUR、BBD、BND、USD |
| Z060 | 0.6 | SI、SA、UY | EUR、SAR、UYU |
| Z065 | 0.65 | TW、KR、JP、ES、KW | TWD、KRW、JPY、EUR、USD |
| Z070 | 0.7 | IT | EUR |
| Z075 | 0.75 | AE、FR | AED、EUR |
| Z080 | 0.8 | HK、DE、MO、AT、BE、FI | HKD、EUR、MOP |
| Z085 | 0.85 | GB、NZ、NL、BS、SE | GBP、NZD、EUR、BSD、SEK |
| Z090 | 0.9 | CA、SG | CAD、SGD |
| Z095 | 0.95 | AU、LU | AUD、EUR |
| Z100 | 1 | US、IE、BM、NO | USD、EUR、BMD、NOK |

共114地区、17区、86种销售币种、103个“区＋币种”组合；德国和奥地利共享Z080/EUR。区标识只用于固定金额路由，不是运行时计算公式。第三组51地区已获用户明确要求加入；原表范围外国家不随本次扩大。

### 3.2 Product与Price组织

沿用每个SKU原Product，按“区＋SKU＋版本”创建新Price，同区的多币种放入currency_options。保存所有旧Price绑定处理历史订单与回调，不覆盖旧金额。

| SKU | 周期／类型 | 金额来源 |
| --- | --- | --- |
| `pro_trial_to_monthly` | week×1，仅首段7天 | 试用7天；转正目标为新Pro月付 |
| `basic_monthly/basic_yearly` | month/year×1 | Basic月／年 |
| `plus_monthly/plus_yearly` | month/year×1 | Plus月／年 |
| `pro_monthly/pro_yearly` | month/year×1 | **新Pro月／年** |
| `addon_assignment_3` | one_time | 作业包 |
| `addon_detection_5` | one_time | 检测包 |
| `addon_humanizer_3` | one_time | Humanizer包 |
| `study_pass_subscription_30d` | month×1，自然月 | 按既有补价政策生成并确认的固定月价 |

目标187条候选Price、1133个区币种金额。表内10个会员／Trial／额外包SKU的1030个金额已录入，Pass的103组合固定建议金额已齐（原61组合保持，本轮新增42），共1133个主单位金额；数值齐全不等于配置／支付验收完成。通用JSON保持账户无关草案；沙盒187条当前Price/Product/account绑定已写入独立环境JSON并回读验证，前版22条Price保留。销售开关关闭，应用尚不会自动读取新增目录。

### 3.3 USD实际销售与技术默认金额

新增Price保留USD默认币种的组织方案，但默认金额不能全区统一套美国价。对于USD实际销售区，必须使用该区新表金额：

| 国家／区 | Basic月 | 新Pro月 | 新Pro年 |
| --- | ---: | ---: | ---: |
| 美国／Z100 | 19.99 | 29.99 | 359.88 |
| 厄瓜多尔／Z035 | 6.99 | 10.99 | 131.88 |
| 津巴布韦／Z020 | 3.99 | 5.99 | 71.88 |

这些区`expectedDefaultAmountMinor`必须等于其`currencyAmounts.usd`，不能同时给同一Price配置两个USD金额。无USD销售路由的区可使用附件`usdDefaultAmounts`作为技术默认参考；该默认值不授予USD销售资格。后端显式选择实际币种，缺目标时拒绝购买，不回退技术USD金额。新目录不覆盖旧Price或旧订单；美国等第二组国家必须切到新地区Price。

### 3.4 金额单位和待补配置

所有表内主单位使用十进制精确转换，币种扣款单位依据目标Stripe支持核验。已整理资料中KRW／JPY／VND／RWF按零小数，TWD／IDR按两位；逐币种核验，不把零小数显示统一除100，也不将payout规则用于charge。不使用未获支持的币种推测扣款单位；GHTN改用已确认的USD金额及两位最小单位。

目标沙盒HK支持清单不含GHS/TND。用户已于当前会话明确确认，加纳、突尼斯改用此前补充表Z025/USD固定新金额；完整金额与授权依据见[币种替代记录](currency-replacements.md)。原GHS/TND主单位值保留在原始用户表作历史参考，当前JSON不再销售这两币种，金额单位均已明确。Study Pass原63地区金额保持，新增补价仍可复算；GHTN合并为USD0.60后，共103组合覆盖全部114地区，均待真实最低扣款及次月续费验证。

Pass的USD0.99是推导基价，USD0.60等值是既有缓冲，不是Stripe统一最低金额。新增折算参考取该币种首个Basic月价÷(19.99×倍率)，EUR沿用德国，USD取1:1，泰国沿用33.5；全部为固定定价参考。取max(原始价、USD0.60参考、HKD4参考折算、已公布同币种结算最低金额)，按固定步长向上取整。新币种步长按取整前金额分段：<1用0.01，<10用0.1，<100用1，<1000用10，否则100；零小数币种至少1，原步长保持。美国USD0.99，厄瓜多尔和津巴布韦均由倍率价上调至USD0.60，印度INR57，日本JPY99。完整计算、门槛依据和114地区金额见[Pass测算](study-pass-pricing.md)。

[Stripe官方最低扣款规则](https://docs.stripe.com/currencies#minimum-and-maximum-charge-amounts)以实际银行结算币种为准，跨币种须满足折算后门槛。现有香港／默认HKD摘要未证明全部结算账户设置；本轮没有实际扣款，不能把参考下限上调称为目标账户实测已通过。启用前核对结算设置、首付、优惠后非零金额及次月续费，不足时预先上调固定配置，运行时不临时加价。

### 3.5 环境与预检

沙盒／生产分别回填account、livemode、Product、Price、固定单价、币种、周期、tax behavior和Trial转正目标；不复制沙盒ID至生产。核对既有优惠、结算及支付方式兼容性，不把Price可保存当作首付／续费成功证据。沿用可信代理和签名webhook；Portal不得绕过后端选价。缺配置条目明确报告待办，技术发布按验证结果受控推进，不改变114地区全部采用新价的验收目标。

## 4. 配置模型与合同上下文

沿用拟新增`billing.regional-pricing.zone-pricing.config-path`（`BILLING_ZONE_PRICING_CONFIG_PATH`）加载附件草案，受原总开关约束。`countryRoutes`覆盖114地区，`enabledCountries`为实际发布状态；初始为空表示未执行配置，不表示只批准11地区或把其余第二组留在旧价。

`activeVersions[zone]`提供新收费版本；`catalogs[version].zones[zone].prices[sku]`包含Price与币种金额。不可变Price注册表反查`区／版本／SKU`；订单`biz_context.pricing`合并快照，保留已有抵扣和Pass字段。复用现有结构，不为版本新增表，不靠复制套餐权益行绕过Price映射。

实际Subscription的区、币种、当前Price与已付事实保持可恢复；新销售／新收费目标从新表解析，不能将合同当前旧Price误当成其他商品的报价上下文。metadata只辅助核对，不能仅凭`plan_code=pro`发权益或授予保旧价。

历史alias、legacy目标与生产Pro资格名单必须只读盘点后补齐，空值禁止声称可发布。legacy用于历史解释和恢复，不是三组新收费的旧价回退。合同归属不明或缺同币种新目标列待处理；不得按当前IP、语言、仅凭币种猜测或自动换币。

## 5. 收费与线上Pro当前周期保护

### 5.1 统一选价

```text
resolve(user, sku, action):
  校验登录、商品可售、试用资格、并发与已有权益规则
  独立新购：可信国家 → 对应区/币种 → 新版本 → SKU Price
  已有合同的新动作：原区/实际币种 → 新版本 → 目标SKU Price
  当前已付线上Pro合同展示/履约：使用资格名单中实际旧Price与已付事实
  该Pro续费：合并Schedule，于原付费周期结束进入新表目标
  缺映射或币种：明确待处理/不可售，不静默选择旧价或技术USD
```

符合资格的线上Pro买会员额外包也使用新表金额；立即升级／变更的目标价使用新表，不将原Pro当前周期保旧价扩散到新交易。既有年付抵扣读取实际净付款，收费算法不变。Basic／Plus／Trial／Pass无保旧价资格，后续应付目标统一新价；不因此凭空生成已付周期差额账单。

### 5.2 唯一保旧价资格与执行名单

在实际迁移前先核对本次需求确认时的生产Pro合同与付款记录，固化资格快照（账户／模式、合同、已付周期起止、原Price、金额币种、确认时间、证据）。沙盒Pro、未付订单、仅试用、仅曾经购买过Pro但合同已结束、Basic／Plus／Pass均不得混入。新合同从首笔起用新Pro价，不因为晚于确认但早于执行仍使用旧代码而自动取得保护资格。

目标保持原合同定价区与实际币种：月付在原月续费点，年付在原年度续费点；月度额度刷新不触发年付换价。当前周期不改价、不改结束时间、不补扣或退差价。例：生产已购且归属核实的BRL Pro，本期R$399.90，下期R$60.90；沙盒的相同金额只可用于测试，不能作为生产资格证据。

### 5.3 Trial、降级和新动作

Trial保持原7天首段及已支付事实，转正必须使用本区新Pro月价，不享受旧Pro当期价。既有Schedule中的降级、取消、优惠、税、数量、币种和日期须合并保留。例如线上Pro已预约下期降Basic，下期使用新Basic价格，不能换回Pro。Pro的当前周期保护不阻止用户主动变更，操作按既有业务规则执行，目标金额用新表。

新Pro月价低于Plus仍按权益等级判升级。德国Plus月付升级新Pro，保留既有monthly_full算法，本次收€20.99并切换权益和周期，不按负差价退€7。年付抵扣继续使用真实净付款，不用新目录反算历史付款。

### 5.4 服务端发布与失败恢复

实现受控dry-run/apply，不新增运营后台。分别输出三组新销售配置状态、其他合同新应付目标更新结果，以及符合资格线上Pro的逐合同下期安排。更改目录不代表合同已安排；无Schedule从实际订阅创建，有Schedule只合并原计划。执行记录持久保留，可回读核对。

写入前重读合同、计划、发票和周期；保留取消，暂停／欠款冲突／已出账／归属不明逐项输出原因，不强行改账或恢复续费。失败是执行阻塞，不授予新的保旧价资格。线上Pro原下期窗口已错过时须列异常并受控处理，不能静默继续旧价并记成功。

幂等上下文包含发布批次、合同、源快照、目标Price、生效时间；超时先读实际Schedule。用户升级改变周期、降级或新版本发布后重新合并，过时任务不能覆盖更新目标。分别确认安排成功、阶段生效、发票支付，付款失败沿用欠款权益规则；迟到回调不回退合同状态。

停止新购、停止未执行任务、撤销未生效换价为不同操作。关闭开关不自动撤销已安排阶段；撤销仅恢复本批次价格字段，保留用户安排。历史映射持续保留，不退回不能识别新Price的旧代码。

## 6. API、前端和后端改动

复用`/v1/billing/config`、`/v1/billing/quote`及购买／变更接口，继续使用`expectedCurrency/expectedAmountCents/quoteVersion`。客户端不得指定区、倍率或Price作为收费依据。报价指纹包含用户、动作、SKU、区、版本、当前／目标Price、币种金额和Trial转正目标；同为€1.99的德国／意大利Trial也不能复用报价。

| 位置 | 职责 |
| --- | --- |
| BillingRegionalPricingProperties／BillingPricingService | 114地区目录、Price正反查、固定新价和环境预检 |
| BillingDomainServiceImpl | 同时替换Price、金额、币种；覆盖新购、变更、额外包、Trial、Pass |
| StripeBillingWebhookService | 多Price识别SKU、真实订单／发票快照、权益幂等及乱序保护 |
| 发布入口与Schedule | 核验线上Pro资格、合并下期目标、持久执行结果、重试并发保护 |
| Study Pass路径 | 自身合同区币种、新应付金额、自然月续费；不继承Pro例外 |
| 前端与Checkout文案 | 新报价确认；线上Pro实际本期与已安排下期分别披露；正确币种单位 |

旧Session只在用户、动作、Price、金额币种、转正目标均匹配时复用。沿用现有报价变化错误协议，前端刷新后等待再次确认，不自动按新价支付。账号不能仅凭新目录声称下期安排已成功。保留字段名兼容，`amountCents`按币种最小单位理解；修复后端`formatBillingMoney`固定两位导致KRW错误的问题，并覆盖第二组零小数币种。

## 7. 验收与待办

| 编号 | 验收重点 | 通过标准 |
| --- | --- | --- |
| A01 | 三组全量范围 | 10＋53＋51＝114地区，17区、103区币种组合；无第二组排除清单 |
| A02 | 固定新价与新Pro列 | 1030个已给定会员／Trial／额外包金额逐项一致（包含已批准GHTN/USD替代），原Pro列不作销售目标 |
| A03 | 同币种不同价 | DE/AT与IT正确；US/EC/ZW的USD默认值和销售金额各自正确 |
| A04 | 线上Pro资格 | 仅需求确认时生产已购Pro；沙盒、Trial、Basic/Plus/Pass及后来新购均不误入 |
| A05 | 月／年Pro换价 | 本期旧金额和日期不变，下次月／年付新Pro价，年付不按额度刷新换价 |
| A06 | 非Pro与新动作 | Basic/Plus/Trial/Pass后续应付及Pro用户额外包用新价，不继承保旧价 |
| A07 | Trial／降级／取消 | 原7天首段、新Pro转正；降级新目标保留；取消不被恢复 |
| A08 | 升级与实际付款 | 更便宜Pro仍按既有升级算法；抵扣用真实净付款；权益只发一次 |
| A09 | 旅行与历史归属 | 保区保币种；缺依据列异常，不以IP或技术USD猜测 |
| A10 | 重试／并发／乱序 | 无重复计划、重复收费／权益，不覆盖用户变更或更新版本 |
| A11 | 账号披露 | 线上Pro实际本期、已安排下期金额与日期和Stripe一致 |
| A12 | Pass全量固定价及真实扣款 | 103组合公式／步长／上调可复算，原63地区金额保持、本轮新增42组合已齐；实际首付、优惠后最低金额和次月扣款验证 |
| A13 | 全币种／渠道 | 每币种金额单位与实际沙盒支付；印度等渠道验收待办不能视作排除国家；GHTN使用已批准USD替代 |
| A14 | 来源／缓存／Portal | 无地区伪造、跨用户串价或绕过后端收费 |
| A15 | 执行状态及回滚 | 区分配置、安排、生效、付款；停止销售不撤销已安排目标 |
| A16 | 基础遗留验收 | 原可信来源链路及真实付款回归仍需证据，不以文档校验替代 |

Spring按仓库策略执行`mvn test`，前端按本分支verification strategy执行；真实Stripe支付和账期推进另留证据。本次通过文档／JSON一致性、OpenSpec与此前176条沙盒Price回读校验；本轮三组187条回读亦通过；应用测试和真实扣款未执行。

## 8. 配置与实施顺序

1. 读取根规则与本方案，保留现有工作；只读核对全114地区商品和价格、历史alias及本次需求确认时生产Pro资格快照。
2. 核对已补齐的103组合Pass金额与上调记录，完成账户最低扣款、币种支持与必要固定替代价确认；除用户明确批准的GH／TN／IQ／JO／VE／KW／OM的USD替代外，其他原表金额不重算。实际Stripe写入另按授权执行。
3. 实现统一新价解析与回调；Pro当前周期保护名单和下期Schedule处理单独验证。三组代码与配置验收目标完整覆盖，不留“第二批沿用旧价”的业务规则。
4. 用fixture与目标沙盒验证，不依赖真实Price ID的代码可先完成；真实首付、Trial转正、续费及金额单位必须实际核验。
5. 生产账户、金额、Price映射单独核对，获实际执行授权后发布新价并安排Pro下期换价，逐项回读与报告；失败不标完成。
6. 需要停用／撤销时按第5.4节执行，保留历史快照和用户安排。文档修正不授权写Stripe、部署、commit或push。

原r3的77条Price、110金额及7–11人日粗估已不适用于当前完整范围。需按114地区、配置缺口、线上Pro资格与真实支付验收重新估算，本文不承诺工期。

本轮第三组补充：51地区全部纳入，当前共114地区、17区、86币种、103个区币种组合、1133个金额。原63地区金额保持；五地USD替代经用户明确确认，见currency-replacements.md。沙盒当前绑定以sandbox-configuration.md为准，业务代码、真实付款及生产订阅均未修改。
