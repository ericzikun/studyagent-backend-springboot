> 早期版本清理：238条旧地区Price已归档，地区目录只保留最终187条启用价格。金额未变，具体见sandbox-configuration.md。

> 2026-10-18 r8：用户已采纳三组审阅表全部44项尾数建议（6地区、40条Price），当前金额以完整价格表和approved-price-rounding.md为准；Pro年价仍为月价×12，Study Pass金额及冻结参考不变。文档已先推送，随后沙盒新建40条替代Price并回读全部187条；不改业务代码。

# 地区定价方案A后端交接

2026-10-18 r7。来源为Verla根`openspec/changes/add-brazil-regional-pricing/`。第一组10、第二组53、第三组51，共114地区／17区全部使用新金额；Pro统一使用新Pro月／年。仅需求确认时线上已购买Pro的当前已付周期保留旧金额，下个付费周期切新价。

本轮在现有项目沙盒新建33条Price，当前187条绑定、1133个地区金额全部回读一致；前205条既有金额及21个Product默认价保持。应用销售关闭；业务代码、生产、真实付款／续费及订阅迁移未实施。

1. [三组完整新价格表](regional-price-matrix.md)与[Excel](三组地区最终新价格表.xlsx)：全部114地区，实际销售币种、新Pro与Study Pass金额。
2. [简版方案](regional-expansion-brief.md)、[详细方案](regional-expansion-plan-a.md)和[线上Pro换价规格](specs/subscription-price-rollout/spec.md)。
3. [价格JSON](regional-expansion-prices.json)、[沙盒绑定](regional-expansion-prices.sandbox.json)、[配置证据与Price ID](sandbox-configuration.md)。
4. [USD替代授权与价格](currency-replacements.md)、[Pass计算及低额上调](study-pass-pricing.md)、[任务](tasks.md)。

GH／TN／IQ／JO／VE／KW／OM使用用户明确确认的USD新价；原63地区金额保持，本轮新增42个区币种组合后共103组合、86销售币种。分组不决定采用新价资格。原Pro只用于历史核对；旧独立额度包不套用会员addon价。`price-matrix.md`是早期巴西沙盒历史。
