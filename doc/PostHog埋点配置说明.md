# PostHog 埋点配置说明

## 概述

项目已集成 PostHog 分析服务，用于追踪用户登录和购买行为。

## 已接入的埋点事件

### 1. 登录相关事件

| 事件名称 | 触发时机 | 属性 |
|---------|---------|------|
| `user_login_success` | 用户成功登录/获取用户信息时 | email, display_name, locale, is_admin, is_new_user |

### 2. 购买相关事件

| 事件名称 | 触发时机 | 属性 |
|---------|---------|------|
| `payment_session_created` | 用户创建支付会话成功时 | package_type, customer_email, session_id |
| `payment_session_failed` | 创建支付会话失败时 | package_type, error_code, error_message |
| `payment_completed` | Stripe 回调确认支付完成时 | session_id, package_type, feature_code, quota_amount, price_cents, currency, customer_email |
| `recharge_success` | 用户额度充值到账时 | order_no, package_code, quota_amount, price_cents, currency |

## 配置方法

### 1. 获取 PostHog API Key

1. 登录 [PostHog 控制台](https://app.posthog.com)
2. 进入项目设置 → Project API Key
3. 复制 API Key（格式如：`phc_xxxxx...`）

### 2. 配置环境变量

在运行环境中设置以下环境变量：

```bash
# 启用 PostHog 埋点
export POSTHOG_ENABLED=true

# PostHog API Key（必需）
export POSTHOG_API_KEY=phc_your_project_api_key_here

# PostHog 服务器地址（可选，默认使用官方云）
export POSTHOG_HOST=https://app.posthog.com
```

### 3. 不同环境的配置

#### 本地开发环境

修改 `application-local.yml`：

```yaml
posthog:
  enabled: true  # 本地测试时启用
  api-key: ${POSTHOG_API_KEY:}
  host: ${POSTHOG_HOST:https://app.posthog.com}
```

#### 生产环境

使用环境变量配置（推荐）：

```bash
POSTHOG_ENABLED=true
POSTHOG_API_KEY=phc_live_xxxxxxxxxx
POSTHOG_HOST=https://app.posthog.com
```

## 代码示例

### 发送自定义事件

```java
@Autowired
private AnalyticsService analyticsService;

public void someMethod(String userId) {
    Map<String, Object> props = new HashMap<>();
    props.put("action", "submit");
    props.put("value", 100);

    analyticsService.capture(userId, "custom_event", props);
}
```

### 设置用户属性

```java
Map<String, Object> userProps = new HashMap<>();
userProps.put("plan", "pro");
userProps.put("signup_date", "2024-01-01");

analyticsService.setUserProperties(userId, userProps);
```

## 查看数据

1. 登录 PostHog 控制台
2. 在 **Insights** 中创建查询：
   - 选择事件类型（如 `user_login_success`）
   - 按用户属性分组（如 `locale`, `is_admin`）
3. 在 **Persons** 中查看用户画像

## 注意事项

1. **隐私合规**：埋点数据不要包含敏感信息（如密码、完整信用卡号）
2. **性能影响**：PostHog SDK 使用异步发送，对性能影响极小
3. **失败处理**：发送失败会记录日志，不会阻断业务流程
4. **本地开发**：默认禁用，如需测试请设置 `POSTHOG_ENABLED=true`

## 故障排查

### 检查埋点是否生效

查看应用日志，应该能看到类似输出：

```
[Analytics] Event: user_login_success | User: user_xxx | Properties: {...}
```

### 常见问题

1. **日志显示 "PostHog 未启用"**
   - 检查 `POSTHOG_ENABLED` 是否设置为 `true`
   - 检查 `POSTHOG_API_KEY` 是否正确配置

2. **日志显示 "PostHog 初始化失败"**
   - 检查 API Key 是否正确
   - 检查网络是否能访问 PostHog 服务器

3. **事件未在 PostHog 控制台显示**
   - 事件可能有延迟，等待 1-2 分钟
   - 检查时间范围过滤是否正确
   - 确认使用的是正确的 Project
