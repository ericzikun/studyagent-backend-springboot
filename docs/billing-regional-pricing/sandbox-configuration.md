# 地区新价格沙盒配置记录

2026-10-18。目标为现有项目测试环境：LASTMILE LIMITED，`acct_1SrJ7Q7GRT6LLkI1`，所有本次Price的`livemode=false`。用户已明确选择沙盒，并确认加纳／突尼斯采用Z025/USD新价。本次只配置Product下的新增Price，不涉及生产、业务代码、真实支付或订阅迁移。

## 已完成与实际证据

| 检查 | 结果 |
| --- | --- |
| 复用Product | 8个；没有复制新Product |
| 新建并逐条回读Price | 176条：先165条，再Z025的11条 |
| 全量地区金额 | 63地区、52币种、61区币种组合；610个会员／Trial／额外包金额＋61个Pass金额，共671个 |
| USD默认金额 | USD实际销售区按本区新价，其他区仅技术默认；已逐字段核验 |
| 税行为 | 沿用对应旧Price；会员月年付inclusive、三个会员额外包exclusive、Trial／Pass unspecified；所有币种option逐项核对 |
| 旧Price | 配置前29条的金额／币种／周期／税／active／lookup与metadata保持不变 |
| Product默认价 | 全部21个已有Product的default_price及active保持不变 |
| 重复防护 | 区／版本／SKU的lookup_key与参数摘要幂等键，重试前查实际对象、逐字段匹配后复用 |
| 沙盒Price绑定 | [完整环境JSON](regional-expansion-prices.sandbox.json)含全部176条Price与Product ID |
| 应用启用 | 未启用；zonePricingEnabled=false，enabledCountries为空，业务代码尚未实施扩展 |

配置以已推送文档提交`85900a61`为初始依据；Z025/USD补充依据为已推送`f8f0fc80`及当前会话用户确认。每条Price metadata记录自身来源提交、区、SKU和版本；实际创建后的映射见本文件和环境JSON。应用通用价格草案保持不含账户ID的通用形态，不能把环境JSON当作生产映射。

本地审计证据位于Verla根`outputs/regional-sku-20261018-01a14a9a/stripe-regional-r5-sandbox/`：`dry-run.json`、`results.json`、`verification-summary.json`、配置前后Price快照及Product快照，均不含密钥或客户付款信息。配置后沙盒共205条Price＝原29条＋本批176条。

## 尚未执行

- 真实Checkout首付、Trial转正、自然月续费、最低扣款及优惠后非零金额测试；Price可保存不代表已通过扣款验收。
- 线上Pro资格盘点、legacy alias完整回填、订阅Schedule换价、应用业务实现与销售启用。
- 生产Price创建、生产部署和任何生产订阅修改。沙盒新价不会自动让线上Pro下期换价。

## 价格描述格式（2026-10-18）

按用户要求，176条本批沙盒Price的`nickname`（Dashboard价格描述）统一改为“倍率（固定两位小数）倍率区（该区完整国家／地区名单）”。同区的11个SKU共用区描述；SKU、区、版本继续由Price ID、lookup_key和metadata识别。只更新nickname，金额、币种、周期、税、active、lookup_key、metadata及Product均通过前后快照核对保持不变，原29条Price描述未修改。

| 定价区 | 当前Stripe价格描述 |
| --- | --- |
| Z020 | 0.20倍率区（印度、巴基斯坦、埃及、尼日利亚、孟加拉国、埃塞俄比亚、马拉维、津巴布韦、巴勒斯坦、卢旺达） |
| Z025 | 0.25倍率区（越南、印度尼西亚、肯尼亚、突尼斯、加纳、乌克兰、玻利维亚） |
| Z030 | 0.30倍率区（菲律宾、阿尔及利亚、摩洛哥、纳米比亚、阿塞拜疆、泰国） |
| Z035 | 0.35倍率区（马来西亚、哥伦比亚、南非、厄瓜多尔、博茨瓦纳、危地马拉） |
| Z040 | 0.40倍率区（巴西、阿根廷、土耳其、牙买加、秘鲁、伯利兹） |
| Z045 | 0.45倍率区（墨西哥、中国大陆、罗马尼亚） |
| Z055 | 0.55倍率区（圭亚那、希腊） |
| Z060 | 0.60倍率区（斯洛文尼亚、沙特阿拉伯、乌拉圭） |
| Z065 | 0.65倍率区（中国台湾、韩国、日本） |
| Z070 | 0.70倍率区（意大利） |
| Z075 | 0.75倍率区（阿联酋、法国） |
| Z080 | 0.80倍率区（中国香港、德国、中国澳门、奥地利） |
| Z085 | 0.85倍率区（英国、新西兰、荷兰、巴哈马、瑞典） |
| Z090 | 0.90倍率区（加拿大、新加坡） |
| Z095 | 0.95倍率区（澳大利亚） |
| Z100 | 1.00倍率区（美国、爱尔兰） |

实际回读证据位于本地审计目录`stripe-regional-r5-sandbox/description-update/`的plan、results、verification及前后Price快照。国家名单来自当前63地区路由；例如Z100包含美国、爱尔兰，Z090包含加拿大、新加坡。

## Price ID清单

| 区 | SKU | 沙盒Product ID | 沙盒Price ID | tax_behavior |
| --- | --- | --- | --- | --- |
| Z020 | addon_assignment_3 | prod_V2XRyb7uDkhTVD | price_1URrvk7GRT6LLkI1uKA4TtpM | exclusive |
| Z020 | addon_detection_5 | prod_V2XRzr2BbiKqJA | price_1URrvk7GRT6LLkI1eleddwzo | exclusive |
| Z020 | addon_humanizer_3 | prod_V2XRbewO3qfwsD | price_1URrvl7GRT6LLkI1g8wSmfnC | exclusive |
| Z020 | basic_monthly | prod_V2XQKO3FZiPKZI | price_1URrvg7GRT6LLkI1WAN5piJL | inclusive |
| Z020 | basic_yearly | prod_V2XQKO3FZiPKZI | price_1URrvi7GRT6LLkI1Qnt7aRUr | inclusive |
| Z020 | plus_monthly | prod_V2XQrWdt06ryxW | price_1URrvh7GRT6LLkI1AQU29tqy | inclusive |
| Z020 | plus_yearly | prod_V2XQrWdt06ryxW | price_1URrvj7GRT6LLkI1E38NXC8g | inclusive |
| Z020 | pro_monthly | prod_V2XRxk7tA14xQr | price_1URrvm7GRT6LLkI1niFzNvIH | inclusive |
| Z020 | pro_trial_to_monthly | prod_V2yikGRbHLdZR6 | price_1URrvg7GRT6LLkI1SNUJVu95 | unspecified |
| Z020 | pro_yearly | prod_V2XRxk7tA14xQr | price_1URrvn7GRT6LLkI1c4v281IA | inclusive |
| Z020 | study_pass_subscription_30d | prod_VJqvMKubYOnfWS | price_1URrvn7GRT6LLkI1mwFTDZoB | unspecified |
| Z025 | addon_assignment_3 | prod_V2XRyb7uDkhTVD | price_1URrz67GRT6LLkI1XjdiqtqS | exclusive |
| Z025 | addon_detection_5 | prod_V2XRzr2BbiKqJA | price_1URrz77GRT6LLkI1dH2tbgA9 | exclusive |
| Z025 | addon_humanizer_3 | prod_V2XRbewO3qfwsD | price_1URrz87GRT6LLkI17fXYE7oL | exclusive |
| Z025 | basic_monthly | prod_V2XQKO3FZiPKZI | price_1URrz37GRT6LLkI1lQ466lhO | inclusive |
| Z025 | basic_yearly | prod_V2XQKO3FZiPKZI | price_1URrz47GRT6LLkI1nU5MghZR | inclusive |
| Z025 | plus_monthly | prod_V2XQrWdt06ryxW | price_1URrz47GRT6LLkI1tQbgKzv4 | inclusive |
| Z025 | plus_yearly | prod_V2XQrWdt06ryxW | price_1URrz57GRT6LLkI14Mz7RW5Z | inclusive |
| Z025 | pro_monthly | prod_V2XRxk7tA14xQr | price_1URrz87GRT6LLkI1Z0psIJcB | inclusive |
| Z025 | pro_trial_to_monthly | prod_V2yikGRbHLdZR6 | price_1URrz27GRT6LLkI1BYoiUcnL | unspecified |
| Z025 | pro_yearly | prod_V2XRxk7tA14xQr | price_1URrz97GRT6LLkI1erq4YsQ7 | inclusive |
| Z025 | study_pass_subscription_30d | prod_VJqvMKubYOnfWS | price_1URrzA7GRT6LLkI1zvA84jph | unspecified |
| Z030 | addon_assignment_3 | prod_V2XRyb7uDkhTVD | price_1URrvs7GRT6LLkI183qwm237 | exclusive |
| Z030 | addon_detection_5 | prod_V2XRzr2BbiKqJA | price_1URrvt7GRT6LLkI1bKn5QaTV | exclusive |
| Z030 | addon_humanizer_3 | prod_V2XRbewO3qfwsD | price_1URrvu7GRT6LLkI16gr5yJkk | exclusive |
| Z030 | basic_monthly | prod_V2XQKO3FZiPKZI | price_1URrvp7GRT6LLkI1dFau8mnq | inclusive |
| Z030 | basic_yearly | prod_V2XQKO3FZiPKZI | price_1URrvr7GRT6LLkI1KbUePdov | inclusive |
| Z030 | plus_monthly | prod_V2XQrWdt06ryxW | price_1URrvq7GRT6LLkI1KAoCwqTm | inclusive |
| Z030 | plus_yearly | prod_V2XQrWdt06ryxW | price_1URrvs7GRT6LLkI1zTeeYGHe | inclusive |
| Z030 | pro_monthly | prod_V2XRxk7tA14xQr | price_1URrvv7GRT6LLkI1aZeFjiih | inclusive |
| Z030 | pro_trial_to_monthly | prod_V2yikGRbHLdZR6 | price_1URrvo7GRT6LLkI1wIzQtQ6p | unspecified |
| Z030 | pro_yearly | prod_V2XRxk7tA14xQr | price_1URrvw7GRT6LLkI1gdtjghlG | inclusive |
| Z030 | study_pass_subscription_30d | prod_VJqvMKubYOnfWS | price_1URrvw7GRT6LLkI1LcXuJrZU | unspecified |
| Z035 | addon_assignment_3 | prod_V2XRyb7uDkhTVD | price_1URrw17GRT6LLkI1eyTE1Gsc | exclusive |
| Z035 | addon_detection_5 | prod_V2XRzr2BbiKqJA | price_1URrw27GRT6LLkI14bZPFJBu | exclusive |
| Z035 | addon_humanizer_3 | prod_V2XRbewO3qfwsD | price_1URrw37GRT6LLkI1rnX3diXu | exclusive |
| Z035 | basic_monthly | prod_V2XQKO3FZiPKZI | price_1URrvy7GRT6LLkI1HBrUdByu | inclusive |
| Z035 | basic_yearly | prod_V2XQKO3FZiPKZI | price_1URrw07GRT6LLkI14dCNzXkF | inclusive |
| Z035 | plus_monthly | prod_V2XQrWdt06ryxW | price_1URrvz7GRT6LLkI1cVGH5bWk | inclusive |
| Z035 | plus_yearly | prod_V2XQrWdt06ryxW | price_1URrw17GRT6LLkI1k4dW83H3 | inclusive |
| Z035 | pro_monthly | prod_V2XRxk7tA14xQr | price_1URrw47GRT6LLkI10CnP0N7y | inclusive |
| Z035 | pro_trial_to_monthly | prod_V2yikGRbHLdZR6 | price_1URrvx7GRT6LLkI1sm1znOvE | unspecified |
| Z035 | pro_yearly | prod_V2XRxk7tA14xQr | price_1URrw57GRT6LLkI16eAqZ89n | inclusive |
| Z035 | study_pass_subscription_30d | prod_VJqvMKubYOnfWS | price_1URrw57GRT6LLkI1lyNekvzw | unspecified |
| Z040 | addon_assignment_3 | prod_V2XRyb7uDkhTVD | price_1URrwA7GRT6LLkI1UmwIbgF2 | exclusive |
| Z040 | addon_detection_5 | prod_V2XRzr2BbiKqJA | price_1URrwB7GRT6LLkI1xIx2gAkW | exclusive |
| Z040 | addon_humanizer_3 | prod_V2XRbewO3qfwsD | price_1URrwC7GRT6LLkI1V9vwHIjC | exclusive |
| Z040 | basic_monthly | prod_V2XQKO3FZiPKZI | price_1URrw77GRT6LLkI1PUhg2WxO | inclusive |
| Z040 | basic_yearly | prod_V2XQKO3FZiPKZI | price_1URrw97GRT6LLkI16cEF3gWN | inclusive |
| Z040 | plus_monthly | prod_V2XQrWdt06ryxW | price_1URrw87GRT6LLkI1a3Z0uE3u | inclusive |
| Z040 | plus_yearly | prod_V2XQrWdt06ryxW | price_1URrwA7GRT6LLkI1fgAOcstS | inclusive |
| Z040 | pro_monthly | prod_V2XRxk7tA14xQr | price_1URrwD7GRT6LLkI1AMmtIIZq | inclusive |
| Z040 | pro_trial_to_monthly | prod_V2yikGRbHLdZR6 | price_1URrw67GRT6LLkI1rWPG6Knm | unspecified |
| Z040 | pro_yearly | prod_V2XRxk7tA14xQr | price_1URrwD7GRT6LLkI14wXk5z26 | inclusive |
| Z040 | study_pass_subscription_30d | prod_VJqvMKubYOnfWS | price_1URrwE7GRT6LLkI1heehqCaf | unspecified |
| Z045 | addon_assignment_3 | prod_V2XRyb7uDkhTVD | price_1URrwJ7GRT6LLkI1FFIPT3q2 | exclusive |
| Z045 | addon_detection_5 | prod_V2XRzr2BbiKqJA | price_1URrwK7GRT6LLkI1LQJ1gKUx | exclusive |
| Z045 | addon_humanizer_3 | prod_V2XRbewO3qfwsD | price_1URrwL7GRT6LLkI1DatGrDf0 | exclusive |
| Z045 | basic_monthly | prod_V2XQKO3FZiPKZI | price_1URrwG7GRT6LLkI17N1lP4hk | inclusive |
| Z045 | basic_yearly | prod_V2XQKO3FZiPKZI | price_1URrwH7GRT6LLkI1bJ82CGyF | inclusive |
| Z045 | plus_monthly | prod_V2XQrWdt06ryxW | price_1URrwH7GRT6LLkI1xinyBy0v | inclusive |
| Z045 | plus_yearly | prod_V2XQrWdt06ryxW | price_1URrwI7GRT6LLkI1iZE9n46F | inclusive |
| Z045 | pro_monthly | prod_V2XRxk7tA14xQr | price_1URrwM7GRT6LLkI1Wt4KJ3MK | inclusive |
| Z045 | pro_trial_to_monthly | prod_V2yikGRbHLdZR6 | price_1URrwF7GRT6LLkI1zniti7j7 | unspecified |
| Z045 | pro_yearly | prod_V2XRxk7tA14xQr | price_1URrwN7GRT6LLkI17oC39eD6 | inclusive |
| Z045 | study_pass_subscription_30d | prod_VJqvMKubYOnfWS | price_1URrwN7GRT6LLkI1DHqFrf2T | unspecified |
| Z055 | addon_assignment_3 | prod_V2XRyb7uDkhTVD | price_1URrwS7GRT6LLkI1cETPTsiG | exclusive |
| Z055 | addon_detection_5 | prod_V2XRzr2BbiKqJA | price_1URrwT7GRT6LLkI1fR2Cdg7Z | exclusive |
| Z055 | addon_humanizer_3 | prod_V2XRbewO3qfwsD | price_1URrwU7GRT6LLkI1whAh5d4Z | exclusive |
| Z055 | basic_monthly | prod_V2XQKO3FZiPKZI | price_1URrwP7GRT6LLkI1Ts5CCxFc | inclusive |
| Z055 | basic_yearly | prod_V2XQKO3FZiPKZI | price_1URrwR7GRT6LLkI1Bj2VRlGK | inclusive |
| Z055 | plus_monthly | prod_V2XQrWdt06ryxW | price_1URrwQ7GRT6LLkI1wJ0XNXp9 | inclusive |
| Z055 | plus_yearly | prod_V2XQrWdt06ryxW | price_1URrwR7GRT6LLkI16dKq7ap1 | inclusive |
| Z055 | pro_monthly | prod_V2XRxk7tA14xQr | price_1URrwV7GRT6LLkI1kPCSY8Is | inclusive |
| Z055 | pro_trial_to_monthly | prod_V2yikGRbHLdZR6 | price_1URrwO7GRT6LLkI1CQThdOWu | unspecified |
| Z055 | pro_yearly | prod_V2XRxk7tA14xQr | price_1URrwV7GRT6LLkI1KRu0kOxO | inclusive |
| Z055 | study_pass_subscription_30d | prod_VJqvMKubYOnfWS | price_1URrwW7GRT6LLkI1AgEGzELI | unspecified |
| Z060 | addon_assignment_3 | prod_V2XRyb7uDkhTVD | price_1URrwb7GRT6LLkI1xaJ6TDiv | exclusive |
| Z060 | addon_detection_5 | prod_V2XRzr2BbiKqJA | price_1URrwc7GRT6LLkI1JEqMTYlB | exclusive |
| Z060 | addon_humanizer_3 | prod_V2XRbewO3qfwsD | price_1URrwd7GRT6LLkI1twi0QrK4 | exclusive |
| Z060 | basic_monthly | prod_V2XQKO3FZiPKZI | price_1URrwY7GRT6LLkI15RdVWR5Q | inclusive |
| Z060 | basic_yearly | prod_V2XQKO3FZiPKZI | price_1URrwZ7GRT6LLkI1CUnllfC0 | inclusive |
| Z060 | plus_monthly | prod_V2XQrWdt06ryxW | price_1URrwZ7GRT6LLkI11tfCyoqM | inclusive |
| Z060 | plus_yearly | prod_V2XQrWdt06ryxW | price_1URrwa7GRT6LLkI1PG3Lap7L | inclusive |
| Z060 | pro_monthly | prod_V2XRxk7tA14xQr | price_1URrwd7GRT6LLkI1PzAAQgv0 | inclusive |
| Z060 | pro_trial_to_monthly | prod_V2yikGRbHLdZR6 | price_1URrwX7GRT6LLkI17pon6WBl | unspecified |
| Z060 | pro_yearly | prod_V2XRxk7tA14xQr | price_1URrwe7GRT6LLkI1s560672r | inclusive |
| Z060 | study_pass_subscription_30d | prod_VJqvMKubYOnfWS | price_1URrwf7GRT6LLkI17voozvv7 | unspecified |
| Z065 | addon_assignment_3 | prod_V2XRyb7uDkhTVD | price_1URrwk7GRT6LLkI1G70D918s | exclusive |
| Z065 | addon_detection_5 | prod_V2XRzr2BbiKqJA | price_1URrwk7GRT6LLkI14Rcou1pn | exclusive |
| Z065 | addon_humanizer_3 | prod_V2XRbewO3qfwsD | price_1URrwl7GRT6LLkI1SY0XYbe7 | exclusive |
| Z065 | basic_monthly | prod_V2XQKO3FZiPKZI | price_1URrwh7GRT6LLkI14W3gVPZQ | inclusive |
| Z065 | basic_yearly | prod_V2XQKO3FZiPKZI | price_1URrwi7GRT6LLkI1GWjaUcy6 | inclusive |
| Z065 | plus_monthly | prod_V2XQrWdt06ryxW | price_1URrwh7GRT6LLkI12MIKM1q8 | inclusive |
| Z065 | plus_yearly | prod_V2XQrWdt06ryxW | price_1URrwj7GRT6LLkI1QQ04iryg | inclusive |
| Z065 | pro_monthly | prod_V2XRxk7tA14xQr | price_1URrwm7GRT6LLkI1YlO1Os7P | inclusive |
| Z065 | pro_trial_to_monthly | prod_V2yikGRbHLdZR6 | price_1URrwg7GRT6LLkI1ELzEzSB9 | unspecified |
| Z065 | pro_yearly | prod_V2XRxk7tA14xQr | price_1URrwn7GRT6LLkI1UmGOnUg2 | inclusive |
| Z065 | study_pass_subscription_30d | prod_VJqvMKubYOnfWS | price_1URrwo7GRT6LLkI1Daz1VW0L | unspecified |
| Z070 | addon_assignment_3 | prod_V2XRyb7uDkhTVD | price_1URrwt7GRT6LLkI1IwuxE3K3 | exclusive |
| Z070 | addon_detection_5 | prod_V2XRzr2BbiKqJA | price_1URrwt7GRT6LLkI18bBIbdgI | exclusive |
| Z070 | addon_humanizer_3 | prod_V2XRbewO3qfwsD | price_1URrwu7GRT6LLkI1PqkWVWfs | exclusive |
| Z070 | basic_monthly | prod_V2XQKO3FZiPKZI | price_1URrwp7GRT6LLkI1r676TLRJ | inclusive |
| Z070 | basic_yearly | prod_V2XQKO3FZiPKZI | price_1URrwr7GRT6LLkI1rKTKBpiq | inclusive |
| Z070 | plus_monthly | prod_V2XQrWdt06ryxW | price_1URrwq7GRT6LLkI1FZP82heW | inclusive |
| Z070 | plus_yearly | prod_V2XQrWdt06ryxW | price_1URrws7GRT6LLkI1R5IreqW5 | inclusive |
| Z070 | pro_monthly | prod_V2XRxk7tA14xQr | price_1URrwv7GRT6LLkI1AnI4kOf4 | inclusive |
| Z070 | pro_trial_to_monthly | prod_V2yikGRbHLdZR6 | price_1URrwp7GRT6LLkI19S5H10ZY | unspecified |
| Z070 | pro_yearly | prod_V2XRxk7tA14xQr | price_1URrww7GRT6LLkI18pYd8hUj | inclusive |
| Z070 | study_pass_subscription_30d | prod_VJqvMKubYOnfWS | price_1URrwx7GRT6LLkI1pS0E8O1K | unspecified |
| Z075 | addon_assignment_3 | prod_V2XRyb7uDkhTVD | price_1URrx17GRT6LLkI1qGsoho4M | exclusive |
| Z075 | addon_detection_5 | prod_V2XRzr2BbiKqJA | price_1URrx27GRT6LLkI1UKo28k3Z | exclusive |
| Z075 | addon_humanizer_3 | prod_V2XRbewO3qfwsD | price_1URrx37GRT6LLkI1qXxIcW6F | exclusive |
| Z075 | basic_monthly | prod_V2XQKO3FZiPKZI | price_1URrwy7GRT6LLkI1W7fMPMIq | inclusive |
| Z075 | basic_yearly | prod_V2XQKO3FZiPKZI | price_1URrx07GRT6LLkI1eOfkPVaI | inclusive |
| Z075 | plus_monthly | prod_V2XQrWdt06ryxW | price_1URrwz7GRT6LLkI1MVqkAsNB | inclusive |
| Z075 | plus_yearly | prod_V2XQrWdt06ryxW | price_1URrx17GRT6LLkI1SXqm2Etw | inclusive |
| Z075 | pro_monthly | prod_V2XRxk7tA14xQr | price_1URrx47GRT6LLkI1rBHzQKGl | inclusive |
| Z075 | pro_trial_to_monthly | prod_V2yikGRbHLdZR6 | price_1URrwx7GRT6LLkI1A89n1ZK6 | unspecified |
| Z075 | pro_yearly | prod_V2XRxk7tA14xQr | price_1URrx57GRT6LLkI1caV5pDOc | inclusive |
| Z075 | study_pass_subscription_30d | prod_VJqvMKubYOnfWS | price_1URrx67GRT6LLkI10Kad8Vzz | unspecified |
| Z080 | addon_assignment_3 | prod_V2XRyb7uDkhTVD | price_1URrxA7GRT6LLkI1bx0O8Ek9 | exclusive |
| Z080 | addon_detection_5 | prod_V2XRzr2BbiKqJA | price_1URrxB7GRT6LLkI1dCI4aOGD | exclusive |
| Z080 | addon_humanizer_3 | prod_V2XRbewO3qfwsD | price_1URrxC7GRT6LLkI19GbDgCFe | exclusive |
| Z080 | basic_monthly | prod_V2XQKO3FZiPKZI | price_1URrx77GRT6LLkI1NRRmlFNt | inclusive |
| Z080 | basic_yearly | prod_V2XQKO3FZiPKZI | price_1URrx97GRT6LLkI1NsPOo2v8 | inclusive |
| Z080 | plus_monthly | prod_V2XQrWdt06ryxW | price_1URrx87GRT6LLkI1Jla1Vouy | inclusive |
| Z080 | plus_yearly | prod_V2XQrWdt06ryxW | price_1URrxA7GRT6LLkI1SdUHuYPT | inclusive |
| Z080 | pro_monthly | prod_V2XRxk7tA14xQr | price_1URrxD7GRT6LLkI1DmR7NpKm | inclusive |
| Z080 | pro_trial_to_monthly | prod_V2yikGRbHLdZR6 | price_1URrx77GRT6LLkI1mafUApZJ | unspecified |
| Z080 | pro_yearly | prod_V2XRxk7tA14xQr | price_1URrxE7GRT6LLkI1vFIavRT0 | inclusive |
| Z080 | study_pass_subscription_30d | prod_VJqvMKubYOnfWS | price_1URrxE7GRT6LLkI1RyxG6n63 | unspecified |
| Z085 | addon_assignment_3 | prod_V2XRyb7uDkhTVD | price_1URrxJ7GRT6LLkI13xn18FmF | exclusive |
| Z085 | addon_detection_5 | prod_V2XRzr2BbiKqJA | price_1URrxK7GRT6LLkI10LPECQ60 | exclusive |
| Z085 | addon_humanizer_3 | prod_V2XRbewO3qfwsD | price_1URrxL7GRT6LLkI1GeCCkuz1 | exclusive |
| Z085 | basic_monthly | prod_V2XQKO3FZiPKZI | price_1URrxG7GRT6LLkI17fqO4WGs | inclusive |
| Z085 | basic_yearly | prod_V2XQKO3FZiPKZI | price_1URrxH7GRT6LLkI1LKRrt9mo | inclusive |
| Z085 | plus_monthly | prod_V2XQrWdt06ryxW | price_1URrxH7GRT6LLkI18ADYfbud | inclusive |
| Z085 | plus_yearly | prod_V2XQrWdt06ryxW | price_1URrxI7GRT6LLkI1k0E1SPEh | inclusive |
| Z085 | pro_monthly | prod_V2XRxk7tA14xQr | price_1URrxL7GRT6LLkI1fRbBkuFi | inclusive |
| Z085 | pro_trial_to_monthly | prod_V2yikGRbHLdZR6 | price_1URrxF7GRT6LLkI1M06PQ0da | unspecified |
| Z085 | pro_yearly | prod_V2XRxk7tA14xQr | price_1URrxM7GRT6LLkI1Y3hfwyKe | inclusive |
| Z085 | study_pass_subscription_30d | prod_VJqvMKubYOnfWS | price_1URrxN7GRT6LLkI1QYKbVFWT | unspecified |
| Z090 | addon_assignment_3 | prod_V2XRyb7uDkhTVD | price_1URrxS7GRT6LLkI1SObEYha0 | exclusive |
| Z090 | addon_detection_5 | prod_V2XRzr2BbiKqJA | price_1URrxS7GRT6LLkI1JNYuLJWq | exclusive |
| Z090 | addon_humanizer_3 | prod_V2XRbewO3qfwsD | price_1URrxT7GRT6LLkI1qs9LhcnL | exclusive |
| Z090 | basic_monthly | prod_V2XQKO3FZiPKZI | price_1URrxP7GRT6LLkI1hVTHR9HS | inclusive |
| Z090 | basic_yearly | prod_V2XQKO3FZiPKZI | price_1URrxQ7GRT6LLkI1lTQTgNlG | inclusive |
| Z090 | plus_monthly | prod_V2XQrWdt06ryxW | price_1URrxP7GRT6LLkI1sTAcfne2 | inclusive |
| Z090 | plus_yearly | prod_V2XQrWdt06ryxW | price_1URrxR7GRT6LLkI1vQfmPkot | inclusive |
| Z090 | pro_monthly | prod_V2XRxk7tA14xQr | price_1URrxU7GRT6LLkI1rZp9mzyv | inclusive |
| Z090 | pro_trial_to_monthly | prod_V2yikGRbHLdZR6 | price_1URrxO7GRT6LLkI1WvBTkVYX | unspecified |
| Z090 | pro_yearly | prod_V2XRxk7tA14xQr | price_1URrxV7GRT6LLkI1rm59srS4 | inclusive |
| Z090 | study_pass_subscription_30d | prod_VJqvMKubYOnfWS | price_1URrxW7GRT6LLkI1eJpTawlV | unspecified |
| Z095 | addon_assignment_3 | prod_V2XRyb7uDkhTVD | price_1URrxb7GRT6LLkI1jL4222FD | exclusive |
| Z095 | addon_detection_5 | prod_V2XRzr2BbiKqJA | price_1URrxb7GRT6LLkI1Hi7uJytk | exclusive |
| Z095 | addon_humanizer_3 | prod_V2XRbewO3qfwsD | price_1URrxc7GRT6LLkI1zH2te5Zt | exclusive |
| Z095 | basic_monthly | prod_V2XQKO3FZiPKZI | price_1URrxY7GRT6LLkI1PEGvdg7b | inclusive |
| Z095 | basic_yearly | prod_V2XQKO3FZiPKZI | price_1URrxZ7GRT6LLkI1A4hV4YPe | inclusive |
| Z095 | plus_monthly | prod_V2XQrWdt06ryxW | price_1URrxY7GRT6LLkI1E4QBXglY | inclusive |
| Z095 | plus_yearly | prod_V2XQrWdt06ryxW | price_1URrxa7GRT6LLkI1Q3Zm2eI9 | inclusive |
| Z095 | pro_monthly | prod_V2XRxk7tA14xQr | price_1URrxd7GRT6LLkI1e8nxttmE | inclusive |
| Z095 | pro_trial_to_monthly | prod_V2yikGRbHLdZR6 | price_1URrxW7GRT6LLkI1siP57nsF | unspecified |
| Z095 | pro_yearly | prod_V2XRxk7tA14xQr | price_1URrxe7GRT6LLkI1B8k1gbCS | inclusive |
| Z095 | study_pass_subscription_30d | prod_VJqvMKubYOnfWS | price_1URrxf7GRT6LLkI16qYqZ6uW | unspecified |
| Z100 | addon_assignment_3 | prod_V2XRyb7uDkhTVD | price_1URrxj7GRT6LLkI1orqZ2bIk | exclusive |
| Z100 | addon_detection_5 | prod_V2XRzr2BbiKqJA | price_1URrxk7GRT6LLkI1TKq0anAE | exclusive |
| Z100 | addon_humanizer_3 | prod_V2XRbewO3qfwsD | price_1URrxl7GRT6LLkI1nKRo8ecV | exclusive |
| Z100 | basic_monthly | prod_V2XQKO3FZiPKZI | price_1URrxg7GRT6LLkI13axvSOdG | inclusive |
| Z100 | basic_yearly | prod_V2XQKO3FZiPKZI | price_1URrxi7GRT6LLkI1M4TGGISR | inclusive |
| Z100 | plus_monthly | prod_V2XQrWdt06ryxW | price_1URrxh7GRT6LLkI1xpT3YQxg | inclusive |
| Z100 | plus_yearly | prod_V2XQrWdt06ryxW | price_1URrxi7GRT6LLkI12kwh4f9g | inclusive |
| Z100 | pro_monthly | prod_V2XRxk7tA14xQr | price_1URrxm7GRT6LLkI1PMLgb0EM | inclusive |
| Z100 | pro_trial_to_monthly | prod_V2yikGRbHLdZR6 | price_1URrxf7GRT6LLkI1h7y2ehzl | unspecified |
| Z100 | pro_yearly | prod_V2XRxk7tA14xQr | price_1URrxn7GRT6LLkI1tny3NzO8 | inclusive |
| Z100 | study_pass_subscription_30d | prod_VJqvMKubYOnfWS | price_1URrxn7GRT6LLkI1W3KctsDo | unspecified |
