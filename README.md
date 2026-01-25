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

### 3. 访问 API 文档

http://localhost:8080/swagger-ui.html

## 模块说明

- **agent-api**: REST API 接口层
- **agent-service**: 领域业务逻辑层
- **agent-infra**: 基础设施实现层
- **agent-start**: 应用启动入口

## 环境配置

参考 `agent-start/src/main/resources/application.yml`

