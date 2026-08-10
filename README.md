# StudyAgent Backend

基于 Spring Boot 3.x + DDD 架构的后端服务，替代 Python 后端的数据获取、文件管理、用户认证、支付等功能。

## 项目结构

```
studyagent-backend/
├── pom.xml                    # 父 POM（统一依赖管理）
├── agent-api/                 # API 层（Controllers、DTOs）
├── agent-service/             # 服务层（领域模型、业务逻辑）
├── agent-infra/               # 基础设施层（数据库、外部服务）
└── agent-start/               # 启动模块（应用入口）
```

## 技术栈

- Spring Boot 3.2.0
- MyBatis-Plus 3.5.3
- MySQL 8.0
- Stripe Java SDK
- Swagger/OpenAPI 3

## 快速开始

### 1. 构建项目

```bash
mvn clean install
```

### 2. 运行应用

```bash
cd agent-start
mvn spring-boot:run
```

### 3. 运行本地 MockPy 后端

用于前端 V2 联调 Spring SSE + Java MockPy，不启动真实 Python V2 agent。

```bash
./start-mock.sh
```

常用覆盖项：

```bash
PORT=8081 BUILD_FIRST=false START_DEPS=false ./start-mock.sh
```

启动脚本会默认补齐本地 mock 数据库缺失的 `user_profiles` 认证表与 `mq_outbox` Verla / claim 字段；如需跳过可设置 `PATCH_MOCK_DB=false`。

启动脚本也会补齐 `verla_attachments.attachment_origin`、`deleted_at` 与相关索引，用于区分用户上传附件和 agent 输出附件，并匹配附件软删除查询条件。

启动脚本还会在已存在 Verla 基础会话表时补齐 `verla_editor_contents` 与 `verla_editor_content_versions`，避免本地 mock 数据库漏跑 V2 editor storage SQL 后在打开编辑器内容时运行时报缺表。

启动脚本会补齐旧 mock 数据库缺失的商业化额度字段：`verla_sessions.quota_ledger_id`、`verla_sessions.quota_amount` 以及 `humanizer_tasks` 的 quota 相关列。

启动脚本会补齐 V2 商业化 mock 数据：`/v1/billing/config` 需要的订阅套餐与 add-on catalog、`/v1/billing/account` 需要的订阅镜像表、`/v1/payment/config` 需要的 Assignment / AI Detection / Humanizer 额度包，以及 quota ledger allocation、payment resume 和充值订单相关表/列。

这些 mock 数据用于本地页面和套餐内容联调；`start-mock.sh` 会先加载项目根目录的 `.env` 和 `.env.local`，再设置本地默认值。默认 `BILLING_CHECKOUT_MOCK_ENABLED=true` 和 `PAYMENT_CHECKOUT_MOCK_ENABLED=true` 时，会员购买会本地更新 `user_subscriptions`、写入 completed `recharge_orders`，并直接回跳 `/payment-success?session_id=mock_cs_...`。

如需真实 Stripe test mode checkout 跳转，在 `.env.local` 或启动命令中设置：

```bash
export BILLING_CHECKOUT_MOCK_ENABLED=false
export PAYMENT_CHECKOUT_MOCK_ENABLED=false
export STRIPE_SECRET_KEY=sk_test_...
export STRIPE_PUBLISHABLE_KEY=pk_test_...
export STRIPE_ALLOW_UNSIGNED_WEBHOOKS=true
export STRIPE_WEBHOOK_SECRET=whsec_xxx
```

然后启动两个终端：

```bash
./start-mock.sh
./start-stripe-webhook.sh
```

`start-stripe-webhook.sh` 会把 Stripe CLI test events 转发到 `http://localhost:8080/v1/webhook/stripe`。完成 Stripe Checkout 测试支付后，后端会通过 `checkout.session.completed` / `invoice.paid` 等事件把订单更新为 completed，并写入可用于账单页的 Stripe invoice 信息。如需严格校验签名，将 `stripe listen` 输出的 `whsec_...` 写入 `.env.local` 的 `STRIPE_WEBHOOK_SECRET` 后重启 `./start-mock.sh`。真实 Stripe checkout 还需要按 `sql/v2_billing_stripe_sandbox_ids.example.sql` 写入对应 sandbox `price_...`。

账单入口 `POST /v1/billing/portal-session` 在 `start-mock.sh` 下默认使用 `BILLING_PORTAL_MOCK_URL=return-url`，避免本地未配置 Stripe Secret Key 时返回 `Stripe not configured`；如需打开真实 Stripe Customer Portal，请传入真实 test mode `STRIPE_SECRET_KEY`，设置 `BILLING_PORTAL_MOCK_ENABLED=false`，并确保本地 `user_subscriptions.stripe_customer_id` 对应同一个 Stripe test account。

启动脚本会补齐旧 mock 数据库缺失的 Verla workforce 进度列与 `verla_editor_previews`，避免 conversation 列表和 compose-progress 链路因旧 schema 报错。

订阅套餐需要在 Stripe Product 中维护 description。后端创建 Hosted Checkout 时会读取 `subscription_plans.stripe_product_id` 指向的 Product description，并展示在支付确认区域；因此普通订阅、Pro Trial 和手动升级都使用同一份套餐说明。description 为空或 Stripe Product 查询暂时失败时，购买流程不会被阻断，只是不显示补充说明。

本地 MockPy 的 Assignment init 默认流会先发送一段基于真实 requirement-analysis case 分割的 `channel=thinking` stream chunk，再发送 `channel=content` 正文 chunk，用于验证 V2 前端左栏 thinking 折叠消息和正文流式消息的切换。

### 4. 访问 API 文档

http://localhost:8080/swagger-ui.html

## 模块说明

- **agent-api**: REST API 接口层
- **agent-service**: 领域业务逻辑层
- **agent-infra**: 基础设施实现层
- **agent-start**: 应用启动入口

## 环境配置

参考 `agent-start/src/main/resources/application.yml`

### Clerk JWT 验签

所有受保护 API（包括 Verla SSE）都会通过 Clerk 官方 SDK 验证 session token 签名，
不再接受只解码 payload 的 JWT。推荐在生产环境配置 Clerk Dashboard API keys 页提供的
JWT 公钥，以便无网络完成验签：

```bash
CLERK_JWT_KEY='-----BEGIN PUBLIC KEY-----\n...\n-----END PUBLIC KEY-----'
CLERK_AUTHORIZED_PARTIES='https://verla.io,https://www.verla.io'
```

`CLERK_JWT_KEY` 未配置时，后端会使用 `CLERK_SECRET_KEY` 让 SDK 获取并缓存 JWKS。
两项验证配置都缺失、签名无效或 JWKS 不可用时，认证将失败关闭，不会降级为未验签解析。
SSE 迁移期间仍兼容 `access_token` 查询参数，但该 Token 与 Authorization header Token
执行完全相同的验签。

### Assignment 完成邮件

V2 Assignment 完成邮件由 Spring Boot 在 Verla generation 首次完成后 best-effort 触发，使用 Resend template。启用时需要配置：

```bash
EMAIL_NOTIFICATION_ENABLED=true
RESEND_API_KEY=re_...
FRONTEND_URL=https://verla.io
EMAIL_ASSIGNMENT_COMPLETED_TEMPLATE_ID=tpl_xxx
```

Resend template 当前只依赖两个变量：`title`（作业名）和 `url`（V2 作业地址）。
后端发送 payload 不覆盖 `from` 和 `subject`，发件人和邮件标题使用已发布 Resend template 内配置的值。
