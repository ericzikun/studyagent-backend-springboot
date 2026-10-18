> 2026-10-18 r7当前范围：第一10＋第二53＋第三51＝114地区、17区全部使用新金额；Pro使用新Pro列，仅确认时线上已购Pro当期保旧、下期切新价。当前价格／USD替代／沙盒完成状态以regional-price-matrix.md、currency-replacements.md、sandbox-configuration.md为准。下文早期阶段记录保留用于追溯。

## ADDED Requirements

### Requirement: Pricing 与账单消费适用地区报价

V2 Pricing、登录后的会员变更及账号账单 SHALL 消费后端适用的 currency 和 amount，而不硬编码 USD 或由前端判定 BRL 资格。新购目录 SHALL 区分访问地区，账号相关报价 SHALL 保持有效订阅币种。金额格式 SHALL 按币种显示，BRL 使用明确的 R$ 格式；语言选项 SHALL 不授予地区价格。公开 Trial 卡仍遵循既有展示策略，Checkout SHALL 披露实际首段和正式续费价格。

#### Scenario: 巴西用户查看 Pricing 和付款
- **GIVEN** 方案A后端返回巴西新Trial为brl599，后续新Pro月付为brl6099
- **WHEN** 用户查看 Pricing 并进入 Checkout
- **THEN** Pricing SHALL 展示 R$ 5,99 与 7 天期限
- **AND** Checkout SHALL 准确披露 7 天后 R$ 60,99/月，不包含硬编码 USD 说明
- **AND** 年付视图 SHALL 使用同一可售月付 Trial，不重新启用停售 yearly Trial

#### Scenario: 账单同时包含多种历史币种
- **WHEN** 用户查看实际 USD、BRL、KRW 历史账单或 Pass 当前续费信息
- **THEN** 每笔 SHALL 按各自后端付款/合同快照展示金额和币种
- **AND** SHALL NOT 按用户当前国家统一改成 BRL

### Requirement: Pricing 在报价变化后恢复确认状态

Pricing SHALL 在登录回跳后刷新账号资格和适用报价；购买/收费变更 SHALL 携带后端报价确认字段。对 PRICING_QUOTE_CHANGED、PRICING_QUOTE_REQUIRED 和资格/币种不可用结果，页面 SHALL 停止 loading、刷新适用价格并恢复正常确认状态，等待用户再次点击；SHALL NOT 自动改价支付或展示后端原始错误。其他错误继续遵循既有行为。

#### Scenario: 查看后登录或地区变化
- **GIVEN** 用户先看到 BRL 新购价格，但登录后已具有 USD 会员或地区证据已变化
- **WHEN** 用户触发购买
- **THEN** 页面 SHALL 展示新的适用价格并要求重新确认
- **AND** SHALL NOT 自动跳转到不同币种的 Checkout

### Requirement: 第一第二组金额按核验的扣款单位展示

前后端金额格式 SHALL 使用 Stripe 扣款最小单位；第一、第二组全币种逐项核验扣款单位；已整理资料中KRW/JPY/VND/RWF为零小数，TWD/IDR等按两位，TND待支持和扣款单位确认。报价接口 SHALL 保持现有字段兼容，页面不自行计算地区价格。Checkout 试用说明 SHALL 使用后端同一合同区版本的首付和转正金额。

#### Scenario: 韩元试用说明与台湾金额
- **WHEN** 后端返回 KRW amountCents=2490 或 TWD amountCents=5900
- **THEN** 页面与 Checkout 说明 SHALL 分别显示 2490 韩元和 59 新台币
- **AND** SHALL NOT 将 KRW 固定除以 100，也不得把 TWD payout 特例用于扣款展示

#### Scenario: 新 Pro 与既有会员展示
- **GIVEN** 新德国用户与本次需求确认时线上已购Pro用户分别查看账号适用价格
- **WHEN** 页面展示购买和后续续费金额
- **THEN** 新合同 SHALL 使用 Z080 新 Pro 月价 EUR 20.99；旧合同 SHALL 显示实际当前金额，并在已有换价安排时披露下一期金额和生效日期
- **AND** 前端不得按当前访问国家改写旧会员报价

### Requirement: 账号披露已安排的下一期价格

账号会员与 Study Pass 续费展示 SHALL 以实际合同及 Schedule 为依据，分别展示本期事实和已确认的下一期价格、生效日期。后端 SHALL 优先复用现有账号字段，确需补充时仅提供展示所需金额、币种与生效日期，兼容无安排的旧响应。前端 MUST NOT 根据当前销售目录推算存量用户续费价格。

#### Scenario: 本期旧价且下期已安排新价
- **GIVEN** 已核实巴西 Pro 当前周期为 BRL 399.90，下期已成功安排 BRL 60.99
- **WHEN** 用户查看账号续费信息
- **THEN** 页面 SHALL 保留本期金额，并展示下一期 BRL 60.99 及实际生效日期
- **AND** 仅修改销售配置、尚未安排成功时 SHALL NOT 展示为已确定的合同新价

### Requirement: 两组新价与唯一Pro例外在界面一致

Pricing、账号、购买弹层和Checkout SHALL 对第一组10地区、第二组53地区全部消费新金额，Pro采用新Pro列。只有本次需求确认时线上已购买Pro的当前已付周期展示旧金额保护，下期展示实际已安排新金额及生效日期。Basic、Plus、Trial、Pass和额外包不得因合同已存在继承旧价；已付历史仍按事实展示，不追溯重算。

#### Scenario: 第二组新Pro与旧Pro区分
- **WHEN** 美国新用户与符合资格的线上旧Pro用户查看账号或购买页
- **THEN** 新用户 SHALL 看到USD29.99/月；旧Pro当前已付金额按实际合同展示，下期目标为本区新Pro金额且安排状态真实
- **AND** SHALL NOT 向美国用户展示“第二组暂不采用新价”

#### Scenario: 非Pro和额外包不继承旧价
- **WHEN** 已有Basic/Plus/Trial/Pass用户取得新报价，或线上旧Pro用户购买会员额外包
- **THEN** 界面 SHALL 展示对应新金额并提交报价确认，不按历史Pro版本补齐商品价格
