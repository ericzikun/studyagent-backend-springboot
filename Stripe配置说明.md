# Stripe Price ID 配置说明

## ⚠️ 重要提示

**Stripe Price ID 配置必须是 Price ID 或 Product ID，不能是数字价格！**

## 🔴 错误配置示例

```bash
# ❌ 错误：这是价格金额，不是 Price ID
STRIPE_PRICE_STARTER=4.99
STRIPE_PRICE_PRO=39.99
STRIPE_PRICE_ACADEMIC=99.99
```

如果配置数字价格，会报错：
```
The `price` parameter should be the ID of a price object, rather than the literal numerical price.
```

## ✅ 正确配置格式

### 方式1：使用 Price ID（推荐）

```bash
# ✅ 正确：使用 Price ID（格式：price_xxxxx）
STRIPE_PRICE_STARTER=price_1ABC123def456GHI789
STRIPE_PRICE_PRO=price_1XYZ789abc123DEF456
STRIPE_PRICE_ACADEMIC=price_1QWE456rty789UIO012
```

### 方式2：使用 Product ID（会自动查找其下的第一个 Price）

```bash
# ✅ 正确：使用 Product ID（格式：prod_xxxxx）
STRIPE_PRICE_STARTER=prod_ThSpkBw4Rny4XM
STRIPE_PRICE_PRO=prod_ThSpkBw4Rny4XM
STRIPE_PRICE_ACADEMIC=prod_ThSpkBw4Rny4XM
```

## 📋 如何获取 Stripe Price ID

### 步骤1：登录 Stripe Dashboard

访问：https://dashboard.stripe.com

### 步骤2：创建产品或使用现有产品

1. 进入 **Products** 页面
2. 点击 **Add product** 创建新产品，或选择现有产品
3. 填写产品信息：
   - **Name**: Starter Pack / Pro Pack / Academic Pack
   - **Description**: 套餐描述

### 步骤3：添加价格（Pricing）

1. 在产品页面，点击 **Add pricing**
2. 设置价格：
   - **Pricing model**: One time（一次性支付）
   - **Price**: 输入金额（例如：$4.99）
   - **Currency**: USD（或其他货币）
3. 点击 **Save pricing**

### 步骤4：复制 Price ID

创建价格后，Stripe 会生成一个 Price ID，格式类似：
- `price_1ABC123def456GHI789`（测试环境）
- `price_1XYZ789abc123DEF456`（生产环境）

**复制这个 Price ID**，不要复制价格金额！

### 步骤5：配置到环境变量

在 `.env` 文件中配置：

```bash
# 方式1：使用 Price ID（推荐）
STRIPE_PRICE_STARTER=price_1ABC123def456GHI789
STRIPE_PRICE_PRO=price_1XYZ789abc123DEF456
STRIPE_PRICE_ACADEMIC=price_1QWE456rty789UIO012

# 方式2：使用 Product ID（会自动查找其下的第一个 Price）
STRIPE_PRICE_STARTER=prod_ThSpkBw4Rny4XM
STRIPE_PRICE_PRO=prod_ThSpkBw4Rny4XM
STRIPE_PRICE_ACADEMIC=prod_ThSpkBw4Rny4XM
```

## 🔍 验证配置

启动应用后，检查日志：

```bash
# 如果配置正确，会看到：
加载环境变量: STRIPE_PRICE_STARTER = price_1ABC123def456GHI789

# 如果配置错误（数字价格），会看到：
加载环境变量: STRIPE_PRICE_STARTER = 4.99
# 然后会报错：配置错误: starter 套餐的 Price ID 配置无效
```

## 📝 参考 Python 后端配置

Python 后端的默认配置（`curve-master/backend/app/config.py`）：

```python
# Stripe Price ID 配置（在Stripe后台创建产品后填写）
STRIPE_PRICE_STARTER: str = Field(default="prod_ThSpkBw4Rny4XM", ...)
STRIPE_PRICE_PRO: str = Field(default="prod_ThSpkBw4Rny4XM", ...)
STRIPE_PRICE_ACADEMIC: str = Field(default="prod_ThSpkBw4Rny4XM", ...)
```

**注意**：Python 后端默认使用的是 Product ID（`prod_ThSpkBw4Rny4XM`），代码会自动查找其下的第一个 Price。

## 🛠️ 代码自动处理逻辑

Spring Boot 后端会自动处理两种情况：

1. **Price ID** (`price_xxxxx`)：直接使用
2. **Product ID** (`prod_xxxxx`)：自动查找该产品下的第一个活跃 Price

如果配置的是数字或其他格式，会返回明确的错误提示。

## ✅ 配置检查清单

- [ ] 已登录 Stripe Dashboard
- [ ] 已创建产品或使用现有产品
- [ ] 已为产品添加价格（Pricing）
- [ ] 已复制 Price ID（不是价格金额）
- [ ] 已在 `.env` 文件中配置 Price ID 或 Product ID
- [ ] Price ID 格式正确（以 `price_` 或 `prod_` 开头）
- [ ] 重启应用后配置生效

## 🆘 常见问题

### Q: 我只有价格金额（如 $4.99），没有 Price ID？

**A**: 你需要在 Stripe Dashboard 中创建产品和价格，Stripe 会自动生成 Price ID。不能直接使用价格金额。

### Q: 我可以使用 Product ID 吗？

**A**: 可以！如果配置的是 Product ID（`prod_xxxxx`），代码会自动查找该产品下的第一个活跃 Price。

### Q: 测试环境和生产环境的 Price ID 一样吗？

**A**: 不一样。测试环境使用测试密钥（`sk_test_...`）和测试 Price ID（`price_1...`），生产环境使用生产密钥（`sk_live_...`）和生产 Price ID。

### Q: 如何查看现有的 Price ID？

**A**: 在 Stripe Dashboard → Products → 选择产品 → 查看价格列表，每个价格旁边都有 Price ID。

