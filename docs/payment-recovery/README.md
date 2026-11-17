# 订阅扣款失败恢复

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
