# 订阅扣款失败恢复

2026-11-19 P1 评审修复：filter 覆盖精确前缀及 servlet path，控制器对所有已映射入口独立复核 loopback/token，并明确返回 401/403；缺失/损坏/并发清理的 TEST 时钟证据不再使账户与额度读取 500，改为保留已付记录/周期/额度的只读锁定，恢复证据后自动恢复。退出操作白名单不撤销历史绑定的只读事实。47 组 996 项联合检查中 995 项通过、1 项远端 IT 跳过，0 失败/错误；clean 打包及夹具排除通过。本轮未调用 Stripe/邮件，未提交或部署。详见[修复证据](/Users/alf/Projects/SA/Backend/Task/Task1104/payment-recovery-evidence-archive/20261119-clock-p1-review/result.json)。

新增默认关闭的部署 TEST Clock 验收模式：显式 `recovery-test-clock` profile、TEST key、独立用户、持久化目录及 loopback/令牌入口齐备时，由运行时代码自行捕获原生首败证据，并推进真实 D7/D14/D21/D28、D30 和年付月边界；没有缩短业务天数或直接运行 worker。普通部署仍保留缺证据拒绝准入。

本机独立原生月付已验证新 Gateway 准入、固定时点及 D30 自然关闭；年付已验证 D7 补付后完整一年、续费 CONFIRMED、两次月额度刷新不重复。最终联合检查共 977 项，其中 976 项通过、1 项默认禁用的远端 IT 跳过；远端 IT 已另行显式启用执行上述原生场景。clean 可执行包包含受控入口及共享取证校验器，不含旧测试 Gateway、测试夹具、JUnit/Mockito 或 test-jar。

新增四项 `BILLING_PAYMENT_RECOVERY_TEST_CLOCK_{ENABLED,DIRECTORY,OPERATOR_TOKEN,ALLOWED_USER_IDS}`，配置见 application.yml。097 无新增迁移；证据目录必须持久化并由 TEST worker 共享。当前未部署、未提交，也未开启灰度；新模式的资金不足四槽、产品页面/测试服部署闭环及换购双付复验仍为 NOT_RUN，不能借用旧夹具结果。配置、操作命令及精确边界见[受控验收说明](/Users/alf/Projects/SA/Backend/Task/Task1104/payment-recovery-evidence-archive/20261119-deployed-test-clock/操作说明.md)。

2026-11-18 晚间验收：24 个指定标量合同通过原生 TEST／本机签名 HTTP／自然 cron 补付，含等价 Schedule RENEW；追加 Basic、Pro 试用的两条 Schedule D30 取消，验证持久化事务外 false/false、无新最终账单／Charge。真实 TEST 服 Clerk Bearer 四项及用户正常浏览器的公网只读鉴权通过。Basic/HKD 的真实 Stripe 公网投递、早到技术发票 inbox 自然重试与原签名重放也已通过；首次准入仍为本机签名重放，范围单列。

本轮修复兼容 Schedule 误阻止发票换卡同步，以及 SHIFT 已成功／技术票未登记窗口误生成零元付款订单的竞态。失败原文保留。修复后 40 组 917 项联合回归、13 项 HTTP／MySQL／自然 cron 及 clean 可执行包均通过。其余精确组合、完整产品页面／现售额度目录／全支付部署链及生产邮件正常 trial 对照仍待，Schedule PERIOD_END 仍禁用。零邮件、零生产或服务器业务库写入，临时对象／库／入口均清理，前端未改、未提交、灰度关闭。详见[增量报告](/Users/alf/Projects/SA/Backend/Task/Task1104/payment-recovery-evidence-archive/20261118-remaining-contracts/README.md)与[实际证据](/Users/alf/Projects/SA/Backend/Task/Task1104/payment-recovery-evidence-archive/20261118-remaining-contracts/result.json)。以下为此前各轮历史记录。

2026-11-18 最新验收：USD 试用转月付的资金不足已通过 HTTP／自然 cron 四槽，D7／D14／D21 各拒付一次，D28 仅更新客户默认卡后由应用同步并原槽成功；每槽 transport_attempts=1，四个 Request ID 与 Stripe 原生事件对应，SEND_EMAIL 动作全程为 0，完整周期／额度／续费 CONFIRMED。另通用失败及资金不足四槽全部拒付后的 D30 自然关闭均通过：前一秒仍开放，取消 false/false 在持久化、事务外执行，无额外最终账单或 Charge，旧试用草稿保留 draft/auto_advance=false 和人工核对。两个独立资金不足场景合计 8 个槽，Request ID、原生事件及实际付款方式均逐一对应，每客户仍最多四槽。新增 Plus／HKD／PERIOD_END 延迟补付合同通过。39 组 901 项联合回归、13 项本机 HTTP 回归及 clean 打包通过，测试夹具未进入部署包。

仍待验证 Stripe 公网投递／真实 Clerk 与部署链、其他精确合同及完整生产邮件对照；本轮通过仅覆盖记录中的精确合同和场景。本轮无业务 Java 改动、零邮件，测试服／生产配置及最终放行键未变，新对象／临时库和认证入口均已清理，未 commit／push。见[本轮验收报告](/Users/alf/Projects/SA/Backend/Task/Task1104/payment-recovery-evidence-archive/20261118-native-http-funds/README.md)和[实际证据](/Users/alf/Projects/SA/Backend/Task/Task1104/payment-recovery-evidence-archive/20261118-native-http-funds/result.json)。

2026-11-18 较早一轮原生 HTTP／自然调度验收：USD 试用转月付、HKD 正式月付、USD 试用转年付并保留周期末取消意图，三个精确合同已通过真实 Stripe TEST 首败／补付、原生事件本机签名 HTTP 重放、真实 inbox／恢复服务／MySQL 和自然 cron。均延迟两天补付后验证完整周期、一次订单／额度、续费 `CONFIRMED`；年付验证首月额度，重复回调与继续扫描未重复订单／额度。39 组 901 项联合回归、13 项本机 HTTP 回归和 clean 可执行打包通过，包内没有测试夹具。新增 [原生 HTTP 验收测试](../../agent-start/src/test/java/com/studyagent/infra/service/billing/StripePaymentRecoveryNativeHttpSchedulerIT.java) 及其跨模块 test-jar 依赖仅用于测试。

该轮边界（后续进展见上）：这是本机签名重放，Stripe 到公网入口投递、真实 Clerk／部署代理、此 HTTP 链路内自然调度的资金不足四槽、其他合同及完整生产邮件对照仍未完成；Analytics／机器人为测试替身。零邮件，测试服与生产配置未改，最终放行键仍为空。所有新测试对象和临时库均已清理。详见[原生验收报告](/Users/alf/Projects/SA/Backend/Task/Task1104/payment-recovery-evidence-archive/20261118-native-http-scheduler/README.md)和[机器证据](/Users/alf/Projects/SA/Backend/Task/Task1104/payment-recovery-evidence-archive/20261118-native-http-scheduler/result.json)。本轮新增验收尚未提交。

2026-11-18 后续验收：通用回迁已提交（后端 `ecec193b`、前端 `fbc3cbe8`），未 push。新增 opt-in [HTTP／真实 cron 集成测试](../../agent-start/src/test/java/com/studyagent/start/billing/SubscriptionPaymentRecoveryHttpSchedulerMysqlIT.java) 13 项通过：随机 HTTP 端口、生产 HMAC、MySQL inbox／并发 CAS、自然重试至 dead-letter，以及动作持久化后派发、UNKNOWN 不重投和过期租约处理。新增测试配置仅在显式 profile 和独立本机测试库下运行；39 组原联合测试 901 项及完整打包复验通过。

上一轮本机隔离测试的边界：Clerk 验签服务、Webhook 的恢复分类、受保护接口后的 BillingDomainService、Stripe 与外部投递为测试替身。不能据此将真实 Stripe 金融重试、部署后代理／Clerk 链路、其他合同或完整生产邮件对照标为通过；灰度与最终放行键仍关闭。每轮临时数据库均已删除，未修改测试服。详见[本轮验收证据](/Users/alf/Projects/SA/Backend/Task/Task1104/payment-recovery-evidence-archive/20261118-http-scheduler-validation/result.json)。新增测试与此记录尚未再次提交。

本功能主要实现已落代码；指定回归与部分 Stripe TEST 原生场景已验证，尚未完成全部合同及上线验收。所有新接管、自动重试和邮件开关默认关闭，用户／精确 caseKey 放行名单为空；部署代码不会自动启用恢复流程。

## 数据库与部署

启动新后端前，先备份目标测试库并执行 [097_subscription_payment_recovery.sql](../../sql/097_subscription_payment_recovery.sql)。它新增三张恢复表、`user_subscriptions.payment_recovery_id` 及索引，不回填历史账单。即使开关关闭，也需要先迁移。SQL 内指定 `USE studyagent;`。

默认关闭部署无需新增恢复环境变量。实际联调前需另行设置准入时间界限、指定测试用户、精确 caseKey、Portal 配置，并按验证范围控制自动重试及邮件开关。Stripe 保持测试密钥，SDK／API 版本不变；不要直接全量开启。

## 验收边界与资料

已验证范围包含指定后端回归、独立 MySQL 并发检查、部分合同的资金不足四槽／D30／补付顺延、用户协助 Portal／Hosted Invoice／换购／双付，以及批准的一封提醒实收。它们不代表全部合同、回调排列或生产放行。

仍需完成其他合同和 Schedule 组合、真实 HTTP／鉴权链路、实际定时扫描、产品前端完整验收，以及 Stripe 生产邮件与正常 trial 对照等门禁；未运行项继续保留 NOT_RUN。

- [精确用例验收索引](evidence/index.json)：保留历史结果，最终放行名单仍为空。
- [原始日志归档清单](evidence/raw-evidence-archive.json)。
- [详细验收资料归档清单](evidence/detailed-evidence-archive.json)。
- [过程文档和辅助工具归档入口](/Users/alf/Projects/SA/Backend/Task/Task1104/payment-recovery-evidence-archive/20261117-notes-and-tools/README.md)。

过程文档与 Python 验收辅助工具不参与 Maven／Docker 构建，已移至仓库外；Java 测试、SQL 迁移及原有项目工具继续保留。归档不随 Git 合并传递，复核时须单独获取；工具需要按归档说明恢复至原仓库位置后使用。
