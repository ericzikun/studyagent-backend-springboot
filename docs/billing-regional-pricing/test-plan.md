# 本地与 Stripe 沙盒验收

> 2026-10-18 r4需求口径：第一组10地区、第二组53地区、第三组51地区全部使用新金额，共114地区；Pro统一使用“新 Pro”月付／年付价格。唯一当期保旧价例外：本次需求确认时线上已购买Pro的用户，当前已付周期保留旧金额，下个付费周期切新价。 以下巴西基础阶段的金额、范围与执行记录仅用于历史追溯；当前需求以[简版方案](regional-expansion-brief.md)、[三组新价格表](regional-price-matrix.md)及[换价规格](specs/subscription-price-rollout/spec.md)为准，不代表新需求已实现或已上线。

此文件保留完整验收计划；本次代码与本地模拟结果见 implementation.md。用户已批准代码和本地测试，其他 SKU 金额、真实 Stripe 写入及生产操作仍未执行。

## 一、本地验证规则及界面

1. 复用 Spring/前端现有本地启动入口和专用测试用户。实现后设置服务端 `BILLING_REGION_TEST_OVERRIDE=BR`，正常登录；该变量已实现，仅在 local/test profile 下允许。
2. 请求 catalog、登录报价及各购买入口，核对实际返回的金额/币种；切换为 `US` 和 `unknown` 并重启对应配置，验证新购 USD。
3. mock 只验证路由、UI、快照和资格逻辑，不作为 Stripe 多币种或 Schedule 验收证据。不得为了地区测试关闭身份认证。
4. 直接向 API 伪造 `CF-IPCountry: BR`、内部国家头、`X-Forwarded-For`、currency/region/Price ID 等参数，验证未认证请求不能获得 BRL。再通过正确的可信代理模拟链路验证 BRL。
5. 使用非 BR 网络的浏览器语言 pt-BR、时区 São Paulo 和 `+location_BR` 邮箱，验证它们不改变后端地区规则；切换服务端 BR override 后普通邮箱也应获得 BRL。
6. 检查生产 profile 与非空 override 的组合拒绝启动；在 unknown/default 条件下不能通过客户端参数修改选择。

## 通用配置本地回归

- 默认开关关闭；开启后仅 BR→BRL，US/KR/SG/unknown→USD。
- 在隔离单元测试中以 KR=krw 验证替换默认 BR 规则、多个规则及大小写规范化；不向实际部署样例加入 KR，也不配置 Stripe 金额。
- 空规则移除 BR；重复国家、非法国家/币种和错误格式拒绝启动。
- 从实际 application.yml 绑定新环境变量，验证旧巴西开关不能启用通用功能。
- local/test 可模拟任意合法国家；生产/混合 profile 仍拒绝地区覆盖。
- 无密钥渲染 Compose 默认、显式 BR 和显式空规则，确认变量实际进入 Spring 容器；仅配置文件解析，不启动服务。
- 规则变更/关闭后已售 BRL/KRW 合同仍保币种，确认期间价格变化继续要求重新确认。

## 二、真实沙盒 Checkout 首笔

1. 确认是 LASTMILE LIMITED 测试环境，使用测试密钥及专用用户/Customer。填齐并核验价格矩阵，禁止使用生产付款对象。
2. 关闭现有 `BILLING_CHECKOUT_MOCK_ENABLED` 和 `PAYMENT_CHECKOUT_MOCK_ENABLED`；通过 `start-stripe-webhook.sh` 或现有 Stripe CLI listen 转发到本地 `/v1/webhook/stripe`，使用本次 listen 对应的签名 secret。保持 `STRIPE_ALLOW_UNSIGNED_WEBHOOKS=false`；不把真实 secret 写入文档或输出。
3. BR override + 普通有效邮箱：获取报价、确认 Pro Trial，断言 Session 的 mode、line item、quantity、Customer email、currency 和 amount_total，截图应为 R$ 4,90；Checkout 披露 7 天后 R$ 399,90/月。
4. 使用 Stripe 测试卡 `4242 4242 4242 4242` 完成付款，检查 Subscription.currency=brl、首张 Invoice、订单实际金额/币种、唯一权益发放和两段 Schedule。单凭 success URL 或截图不得判定通过。
5. 对 `customer_email` 自动本地化另建隔离测试：省略显式 currency 并传 `+location_BR` 测试邮箱，验证 Stripe 机制；这一辅助测试不替代本需求的明确选币/后端资格验收。只使用规范的大写 BR 标记，不把小写标记识别作为上线条件。

## 三、推进七天并验证正式扣款

首选对**专用沙盒**的已付 Subscription 使用 Stripe Dashboard 的 Run simulation/Test Clock，在账号/API 当前允许的条件下推进到 7 天转换点，再推进一个正式月付周期。须等待 clock 及 webhook 处理完成，并针对该 Customer/Subscription 查询，避免 list-all 漏掉 simulation 对象。

验证：首段的实际起止相差 7 天；每个 phase.currency=brl；转换后为正式 Pro 月付 Price；第一张正常全价续费 Invoice.currency=brl、amount_paid=39990；再下一张按自然月续费。测试无新增税/折扣/余额，避免与全价断言混用。

Run simulation 的资格受 Customer 复杂度、订阅数量及账号 Automations 等条件影响。只新建本任务隔离对象或使用允许的模拟流程；不删除账号 Automations、不修改共享 Customer。不能对 Checkout 完整链路加速时，保留实际 7 天端到端测试；独立 Clock 创建等价 Schedule 可验证阶段切换，但不能冒充该 Checkout 的完整转正验收。删除 simulation 会删除其 Customer/Subscription，故不要绑定共享用户做破坏性清理。

依据：[Stripe 订阅模拟](https://docs.stripe.com/billing/testing/test-clocks/simulate-subscriptions)、[Test Clock 使用限制](https://docs.stripe.com/billing/testing/test-clocks/api-advanced-usage)。

## 四、必须覆盖的回归和异常

| 编号 | 场景 | 预期 |
| --- | --- | --- |
| R01 | US、unknown 新购 Trial | USD 首笔及正式续费不受影响 |
| R02 | BRL 会员改为 US 网络；USD 会员改为 BR 网络 | 续费、升降级、会员 extra packs 保持原币种 |
| R03 | 历史 KRW 会员 | 后续动作保持 KRW；不移除已有 option |
| R04 | BRL 会员升级，存在同币种抵扣 | 净额/price_data/订单为 BRL，算法权益及周期不变 |
| R05 | BRL 会员降级、年付相关变更 | 当前段及目标段 currency 都为 BRL |
| R06 | 曾有 USD 账单但旧订阅已结束，在 BR 新购 | 新合同按 BRL，旧账单可读；验证 Customer 复用的实际兼容性 |
| R07 | JWT 无 email；Customer email 为空；Clerk email 第一项非主邮箱 | 通过 primary_email_address_id 补齐真实主邮箱；无邮箱返回可恢复错误 |
| R08 | 已有 open USD Session，再取得 BRL 报价 | 不复用 USD URL；仅处理本用户本次流程的旧 Session |
| R09 | 地区/商品价格/会员状态在展示与确认之间变化 | 返回稳定业务码；刷新报价并再次确认，首次请求无收费副作用 |
| R10 | 缺 BRL option、转换目标、周期错误、Price 环境不符 | 禁止相应购买；不回退 USD，不生成错误 Schedule |
| R11 | 篡改报价金额、currency、quoteVersion 或另一用户标识 | 后端重算/归属校验拒绝；无跨用户访问或低价支付 |
| R12 | BR 与 US 页面/CDN/SSR 请求交替；登录前后账号状态变化 | 不共享地区/账号报价，API/浏览器缓存策略正确 |
| R13 | extra pack 真实付款；重复/乱序 webhook | 根据真实同币种订单快照核验，权益只发一次 |
| R14 | Trial Schedule 创建失败或创建后 phases 更新失败 | 失败可查/可重试；无重复 Schedule，无虚假转换成功 |
| R15 | 同一个已付 Trial 事件重放 | 幂等；币种/参数版本不造成 Stripe key 冲突 |
| R16 | 巴西 Study Pass 新购、续费、结束后重购 | 所选 BRL；自然月；历史 30 天/一次性记录不转换 |
| R17 | 只有 USD Pass，在 BR 新购会员；Pass 与会员并发付款 | 会员独立 BRL；覆盖/取消/合法退款沿用既有规则，无跨币种抵扣 |
| R18 | 合法折扣/余额/零额升级既有路径 | 核对实际应付构成，避免硬套全价；历史账单各自币种正确 |
| R19 | 生产 override、API 直连、绕过自有 Cloudflare zone、Portal 销售旁路 | 拒绝模拟/未认证地区；无能绕过后端的低价销售入口 |
| R20 | 关闭新购地区开关 | 新入口恢复旧策略，已有 BRL 合同/转正/历史账单继续正常 |
| R21 | 取消 Trial 后跨过第 7 天 | 遵循既有取消规则，不产生未经同意的正式续费 |
| R22 | 所有价格矩阵可售 SKU 逐项报价/支付 | 展示、Price、mode、金额/币种及权益一致；不重新出售停售 Trial |

## 五、验收记录

真实验收记录需包含环境、用户/Customer、Session、Subscription、Schedule、相关 Invoice ID，预期/实际金额币种、7 天阶段日期、付款及权益结果。日志不含 API secret、签名 secret 或完整 token。mock 通过、沙盒首付通过、Schedule 配置通过、实际转换扣款通过分别记录。

本地实施和测试已执行，详见 implementation.md；真实沙盒首付、完整 7 天转正及部署来源链路仍待验收。

## 方案A第一、第二、第三组新价及线上Pro验收

本节为当前需求验收，上文BRL490／39990等属于巴西基础阶段回归数据。以三组新价矩阵、价格JSON及subscription-price-rollout为准；文档与沙盒Price配置不代表以下支付／续费测试已通过；Price回读证据见[sandbox-configuration](sandbox-configuration.md)。

- 全部114地区逐SKU核对新表金额，Pro只用新Pro列；第二组美国USD29.99/月、印度INR569/月、巴西BRL60.99/月均纳入。
- 验证德国／意大利EUR及美国／厄瓜多尔／津巴布韦USD不同价格，实际USD销售区默认金额不误用技术美国价。
- 固化本次需求确认时生产已购Pro资格名单、时间和证据；排除沙盒、Trial、未付款、Basic／Plus／Pass及后来新购。
- 线上已购Pro本期金额／日期不变，下个付费周期切新价；月付按月，年付按年，不按额度刷新。
- Basic／Plus／Trial／Pass后续应付与新报价使用新金额；Pro新额外包也按新表，不因Pro保护沿用旧价。保留历史付款，不生成追溯差额账单。
- Trial转正新Pro、预约降级新目标、取消、欠款、已出账冲突及旅行场景按实际计划验证；缺归属不能猜测或自动换币。
- 核对Pass全部103组合固定价、86个币种锚点及每项公式／低额参考／取整；原10组合不变，r5新增52组合已补；GHTN合并USD后当前103组合无空主单位。USD使用1:1参考，EUR复用德国；印度INR57、日本JPY99、厄瓜多尔／津巴布韦USD0.60。
- 对低额上调断言最终值不低于原始价及参考下限、取整前后差额小于一步长；GHTN的USD替代已确认；实际账户结算、首付与次月扣款仍待验证，不以数值齐全替代真实支付证据。
- 校验报价确认、写入超时重试、用户并发操作、新旧任务乱序、真实付款和唯一权益、账号本期／下期披露。
- 分别验证停新购、停止任务和撤销未来阶段，保留历史映射与用户安排；缺独立额度SKU金额列待核对，不套用addon。

本轮第三组补充：51地区全部纳入，当前共114地区、17区、86币种、103个区币种组合、1133个金额。原63地区金额保持；五地USD替代经用户明确确认，见currency-replacements.md。沙盒当前绑定以sandbox-configuration.md为准，业务代码、真实付款及生产订阅均未修改。
