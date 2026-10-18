# 地区定价方案A后端交接

2026-10-18 r6。跨工程来源：Verla根`openspec/changes/add-brazil-regional-pricing/`；调整同时同步来源与此交接目录。本轮仅修正文档与配置草案，代码扩展、Stripe写入和部署尚未执行。

第一组10地区、第二组53地区全部使用新金额，共63地区；Pro统一使用“新 Pro”月付／年付价格。唯一当期保旧价例外：本次需求确认时线上已购买Pro的用户，当前已付周期保留旧金额，下个付费周期切新价。

阅读顺序：

1. [简版方案](regional-expansion-brief.md)：范围、唯一例外及例子。
2. [两组完整新价格表](regional-price-matrix.md)：63地区固定金额及来源。
3. [线上Pro换价规格](specs/subscription-price-rollout/spec.md)：资格、当期保护、下期换价与验收。
4. [详细方案](regional-expansion-plan-a.md)、[价格JSON](regional-expansion-prices.json)和[任务](tasks.md)。

原“仅第一组加巴西”及“所有存量订阅本期保旧价”的口径已被替代。JSON已包含63地区；销售开关关闭、ID为空表示未配置，不是第二组不在需求范围。Study Pass已补齐原缺失52组合，当前61组合固定建议金额已齐（GHTN合并为经用户批准的Z025/USD），原10组合保持；[测算与低额上调](study-pass-pricing.md)可复核。GHTN的USD替代价已获用户确认；实际最低扣款／续费仍待核验。配置缺口不能成为继续旧价的业务例外。`price-matrix.md`只记录历史巴西沙盒价格。
