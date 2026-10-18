# 早期地区版本已归档

2026-10-18，按用户“只保留最终版本”的要求，现有项目沙盒已归档238条早期地区Price：G1+G2 v1共176条，已被替换的地区绑定62条。清理前未发现这些Price被订阅引用。最终187条地区Price全部保持active=true且内容不变；29条原有非本批地区版本Price和21个Product完整不变。金额、业务代码、生产和订阅未修改。Stripe API不支持永久删除Price，本次采用active=false归档，历史记录仍可查询。

审计目录：`outputs/regional-pricing-approved-r8-01a14e97/archive-old-versions/`，包含保留/归档清单、前后快照和verification.json。下方r8创建阶段“旧Price完整不变”是当时的历史事实；本次仅将旧版本active改为false，不改变金额、币种、周期、lookup_key及metadata。

# r8批准尾数：沙盒已配置并回读

2026-10-18 r8。44项金额已按用户批准同步；现有项目沙盒`acct_1SrJ7Q7GRT6LLkI1`全部对象livemode=false。文档先以`480e3604`提交推送后，创建40条替代Price，复用147条，全187条当前目录/1133地区金额已回读一致。配置前已有414条Price完整不变，21个Product完整不变（包括default_price）；当前沙盒共454条Price。目录外既有对象仅保留，不纳入当前187条绑定。

Study Pass全部103组合及参考保持；Pro年价继续等于月价×12。62条前版地区绑定（此前22＋本轮40）在环境JSON中保留替代链。税行为、周期和“1.00倍率区（美国、…）”形式的完整地区描述沿用。业务代码、生产、真实订阅及Schedule未由本任务修改；未做实际Checkout或续费验证。

审计目录：Verla根`outputs/regional-pricing-approved-r8-01a14e97/stripe/`，包含plan、results、verification、配置前后Price/Product快照与Country Spec，不含密钥。全量绑定见[环境JSON](regional-expansion-prices.sandbox.json)，金额与批准来源见[44项批准表](approved-price-rounding.md)。

## r8当前Price绑定

| 区 | SKU | Product ID | Price ID | 税行为 |
| --- | --- | --- | --- | --- |
| Z020 | pro_trial_to_monthly | prod_V2yikGRbHLdZR6 | price_1URrvg7GRT6LLkI1SNUJVu95 | unspecified |
| Z020 | basic_monthly | prod_V2XQKO3FZiPKZI | price_1URrvg7GRT6LLkI1WAN5piJL | inclusive |
| Z020 | plus_monthly | prod_V2XQrWdt06ryxW | price_1URrvh7GRT6LLkI1AQU29tqy | inclusive |
| Z020 | basic_yearly | prod_V2XQKO3FZiPKZI | price_1URrvi7GRT6LLkI1Qnt7aRUr | inclusive |
| Z020 | plus_yearly | prod_V2XQrWdt06ryxW | price_1URrvj7GRT6LLkI1E38NXC8g | inclusive |
| Z020 | addon_assignment_3 | prod_V2XRyb7uDkhTVD | price_1URrvk7GRT6LLkI1uKA4TtpM | exclusive |
| Z020 | addon_detection_5 | prod_V2XRzr2BbiKqJA | price_1URrvk7GRT6LLkI1eleddwzo | exclusive |
| Z020 | addon_humanizer_3 | prod_V2XRbewO3qfwsD | price_1URrvl7GRT6LLkI1g8wSmfnC | exclusive |
| Z020 | pro_monthly | prod_V2XRxk7tA14xQr | price_1URrvm7GRT6LLkI1niFzNvIH | inclusive |
| Z020 | pro_yearly | prod_V2XRxk7tA14xQr | price_1URrvn7GRT6LLkI1c4v281IA | inclusive |
| Z020 | study_pass_subscription_30d | prod_VJqvMKubYOnfWS | price_1URrvn7GRT6LLkI1mwFTDZoB | unspecified |
| Z025 | pro_trial_to_monthly | prod_V2yikGRbHLdZR6 | price_1URtVZ7GRT6LLkI1ukNLCAHY | unspecified |
| Z025 | basic_monthly | prod_V2XQKO3FZiPKZI | price_1URtVa7GRT6LLkI1cY6OB9kR | inclusive |
| Z025 | plus_monthly | prod_V2XQrWdt06ryxW | price_1URrz47GRT6LLkI1tQbgKzv4 | inclusive |
| Z025 | basic_yearly | prod_V2XQKO3FZiPKZI | price_1URrz47GRT6LLkI1nU5MghZR | inclusive |
| Z025 | plus_yearly | prod_V2XQrWdt06ryxW | price_1URrz57GRT6LLkI14Mz7RW5Z | inclusive |
| Z025 | addon_assignment_3 | prod_V2XRyb7uDkhTVD | price_1URtVb7GRT6LLkI1BRbf9Xjq | exclusive |
| Z025 | addon_detection_5 | prod_V2XRzr2BbiKqJA | price_1URtVc7GRT6LLkI1ernnS2ZB | exclusive |
| Z025 | addon_humanizer_3 | prod_V2XRbewO3qfwsD | price_1URtVd7GRT6LLkI18rl41MaO | exclusive |
| Z025 | pro_monthly | prod_V2XRxk7tA14xQr | price_1URrz87GRT6LLkI1Z0psIJcB | inclusive |
| Z025 | pro_yearly | prod_V2XRxk7tA14xQr | price_1URrz97GRT6LLkI1erq4YsQ7 | inclusive |
| Z025 | study_pass_subscription_30d | prod_VJqvMKubYOnfWS | price_1URrzA7GRT6LLkI1zvA84jph | unspecified |
| Z030 | pro_trial_to_monthly | prod_V2yikGRbHLdZR6 | price_1URrvo7GRT6LLkI1wIzQtQ6p | unspecified |
| Z030 | basic_monthly | prod_V2XQKO3FZiPKZI | price_1URrvp7GRT6LLkI1dFau8mnq | inclusive |
| Z030 | plus_monthly | prod_V2XQrWdt06ryxW | price_1URrvq7GRT6LLkI1KAoCwqTm | inclusive |
| Z030 | basic_yearly | prod_V2XQKO3FZiPKZI | price_1URrvr7GRT6LLkI1KbUePdov | inclusive |
| Z030 | plus_yearly | prod_V2XQrWdt06ryxW | price_1URrvs7GRT6LLkI1zTeeYGHe | inclusive |
| Z030 | addon_assignment_3 | prod_V2XRyb7uDkhTVD | price_1URrvs7GRT6LLkI183qwm237 | exclusive |
| Z030 | addon_detection_5 | prod_V2XRzr2BbiKqJA | price_1URrvt7GRT6LLkI1bKn5QaTV | exclusive |
| Z030 | addon_humanizer_3 | prod_V2XRbewO3qfwsD | price_1URrvu7GRT6LLkI16gr5yJkk | exclusive |
| Z030 | pro_monthly | prod_V2XRxk7tA14xQr | price_1URrvv7GRT6LLkI1aZeFjiih | inclusive |
| Z030 | pro_yearly | prod_V2XRxk7tA14xQr | price_1URrvw7GRT6LLkI1gdtjghlG | inclusive |
| Z030 | study_pass_subscription_30d | prod_VJqvMKubYOnfWS | price_1URrvw7GRT6LLkI1LcXuJrZU | unspecified |
| Z035 | pro_trial_to_monthly | prod_V2yikGRbHLdZR6 | price_1URrvx7GRT6LLkI1sm1znOvE | unspecified |
| Z035 | basic_monthly | prod_V2XQKO3FZiPKZI | price_1URrvy7GRT6LLkI1HBrUdByu | inclusive |
| Z035 | plus_monthly | prod_V2XQrWdt06ryxW | price_1URrvz7GRT6LLkI1cVGH5bWk | inclusive |
| Z035 | basic_yearly | prod_V2XQKO3FZiPKZI | price_1URrw07GRT6LLkI14dCNzXkF | inclusive |
| Z035 | plus_yearly | prod_V2XQrWdt06ryxW | price_1URrw17GRT6LLkI1k4dW83H3 | inclusive |
| Z035 | addon_assignment_3 | prod_V2XRyb7uDkhTVD | price_1URrw17GRT6LLkI1eyTE1Gsc | exclusive |
| Z035 | addon_detection_5 | prod_V2XRzr2BbiKqJA | price_1URrw27GRT6LLkI14bZPFJBu | exclusive |
| Z035 | addon_humanizer_3 | prod_V2XRbewO3qfwsD | price_1URrw37GRT6LLkI1rnX3diXu | exclusive |
| Z035 | pro_monthly | prod_V2XRxk7tA14xQr | price_1URrw47GRT6LLkI10CnP0N7y | inclusive |
| Z035 | pro_yearly | prod_V2XRxk7tA14xQr | price_1URrw57GRT6LLkI16eAqZ89n | inclusive |
| Z035 | study_pass_subscription_30d | prod_VJqvMKubYOnfWS | price_1URrw57GRT6LLkI1lyNekvzw | unspecified |
| Z040 | pro_trial_to_monthly | prod_V2yikGRbHLdZR6 | price_1URtVe7GRT6LLkI1Hxvmq5OI | unspecified |
| Z040 | basic_monthly | prod_V2XQKO3FZiPKZI | price_1URtVf7GRT6LLkI1A21U94Y8 | inclusive |
| Z040 | plus_monthly | prod_V2XQrWdt06ryxW | price_1URtVg7GRT6LLkI10r8t0cNk | inclusive |
| Z040 | basic_yearly | prod_V2XQKO3FZiPKZI | price_1URtVh7GRT6LLkI1yO0RNor0 | inclusive |
| Z040 | plus_yearly | prod_V2XQrWdt06ryxW | price_1URtVi7GRT6LLkI1gegv9hqs | inclusive |
| Z040 | addon_assignment_3 | prod_V2XRyb7uDkhTVD | price_1URtVj7GRT6LLkI1EsF2nGs3 | exclusive |
| Z040 | addon_detection_5 | prod_V2XRzr2BbiKqJA | price_1URtVk7GRT6LLkI1Lw6WAtYL | exclusive |
| Z040 | addon_humanizer_3 | prod_V2XRbewO3qfwsD | price_1URtVl7GRT6LLkI1OvvJp2sU | exclusive |
| Z040 | pro_monthly | prod_V2XRxk7tA14xQr | price_1URtVn7GRT6LLkI1P69dKRSD | inclusive |
| Z040 | pro_yearly | prod_V2XRxk7tA14xQr | price_1URtVo7GRT6LLkI1PLxl0Wkp | inclusive |
| Z040 | study_pass_subscription_30d | prod_VJqvMKubYOnfWS | price_1URrwE7GRT6LLkI1heehqCaf | unspecified |
| Z045 | pro_trial_to_monthly | prod_V2yikGRbHLdZR6 | price_1URrwF7GRT6LLkI1zniti7j7 | unspecified |
| Z045 | basic_monthly | prod_V2XQKO3FZiPKZI | price_1URrwG7GRT6LLkI17N1lP4hk | inclusive |
| Z045 | plus_monthly | prod_V2XQrWdt06ryxW | price_1URrwH7GRT6LLkI1xinyBy0v | inclusive |
| Z045 | basic_yearly | prod_V2XQKO3FZiPKZI | price_1URrwH7GRT6LLkI1bJ82CGyF | inclusive |
| Z045 | plus_yearly | prod_V2XQrWdt06ryxW | price_1URrwI7GRT6LLkI1iZE9n46F | inclusive |
| Z045 | addon_assignment_3 | prod_V2XRyb7uDkhTVD | price_1URrwJ7GRT6LLkI1FFIPT3q2 | exclusive |
| Z045 | addon_detection_5 | prod_V2XRzr2BbiKqJA | price_1URrwK7GRT6LLkI1LQJ1gKUx | exclusive |
| Z045 | addon_humanizer_3 | prod_V2XRbewO3qfwsD | price_1URrwL7GRT6LLkI1DatGrDf0 | exclusive |
| Z045 | pro_monthly | prod_V2XRxk7tA14xQr | price_1URrwM7GRT6LLkI1Wt4KJ3MK | inclusive |
| Z045 | pro_yearly | prod_V2XRxk7tA14xQr | price_1URrwN7GRT6LLkI17oC39eD6 | inclusive |
| Z045 | study_pass_subscription_30d | prod_VJqvMKubYOnfWS | price_1URrwN7GRT6LLkI1DHqFrf2T | unspecified |
| Z050 | pro_trial_to_monthly | prod_V2yikGRbHLdZR6 | price_1URsKX7GRT6LLkI11WI6r22n | unspecified |
| Z050 | basic_monthly | prod_V2XQKO3FZiPKZI | price_1URsKY7GRT6LLkI1AJka3B5H | inclusive |
| Z050 | plus_monthly | prod_V2XQrWdt06ryxW | price_1URsKZ7GRT6LLkI1qjnFczml | inclusive |
| Z050 | basic_yearly | prod_V2XQKO3FZiPKZI | price_1URsKa7GRT6LLkI15EQKyjSY | inclusive |
| Z050 | plus_yearly | prod_V2XQrWdt06ryxW | price_1URsKb7GRT6LLkI1xJkFHUj7 | inclusive |
| Z050 | addon_assignment_3 | prod_V2XRyb7uDkhTVD | price_1URsKb7GRT6LLkI1LltUASkL | exclusive |
| Z050 | addon_detection_5 | prod_V2XRzr2BbiKqJA | price_1URsKc7GRT6LLkI1GtqawNoF | exclusive |
| Z050 | addon_humanizer_3 | prod_V2XRbewO3qfwsD | price_1URsKd7GRT6LLkI19AdL2PHz | exclusive |
| Z050 | pro_monthly | prod_V2XRxk7tA14xQr | price_1URsKe7GRT6LLkI1GM335bxO | inclusive |
| Z050 | pro_yearly | prod_V2XRxk7tA14xQr | price_1URsKf7GRT6LLkI12mPynyvD | inclusive |
| Z050 | study_pass_subscription_30d | prod_VJqvMKubYOnfWS | price_1URsKf7GRT6LLkI1xqlbMZZN | unspecified |
| Z055 | pro_trial_to_monthly | prod_V2yikGRbHLdZR6 | price_1URsKg7GRT6LLkI1vPBL3VUg | unspecified |
| Z055 | basic_monthly | prod_V2XQKO3FZiPKZI | price_1URsKh7GRT6LLkI1juSuTrnY | inclusive |
| Z055 | plus_monthly | prod_V2XQrWdt06ryxW | price_1URsKi7GRT6LLkI1QqkwZLQb | inclusive |
| Z055 | basic_yearly | prod_V2XQKO3FZiPKZI | price_1URsKj7GRT6LLkI1pAKVfYnq | inclusive |
| Z055 | plus_yearly | prod_V2XQrWdt06ryxW | price_1URsKj7GRT6LLkI1397t566S | inclusive |
| Z055 | addon_assignment_3 | prod_V2XRyb7uDkhTVD | price_1URsKk7GRT6LLkI1n4cjnZ8q | exclusive |
| Z055 | addon_detection_5 | prod_V2XRzr2BbiKqJA | price_1URsKl7GRT6LLkI1WnPsxDCA | exclusive |
| Z055 | addon_humanizer_3 | prod_V2XRbewO3qfwsD | price_1URsKm7GRT6LLkI1csLLRoV0 | exclusive |
| Z055 | pro_monthly | prod_V2XRxk7tA14xQr | price_1URsKn7GRT6LLkI14o3ccC2S | inclusive |
| Z055 | pro_yearly | prod_V2XRxk7tA14xQr | price_1URsKn7GRT6LLkI15XzmpbYm | inclusive |
| Z055 | study_pass_subscription_30d | prod_VJqvMKubYOnfWS | price_1URsKo7GRT6LLkI13JjLuc2g | unspecified |
| Z060 | pro_trial_to_monthly | prod_V2yikGRbHLdZR6 | price_1URrwX7GRT6LLkI17pon6WBl | unspecified |
| Z060 | basic_monthly | prod_V2XQKO3FZiPKZI | price_1URrwY7GRT6LLkI15RdVWR5Q | inclusive |
| Z060 | plus_monthly | prod_V2XQrWdt06ryxW | price_1URrwZ7GRT6LLkI11tfCyoqM | inclusive |
| Z060 | basic_yearly | prod_V2XQKO3FZiPKZI | price_1URrwZ7GRT6LLkI1CUnllfC0 | inclusive |
| Z060 | plus_yearly | prod_V2XQrWdt06ryxW | price_1URrwa7GRT6LLkI1PG3Lap7L | inclusive |
| Z060 | addon_assignment_3 | prod_V2XRyb7uDkhTVD | price_1URrwb7GRT6LLkI1xaJ6TDiv | exclusive |
| Z060 | addon_detection_5 | prod_V2XRzr2BbiKqJA | price_1URrwc7GRT6LLkI1JEqMTYlB | exclusive |
| Z060 | addon_humanizer_3 | prod_V2XRbewO3qfwsD | price_1URrwd7GRT6LLkI1twi0QrK4 | exclusive |
| Z060 | pro_monthly | prod_V2XRxk7tA14xQr | price_1URrwd7GRT6LLkI1PzAAQgv0 | inclusive |
| Z060 | pro_yearly | prod_V2XRxk7tA14xQr | price_1URrwe7GRT6LLkI1s560672r | inclusive |
| Z060 | study_pass_subscription_30d | prod_VJqvMKubYOnfWS | price_1URrwf7GRT6LLkI17voozvv7 | unspecified |
| Z065 | pro_trial_to_monthly | prod_V2yikGRbHLdZR6 | price_1URtVp7GRT6LLkI1nW6eTejg | unspecified |
| Z065 | basic_monthly | prod_V2XQKO3FZiPKZI | price_1URsKu7GRT6LLkI1oaNsbVRW | inclusive |
| Z065 | plus_monthly | prod_V2XQrWdt06ryxW | price_1URsKv7GRT6LLkI1mkLMl242 | inclusive |
| Z065 | basic_yearly | prod_V2XQKO3FZiPKZI | price_1URtVq7GRT6LLkI19bBwblJ1 | inclusive |
| Z065 | plus_yearly | prod_V2XQrWdt06ryxW | price_1URtVr7GRT6LLkI1srKukIEA | inclusive |
| Z065 | addon_assignment_3 | prod_V2XRyb7uDkhTVD | price_1URsKx7GRT6LLkI11BSEqe4n | exclusive |
| Z065 | addon_detection_5 | prod_V2XRzr2BbiKqJA | price_1URtVs7GRT6LLkI15fy1Wub7 | exclusive |
| Z065 | addon_humanizer_3 | prod_V2XRbewO3qfwsD | price_1URtVt7GRT6LLkI1FW7Q01hP | exclusive |
| Z065 | pro_monthly | prod_V2XRxk7tA14xQr | price_1URsKz7GRT6LLkI1sEILJd6a | inclusive |
| Z065 | pro_yearly | prod_V2XRxk7tA14xQr | price_1URsL07GRT6LLkI1qU6NMcSm | inclusive |
| Z065 | study_pass_subscription_30d | prod_VJqvMKubYOnfWS | price_1URsL17GRT6LLkI1Km8DfzTG | unspecified |
| Z070 | pro_trial_to_monthly | prod_V2yikGRbHLdZR6 | price_1URrwp7GRT6LLkI19S5H10ZY | unspecified |
| Z070 | basic_monthly | prod_V2XQKO3FZiPKZI | price_1URrwp7GRT6LLkI1r676TLRJ | inclusive |
| Z070 | plus_monthly | prod_V2XQrWdt06ryxW | price_1URrwq7GRT6LLkI1FZP82heW | inclusive |
| Z070 | basic_yearly | prod_V2XQKO3FZiPKZI | price_1URrwr7GRT6LLkI1rKTKBpiq | inclusive |
| Z070 | plus_yearly | prod_V2XQrWdt06ryxW | price_1URrws7GRT6LLkI1R5IreqW5 | inclusive |
| Z070 | addon_assignment_3 | prod_V2XRyb7uDkhTVD | price_1URrwt7GRT6LLkI1IwuxE3K3 | exclusive |
| Z070 | addon_detection_5 | prod_V2XRzr2BbiKqJA | price_1URrwt7GRT6LLkI18bBIbdgI | exclusive |
| Z070 | addon_humanizer_3 | prod_V2XRbewO3qfwsD | price_1URrwu7GRT6LLkI1PqkWVWfs | exclusive |
| Z070 | pro_monthly | prod_V2XRxk7tA14xQr | price_1URrwv7GRT6LLkI1AnI4kOf4 | inclusive |
| Z070 | pro_yearly | prod_V2XRxk7tA14xQr | price_1URrww7GRT6LLkI18pYd8hUj | inclusive |
| Z070 | study_pass_subscription_30d | prod_VJqvMKubYOnfWS | price_1URrwx7GRT6LLkI1pS0E8O1K | unspecified |
| Z075 | pro_trial_to_monthly | prod_V2yikGRbHLdZR6 | price_1URrwx7GRT6LLkI1A89n1ZK6 | unspecified |
| Z075 | basic_monthly | prod_V2XQKO3FZiPKZI | price_1URrwy7GRT6LLkI1W7fMPMIq | inclusive |
| Z075 | plus_monthly | prod_V2XQrWdt06ryxW | price_1URrwz7GRT6LLkI1MVqkAsNB | inclusive |
| Z075 | basic_yearly | prod_V2XQKO3FZiPKZI | price_1URrx07GRT6LLkI1eOfkPVaI | inclusive |
| Z075 | plus_yearly | prod_V2XQrWdt06ryxW | price_1URrx17GRT6LLkI1SXqm2Etw | inclusive |
| Z075 | addon_assignment_3 | prod_V2XRyb7uDkhTVD | price_1URrx17GRT6LLkI1qGsoho4M | exclusive |
| Z075 | addon_detection_5 | prod_V2XRzr2BbiKqJA | price_1URrx27GRT6LLkI1UKo28k3Z | exclusive |
| Z075 | addon_humanizer_3 | prod_V2XRbewO3qfwsD | price_1URrx37GRT6LLkI1qXxIcW6F | exclusive |
| Z075 | pro_monthly | prod_V2XRxk7tA14xQr | price_1URrx47GRT6LLkI1rBHzQKGl | inclusive |
| Z075 | pro_yearly | prod_V2XRxk7tA14xQr | price_1URrx57GRT6LLkI1caV5pDOc | inclusive |
| Z075 | study_pass_subscription_30d | prod_VJqvMKubYOnfWS | price_1URrx67GRT6LLkI10Kad8Vzz | unspecified |
| Z080 | pro_trial_to_monthly | prod_V2yikGRbHLdZR6 | price_1URrx77GRT6LLkI1mafUApZJ | unspecified |
| Z080 | basic_monthly | prod_V2XQKO3FZiPKZI | price_1URrx77GRT6LLkI1NRRmlFNt | inclusive |
| Z080 | plus_monthly | prod_V2XQrWdt06ryxW | price_1URrx87GRT6LLkI1Jla1Vouy | inclusive |
| Z080 | basic_yearly | prod_V2XQKO3FZiPKZI | price_1URrx97GRT6LLkI1NsPOo2v8 | inclusive |
| Z080 | plus_yearly | prod_V2XQrWdt06ryxW | price_1URrxA7GRT6LLkI1SdUHuYPT | inclusive |
| Z080 | addon_assignment_3 | prod_V2XRyb7uDkhTVD | price_1URrxA7GRT6LLkI1bx0O8Ek9 | exclusive |
| Z080 | addon_detection_5 | prod_V2XRzr2BbiKqJA | price_1URrxB7GRT6LLkI1dCI4aOGD | exclusive |
| Z080 | addon_humanizer_3 | prod_V2XRbewO3qfwsD | price_1URrxC7GRT6LLkI19GbDgCFe | exclusive |
| Z080 | pro_monthly | prod_V2XRxk7tA14xQr | price_1URrxD7GRT6LLkI1DmR7NpKm | inclusive |
| Z080 | pro_yearly | prod_V2XRxk7tA14xQr | price_1URrxE7GRT6LLkI1vFIavRT0 | inclusive |
| Z080 | study_pass_subscription_30d | prod_VJqvMKubYOnfWS | price_1URrxE7GRT6LLkI1RyxG6n63 | unspecified |
| Z085 | pro_trial_to_monthly | prod_V2yikGRbHLdZR6 | price_1URtVu7GRT6LLkI1v2dcFCMW | unspecified |
| Z085 | basic_monthly | prod_V2XQKO3FZiPKZI | price_1URtVv7GRT6LLkI1xcn7ZvMJ | inclusive |
| Z085 | plus_monthly | prod_V2XQrWdt06ryxW | price_1URtVw7GRT6LLkI1gCrd6WBz | inclusive |
| Z085 | basic_yearly | prod_V2XQKO3FZiPKZI | price_1URtVx7GRT6LLkI1PmiEQYKd | inclusive |
| Z085 | plus_yearly | prod_V2XQrWdt06ryxW | price_1URtVy7GRT6LLkI1pOy9n3BZ | inclusive |
| Z085 | addon_assignment_3 | prod_V2XRyb7uDkhTVD | price_1URtVz7GRT6LLkI1XxgkNEYd | exclusive |
| Z085 | addon_detection_5 | prod_V2XRzr2BbiKqJA | price_1URtW07GRT6LLkI1KXhSeSFw | exclusive |
| Z085 | addon_humanizer_3 | prod_V2XRbewO3qfwsD | price_1URtW17GRT6LLkI1L54bUHC7 | exclusive |
| Z085 | pro_monthly | prod_V2XRxk7tA14xQr | price_1URtW27GRT6LLkI1h0DJAY8G | inclusive |
| Z085 | pro_yearly | prod_V2XRxk7tA14xQr | price_1URtW37GRT6LLkI1Rumqp3BS | inclusive |
| Z085 | study_pass_subscription_30d | prod_VJqvMKubYOnfWS | price_1URrxN7GRT6LLkI1QYKbVFWT | unspecified |
| Z090 | pro_trial_to_monthly | prod_V2yikGRbHLdZR6 | price_1URrxO7GRT6LLkI1WvBTkVYX | unspecified |
| Z090 | basic_monthly | prod_V2XQKO3FZiPKZI | price_1URrxP7GRT6LLkI1hVTHR9HS | inclusive |
| Z090 | plus_monthly | prod_V2XQrWdt06ryxW | price_1URrxP7GRT6LLkI1sTAcfne2 | inclusive |
| Z090 | basic_yearly | prod_V2XQKO3FZiPKZI | price_1URrxQ7GRT6LLkI1lTQTgNlG | inclusive |
| Z090 | plus_yearly | prod_V2XQrWdt06ryxW | price_1URrxR7GRT6LLkI1vQfmPkot | inclusive |
| Z090 | addon_assignment_3 | prod_V2XRyb7uDkhTVD | price_1URrxS7GRT6LLkI1SObEYha0 | exclusive |
| Z090 | addon_detection_5 | prod_V2XRzr2BbiKqJA | price_1URrxS7GRT6LLkI1JNYuLJWq | exclusive |
| Z090 | addon_humanizer_3 | prod_V2XRbewO3qfwsD | price_1URrxT7GRT6LLkI1qs9LhcnL | exclusive |
| Z090 | pro_monthly | prod_V2XRxk7tA14xQr | price_1URrxU7GRT6LLkI1rZp9mzyv | inclusive |
| Z090 | pro_yearly | prod_V2XRxk7tA14xQr | price_1URrxV7GRT6LLkI1rm59srS4 | inclusive |
| Z090 | study_pass_subscription_30d | prod_VJqvMKubYOnfWS | price_1URrxW7GRT6LLkI1eJpTawlV | unspecified |
| Z095 | pro_trial_to_monthly | prod_V2yikGRbHLdZR6 | price_1URrxW7GRT6LLkI1siP57nsF | unspecified |
| Z095 | basic_monthly | prod_V2XQKO3FZiPKZI | price_1URrxY7GRT6LLkI1PEGvdg7b | inclusive |
| Z095 | plus_monthly | prod_V2XQrWdt06ryxW | price_1URrxY7GRT6LLkI1E4QBXglY | inclusive |
| Z095 | basic_yearly | prod_V2XQKO3FZiPKZI | price_1URrxZ7GRT6LLkI1A4hV4YPe | inclusive |
| Z095 | plus_yearly | prod_V2XQrWdt06ryxW | price_1URrxa7GRT6LLkI1Q3Zm2eI9 | inclusive |
| Z095 | addon_assignment_3 | prod_V2XRyb7uDkhTVD | price_1URrxb7GRT6LLkI1jL4222FD | exclusive |
| Z095 | addon_detection_5 | prod_V2XRzr2BbiKqJA | price_1URrxb7GRT6LLkI1Hi7uJytk | exclusive |
| Z095 | addon_humanizer_3 | prod_V2XRbewO3qfwsD | price_1URrxc7GRT6LLkI1zH2te5Zt | exclusive |
| Z095 | pro_monthly | prod_V2XRxk7tA14xQr | price_1URrxd7GRT6LLkI1e8nxttmE | inclusive |
| Z095 | pro_yearly | prod_V2XRxk7tA14xQr | price_1URrxe7GRT6LLkI1B8k1gbCS | inclusive |
| Z095 | study_pass_subscription_30d | prod_VJqvMKubYOnfWS | price_1URrxf7GRT6LLkI16qYqZ6uW | unspecified |
| Z100 | pro_trial_to_monthly | prod_V2yikGRbHLdZR6 | price_1URtW47GRT6LLkI1T2L7ElnN | unspecified |
| Z100 | basic_monthly | prod_V2XQKO3FZiPKZI | price_1URtW57GRT6LLkI1yQR8PElp | inclusive |
| Z100 | plus_monthly | prod_V2XQrWdt06ryxW | price_1URtW77GRT6LLkI1dZERBRXT | inclusive |
| Z100 | basic_yearly | prod_V2XQKO3FZiPKZI | price_1URtW87GRT6LLkI1nSjixnt7 | inclusive |
| Z100 | plus_yearly | prod_V2XQrWdt06ryxW | price_1URtW97GRT6LLkI1x7J7ine0 | inclusive |
| Z100 | addon_assignment_3 | prod_V2XRyb7uDkhTVD | price_1URtWA7GRT6LLkI1z1ZH8kXu | exclusive |
| Z100 | addon_detection_5 | prod_V2XRzr2BbiKqJA | price_1URtWB7GRT6LLkI14Vet3GgH | exclusive |
| Z100 | addon_humanizer_3 | prod_V2XRbewO3qfwsD | price_1URtWC7GRT6LLkI1r9zcZ1NF | exclusive |
| Z100 | pro_monthly | prod_V2XRxk7tA14xQr | price_1URtWD7GRT6LLkI1y5N9CMgw | inclusive |
| Z100 | pro_yearly | prod_V2XRxk7tA14xQr | price_1URtWF7GRT6LLkI12Xx5NRzO | inclusive |
| Z100 | study_pass_subscription_30d | prod_VJqvMKubYOnfWS | price_1URrxn7GRT6LLkI1W3KctsDo | unspecified |

# r7历史配置记录


2026-10-18 r7。现有项目沙盒LASTMILE LIMITED，`acct_1SrJ7Q7GRT6LLkI1`，本次所有对象均为`livemode=false`。已按用户要求并入第三组51地区；第一10＋第二53＋第三51＝114地区，全部使用新金额，Pro只用新Pro列。五地USD替代获当前会话明确确认，完整金额见[currency-replacements.md](currency-replacements.md)。

## 已完成与证据

| 检查 | 实际结果 |
| --- | --- |
| 当前价格目录 | 17区、86销售币种、103区币种组合、11SKU、187条当前绑定Price、1133个地区金额 |
| 本轮新建 | 33条：新增Z050的11条；Z055／Z065各11条替换绑定 |
| 复用与扩充 | 154条既有地区Price继续作为当前绑定；99条既有Price更新新增币种option及／或完整地区描述 |
| USD默认值 | Z020／Z025／Z035／Z050／Z055／Z065／Z100实际销售USD采用各区表价；其余区仅技术默认，不用于销售回退 |
| 保留既有金额 | 本轮配置前205条Price的既有金额／币种option、周期、税、active、lookup、metadata等核心字段全部保持；只增option或更新本批地区Price描述 |
| 原始旧价格 | 最初29条Price完整不变；本轮被替换绑定的22条也完整保留、active不变 |
| Product | 复用8个；已有21个Product对象完整不变，包括default_price；未创建Product |
| 全量回读 | 当前187条Price逐条读取，1133地区金额、USD默认、Product、周期及各币种税行为一致 |
| 当前沙盒Price总数 | 238＝原始29＋此前176＋本轮33；其中当前目录使用187，另22条前版地区Price保留 |
| 应用与生产 | zonePricingEnabled=false、enabledCountries为空；未改业务代码、生产、真实订阅或Schedule |

Z055原Price默认USD按技术参考，现新增波多黎各实际USD销售金额；Z065同理新增已确认的科威特USD销售金额。Stripe默认单价不可改，因此新建对应Price，不改旧Price或转移其lookup_key。当前187条绑定完整写入[环境JSON](regional-expansion-prices.sandbox.json)，旧22条与替代关系保留于其中`supersededSandboxPriceBindings`，供核对和历史恢复使用；不作为销售回退。

本轮价格文档先以`cec83e69`提交并推送至后端feat/billing-brazil-pricing，再执行沙盒配置。审计位于Verla根`outputs/regional-sku-20261018-01a14a9a/stripe-regional-r7-sandbox/`：plan、results、verification、配置前后Price／Product快照与Country Spec。记录不含密钥或客户付款信息。此前r6配置证据仍在`stripe-regional-r5-sandbox/`，其176条创建数量是历史事实。

新增CLP／XAF／XOF使用零小数charge单位；UGX主单位整数而API兼容乘100，末两位00；HUF收费按两位最小单位。[Stripe币种及最低额规则](https://docs.stripe.com/currencies)。最低扣款依赖实际结算币种，Price保存与回读不等于真实扣款通过。

## 尚未执行

- 实际Checkout首付、Trial转正、优惠后非零最低金额及自然月续费验证。
- 业务目录读取与发布、生产历史alias／线上已购Pro资格盘点、下期Schedule换价与订阅迁移。
- 生产Price创建、生产部署或任何生产订阅修改。

## 沿用的Stripe价格描述

同区11个SKU使用“固定两位倍率＋倍率区（完整国家／地区名单）”。例如0.90倍率区（加拿大、新加坡）。

| 区 | Dashboard价格描述 |
| --- | --- |
| Z020 | 0.20倍率区（印度、巴基斯坦、埃及、尼日利亚、孟加拉国、埃塞俄比亚、马拉维、津巴布韦、巴勒斯坦、卢旺达、坦桑尼亚、乌干达、委内瑞拉、赞比亚、阿富汗、喀麦隆、老挝、利比里亚、缅甸、尼泊尔、乌兹别克斯坦） |
| Z025 | 0.25倍率区（越南、印度尼西亚、肯尼亚、突尼斯、加纳、乌克兰、玻利维亚、斯里兰卡、柬埔寨、科特迪瓦） |
| Z030 | 0.30倍率区（菲律宾、阿尔及利亚、摩洛哥、纳米比亚、阿塞拜疆、泰国、洪都拉斯、蒙古） |
| Z035 | 0.35倍率区（马来西亚、哥伦比亚、南非、厄瓜多尔、博茨瓦纳、危地马拉、伊拉克、约旦、萨尔瓦多、加蓬、格鲁吉亚、黎巴嫩、摩尔多瓦） |
| Z040 | 0.40倍率区（巴西、阿根廷、土耳其、牙买加、秘鲁、伯利兹、阿尔巴尼亚、哈萨克斯坦、黑山、巴布亚新几内亚、塞尔维亚） |
| Z045 | 0.45倍率区（墨西哥、中国大陆、罗马尼亚、圣卢西亚、智利、马尔代夫） |
| Z050 | 0.50倍率区（巴拿马、哥斯达黎加、克罗地亚、匈牙利、阿曼、波兰、塞舌尔、特立尼达和多巴哥） |
| Z055 | 0.55倍率区（圭亚那、希腊、巴巴多斯、文莱、拉脱维亚、波多黎各、斯洛伐克） |
| Z060 | 0.60倍率区（斯洛文尼亚、沙特阿拉伯、乌拉圭） |
| Z065 | 0.65倍率区（中国台湾、韩国、日本、西班牙、科威特） |
| Z070 | 0.70倍率区（意大利） |
| Z075 | 0.75倍率区（阿联酋、法国） |
| Z080 | 0.80倍率区（中国香港、德国、中国澳门、奥地利、比利时、芬兰） |
| Z085 | 0.85倍率区（英国、新西兰、荷兰、巴哈马、瑞典） |
| Z090 | 0.90倍率区（加拿大、新加坡） |
| Z095 | 0.95倍率区（澳大利亚、卢森堡） |
| Z100 | 1.00倍率区（美国、爱尔兰、百慕大、挪威） |

## r7历史Price绑定（已由上方r8表取代）

| 区 | SKU | Product ID | Price ID | 税行为 |
| --- | --- | --- | --- | --- |
| Z020 | pro_trial_to_monthly | prod_V2yikGRbHLdZR6 | price_1URrvg7GRT6LLkI1SNUJVu95 | unspecified |
| Z020 | basic_monthly | prod_V2XQKO3FZiPKZI | price_1URrvg7GRT6LLkI1WAN5piJL | inclusive |
| Z020 | plus_monthly | prod_V2XQrWdt06ryxW | price_1URrvh7GRT6LLkI1AQU29tqy | inclusive |
| Z020 | basic_yearly | prod_V2XQKO3FZiPKZI | price_1URrvi7GRT6LLkI1Qnt7aRUr | inclusive |
| Z020 | plus_yearly | prod_V2XQrWdt06ryxW | price_1URrvj7GRT6LLkI1E38NXC8g | inclusive |
| Z020 | addon_assignment_3 | prod_V2XRyb7uDkhTVD | price_1URrvk7GRT6LLkI1uKA4TtpM | exclusive |
| Z020 | addon_detection_5 | prod_V2XRzr2BbiKqJA | price_1URrvk7GRT6LLkI1eleddwzo | exclusive |
| Z020 | addon_humanizer_3 | prod_V2XRbewO3qfwsD | price_1URrvl7GRT6LLkI1g8wSmfnC | exclusive |
| Z020 | pro_monthly | prod_V2XRxk7tA14xQr | price_1URrvm7GRT6LLkI1niFzNvIH | inclusive |
| Z020 | pro_yearly | prod_V2XRxk7tA14xQr | price_1URrvn7GRT6LLkI1c4v281IA | inclusive |
| Z020 | study_pass_subscription_30d | prod_VJqvMKubYOnfWS | price_1URrvn7GRT6LLkI1mwFTDZoB | unspecified |
| Z025 | pro_trial_to_monthly | prod_V2yikGRbHLdZR6 | price_1URrz27GRT6LLkI1BYoiUcnL | unspecified |
| Z025 | basic_monthly | prod_V2XQKO3FZiPKZI | price_1URrz37GRT6LLkI1lQ466lhO | inclusive |
| Z025 | plus_monthly | prod_V2XQrWdt06ryxW | price_1URrz47GRT6LLkI1tQbgKzv4 | inclusive |
| Z025 | basic_yearly | prod_V2XQKO3FZiPKZI | price_1URrz47GRT6LLkI1nU5MghZR | inclusive |
| Z025 | plus_yearly | prod_V2XQrWdt06ryxW | price_1URrz57GRT6LLkI14Mz7RW5Z | inclusive |
| Z025 | addon_assignment_3 | prod_V2XRyb7uDkhTVD | price_1URrz67GRT6LLkI1XjdiqtqS | exclusive |
| Z025 | addon_detection_5 | prod_V2XRzr2BbiKqJA | price_1URrz77GRT6LLkI1dH2tbgA9 | exclusive |
| Z025 | addon_humanizer_3 | prod_V2XRbewO3qfwsD | price_1URrz87GRT6LLkI17fXYE7oL | exclusive |
| Z025 | pro_monthly | prod_V2XRxk7tA14xQr | price_1URrz87GRT6LLkI1Z0psIJcB | inclusive |
| Z025 | pro_yearly | prod_V2XRxk7tA14xQr | price_1URrz97GRT6LLkI1erq4YsQ7 | inclusive |
| Z025 | study_pass_subscription_30d | prod_VJqvMKubYOnfWS | price_1URrzA7GRT6LLkI1zvA84jph | unspecified |
| Z030 | pro_trial_to_monthly | prod_V2yikGRbHLdZR6 | price_1URrvo7GRT6LLkI1wIzQtQ6p | unspecified |
| Z030 | basic_monthly | prod_V2XQKO3FZiPKZI | price_1URrvp7GRT6LLkI1dFau8mnq | inclusive |
| Z030 | plus_monthly | prod_V2XQrWdt06ryxW | price_1URrvq7GRT6LLkI1KAoCwqTm | inclusive |
| Z030 | basic_yearly | prod_V2XQKO3FZiPKZI | price_1URrvr7GRT6LLkI1KbUePdov | inclusive |
| Z030 | plus_yearly | prod_V2XQrWdt06ryxW | price_1URrvs7GRT6LLkI1zTeeYGHe | inclusive |
| Z030 | addon_assignment_3 | prod_V2XRyb7uDkhTVD | price_1URrvs7GRT6LLkI183qwm237 | exclusive |
| Z030 | addon_detection_5 | prod_V2XRzr2BbiKqJA | price_1URrvt7GRT6LLkI1bKn5QaTV | exclusive |
| Z030 | addon_humanizer_3 | prod_V2XRbewO3qfwsD | price_1URrvu7GRT6LLkI16gr5yJkk | exclusive |
| Z030 | pro_monthly | prod_V2XRxk7tA14xQr | price_1URrvv7GRT6LLkI1aZeFjiih | inclusive |
| Z030 | pro_yearly | prod_V2XRxk7tA14xQr | price_1URrvw7GRT6LLkI1gdtjghlG | inclusive |
| Z030 | study_pass_subscription_30d | prod_VJqvMKubYOnfWS | price_1URrvw7GRT6LLkI1LcXuJrZU | unspecified |
| Z035 | pro_trial_to_monthly | prod_V2yikGRbHLdZR6 | price_1URrvx7GRT6LLkI1sm1znOvE | unspecified |
| Z035 | basic_monthly | prod_V2XQKO3FZiPKZI | price_1URrvy7GRT6LLkI1HBrUdByu | inclusive |
| Z035 | plus_monthly | prod_V2XQrWdt06ryxW | price_1URrvz7GRT6LLkI1cVGH5bWk | inclusive |
| Z035 | basic_yearly | prod_V2XQKO3FZiPKZI | price_1URrw07GRT6LLkI14dCNzXkF | inclusive |
| Z035 | plus_yearly | prod_V2XQrWdt06ryxW | price_1URrw17GRT6LLkI1k4dW83H3 | inclusive |
| Z035 | addon_assignment_3 | prod_V2XRyb7uDkhTVD | price_1URrw17GRT6LLkI1eyTE1Gsc | exclusive |
| Z035 | addon_detection_5 | prod_V2XRzr2BbiKqJA | price_1URrw27GRT6LLkI14bZPFJBu | exclusive |
| Z035 | addon_humanizer_3 | prod_V2XRbewO3qfwsD | price_1URrw37GRT6LLkI1rnX3diXu | exclusive |
| Z035 | pro_monthly | prod_V2XRxk7tA14xQr | price_1URrw47GRT6LLkI10CnP0N7y | inclusive |
| Z035 | pro_yearly | prod_V2XRxk7tA14xQr | price_1URrw57GRT6LLkI16eAqZ89n | inclusive |
| Z035 | study_pass_subscription_30d | prod_VJqvMKubYOnfWS | price_1URrw57GRT6LLkI1lyNekvzw | unspecified |
| Z040 | pro_trial_to_monthly | prod_V2yikGRbHLdZR6 | price_1URrw67GRT6LLkI1rWPG6Knm | unspecified |
| Z040 | basic_monthly | prod_V2XQKO3FZiPKZI | price_1URrw77GRT6LLkI1PUhg2WxO | inclusive |
| Z040 | plus_monthly | prod_V2XQrWdt06ryxW | price_1URrw87GRT6LLkI1a3Z0uE3u | inclusive |
| Z040 | basic_yearly | prod_V2XQKO3FZiPKZI | price_1URrw97GRT6LLkI16cEF3gWN | inclusive |
| Z040 | plus_yearly | prod_V2XQrWdt06ryxW | price_1URrwA7GRT6LLkI1fgAOcstS | inclusive |
| Z040 | addon_assignment_3 | prod_V2XRyb7uDkhTVD | price_1URrwA7GRT6LLkI1UmwIbgF2 | exclusive |
| Z040 | addon_detection_5 | prod_V2XRzr2BbiKqJA | price_1URrwB7GRT6LLkI1xIx2gAkW | exclusive |
| Z040 | addon_humanizer_3 | prod_V2XRbewO3qfwsD | price_1URrwC7GRT6LLkI1V9vwHIjC | exclusive |
| Z040 | pro_monthly | prod_V2XRxk7tA14xQr | price_1URrwD7GRT6LLkI1AMmtIIZq | inclusive |
| Z040 | pro_yearly | prod_V2XRxk7tA14xQr | price_1URrwD7GRT6LLkI14wXk5z26 | inclusive |
| Z040 | study_pass_subscription_30d | prod_VJqvMKubYOnfWS | price_1URrwE7GRT6LLkI1heehqCaf | unspecified |
| Z045 | pro_trial_to_monthly | prod_V2yikGRbHLdZR6 | price_1URrwF7GRT6LLkI1zniti7j7 | unspecified |
| Z045 | basic_monthly | prod_V2XQKO3FZiPKZI | price_1URrwG7GRT6LLkI17N1lP4hk | inclusive |
| Z045 | plus_monthly | prod_V2XQrWdt06ryxW | price_1URrwH7GRT6LLkI1xinyBy0v | inclusive |
| Z045 | basic_yearly | prod_V2XQKO3FZiPKZI | price_1URrwH7GRT6LLkI1bJ82CGyF | inclusive |
| Z045 | plus_yearly | prod_V2XQrWdt06ryxW | price_1URrwI7GRT6LLkI1iZE9n46F | inclusive |
| Z045 | addon_assignment_3 | prod_V2XRyb7uDkhTVD | price_1URrwJ7GRT6LLkI1FFIPT3q2 | exclusive |
| Z045 | addon_detection_5 | prod_V2XRzr2BbiKqJA | price_1URrwK7GRT6LLkI1LQJ1gKUx | exclusive |
| Z045 | addon_humanizer_3 | prod_V2XRbewO3qfwsD | price_1URrwL7GRT6LLkI1DatGrDf0 | exclusive |
| Z045 | pro_monthly | prod_V2XRxk7tA14xQr | price_1URrwM7GRT6LLkI1Wt4KJ3MK | inclusive |
| Z045 | pro_yearly | prod_V2XRxk7tA14xQr | price_1URrwN7GRT6LLkI17oC39eD6 | inclusive |
| Z045 | study_pass_subscription_30d | prod_VJqvMKubYOnfWS | price_1URrwN7GRT6LLkI1DHqFrf2T | unspecified |
| Z050 | pro_trial_to_monthly | prod_V2yikGRbHLdZR6 | price_1URsKX7GRT6LLkI11WI6r22n | unspecified |
| Z050 | basic_monthly | prod_V2XQKO3FZiPKZI | price_1URsKY7GRT6LLkI1AJka3B5H | inclusive |
| Z050 | plus_monthly | prod_V2XQrWdt06ryxW | price_1URsKZ7GRT6LLkI1qjnFczml | inclusive |
| Z050 | basic_yearly | prod_V2XQKO3FZiPKZI | price_1URsKa7GRT6LLkI15EQKyjSY | inclusive |
| Z050 | plus_yearly | prod_V2XQrWdt06ryxW | price_1URsKb7GRT6LLkI1xJkFHUj7 | inclusive |
| Z050 | addon_assignment_3 | prod_V2XRyb7uDkhTVD | price_1URsKb7GRT6LLkI1LltUASkL | exclusive |
| Z050 | addon_detection_5 | prod_V2XRzr2BbiKqJA | price_1URsKc7GRT6LLkI1GtqawNoF | exclusive |
| Z050 | addon_humanizer_3 | prod_V2XRbewO3qfwsD | price_1URsKd7GRT6LLkI19AdL2PHz | exclusive |
| Z050 | pro_monthly | prod_V2XRxk7tA14xQr | price_1URsKe7GRT6LLkI1GM335bxO | inclusive |
| Z050 | pro_yearly | prod_V2XRxk7tA14xQr | price_1URsKf7GRT6LLkI12mPynyvD | inclusive |
| Z050 | study_pass_subscription_30d | prod_VJqvMKubYOnfWS | price_1URsKf7GRT6LLkI1xqlbMZZN | unspecified |
| Z055 | pro_trial_to_monthly | prod_V2yikGRbHLdZR6 | price_1URsKg7GRT6LLkI1vPBL3VUg | unspecified |
| Z055 | basic_monthly | prod_V2XQKO3FZiPKZI | price_1URsKh7GRT6LLkI1juSuTrnY | inclusive |
| Z055 | plus_monthly | prod_V2XQrWdt06ryxW | price_1URsKi7GRT6LLkI1QqkwZLQb | inclusive |
| Z055 | basic_yearly | prod_V2XQKO3FZiPKZI | price_1URsKj7GRT6LLkI1pAKVfYnq | inclusive |
| Z055 | plus_yearly | prod_V2XQrWdt06ryxW | price_1URsKj7GRT6LLkI1397t566S | inclusive |
| Z055 | addon_assignment_3 | prod_V2XRyb7uDkhTVD | price_1URsKk7GRT6LLkI1n4cjnZ8q | exclusive |
| Z055 | addon_detection_5 | prod_V2XRzr2BbiKqJA | price_1URsKl7GRT6LLkI1WnPsxDCA | exclusive |
| Z055 | addon_humanizer_3 | prod_V2XRbewO3qfwsD | price_1URsKm7GRT6LLkI1csLLRoV0 | exclusive |
| Z055 | pro_monthly | prod_V2XRxk7tA14xQr | price_1URsKn7GRT6LLkI14o3ccC2S | inclusive |
| Z055 | pro_yearly | prod_V2XRxk7tA14xQr | price_1URsKn7GRT6LLkI15XzmpbYm | inclusive |
| Z055 | study_pass_subscription_30d | prod_VJqvMKubYOnfWS | price_1URsKo7GRT6LLkI13JjLuc2g | unspecified |
| Z060 | pro_trial_to_monthly | prod_V2yikGRbHLdZR6 | price_1URrwX7GRT6LLkI17pon6WBl | unspecified |
| Z060 | basic_monthly | prod_V2XQKO3FZiPKZI | price_1URrwY7GRT6LLkI15RdVWR5Q | inclusive |
| Z060 | plus_monthly | prod_V2XQrWdt06ryxW | price_1URrwZ7GRT6LLkI11tfCyoqM | inclusive |
| Z060 | basic_yearly | prod_V2XQKO3FZiPKZI | price_1URrwZ7GRT6LLkI1CUnllfC0 | inclusive |
| Z060 | plus_yearly | prod_V2XQrWdt06ryxW | price_1URrwa7GRT6LLkI1PG3Lap7L | inclusive |
| Z060 | addon_assignment_3 | prod_V2XRyb7uDkhTVD | price_1URrwb7GRT6LLkI1xaJ6TDiv | exclusive |
| Z060 | addon_detection_5 | prod_V2XRzr2BbiKqJA | price_1URrwc7GRT6LLkI1JEqMTYlB | exclusive |
| Z060 | addon_humanizer_3 | prod_V2XRbewO3qfwsD | price_1URrwd7GRT6LLkI1twi0QrK4 | exclusive |
| Z060 | pro_monthly | prod_V2XRxk7tA14xQr | price_1URrwd7GRT6LLkI1PzAAQgv0 | inclusive |
| Z060 | pro_yearly | prod_V2XRxk7tA14xQr | price_1URrwe7GRT6LLkI1s560672r | inclusive |
| Z060 | study_pass_subscription_30d | prod_VJqvMKubYOnfWS | price_1URrwf7GRT6LLkI17voozvv7 | unspecified |
| Z065 | pro_trial_to_monthly | prod_V2yikGRbHLdZR6 | price_1URsKt7GRT6LLkI13obBZ7Ae | unspecified |
| Z065 | basic_monthly | prod_V2XQKO3FZiPKZI | price_1URsKu7GRT6LLkI1oaNsbVRW | inclusive |
| Z065 | plus_monthly | prod_V2XQrWdt06ryxW | price_1URsKv7GRT6LLkI1mkLMl242 | inclusive |
| Z065 | basic_yearly | prod_V2XQKO3FZiPKZI | price_1URsKv7GRT6LLkI1vEe3ft5i | inclusive |
| Z065 | plus_yearly | prod_V2XQrWdt06ryxW | price_1URsKw7GRT6LLkI1RUHGXv83 | inclusive |
| Z065 | addon_assignment_3 | prod_V2XRyb7uDkhTVD | price_1URsKx7GRT6LLkI11BSEqe4n | exclusive |
| Z065 | addon_detection_5 | prod_V2XRzr2BbiKqJA | price_1URsKy7GRT6LLkI1S4nM1SFn | exclusive |
| Z065 | addon_humanizer_3 | prod_V2XRbewO3qfwsD | price_1URsKz7GRT6LLkI1oGo4rD6J | exclusive |
| Z065 | pro_monthly | prod_V2XRxk7tA14xQr | price_1URsKz7GRT6LLkI1sEILJd6a | inclusive |
| Z065 | pro_yearly | prod_V2XRxk7tA14xQr | price_1URsL07GRT6LLkI1qU6NMcSm | inclusive |
| Z065 | study_pass_subscription_30d | prod_VJqvMKubYOnfWS | price_1URsL17GRT6LLkI1Km8DfzTG | unspecified |
| Z070 | pro_trial_to_monthly | prod_V2yikGRbHLdZR6 | price_1URrwp7GRT6LLkI19S5H10ZY | unspecified |
| Z070 | basic_monthly | prod_V2XQKO3FZiPKZI | price_1URrwp7GRT6LLkI1r676TLRJ | inclusive |
| Z070 | plus_monthly | prod_V2XQrWdt06ryxW | price_1URrwq7GRT6LLkI1FZP82heW | inclusive |
| Z070 | basic_yearly | prod_V2XQKO3FZiPKZI | price_1URrwr7GRT6LLkI1rKTKBpiq | inclusive |
| Z070 | plus_yearly | prod_V2XQrWdt06ryxW | price_1URrws7GRT6LLkI1R5IreqW5 | inclusive |
| Z070 | addon_assignment_3 | prod_V2XRyb7uDkhTVD | price_1URrwt7GRT6LLkI1IwuxE3K3 | exclusive |
| Z070 | addon_detection_5 | prod_V2XRzr2BbiKqJA | price_1URrwt7GRT6LLkI18bBIbdgI | exclusive |
| Z070 | addon_humanizer_3 | prod_V2XRbewO3qfwsD | price_1URrwu7GRT6LLkI1PqkWVWfs | exclusive |
| Z070 | pro_monthly | prod_V2XRxk7tA14xQr | price_1URrwv7GRT6LLkI1AnI4kOf4 | inclusive |
| Z070 | pro_yearly | prod_V2XRxk7tA14xQr | price_1URrww7GRT6LLkI18pYd8hUj | inclusive |
| Z070 | study_pass_subscription_30d | prod_VJqvMKubYOnfWS | price_1URrwx7GRT6LLkI1pS0E8O1K | unspecified |
| Z075 | pro_trial_to_monthly | prod_V2yikGRbHLdZR6 | price_1URrwx7GRT6LLkI1A89n1ZK6 | unspecified |
| Z075 | basic_monthly | prod_V2XQKO3FZiPKZI | price_1URrwy7GRT6LLkI1W7fMPMIq | inclusive |
| Z075 | plus_monthly | prod_V2XQrWdt06ryxW | price_1URrwz7GRT6LLkI1MVqkAsNB | inclusive |
| Z075 | basic_yearly | prod_V2XQKO3FZiPKZI | price_1URrx07GRT6LLkI1eOfkPVaI | inclusive |
| Z075 | plus_yearly | prod_V2XQrWdt06ryxW | price_1URrx17GRT6LLkI1SXqm2Etw | inclusive |
| Z075 | addon_assignment_3 | prod_V2XRyb7uDkhTVD | price_1URrx17GRT6LLkI1qGsoho4M | exclusive |
| Z075 | addon_detection_5 | prod_V2XRzr2BbiKqJA | price_1URrx27GRT6LLkI1UKo28k3Z | exclusive |
| Z075 | addon_humanizer_3 | prod_V2XRbewO3qfwsD | price_1URrx37GRT6LLkI1qXxIcW6F | exclusive |
| Z075 | pro_monthly | prod_V2XRxk7tA14xQr | price_1URrx47GRT6LLkI1rBHzQKGl | inclusive |
| Z075 | pro_yearly | prod_V2XRxk7tA14xQr | price_1URrx57GRT6LLkI1caV5pDOc | inclusive |
| Z075 | study_pass_subscription_30d | prod_VJqvMKubYOnfWS | price_1URrx67GRT6LLkI10Kad8Vzz | unspecified |
| Z080 | pro_trial_to_monthly | prod_V2yikGRbHLdZR6 | price_1URrx77GRT6LLkI1mafUApZJ | unspecified |
| Z080 | basic_monthly | prod_V2XQKO3FZiPKZI | price_1URrx77GRT6LLkI1NRRmlFNt | inclusive |
| Z080 | plus_monthly | prod_V2XQrWdt06ryxW | price_1URrx87GRT6LLkI1Jla1Vouy | inclusive |
| Z080 | basic_yearly | prod_V2XQKO3FZiPKZI | price_1URrx97GRT6LLkI1NsPOo2v8 | inclusive |
| Z080 | plus_yearly | prod_V2XQrWdt06ryxW | price_1URrxA7GRT6LLkI1SdUHuYPT | inclusive |
| Z080 | addon_assignment_3 | prod_V2XRyb7uDkhTVD | price_1URrxA7GRT6LLkI1bx0O8Ek9 | exclusive |
| Z080 | addon_detection_5 | prod_V2XRzr2BbiKqJA | price_1URrxB7GRT6LLkI1dCI4aOGD | exclusive |
| Z080 | addon_humanizer_3 | prod_V2XRbewO3qfwsD | price_1URrxC7GRT6LLkI19GbDgCFe | exclusive |
| Z080 | pro_monthly | prod_V2XRxk7tA14xQr | price_1URrxD7GRT6LLkI1DmR7NpKm | inclusive |
| Z080 | pro_yearly | prod_V2XRxk7tA14xQr | price_1URrxE7GRT6LLkI1vFIavRT0 | inclusive |
| Z080 | study_pass_subscription_30d | prod_VJqvMKubYOnfWS | price_1URrxE7GRT6LLkI1RyxG6n63 | unspecified |
| Z085 | pro_trial_to_monthly | prod_V2yikGRbHLdZR6 | price_1URrxF7GRT6LLkI1M06PQ0da | unspecified |
| Z085 | basic_monthly | prod_V2XQKO3FZiPKZI | price_1URrxG7GRT6LLkI17fqO4WGs | inclusive |
| Z085 | plus_monthly | prod_V2XQrWdt06ryxW | price_1URrxH7GRT6LLkI18ADYfbud | inclusive |
| Z085 | basic_yearly | prod_V2XQKO3FZiPKZI | price_1URrxH7GRT6LLkI1LKRrt9mo | inclusive |
| Z085 | plus_yearly | prod_V2XQrWdt06ryxW | price_1URrxI7GRT6LLkI1k0E1SPEh | inclusive |
| Z085 | addon_assignment_3 | prod_V2XRyb7uDkhTVD | price_1URrxJ7GRT6LLkI13xn18FmF | exclusive |
| Z085 | addon_detection_5 | prod_V2XRzr2BbiKqJA | price_1URrxK7GRT6LLkI10LPECQ60 | exclusive |
| Z085 | addon_humanizer_3 | prod_V2XRbewO3qfwsD | price_1URrxL7GRT6LLkI1GeCCkuz1 | exclusive |
| Z085 | pro_monthly | prod_V2XRxk7tA14xQr | price_1URrxL7GRT6LLkI1fRbBkuFi | inclusive |
| Z085 | pro_yearly | prod_V2XRxk7tA14xQr | price_1URrxM7GRT6LLkI1Y3hfwyKe | inclusive |
| Z085 | study_pass_subscription_30d | prod_VJqvMKubYOnfWS | price_1URrxN7GRT6LLkI1QYKbVFWT | unspecified |
| Z090 | pro_trial_to_monthly | prod_V2yikGRbHLdZR6 | price_1URrxO7GRT6LLkI1WvBTkVYX | unspecified |
| Z090 | basic_monthly | prod_V2XQKO3FZiPKZI | price_1URrxP7GRT6LLkI1hVTHR9HS | inclusive |
| Z090 | plus_monthly | prod_V2XQrWdt06ryxW | price_1URrxP7GRT6LLkI1sTAcfne2 | inclusive |
| Z090 | basic_yearly | prod_V2XQKO3FZiPKZI | price_1URrxQ7GRT6LLkI1lTQTgNlG | inclusive |
| Z090 | plus_yearly | prod_V2XQrWdt06ryxW | price_1URrxR7GRT6LLkI1vQfmPkot | inclusive |
| Z090 | addon_assignment_3 | prod_V2XRyb7uDkhTVD | price_1URrxS7GRT6LLkI1SObEYha0 | exclusive |
| Z090 | addon_detection_5 | prod_V2XRzr2BbiKqJA | price_1URrxS7GRT6LLkI1JNYuLJWq | exclusive |
| Z090 | addon_humanizer_3 | prod_V2XRbewO3qfwsD | price_1URrxT7GRT6LLkI1qs9LhcnL | exclusive |
| Z090 | pro_monthly | prod_V2XRxk7tA14xQr | price_1URrxU7GRT6LLkI1rZp9mzyv | inclusive |
| Z090 | pro_yearly | prod_V2XRxk7tA14xQr | price_1URrxV7GRT6LLkI1rm59srS4 | inclusive |
| Z090 | study_pass_subscription_30d | prod_VJqvMKubYOnfWS | price_1URrxW7GRT6LLkI1eJpTawlV | unspecified |
| Z095 | pro_trial_to_monthly | prod_V2yikGRbHLdZR6 | price_1URrxW7GRT6LLkI1siP57nsF | unspecified |
| Z095 | basic_monthly | prod_V2XQKO3FZiPKZI | price_1URrxY7GRT6LLkI1PEGvdg7b | inclusive |
| Z095 | plus_monthly | prod_V2XQrWdt06ryxW | price_1URrxY7GRT6LLkI1E4QBXglY | inclusive |
| Z095 | basic_yearly | prod_V2XQKO3FZiPKZI | price_1URrxZ7GRT6LLkI1A4hV4YPe | inclusive |
| Z095 | plus_yearly | prod_V2XQrWdt06ryxW | price_1URrxa7GRT6LLkI1Q3Zm2eI9 | inclusive |
| Z095 | addon_assignment_3 | prod_V2XRyb7uDkhTVD | price_1URrxb7GRT6LLkI1jL4222FD | exclusive |
| Z095 | addon_detection_5 | prod_V2XRzr2BbiKqJA | price_1URrxb7GRT6LLkI1Hi7uJytk | exclusive |
| Z095 | addon_humanizer_3 | prod_V2XRbewO3qfwsD | price_1URrxc7GRT6LLkI1zH2te5Zt | exclusive |
| Z095 | pro_monthly | prod_V2XRxk7tA14xQr | price_1URrxd7GRT6LLkI1e8nxttmE | inclusive |
| Z095 | pro_yearly | prod_V2XRxk7tA14xQr | price_1URrxe7GRT6LLkI1B8k1gbCS | inclusive |
| Z095 | study_pass_subscription_30d | prod_VJqvMKubYOnfWS | price_1URrxf7GRT6LLkI16qYqZ6uW | unspecified |
| Z100 | pro_trial_to_monthly | prod_V2yikGRbHLdZR6 | price_1URrxf7GRT6LLkI1h7y2ehzl | unspecified |
| Z100 | basic_monthly | prod_V2XQKO3FZiPKZI | price_1URrxg7GRT6LLkI13axvSOdG | inclusive |
| Z100 | plus_monthly | prod_V2XQrWdt06ryxW | price_1URrxh7GRT6LLkI1xpT3YQxg | inclusive |
| Z100 | basic_yearly | prod_V2XQKO3FZiPKZI | price_1URrxi7GRT6LLkI1M4TGGISR | inclusive |
| Z100 | plus_yearly | prod_V2XQrWdt06ryxW | price_1URrxi7GRT6LLkI12kwh4f9g | inclusive |
| Z100 | addon_assignment_3 | prod_V2XRyb7uDkhTVD | price_1URrxj7GRT6LLkI1orqZ2bIk | exclusive |
| Z100 | addon_detection_5 | prod_V2XRzr2BbiKqJA | price_1URrxk7GRT6LLkI1TKq0anAE | exclusive |
| Z100 | addon_humanizer_3 | prod_V2XRbewO3qfwsD | price_1URrxl7GRT6LLkI1nKRo8ecV | exclusive |
| Z100 | pro_monthly | prod_V2XRxk7tA14xQr | price_1URrxm7GRT6LLkI1PMLgb0EM | inclusive |
| Z100 | pro_yearly | prod_V2XRxk7tA14xQr | price_1URrxn7GRT6LLkI1tny3NzO8 | inclusive |
| Z100 | study_pass_subscription_30d | prod_VJqvMKubYOnfWS | price_1URrxn7GRT6LLkI1W3KctsDo | unspecified |
