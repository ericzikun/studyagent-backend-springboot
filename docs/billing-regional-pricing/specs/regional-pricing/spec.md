## ADDED Requirements

### Requirement: 可信地区决定独立新购资格

系统 SHALL 仅使用通过自有代理链路认证的国家信号和服务端配置决定独立新购币种。legacy 国家→币种规则 SHALL 保留 BR→brl；方案 A SHALL 对启用名单中的 HK、TW、ID、KR、TR、DE、IT、SE、TH、AT、BR 的会员、extra packs 及 Study Pass 叠加地区目录路由。非首批或未启用国家 SHALL 走 legacy 路由；unknown 或地区总开关关闭时新购 SHALL 使用既有 USD 销售策略。旧 credit packs SHALL 保留 legacy 规则；Study Pass 新购纳入新增路由；适用存量合同本期保持原价，下个付费周期按 subscription-price-rollout 规格切换新版本，区和币种不变。系统 MUST NOT 根据客户端传入的 currency/region/Price ID、界面语言、时区、Clerk 邮箱 location 标记或未认证转发头授予地区优惠资格。首版国家信号表示当前网络地区，不证明居住地。

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

系统 SHALL 使用通用总开关 BILLING_REGIONAL_PRICING_ENABLED，默认 false；国家→币种规则 SHALL 由 BILLING_REGIONAL_PRICING_COUNTRY_CURRENCIES 提供，以逗号分隔 COUNTRY=currency，默认仅 BR=brl。空规则 SHALL 移除全部新购地区映射；规则 SHALL 整体替换而非与默认 BR 规则合并。系统 SHALL 规范化国家大写、币种小写，并在启动时拒绝非法 ISO 国家、币种、格式或重复国家。未来经业务批准并配置对应 SKU 价格后 SHALL 能通过规则增加国家，不增加国家专用开关；方案 A SHALL 通过独立定价区目录叠加 11 地区路由，legacy 配置语义保持；实际 Stripe 配置按对应执行授权处理。BILLING_BRAZIL_PRICING_ENABLED SHALL 不再作为启用入口，前端报价契约和已有订阅币种权威 SHALL 保持不变。

#### Scenario: 当前默认规则与 unknown
- **GIVEN** 通用开关开启且规则为 BR=brl
- **WHEN** 可信国家分别为 BR、KR、US、SG 或 unknown
- **THEN** 仅 BR SHALL 使用 brl，其他 SHALL 使用 usd

#### Scenario: 配置替换与扩展
- **GIVEN** 隔离本地测试配置 KR=krw，并使用 mock Stripe 价格
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

### Requirement: 首批定价区选择固定版本 Price

系统 SHALL 对方案 A 的 11 地区使用后端国家→区/币种→版本→SKU Price 映射，覆盖七个定价区的 11 个在售 SKU。首批新合同 Pro SHALL 使用“新 Pro”列，权益和 SKU 不变；具体金额 SHALL 以 regional-expansion-prices.json 的固定金额为配置核对基准。Stripe 读取值与核对基准不一致时 MUST 禁止相关销售。系统 MUST NOT 运行时计算倍率/汇率，也 MUST NOT 因区 Price 缺币种而静默使用 USD。仅新建地区 Price 的统一 USD 默认金额不得覆盖 legacy 商品表金额。

#### Scenario: 欧元同币种不同价
- **GIVEN** DE、AT 和 IT 已启用方案 A
- **WHEN** 用户分别新购 Basic 月付
- **THEN** DE/AT SHALL 共用 Z080 的 EUR 13.99 Price，IT SHALL 使用 Z070 的 EUR 11.99 Price
- **AND** 虽然两个地区 Trial 均为 EUR 1.99，其转正 Pro 月价 SHALL 分别为 EUR 20.99 和 EUR 18.99

#### Scenario: 未开放地区与范围外商品
- **WHEN** 非首批地区新购，或用户购买旧 credit packs
- **THEN** 系统 SHALL 沿用原 legacy 路由、SKU 及价格，不因附加目录含 USD 或其他本币而自动切换
- **AND** 巴西已在售旧 Price、当前已付周期及历史订单 SHALL NOT 被覆盖；适用存量合同 SHALL 按下周期换价规格安排新 Price；巴西新购 SHALL 使用新建 Z040/BRL Price，Trial 5.99 后转 Pro 60.99/月

### Requirement: 合同保留定价区且按生效阶段解析版本

系统 SHALL 维护不可变 Price ID→区/版本/SKU 注册表，并在已有订单 biz_context 合并 pricing 快照。合同 SHALL 保留原区和实际 Subscription.currency；当前周期动作使用实际生效版本，下一付费周期按 subscription-price-rollout 规格切换已安排版本。Trial 转正、升级、降级、会员 extra packs 及重试 SHALL 区分当前与未来目标，不提前覆盖当前价格。系统 SHALL 支持旧合同无新增 metadata 的 legacy alias 解析；未登记 ID 不得凭国家、金额或 metadata 猜测 SKU。历史权益、退款、事件时间排序及幂等保护 SHALL 保持。

#### Scenario: 旅行与目录更新
- **GIVEN** 用户已购买 Z080/v1/EUR，后访问 IT，且新购销售版本变为 v2
- **WHEN** 用户续费、变更套餐或购买会员额外包
- **THEN** 系统 SHALL 保留 Z080/EUR；本期使用 v1，下期按已成功安排的目标使用 v2，不因旅行切到 Z070

#### Scenario: 旧合同和新 Price 回调
- **WHEN** 收到已登记的旧 USD/BRL/KRW 或新地区 Price 的签名支付事件
- **THEN** 系统 SHALL 按该事件合同及订单快照识别 SKU、金额、币种和区版本，按既有幂等规则处理
- **AND** SHALL NOT 因商品表只有一个当前 Price ID 而丢失新 Price 的权益发放

### Requirement: 地区报价覆盖当前与未来收费目标

报价指纹 SHALL 绑定区、版本、当前/目标 Price、币种和金额，Trial SHALL 同时绑定转正 Price 和金额。创建 Checkout、修改订阅或 Schedule 前 MUST 从同一上下文重验。Schedule 创建失败 SHALL 保留恢复能力，重复事件 SHALL NOT 创建错误版本的转正目标。

#### Scenario: 相同试用金额但不同转正金额
- **GIVEN** 用户先取得 DE Trial EUR 1.99 报价，确认时仅符合 IT 新购资格
- **WHEN** 用户提交旧 quoteVersion
- **THEN** 系统 SHALL 返回报价变化，不因首付金额相同而接受旧报价

### Requirement: 新 Pro 保持既有套餐变更算法

系统 SHALL 按既有 plan/tier/interval 判断升降级，不用金额大小替代权益等级。新目录仅提供正确的当前/目标价格；月付升级、年付抵扣、零额和实际付款处理 SHALL 保持既有政策。同 SKU 存量换价 SHALL 通过受控发布流程在下一付费周期生效，不作为立即升级或降级，不产生本期差额。

#### Scenario: 更便宜的 Pro 月付仍是权益升级
- **GIVEN** 用户持有 Z080/v1 的 Plus 月付 EUR 27.99
- **WHEN** 升级到同版本 Pro 月付 EUR 20.99
- **THEN** 系统 SHALL 按既有 monthly_full 规则报价 EUR 20.99 并在付款后切换权益及周期
- **AND** SHALL NOT 将其按负差价退款或按售价判成权益降级

### Requirement: 地区启用与历史履约相互独立

地区新购开关和国家名单 SHALL 只决定新合同销售路由，不影响已建立合同的 Price 注册表解析和履约。只有所需 Price、币种、Product、环境和目标全部验证通过的国家才能启用。回滚 SHALL 保留历史映射及兼容代码。

#### Scenario: 关闭首批销售
- **GIVEN** 已存在首批地区合同
- **WHEN** 管理员从名单移除国家或关闭地区新购
- **THEN** 新购 SHALL 回到 legacy 销售路由，旧合同 SHALL 保持原区和币种，按当前生效 Price 及已安排 Schedule 完成续费、转正和故障恢复；关闭新购不撤销已安排换价
- **AND** 已完成旧 Checkout 的延迟支付事件 SHALL 仍按原快照处理


### Requirement: Study Pass 地区固定月价与最低扣款核验

Study Pass 新购 SHALL 在 11 地区按 USD 0.99 基价、地区倍率和文档固定折算参考生成本币月价；低于实际最低扣款时 SHALL 在上线配置阶段上调固定价格，不在付款时动态加价。USD 0.60 等值仅为建议配置缓冲参考，MUST NOT 当作 Stripe 实际统一门槛。启用前 SHALL 核验结算币种、支付方式和优惠后金额，并验证首付及次月扣款。Pass SHALL 保持自身区、币种和独立周期，并按下周期换价规格更新版本，不继承会员的定价上下文。

#### Scenario: 低倍率地区补价
- **GIVEN** 按倍率计算的本币金额低于目标账户实际最低扣款
- **WHEN** 准备启用该地区 Study Pass
- **THEN** 配置负责人 SHALL 上调固定本币 Price 并完成核验后再启用
- **AND** 未完成核验的建议金额 SHALL NOT 被标记为已验证可扣款，运行时 SHALL NOT 临时抬高已确认报价

#### Scenario: Pass 续费和会员分别定价
- **GIVEN** 用户已有一个 legacy 或 Z080/v1 的 Study Pass，之后旅行或购买其他适用地区会员
- **WHEN** 处理 Pass 续费、覆盖取消或重复事件
- **THEN** 系统 SHALL 按 Pass 实际 Price、Subscription.currency 和订单快照解析自身合同
- **AND** SHALL NOT 用当前 Study Pass 商品表 Price、当前国家或会员区价覆盖实际价格；发布新版本时 SHALL 按自身 Schedule 在下一付费周期生效
