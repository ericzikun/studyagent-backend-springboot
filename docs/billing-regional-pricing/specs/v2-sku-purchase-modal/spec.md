## ADDED Requirements

### Requirement: SKU 与 Study Pass 购买界面使用同一地区报价契约

SKU 弹层、题库 Study Pass 确认和已有套餐变更确认 SHALL 使用后端实际报价展示首笔/本次应付、币种及适用周期，提交对应的报价确认字段。会员依附 extra pack 和升级差价 SHALL 保持有效订阅币种；独立新购 SHALL 按后端地区规则。不支持所需币种的商品 SHALL 无可执行购买动作，不能使用写死 USD 金额补齐。

#### Scenario: BRL 会员在海外购买 extra pack
- **GIVEN** 当前会员为 BRL，但用户当前国家为 US
- **WHEN** 用户打开 Assignment、Detection 或 Humanizer 购买弹层
- **THEN** 会员依附 extra pack 和升级本次应付 SHALL 使用 BRL 报价
- **AND** 实际创建支付 SHALL 使用相同币种和已确认金额

#### Scenario: 巴西用户购买 Study Pass
- **GIVEN** 用户无有效会员/同类 Pass，可信国家为 BR，Pass 已配置确认的 BRL 金额
- **WHEN** 用户打开 Pass 购买确认
- **THEN** 界面 SHALL 显示后端 BRL 金额、自然月自动续费及既有会员覆盖说明
- **AND** SHALL NOT 将 passCode 的 30d 后缀解释为当前新购计费周期

### Requirement: 购买弹层在地区或报价变化后等待确认

购买界面 SHALL 在报价变化时刷新商品与资格，保留页面输入和购买上下文，停止旧 loading 并恢复正常确认交互；SHALL NOT 自动按新报价支付或显示后端原始错误。既有登录回跳、权益限制及支付返回路径约束 SHALL 保持有效。

#### Scenario: 老弹层报价失效
- **GIVEN** 用户打开了旧 USD 报价弹层，新的有效独立新购报价为 BRL
- **WHEN** 提交旧确认得到 PRICING_QUOTE_CHANGED
- **THEN** 弹层 SHALL 展示最新适用金额并等待用户重新确认
- **AND** SHALL NOT 自动创建新 Checkout 或丢失任务输入

### Requirement: 两组弹层统一新金额且不扩散Pro例外

第一、第二组全部地区的购买弹层 SHALL 消费新价格表，Pro仅采用新Pro金额。线上已购Pro当前周期保旧价仅限该会员已付周期，不扩展到本次额外包、套餐变更或独立Study Pass报价。

#### Scenario: 保旧价Pro购买新额外包
- **GIVEN** 符合资格的巴西线上Pro本期已付BRL399.90
- **WHEN** 打开会员作业额外包弹层
- **THEN** 界面 SHALL 报价新表BRL19.99，并保持原任务输入和确认交互
