> 2026-10-18 r8：用户已采纳三组审阅表全部44项尾数建议（6地区、40条Price），当前金额以完整价格表和approved-price-rounding.md为准；Pro年价仍为月价×12，Study Pass金额及冻结参考不变。文档已先推送，随后沙盒新建40条替代Price并回读全部187条；不改业务代码。

## ADDED Requirements

2026-10-18 r7（Pass全量固定金额已齐）：第一、第二、第三组全部新价，仅本次需求确认时线上已购Pro当前已付周期保旧价、下个付费周期切新价。下文早期BR-only场景用于legacy功能关闭／旧事件回归；扩展启用后以“三组定价区选择新金额”和线上Pro换价规格为准。

### Requirement: 可信地区决定独立新购资格

系统 SHALL 仅使用通过自有代理链路认证的国家信号和服务端配置决定独立新购币种。方案A SHALL 对第一组10地区、第二组53地区、第三组51地区全部使用地区新目录，覆盖会员、Trial、extra packs与Study Pass；Pro只用新Pro价格，不按组别排除国家。只有本次需求确认时线上已购买Pro的当前已付周期按subscription-price-rollout保留旧金额，下个付费周期切新价；其他商品和新收费动作不得继承此例外。三组范围外、unknown及总开关关闭时保留既有销售路由供兼容；范围内尚未完成配置须明确列待办，不能以旧价销售声称完成新需求。旧独立额度SKU缺少对应新金额时先核对，不套用会员addon金额或新增保旧价资格。系统 MUST NOT 根据客户端currency/region/Price ID、界面语言、时区、邮箱location标记或未认证转发头授予地区优惠资格。可信国家信号表示网络地区，不证明居住地。

#### Scenario: 巴西用户首次购买
- **GIVEN** 用户通过可信链路访问，国家为 BR，且不存在需恢复的有效同类订阅
- **WHEN** 用户请求目录、购买报价并确认新购
- **THEN** 系统 SHALL 提供 BRL 价格并使用 brl 创建支付
- **AND** 系统 SHALL 保留既有登录、Trial 资格及商品可售校验

#### Scenario: 伪造国家或无法识别地区
- **GIVEN** 国家头来自未认证来源，或国家为空、XX、T1、格式错误
- **WHEN** 用户请求新购报价并在请求体填写 region=BR 或 currency=brl
- **THEN** 系统 SHALL 忽略其地区/选币主张并按 unknown 的 USD 规则处理
- **AND** 确认值与真实报价不一致时 SHALL NOT 创建 Stripe 支付对象

### Requirement: 有效订阅动作保持真实币种

系统 SHALL 以实际 Stripe Subscription.currency 决定续费、Trial 转正、升降级和依附会员的 extra pack 币种。有效期内已取消、欠款恢复和未结束流程 SHALL 按既有领域规则保持原币种。独立 Study Pass SHALL 保持自己的续费币种；会员新购不因仅有 Pass 而继承 Pass 币种。

#### Scenario: 会员旅行期间付款
- **GIVEN** 用户具有有效 BRL 会员但当前访问国家为 US
- **WHEN** 用户续费、升级、降级或购买依附会员的 extra pack
- **THEN** 相关报价、抵扣、扣款和 Schedule phases SHALL 继续使用 brl

#### Scenario: 历史币种及结束后重购
- **GIVEN** 用户的实际有效订阅币种为 usd 或 krw，当前访问国家为 BR
- **WHEN** 用户执行该订阅的后续动作
- **THEN** 系统 SHALL 保持实际币种
- **WHEN** 该订阅真正终止且新的独立购买符合资格
- **THEN** 系统 SHALL 重新使用可信地区决定新购币种，不修改历史订单

#### Scenario: 只有 Pass 的用户首次购买会员
- **GIVEN** 用户只有有效 USD Study Pass，当前可信访问国家为 BR
- **WHEN** 用户购买新的会员
- **THEN** 新会员 SHALL 使用 brl
- **AND** Pass 的会员覆盖、取消和并发退款行为 SHALL 遵循既有规则，不增加跨币种抵扣

### Requirement: 全部可售付费 SKU 支持明确币种价格

系统 SHALL 对确认可售的会员、Trial、extra packs、Study Pass 和旧 credit packs 使用对应 Stripe Price 的默认金额或所选 currency_options 金额。新 BRL 销售启用前 MUST 完成全部可售商品的 BRL 价格矩阵；停售/免费 SKU 不得因此上架。系统 SHALL 校验环境、SKU 映射、数量、周期和转换目标，不把默认 USD 金额视为 BRL 金额。

#### Scenario: Pro Trial 巴西报价
- **GIVEN** 未启用方案 A 的 legacy 月付 Trial Price 的 BRL 金额为 490，转换目标 Pro 月付 BRL 金额为 39990
- **WHEN** 符合 BRL 新购资格的用户请求该报价
- **THEN** 首笔 SHALL 为 BRL 490，覆盖 7 天
- **AND** 后续报价 SHALL 为 BRL 39990/月，使用已配置的正式 Pro 月付 Price

#### Scenario: 缺失 BRL 或转换目标
- **GIVEN** 某可售商品或其必须的转正目标缺少 BRL option
- **WHEN** BRL 用户访问该商品或直接请求 Checkout
- **THEN** 系统 SHALL 禁止该币种购买并返回稳定的不可用结果
- **AND** 系统 SHALL NOT 自动换算金额或静默回退 USD 创建支付

### Requirement: 目录及报价防止缓存混用

地区化 catalog 和用户报价 SHALL 使用共享 resolver 返回实际 amount/currency。系统 SHALL 防止 CDN、SSR、浏览器共享状态或服务端缓存跨地区/用户混用；带地区或身份的响应 MUST 使用 private, no-store。服务端 Price 配置缓存 SHALL 隔离环境、Price 和币种，并在配置变化时正确失效。

#### Scenario: 巴西和美国请求同一页面
- **WHEN** BR 与 US 用户先后访问 Pricing 或同一 SKU 弹层
- **THEN** 每人 SHALL 得到自己的适用报价
- **AND** 系统 SHALL NOT 返回另一个请求的 BRL/USD 目录快照

#### Scenario: 登录后存在原币种会员
- **GIVEN** 匿名阶段因 BR 网络显示 BRL，登录后读取到有效 USD 会员
- **WHEN** 页面展示该会员的变更和 extra pack 选项
- **THEN** 系统 SHALL 重新读取账号相关报价并使用 USD
- **AND** 匿名 BRL 目录 SHALL NOT 直接用于该会员收费动作

### Requirement: 购买动作重新校验报价确认值

系统 SHALL 为登录用户提供只接受商品类型及 SKU 的购买报价。购买/收费变更启用新地区能力后 MUST 接收 expectedCurrency、expectedAmountCents、quoteVersion，并从当前事实重新计算；这些字段只用于确认对比，不是价格或资格的授权源。报价/资格变化 SHALL 返回稳定业务码及适用新报价，且 SHALL NOT 先收费再请求确认。

#### Scenario: 显示价格后地区或配置变化
- **GIVEN** 用户确认的报价为 BRL，但创建支付时新购资格或金额已变化
- **WHEN** 用户提交购买确认值
- **THEN** 系统 SHALL 返回 PRICING_QUOTE_CHANGED 或 PRICING_NOT_ELIGIBLE
- **AND** 系统 SHALL NOT 创建收费 Checkout 或执行收费订阅变更
- **AND** 前端 SHALL 获取当前报价并等待用户再次确认

#### Scenario: 篡改报价或缺少确认
- **WHEN** 用户伪造低金额/币种/指纹，或旧客户端在地区能力启用后省略确认字段
- **THEN** 系统 SHALL 按真实报价拒绝不一致或缺失的确认
- **AND** 伪造指纹 SHALL NOT 改变后端选币和商品价格

### Requirement: Checkout 明确传递模式和币种

所有适用的支付路径 SHALL 显式选择 Session.currency；订阅、付费 Trial 和订阅型 Pass SHALL 使用 subscription 模式，一次性 packs SHALL 使用 payment 模式。inline price_data、手工 Invoice item 和差价支付 MUST 使用相同的实际币种。服务端 SHALL 使用自身映射的 Price 与合法数量。

#### Scenario: 普通邮箱的巴西用户
- **GIVEN** 可信地区为 BR，用户邮箱没有 location 标记
- **WHEN** 用户购买 Pro Trial
- **THEN** Session SHALL 为 mode=subscription、currency=brl、首笔 490
- **AND** 关联 Subscription SHALL 为 brl，邮箱是否带测试标记 SHALL 不改变后端资格

### Requirement: Customer 邮箱来自已验证身份

系统 SHALL 从已验证 Clerk 会话或 Backend API 的 primary_email_address_id 获取主邮箱，验证 Customer 归属并补齐空 email。系统 MUST NOT 用未验证请求邮箱认定地区或用户，也 MUST NOT 同时传互斥的 customer 与 customer_email。邮箱不可获得时 SHALL 返回可恢复结果，不静默创建空邮箱 Customer。

#### Scenario: JWT 无 email 且 Customer email 为空
- **GIVEN** 用户已认证，JWT 无 email，已关联 Customer 的 email 为空
- **WHEN** 用户创建 Checkout
- **THEN** 系统 SHALL 按 Clerk 主邮箱 ID 查找并补齐 Customer email
- **AND** SHALL NOT 使用邮箱数组第一项或客户端任意值代替已验证主邮箱

### Requirement: 未支付 Checkout 复用受报价约束

系统 SHALL 仅复用属于当前用户、当前商品/动作、Price、数量、金额、币种、资格和有效期均匹配的 open Checkout。发生新报价冲突时 SHALL 对该购买流程的旧 Session 执行可恢复的失效/重建，不清理其他用户对象；已完成旧付款 SHALL 按原订单快照处理。

#### Scenario: 已有 USD Trial 未支付链接
- **GIVEN** 用户之前有 open USD Trial Session，现在取得有效 BRL 新购报价
- **WHEN** 用户重新确认 BRL 购买
- **THEN** 系统 SHALL NOT 返回旧 USD checkoutUrl
- **AND** 新 Session SHALL 使用 BRL；旧完成事件若存在 SHALL 不被改记为 BRL

### Requirement: Trial 及变更 Schedule 逐阶段保持币种

系统 SHALL 对新建或更新 Schedule 的每个当前/后续 phase 显式设置实际 Subscription.currency，并验证目标 Price 支持该币种。BRL Pro Trial SHALL 在首段结束后进入已确认正式 Pro 月付 Price（legacy 沙盒基线 BRL 39990/月，方案 A 为 BRL 6099/月）；日期、权益及取消行为 SHALL 遵循既有流程。发布新版本时 SHALL 按 subscription-price-rollout 规格合并转正目标，不延长 Trial 或重复首笔费用。

#### Scenario: 七天后正式月付
- **GIVEN** BRL 490 Pro Trial 已付，首段与正式月付 phase 的 currency 都为 brl
- **WHEN** 未取消的订阅到达 7 天转换点
- **THEN** Subscription SHALL 切换到正式 Pro 月付 Price
- **AND** 在尚未安排新版本的 legacy 基线场景中，下一笔正常全价 Invoice SHALL 为 BRL 39990，后续按自然月计费
- **AND** 系统 SHALL NOT 再按 weekly Trial Price 收取下一周费用

#### Scenario: 降级或其他周期调整
- **GIVEN** 用户现有实际订阅币种为 brl 或历史 krw
- **WHEN** 创建或更新包含当前段/目标段的 Schedule
- **THEN** 每个 phase SHALL 明确使用该币种
- **AND** 不支持该币种的目标 SHALL 被拒绝，不回退 usd

### Requirement: Schedule 失败可重试且可对账

系统 SHALL 将已付款与转正 Schedule 配置成功分别核实；Schedule 创建/更新失败 SHALL 保持可恢复处理并被既有运维查询/告警发现，不记录虚假的转正成功。重试 MUST 幂等，不创建重复 Schedule、不重复授予权益，并为不同币种/参数版本使用兼容的 idempotency key。

#### Scenario: 从订阅创建 Schedule 后更新 phases 失败
- **WHEN** 首笔已付，但 phases 更新被 Stripe 拒绝
- **THEN** 处理 SHALL 留下可定位的失败事实并进入既有重试机制
- **AND** 对账 SHALL 识别该 Trial 未完成正确转正配置
- **WHEN** 重试成功或执行经授权的处置
- **THEN** 系统 SHALL 核验单个正确 Schedule，不重复扣款/权益

### Requirement: 升级差价与订单付款事实使用同币种

系统 SHALL 从所选币种计算套餐金额、合法抵扣和本次差价，不直接使用商品表 USD 基准计算 BRL 费用。订单及 Pass SHALL 使用既有金额/币种字段保存对应快照；签名 webhook SHALL 校验归属、币种、SKU 和应付构成并记录真实付款。历史订单 MUST 保持历史事实，不能因当前地区重算。

#### Scenario: BRL 会员升级并购买 extra pack
- **WHEN** BRL 会员请求升级报价或 extra pack 支付
- **THEN** 套餐金额、同币种抵扣、净额和订单 SHALL 全部为 BRL
- **AND** 合法完成事件 SHALL 发放一次对应权益，不因旧 USD 校验规则误拒绝

#### Scenario: 实际付款与基准全价不同
- **GIVEN** 存在既有合法折扣、税、余额抵扣或零额升级
- **WHEN** 处理同币种已验签的支付事件
- **THEN** 系统 SHALL 按合法构成核对实际付款，不用基准全价相等替代完整校验
- **AND** 不同币种金额 SHALL NOT 直接抵扣

### Requirement: 本地地区模拟不能进入生产

系统 SHALL 提供仅 local/test 可用的服务端地区覆盖配置，以任意合法 ISO 国家或 unknown 模拟完整规则；生产配置非空覆盖值 MUST 启动失败。测试 SHALL 区分 mock、真实沙盒支付和时间推进证据，真实沙盒 SHALL 使用测试密钥、有效登录及签名 webhook。

#### Scenario: 本地模拟巴西
- **GIVEN** local 环境配置 BILLING_REGION_TEST_OVERRIDE=BR
- **WHEN** 正常登录用户使用普通邮箱购买
- **THEN** 报价和沙盒 Checkout SHALL 使用 BRL，无需 VPN

#### Scenario: 生产尝试启用覆盖
- **GIVEN** 生产环境存在非空 BILLING_REGION_TEST_OVERRIDE
- **WHEN** 应用启动
- **THEN** 应用 MUST 拒绝启动并报告配置错误，不能使用模拟地区销售

### Requirement: 回滚保留已售 BRL 合同

新购地区能力 SHALL 默认关闭且可通过独立开关停用；停用 SHALL NOT 撤回已售 BRL 订阅的币种继承、Schedule 或实际账单处理。实现 MUST 复用现有 schema，不新增地区字段、报价表或币种专用 SKU。

#### Scenario: 启用后关闭新购功能
- **GIVEN** 已有 BRL Trial/正式会员/Pass 在有效期内
- **WHEN** 停用新的地区销售开关
- **THEN** 已有合同 SHALL 继续按原币种转正及续费，历史账单保持可读
- **AND** 本次 SHALL 无新增数据库结构迁移需要回滚

### Requirement: 通用地区定价配置

系统 SHALL 保留通用总开关BILLING_REGIONAL_PRICING_ENABLED（默认false）及legacy国家币种配置BILLING_REGIONAL_PRICING_COUNTRY_CURRENCIES（默认BR=brl）的兼容语义。legacy空规则移除映射、整体替换默认规则、规范化大小写及拒绝非法或重复配置的行为不变。方案A SHALL 通过独立定价区目录覆盖第一、第二、第三组全部114地区；legacy的BR-only回归场景不限定扩展范围。开关和国家名单记录实际发布状态，配置未齐不构成第二组继续旧价的规则。实际Stripe写入按对应执行授权处理，旧BILLING_BRAZIL_PRICING_ENABLED不再生效，报价确认及合同币种权威保持。

#### Scenario: legacy默认规则与unknown回归
- **GIVEN** 未启用方案A扩展目录，通用开关开启且legacy规则为BR=brl
- **WHEN** 可信国家分别为 BR、KR、US、SG 或 unknown
- **THEN** 仅 BR SHALL 使用 brl，其他 SHALL 使用 usd

#### Scenario: legacy配置替换与扩展回归
- **GIVEN** 未启用方案A扩展目录，隔离本地测试配置KR=krw，并使用mock Stripe价格
- **WHEN** 系统解析可信 KR 与 BR 的独立新购报价
- **THEN** KR SHALL 使用 krw，BR SHALL 使用 usd，不保留默认 BR 映射
- **AND** 已有 BRL/USD 订阅 SHALL 保持原币种
- **WHEN** 规则改为空或总开关关闭
- **THEN** 独立新购 SHALL 使用 usd

#### Scenario: 错误配置拒绝启动
- **GIVEN** 国家或币种非法、规则缺少等号或包含大小写规范化后重复的国家
- **WHEN** 应用启动，包括总开关关闭时
- **THEN** 应用 MUST 拒绝启动并报告规则配置错误，不能静默忽略错价配置

#### Scenario: 通用环境配置传入容器
- **GIVEN** Server_Config 加载 docker-compose.regional-pricing.yml，未指定真实密钥
- **WHEN** 渲染 Compose 配置
- **THEN** Spring 容器 SHALL 收到通用开关和完整国家规则，默认 false 与 BR=brl
- **AND** 显式空规则 SHALL 保持为空，不重新插入默认 BR

### Requirement: 三组定价区选择新金额

系统 SHALL 对第一组10地区、第二组53地区、第三组51地区全部选择对应新价格，Pro SHALL 只使用新Pro月／年列，Trial转正 SHALL 使用新Pro月价。分组不是销售资格条件，巴西无单独准入特例。固定金额以regional-price-matrix.md及regional-expansion-prices.json核对；共114地区、17区、103个区币种组合。系统 MUST NOT 运行时按倍率／汇率计算，缺配置 MUST NOT 回退旧销售价或技术USD默认金额。

#### Scenario: 第二组美国印度使用新金额
- **WHEN** 美国或印度用户新购Pro
- **THEN** 系统 SHALL 分别报价USD29.99/月、INR569/月，年付为USD359.88、INR6828
- **AND** SHALL NOT 因属于第二组沿用旧Pro价格或排除本次范围

#### Scenario: 同币种地区金额不同
- **WHEN** DE、AT、IT分别请求Basic月价，US、EC、ZW分别请求USD Basic月价
- **THEN** DE/AT SHALL 为EUR13.99，IT为EUR11.99；US/EC/ZW SHALL 分别为USD19.99/6.99/3.99
- **AND** USD实际销售区Price默认金额 SHALL 与该区USD新金额一致，不能覆盖成统一美国价

### Requirement: 线上Pro当前周期保护不扩展到其他商品

系统 SHALL 按subscription-price-rollout固化线上已购Pro资格并保留其当前已付事实，下个付费周期采用同区同币种新金额。新购Pro、Basic、Plus、Trial、Pass、额外包及新变更目标 SHALL 使用新表；合同当前旧Price仅解释已有付款和权益，不作为其他商品新报价依据。

#### Scenario: 旧Pro合同购买新额外包
- **GIVEN** 线上已购巴西Pro当前已付BRL399.90，下期安排BRL60.90
- **WHEN** 用户购买会员作业额外包
- **THEN** 本次 SHALL 为新表BRL19.99，本期Pro原已付金额保持，不沿用旧版本额外包价

#### Scenario: BasicPlus与Trial不享受Pro保护
- **WHEN** 已有Basic、Plus、Trial或Pass产生上线后的新报价／后续应付
- **THEN** 系统 SHALL 使用新金额，Trial转正用新Pro月价，不授予当期保旧价资格
- **AND** 已支付历史 SHALL 不追溯改写、补扣或退款

### Requirement: 合同归属与历史快照可恢复

系统 SHALL 维护Price ID到区／版本／SKU的不可变注册表，订单biz_context合并pricing快照。已有合同保持区和实际币种，旅行不换区；实际当前Price、已安排目标与新收费上下文分别处理。只信任登记映射与可靠购买证据，未知ID不猜SKU，旧事件不回退新合同状态。

#### Scenario: 旅行后按原合同区使用新报价
- **GIVEN** 德国区EUR合同用户访问意大利
- **WHEN** 报价或续费
- **THEN** 系统 SHALL 保留德国区EUR，使用该区适用新目标；只有符合线上Pro资格的当前已付周期保留原金额

#### Scenario: 历史区无法确认
- **WHEN** 旧EUR合同无法确认所属德国或意大利
- **THEN** 系统 SHALL 列待处理并记录原因，不以当前IP猜区，不把待处理当成永久保旧价资格

### Requirement: 地区报价覆盖实际收费目标

报价指纹 SHALL 绑定用户、动作、SKU、区、版本、当前／目标Price、币种金额与Trial转正Price金额。Checkout／Schedule变更前重验；新旧Session仅在完整上下文匹配时复用，报价变化等待用户再次确认。webhook SHALL 按实际付款快照识别SKU并幂等发权益。

#### Scenario: 相同试用价但转正目标不同
- **GIVEN** 原报价为德国Trial EUR1.99，确认时独立新购资格变为意大利
- **WHEN** 提交旧quoteVersion
- **THEN** 系统 SHALL 拒绝旧报价并刷新，因转正价分别为EUR20.99与EUR18.99

### Requirement: 新Pro保留既有套餐变更算法

系统 SHALL 按权益等级及既有周期规则判断升降级，价格目标用新表，抵扣依据真实净付款。Pro当前周期保护不阻止用户主动变更，不给新变更保旧价；已预约降级仍保留用户目标和日期。

#### Scenario: 新Pro低于Plus月价
- **GIVEN** 德国Plus月付用户请求升级Pro
- **WHEN** 按原monthly_full算法计算
- **THEN** 系统 SHALL 报价新Pro EUR20.99，按既有规则切换权益和周期，不按负差价退EUR7

### Requirement: 全部地区配置待办不能变成旧价特例

三组114地区 SHALL 全部纳入新价交付。金额未齐、Stripe币种支持未验证、Price未创建等 SHALL 显式列为配置／验证待办，不能排除国家或继续以旧价声称需求完成。11个SKU目标187条Price／1133个金额；现有1030个会员／Trial／额外包金额和103个Pass建议金额已补齐主单位值，但不等于全部配置通过；加纳／突尼斯USD替代价已明确确认。目标沙盒不支持GHS/TND，用户已确认两地使用Z025/USD新金额，详见currency-replacements.md。旧独立额度SKU未有新金额时列待核对，不套用会员addon金额、不新增保旧价资格。

#### Scenario: 第二组Pass金额缺失
- **WHEN** 某地区Study Pass尚无确认固定金额或最低扣款证据
- **THEN** 系统 SHALL 保持该地区在需求范围，缺项标待补，不使用旧金额或把整个地区改成范围外

### Requirement: StudyPass独立合同使用新应付金额

Study Pass SHALL 按自身区、币种、自然月合同使用确认的新金额，不继承Pro保旧价资格。固定月价按既有USD0.99基价、倍率与固定折算参考补齐，全部103组合以JSON及study-pass-pricing.md为准。建议低额下限 SHALL 取USD0.60固定参考缓冲、HKD4按固定参考折算及已公布同币种结算最低金额中的最大值，金额 SHALL 不低于原始倍率价和该下限，按记录步长向上取整。实际最低扣款仍取决于目标账户结算设置；未实测不得声称已通过，低于实测下限时上线配置阶段再上调，不得付款时动态加价。首付、优惠后金额和次月扣款均需目标账户验证。

#### Scenario: 会员与Pass分开处理
- **GIVEN** 用户有Study Pass或以后购买会员
- **WHEN** 处理Pass续费／覆盖取消／重复事件
- **THEN** 系统 SHALL 按Pass自身合同与实际付款处理，后续目标用新金额，不沿用Pro当期保护


#### Scenario: 第二组倍率低额价上调并全部补齐
- **WHEN** 计算美国、印度、厄瓜多尔、津巴布韦的Study Pass月价
- **THEN** 固定建议金额 SHALL 分别为USD0.99、INR57、USD0.60、USD0.60，且印度及两个低倍率USD区 SHALL 记录低额上调依据
- **AND** 全部114地区的103个区币种主单位金额 SHALL 非空，原63地区的61组合保持；计算与最低参考通过不代表实际账户扣款已通过

### Requirement: 发布状态与历史履约独立

地区开关 SHALL 记录实际配置发布状态，不能表达第二组继续旧价的业务规则。关停期间停止新销售不删除历史注册表；已安排Schedule继续按实际目标处理。停止销售、停止待执行迁移、撤销未来阶段分别执行，不影响历史快照。

#### Scenario: 关停不撤销Pro下期新价
- **GIVEN** 已为线上Pro安排下期新Price
- **WHEN** 关闭新购开关
- **THEN** 该安排 SHALL 保持；撤销必须另行核对并保留用户取消／降级等设置

本轮第三组补充：51地区全部纳入，当前共114地区、17区、86币种、103个区币种组合、1133个金额。原63地区金额保持；五地USD替代经用户明确确认，见currency-replacements.md。沙盒当前绑定以sandbox-configuration.md为准，业务代码、真实付款及生产订阅均未修改。
