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

### 2.1 运行 Mock 应用（前端联调）

```bash
./start-mock.sh
```

或使用 Maven 直接切换到 Mock 入口：

```bash
cd agent-start
mvn spring-boot:run -Dapp.mainClass=com.studyagent.start.MockStudyAgentApplication
```

如需避开已占用端口，可指定：

```bash
PORT=18080 ./start-mock.sh
```

Mock 模式特性：
- 不依赖数据库、Clerk、Python 后端
- 内存态数据，重启后重置
- 提供 `task/file/auth/health/quota/payment/feedback/announcement` mock 接口（保持 Spring 现有响应结构）
- 可与前端 Clerk 正常登录并存：前端仍使用 Clerk 登录，Mock API 只做业务接口联调

### 3. 访问 API 文档

http://localhost:8080/swagger-ui.html

## 模块说明

- **agent-api**: REST API 接口层
- **agent-service**: 领域业务逻辑层
- **agent-infra**: 基础设施实现层
- **agent-start**: 应用启动入口

## 环境配置

参考 `agent-start/src/main/resources/application.yml`
