## ADDED Requirements

### Requirement: 价格版本在下一个付费周期生效

系统 SHALL 为已发布新价格且归属明确的会员、付费 Trial 和订阅型 Study Pass 安排下一个付费周期使用新 Price。当前已付周期的价格、周期结束时间及历史订单 MUST 保持不变，不因本次换价立即扣款、补差价或退款。目标 SHALL 保持同一定价区、实际 Subscription.currency、SKU 及计费周期；既有 Trial 转正或用户预约套餐变更的目标 SKU/周期按其既有安排保留。旧独立额度包和一次性 extra packs MUST NOT 作为订阅迁移对象。

#### Scenario: 巴西月付下期使用新价
- **GIVEN** 归属已核实为 Z040/BRL 的有效 Pro 月付当前价格为 BRL 399.90，新版本为 BRL 60.99
- **WHEN** 发布新版本并在续费账单生成前成功安排换价
- **THEN** 当前周期 SHALL 保持 BRL 399.90 的已付事实，下次月续费 SHALL 使用 BRL 60.99
- **AND** 本期 SHALL NOT 产生换价差额账单或退款

#### Scenario: 年付按年度边界换价
- **GIVEN** 年付合同的当前付费周期仍有数月，月度权益刷新先于年度续费
- **WHEN** 发布新的年付 Price
- **THEN** 换价生效时间 SHALL 为实际年度付费周期结束点，不是下次月度权益刷新日

#### Scenario: Study Pass 独立换价
- **GIVEN** Study Pass 与会员为独立合同，Pass 的目标本币价格已通过最低扣款核验
- **WHEN** 为 Pass 发布新价
- **THEN** 系统 SHALL 按 Pass 自己的付费周期、区和币种安排换价，不继承会员的周期或区价

### Requirement: 存量归属与目标币种必须明确

迁移前系统 SHALL 通过已登记的 Price 绑定、可靠的购买记录或经核对的合同归属映射确定定价区，以实际 Subscription.currency 为币种依据。系统 MUST NOT 用当前访问 IP、语言或仅凭币种猜测存量合同的区，也 MUST NOT 因新 Price 具有技术性 USD 默认值而认定旧 USD 合同可迁移到该区。归属不明、目标币种未列为该区可售币种、目标 Price 未验证或 Study Pass 最低扣款未通过的合同 SHALL 列入待核对清单，不自动换币或选价。

#### Scenario: 旧 EUR 订阅缺少地区归属
- **GIVEN** 一个旧 EUR Price 同时服务过多个地区，无法确认该合同属于德国还是意大利
- **WHEN** 生成新价迁移清单
- **THEN** 该合同 SHALL 标为待核对，记录原因，不能根据最近访问国家选择 Z080 或 Z070

#### Scenario: 旧 USD 合同不能借用技术默认金额
- **GIVEN** 合同币种为 USD，目标地区目录只允许本币销售，但其多币种 Price 具有 USD 默认金额
- **WHEN** 预检迁移目标
- **THEN** 系统 SHALL 阻止自动迁移，不将 USD 默认值视为获批准的存量 USD 新价格，不切换成本币

### Requirement: 发布新价必须安排实际订阅变更

更新销售配置 SHALL NOT 被视为存量订阅已经换价。实现 SHALL 提供服务端受控执行入口，先生成 dry-run 清单，再对明确发布的目标版本执行逐合同安排；本轮规格编写不代表已获生产执行授权。清单 SHALL 包含环境/账户、合同标识、归属依据、源 Price、目标 Price、当前/下一期金额及币种、生效时间和处理结果。只处理范围内合同，保留成功、待核对、失败、跳过等可追溯结果。

#### Scenario: 配置更新但订阅尚未安排
- **WHEN** 新版本已成为新购销售版本，但存量安排尚未完成
- **THEN** 系统 SHALL 区分新购启用与存量安排结果，不声称全部用户下期已经换价
- **AND** 未安排成功的合同 SHALL 可被查询和重试，不静默遗漏

### Requirement: 合并 Subscription Schedule 而非覆盖用户安排

无 Schedule 的合同 SHALL 从实际 Subscription 创建计划，再保留当前 phase 并在原周期结束点加入目标 Price phase。已有 Schedule 的合同 SHALL 修改现有计划，保留当前和仍需执行的未来 phases、SKU、日期、数量、优惠、税设置、取消语义和币种；不得创建相互竞争的第二个计划。换价操作 SHALL 不重置当前周期、不延长 Trial，并按无本期 prorations 的方式处理。本期只读事实不能被目标 Price 替换。

#### Scenario: 试用转正遇到新价发布
- **GIVEN** 已付 7 天 Trial 的 Schedule 计划转入 Pro 月付，尚未生成转正账单
- **WHEN** 发布该区的新 Pro 月价
- **THEN** 系统 SHALL 更新原计划的 Pro 转正目标 Price，保留原 7 天结束时间、首付和月付周期
- **AND** SHALL NOT 重复收 Trial 周费、延长试用或另建并行 Schedule

#### Scenario: 已预约降级
- **GIVEN** 用户已预约当前周期结束后从 Pro 降级为 Basic
- **WHEN** 新版地区价发布
- **THEN** 下一阶段 SHALL 仍为 Basic，采用已验证的同区同币种新版本 Basic Price
- **AND** SHALL NOT 因换价任务把用户改回 Pro 或清除降级安排

#### Scenario: 同币种月付保持原计费日
- **WHEN** 为未预约套餐变更的月付合同安排新价格
- **THEN** 当前阶段 SHALL 保持原 Price 和结束时间，下一阶段使用新 Price 且不重置计费日期

### Requirement: 取消、欠款和已出账合同不可被强行覆盖

执行前系统 SHALL 重新读取合同状态、Schedule、周期与相关发票。已取消、已预约到期取消、暂停续费或存在欠款恢复/未完成支付冲突的合同 SHALL 保留原状态并输出跳过或待处理原因，不恢复续费。目标周期账单已经生成或截止时间已经经过时 SHALL 标记冲突并停止该次写入，重新确定可用窗口后再安排，不重写既有发票、不补收旧新价差额，也不能将其标为按时完成。

#### Scenario: 到期取消与价格发布并发
- **GIVEN** dry-run 后用户取消了下期续费
- **WHEN** 执行该合同换价
- **THEN** 系统 SHALL 检测取消状态并跳过，不移除 cancel_at_period_end 或恢复续费

#### Scenario: 续费账单已生成
- **GIVEN** 计划处理的下一期发票已经生成
- **WHEN** 换价执行器再次读取合同
- **THEN** 系统 SHALL 返回窗口冲突，不修改该发票、不追溯换价，不静默将本次安排算作成功

### Requirement: 换价执行具备幂等与并发保护

每次发布 SHALL 绑定明确的目标版本和逐合同源快照，幂等键至少区分发布批次、合同、目标 Price 与生效时间。执行前 SHALL 重验快照；用户套餐变更、取消或更晚版本发布后，过时任务 MUST NOT 覆盖新状态。网络超时后 SHALL 读取实际 Schedule 判断是否已经写入，再恢复执行。实际写入成功、阶段生效和账单支付成功 SHALL 分别确认，不以其中一个代替另外两个。

#### Scenario: Schedule 写入超时后重试
- **GIVEN** Stripe 可能已保存目标 phase，但执行端未收到响应
- **WHEN** 重试同一合同
- **THEN** 系统 SHALL 读取并核对实际 Schedule，复用已匹配安排，不创建重复计划或重复收费

#### Scenario: 用户升级改变当前周期
- **GIVEN** 旧任务记录了原周期结束时间，用户随后完成升级并重置周期
- **WHEN** 旧任务尝试安排换价
- **THEN** 系统 SHALL 检测合同快照变化，重新解析已确认套餐和新周期，不覆盖旧快照到新合同
- **AND** 若用户操作需重建 Schedule，业务流程 SHALL 保留或重新安排适用的新价迁移，不无声丢弃

#### Scenario: 新旧发布任务乱序执行
- **GIVEN** 同一合同已安排 v3，旧 v2 任务随后重试
- **WHEN** 旧任务核对目标与发布顺序
- **THEN** 系统 SHALL 将旧任务标为已被取代，不把目标 Price 降回 v2

### Requirement: 历史快照与当前下一期价格分别处理

订单、退款依据、已支付发票与事件快照 SHALL 保持原 Price/金额/币种；Price 注册表保留历史版本。当前合同版本 SHALL 以 Stripe 实际生效 Price 为准，未来目标存于 Schedule/迁移记录，不提前覆盖当前版本。当前周期的额外包和立即套餐变更 SHALL 使用当前生效版本；在周期边界生效的套餐变更 SHALL 与已安排的新版本合并。回调 SHALL 按对应发票/订单事实处理，迟到旧事件不得将合同镜像回退。采用新 Price 但付款失败时 SHALL 沿用欠款/权益规则，不能记录虚假已付款。

#### Scenario: 切换前购买额外包与切换后续费
- **GIVEN** 合同当前为 v1，下一周期已安排 v2
- **WHEN** 用户在本期购买额外包
- **THEN** 系统 SHALL 使用当前 v1 的适用价格
- **WHEN** 下一周期正式生效
- **THEN** 后续适用报价 SHALL 使用 v2，同时旧订单仍保留 v1 的实际金额

#### Scenario: 新价格已生效但续费失败
- **WHEN** Schedule 已切换为目标 Price，但对应续费发票未支付
- **THEN** 系统 SHALL 区分价格生效与支付状态，按原欠款恢复流程处理，不因迁移完成重复发放已付权益

### Requirement: 回滚区分新购开关与已安排的合同换价

新购地区开关、停止后续迁移任务和撤销已安排未来阶段 SHALL 是不同操作。停止发布 SHALL 不删除历史映射、不改已生效周期；已经写入的未来 phase 不会因关闭新购开关而自动撤销。确需撤销未生效换价时 SHALL 重新核对当前合同和用户安排，仅恢复本次发布改动的价格字段，保留其他设置；已经生效的价格不得追溯回滚或自动退款。

#### Scenario: 关闭销售开关
- **GIVEN** 某合同已成功安排下期 v2
- **WHEN** 关闭地区新购开关或停止未执行任务
- **THEN** 已安排的 v2 SHALL 继续按实际 Schedule 执行，当前/历史账单保持原事实
- **AND** 运维结果 SHALL 明确已安排目标仍存在，不能声称合同换价已经撤销

#### Scenario: 撤销未生效安排
- **GIVEN** 发布批次的目标 phase 尚未生效且可确认没有冲突
- **WHEN** 执行明确要求的撤销
- **THEN** 系统 SHALL 只撤销该批次价格替换，保留用户降级/取消、原日期、税和优惠设置，并回读确认
