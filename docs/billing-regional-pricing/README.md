# 地区定价方案 A 后端交接

2026-10-18 按用户要求随后端 `feat/billing-brazil-pricing` 分支提交的交接快照。跨工程规格来源为 Verla 根仓库 `openspec/changes/add-brazil-regional-pricing/`；后续规则调整同步该来源与本目录。当前仅交付文档和配置草案，扩展功能尚未实施，未修改 Stripe。

阅读顺序：

1. [简版技术方案](regional-expansion-brief.md)：范围、问题和实现方式。
2. [下周期换价规格](specs/subscription-price-rollout/spec.md)：本期不变、下个付费周期同区同币种换新价及验收场景。
3. [实施任务](tasks.md)、[详细交接方案](regional-expansion-plan-a.md) 与 [价格草案](regional-expansion-prices.json)。
4. [数据库化实施定稿](regional-expansion-plan-a-db.md)：用户确认新增 DB 表列后，Price 注册表/国家路由/换价执行记录的落库形态（5 表 + 2 加列）与实施顺序；业务规则仍以 plan-a 与 specs 为准。

旧独立额度包保持原规则。Study Pass 建议金额仍需目标账户最低扣款及续费验证。价格草案不是当前应用会自动读取的运行配置，Price ID 与地区启用状态尚未填写或开启。`price-matrix.md` 记录巴西基础阶段历史价格，不作为方案 A 新价；新价以价格草案为准。
