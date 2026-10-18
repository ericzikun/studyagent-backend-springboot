# 加纳、突尼斯改用Z025/USD新价

2026-10-18 r6。目标为现有项目沙盒LASTMILE LIMITED（acct_1SrJ7Q7GRT6LLkI1，livemode=false）。只读Country Spec确认HK支持清单不含GHS/TND；当前会话用户明确选择“采用上述USD新价”。本记录为本次配置授权与金额依据，替代此前待确认建议，不改变Pro唯一当期保护规则。

两地仍在第二组、Z025（0.25），销售币种改为USD，复用同一组Price；不排除国家，不回退旧价，不在支付时换汇。

| SKU | USD新金额 | 周期 |
| --- | ---: | --- |
| pro_trial_to_monthly | 0.79 | week × 1 |
| basic_monthly | 4.99 | month × 1 |
| basic_yearly | 23.99 | year × 1 |
| plus_monthly | 9.99 | month × 1 |
| plus_yearly | 47.99 | year × 1 |
| pro_monthly | 7.49 | month × 1 |
| pro_yearly | 89.88 | year × 1 |
| addon_assignment_3 | 2.49 | one_time × 1 |
| addon_detection_5 | 1.29 | one_time × 1 |
| addon_humanizer_3 | 1.79 | one_time × 1 |
| study_pass_subscription_30d | 0.60 | month × 1 |

会员／Trial／额外包金额来自此前《地区价格补充与美元替代.xlsx》D11:D22的新Pro列与对应行；Study Pass原始USD0.99×0.25=0.2475，按低额参考上调至USD0.60。Z025 Price默认USD金额必须使用本表，不再使用仅作技术默认的美国金额。

调整后仍为63地区、16区、176条Price，实际销售币种52种、区币种组合61个，610个会员／Trial／额外包金额＋61个Pass金额，共671个地区金额。原GHS/TND金额保留在原始用户表作历史参考，当前销售矩阵与JSON不再将它们作为候选销售币种。真实支付及最低扣款仍待验收。
