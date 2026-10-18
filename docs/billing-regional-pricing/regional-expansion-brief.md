> 2026-10-18 r8：用户已采纳三组审阅表全部44项尾数建议（6地区、40条Price），当前金额以完整价格表和approved-price-rounding.md为准；Pro年价仍为月价×12，Study Pass金额及冻结参考不变。本轮先同步文档，再更新沙盒；不改业务代码。

# 地区差异化定价：简版技术方案

版本：2026-10-18 r7（Study Pass全量固定金额已补齐）。基于前后端 `feat/billing-brazil-pricing`；三组187条当前绑定Price已全部配置并逐条回读（本轮新建33条）；见[配置记录](sandbox-configuration.md)与[环境绑定](regional-expansion-prices.sandbox.json)。业务代码扩展、真实支付、订阅迁移及生产部署未执行。

## 1. 已确认需求

第一组10地区、第二组53地区、第三组51地区全部使用新金额，共114地区；Pro统一使用“新 Pro”月付／年付价格。唯一当期保旧价例外：本次需求确认时线上已购买Pro的用户，当前已付周期保留旧金额，下个付费周期切新价。

分组只保留原表分类，不作为是否采用新价格的条件。巴西原属第二组，与美国、印度、日本等其余第二组地区适用同一新价要求。具体金额见[三组完整价格表](regional-price-matrix.md)，机器可读草案见[价格JSON](regional-expansion-prices.json)。新价格不改变套餐权益、SKU或原有计费周期，不新增`pro_new`。

| 对象 | 价格规则 |
| --- | --- |
| 第一、第二、第三组新购及新收费报价 | 全部使用对应地区新表金额，Pro只用新Pro列 |
| 本次需求确认时线上已购买Pro的当前已付周期 | 保留真实旧金额、权益和原周期结束时间 |
| 上述线上Pro下个付费周期 | 月付在下次月续费、年付在下次年度续费切新价 |
| Basic、Plus、付费Trial、Study Pass和会员额外包 | 没有当期保旧价资格；上线后的报价和后续应付使用新金额 |
| 历史已付账单 | 保留付款事实；不因采用新价追溯重算、自动补扣或退款 |

Trial不是“已购买Pro”的例外；首段已支付事实不改，7天结束后转正使用新Pro月价。Study Pass按自身自然月合同处理，不继承Pro保旧价资格。旧独立额度SKU与三个会员额外包不同，新表没有提供其对应金额时列待核对，不套用addon金额，也不新增保旧价例外。

## 2. 地区及固定金额

| 定价区 | 参考倍率 | 国家／地区代码 | 销售币种 |
| --- | ---: | --- | --- |
| Z020 | 0.2 | IN、PK、EG、NG、BD、ET、MW、ZW、PS、RW、TZ、UG、VE、ZM、AF、CM、LA、LR、MM、NP、UZ | INR、PKR、EGP、NGN、BDT、ETB、MWK、USD、ILS、RWF、TZS、UGX、ZMW、AFN、XAF、LAK、LRD、MMK、NPR、UZS |
| Z025 | 0.25 | VN、ID、KE、TN、GH、UA、BO、LK、KH、CI | VND、IDR、KES、USD、UAH、BOB、LKR、KHR、XOF |
| Z030 | 0.3 | PH、DZ、MA、NA、AZ、TH、HN、MN | PHP、DZD、MAD、NAD、AZN、THB、HNL、MNT |
| Z035 | 0.35 | MY、CO、ZA、EC、BW、GT、IQ、JO、SV、GA、GE、LB、MD | MYR、COP、ZAR、USD、BWP、GTQ、XAF、GEL、LBP、MDL |
| Z040 | 0.4 | BR、AR、TR、JM、PE、BZ、AL、KZ、ME、PG、RS | BRL、ARS、TRY、JMD、PEN、BZD、ALL、KZT、EUR、PGK、RSD |
| Z045 | 0.45 | MX、CN、RO、LC、CL、MV | MXN、CNY、RON、XCD、CLP、MVR |
| Z050 | 0.5 | PA、CR、HR、HU、OM、PL、SC、TT | USD、CRC、EUR、HUF、PLN、SCR、TTD |
| Z055 | 0.55 | GY、GR、BB、BN、LV、PR、SK | GYD、EUR、BBD、BND、USD |
| Z060 | 0.6 | SI、SA、UY | EUR、SAR、UYU |
| Z065 | 0.65 | TW、KR、JP、ES、KW | TWD、KRW、JPY、EUR、USD |
| Z070 | 0.7 | IT | EUR |
| Z075 | 0.75 | AE、FR | AED、EUR |
| Z080 | 0.8 | HK、DE、MO、AT、BE、FI | HKD、EUR、MOP |
| Z085 | 0.85 | GB、NZ、NL、BS、SE | GBP、NZD、EUR、BSD、SEK |
| Z090 | 0.9 | CA、SG | CAD、SGD |
| Z095 | 0.95 | AU、LU | AUD、EUR |
| Z100 | 1 | US、IE、BM、NO | USD、EUR、BMD、NOK |

共114地区、17区、86种销售币种、103个“区＋币种”组合。德国和奥地利共用Z080/EUR；意大利为Z070/EUR。运行时不计算倍率或实时汇率。

例如德国Trial €1.99/7天，随后Pro €20.99/月；美国新Pro $29.99/月、$359.88/年；印度新Pro ₹569/月、₹6828/年；巴西Trial R$5.90，随后Pro R$60.90/月。美国、印度不会因属于第二组继续使用旧销售价。

## 3. 选价和线上Pro换价

```text
独立新购：可信国家 → 定价区＋币种 → 新价格版本 → SKU Price
已有合同的新收费动作：原合同区＋币种 → 新价格版本 → 目标SKU Price
符合资格的线上已购Pro当前周期：保留实际旧Price和已付金额
该Pro下个付费周期：合并既有Schedule，采用同区同币种新价格
```

线上Pro资格来自本次需求确认时的生产付款／合同快照，固定记录名单与确认时间，不能仅凭旧Price、Pro权益标签或沙盒数据授予。用户旅行不改变合同区和币种。区归属不明或缺同币种目标时列待处理，不按当前IP猜测、不自动换币；配置未齐不能作为长期保旧价规则。

例如：已核实且符合线上Pro资格的巴西合同，本期已付R$399.90，原周期结束后续费R$60.90，本期不退差价。即使该用户此时购买会员作业额外包，也按新表R$19.90报价，不能把Pro保旧价资格扩展到额外包。第二组美国用户新购Pro直接使用$29.99，无需等待第二批开放。

已有试用转正、预约降级和取消必须保留。已预约从Pro降到Basic的，下期使用新Basic金额；已取消续费的不被重开。只更新销售目录不等于Pro已安排下期换价，必须逐合同执行、记录并回读。

## 4. 实现与配置边界

复用既有Product，按“区＋SKU”配置Price；11个SKU对应187条候选Price，会员／Trial／额外包的1030个固定金额已从表中录入。Study Pass已覆盖全部114地区、103个区币种组合，原63地区的61组合保持，本轮新增42组合；全量1133个主单位金额已齐。Pass按USD0.99×倍率×固定参考，低额取USD0.60缓冲、HKD4参考折算及已公布同币种结算最低金额的最大参考值后向上取整，计算依据见[Pass测算](study-pass-pricing.md)。所有金额已完成沙盒配置回读，仍需真实扣款验证；加纳／突尼斯按已批准的Z025/USD替代价配置。

USD也是美国、厄瓜多尔、津巴布韦的实际销售币种，必须使用各自定价区的新表USD金额；其余区的技术性USD默认金额不得作为销售回退。加纳／突尼斯已获用户确认改用Z025/USD；Pass实际扣款列为验证待办，不能因此将地区移出本次范围。

后端统一选择Price、金额、币种，并用Price注册表处理新旧回调；现有订单`biz_context`合并快照，不为版本新建表。前端消费后端报价，价格或Trial转正目标变化时要求再次确认。账号分别披露实际本期付款、已安排下期金额和生效日期。

## 5. 验收与交付

- 全量核对第一、第二、第三组114地区的新金额和新Pro列，重点覆盖美国／厄瓜多尔／津巴布韦同为USD不同价及德国／意大利EUR不同价。
- 验证线上已购Pro月／年付本期旧价、下期新价；验证Basic／Plus／Trial／Pass及额外包没有保旧价资格，沙盒Pro及新购Pro不会误入例外。
- 核对103个Pass计算结果与上调下限；完成各币种扣款单位、最低扣款及真实续费验证，保留报价确认、支付幂等、取消、试用转正、升降级和失败恢复。
- 文档、代码、沙盒配置、真实存量迁移和生产发布分别记录状态；本次同步文档并扩充现有项目沙盒价格；业务代码未改。

详细规则见[线上Pro换价规格](specs/subscription-price-rollout/spec.md)。

本轮第三组补充：51地区全部纳入，当前共114地区、17区、86币种、103个区币种组合、1133个金额。原63地区金额保持；五地USD替代经用户明确确认，见currency-replacements.md。沙盒当前绑定以sandbox-configuration.md为准，业务代码、真实付款及生产订阅均未修改。
