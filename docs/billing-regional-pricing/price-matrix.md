# BRL 价格矩阵与配置边界

本表用于业务金额确认和沙盒/生产映射核对；仅 2026-10-13 下方标记的目标沙盒配置已执行；生产未修改。金额为固定地区价格，不根据实时汇率换算。实际可售集合以启用时的 catalog、商品状态和已批准 Trial/Pass 决策为准。

## 调整前的历史沙盒配置

账号：LASTMILE LIMITED，`acct_1SrJ7Q7GRT6LLkI1`。以下 ID 属于本次调查的测试环境；不得直接当作生产配置。

| SKU/用途 | 沙盒 Price ID | 默认 USD 最小单位 | BRL 最小单位 | Stripe 周期 / Verla 用途 | 状态 |
| --- | --- | --- | --- | --- | --- |
| `pro_trial_to_monthly` | `price_1U2slM7GRT6LLkI1RYM0atFA` | 299 | 499 | week × 1；仅首段 7 天 | 已知 BRL option；需完整支付及转正验收 |
| `pro_monthly` | `price_1U2SNp7GRT6LLkI17wGltgTq` | 7999 | 39999 | month × 1；Trial 转正及正式月付 | 已知 BRL option；需核验当前环境映射 |
| 历史 yearly Trial Price | `price_1U2sli7GRT6LLkI13rZpSxDG` | 299 | 499 | week × 1；历史映射/回归用途 | 不因此重新上架；历史转换目标按既有规则 |

已知两条 Trial Price 另有 KRW 4100，Pro 月付有 KRW 110000；这些 option 和历史订阅保留。本次没有检验或修改生产 Price。

## 2026-10-13 已配置的沙盒测试价格

确认来源：本会话用户授权配置目标沙盒所有可售产品，先指定美元数值 ×5，再允许按巴西标价形式小幅调整。本轮在 ×5 附近使用 `.90` 尾数；随后用户要求 Trial、Pro 月付也统一为 `.90`：当前新购 Trial 4.90、Pro 月付 399.90。旧 Price 和已建立合同保留原价。尾数参考当地订阅的标价形式，例如 [Amazon Brasil Prime](https://www.amazon.com.br/prime) 使用 `.90`；这不是市场支付偏好的统计结论。以下金额是沙盒测试配置，生产金额仍待另行确认。

| SKU | USD 基准金额 | BRL 测试金额 | 沙盒 Price ID | 周期 |
| --- | ---: | ---: | --- | --- |
| `basic_monthly` | 19.99 | 99.90 | `price_1U2SNZ7GRT6LLkI1XgzarCSV` | month ×1 |
| `basic_yearly` | 95.88 | 479.90 | `price_1U2SNd7GRT6LLkI1icheuGId` | year ×1 |
| `plus_monthly` | 39.99 | 199.90 | `price_1U2SNh7GRT6LLkI1xwWeZJn2` | month ×1 |
| `plus_yearly` | 191.88 | 959.90 | `price_1U2SNl7GRT6LLkI1wICSgCbq` | year ×1 |
| `pro_monthly` | 79.99 | 399.90 | `price_1UQ1Sh7GRT6LLkI1K09CTtGF` | month ×1 |
| `pro_yearly` | 383.88 | 1919.90 | `price_1U2SNt7GRT6LLkI1NwuJJvcc` | year ×1 |
| `pro_trial_to_monthly` | 2.99 | 4.90 | `price_1UQ1Sf7GRT6LLkI1bHoRULQr` | week ×1（Trial 首段7天） |
| `addon_assignment_3` | 9.99 | 49.90 | `price_1U2SNx7GRT6LLkI10MleQRko` | one_time |
| `addon_detection_5` | 4.99 | 24.90 | `price_1U2SO17GRT6LLkI17H9Bs8DQ` | one_time |
| `addon_humanizer_3` | 6.99 | 34.90 | `price_1U2SO57GRT6LLkI1BAvC57zP` | one_time |
| `assignment_1` | 2.99 | 14.90 | `price_1UQ0Ud7GRT6LLkI1vCAcalAD` | one_time |
| `assignment_5` | 12.99 | 64.90 | `price_1UQ0Uf7GRT6LLkI1xvR0kiAh` | one_time |
| `assignment_10` | 23.99 | 119.90 | `price_1UQ0Uh7GRT6LLkI1VihpGBvP` | one_time |
| `assignment_50` | 99.99 | 499.90 | `price_1UQ0Uk7GRT6LLkI1AZqum4oL` | one_time |
| `detection_10k` | 1.99 | 9.90 | `price_1UQ0Um7GRT6LLkI1qtL9Fyo0` | one_time |
| `detection_50k` | 7.99 | 39.90 | `price_1UQ0Uo7GRT6LLkI1kQJQKlfd` | one_time |
| `detection_200k` | 23.99 | 119.90 | `price_1UQ0Uq7GRT6LLkI1pFYIW75n` | one_time |
| `humanizer_10k` | 2.99 | 14.90 | `price_1UQ0Us7GRT6LLkI1JBQtZT5l` | one_time |
| `humanizer_50k` | 11.99 | 59.90 | `price_1UQ0Uu7GRT6LLkI10PPujpkC` | one_time |
| `humanizer_200k` | 39.99 | 199.90 | `price_1UQ0Uw7GRT6LLkI1NFg2jNVT` | one_time |
| `study_pass_subscription_30d` | 0.99 | 4.90 | `price_1UQ0Uy7GRT6LLkI1bDLfKmZy` | month ×1 |

已只读回验全部 Price 的 `livemode=false`、USD 基准及 BRL option，并更新本地数据库映射；未新增表/列。本地原先缺失的 Study Pass 基础表已按既有迁移初始化。Study Pass 新增自然月 Price，保留旧 day ×30 对象；旧 Basic/yearly Trial 和内部 `mock_*` 套餐不重新上架。

现有 USD/KRW option、历史订单和订阅未修改；UI 的独立新购使用地区币种，会员依附动作仍使用实际合同币种。SKU 弹层与 Pricing 在地区定价启用但缺少可用额外包时显示 `—`，不用硬编码 USD 值补齐。

## 配置与启用检查

- 对每个确认可售项记录环境、商品 code、Price ID、固定 BRL 整数金额、周期、目标转换 Price（如有）及确认来源。
- 沙盒和生产分别核对。不要将 sandbox Price ID 写入生产映射，不整体把商品表 USD 基准改成 BRL。
- 多币种 Price option 必须实际存在，不能只在前端配置一个 BRL 数字。新建 Price 如成为必要选择，需同步既有映射数据并列出变更对象；本次不新增映射表。
- 如果实际 checkout 已含 coupon、shipping 或 tax，核对其 BRL 兼容性，沿用原政策；本次不添加这些新能力。
- 全部确认可售商品的 BRL 金额与配置完成前，不启用整体巴西销售。缺失时仅返回不可用状态，不静默卖 USD。
