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

启动脚本也会补齐 `verla_attachments.attachment_origin`，用于区分用户上传附件和 agent 输出附件。

启动脚本还会在已存在 Verla 基础会话表时补齐 `verla_editor_contents` 与 `verla_editor_content_versions`，避免本地 mock 数据库漏跑 V2 editor storage SQL 后在打开编辑器内容时运行时报缺表。

启动脚本会补齐旧 mock 数据库缺失的商业化额度字段：`verla_sessions.quota_ledger_id`、`verla_sessions.quota_amount` 以及 `humanizer_tasks` 的 quota 相关列。

启动脚本会补齐旧 mock 数据库缺失的 Verla workforce 进度列与 `verla_editor_previews`，避免 conversation 列表和 compose-progress 链路因旧 schema 报错。

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
