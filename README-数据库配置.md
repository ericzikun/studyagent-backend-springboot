# 数据库配置快速指南

## 🚀 快速开始

### 本地环境（推荐）

1. **启动 MySQL（使用 Docker）**
   ```bash
   cd docker
   docker-compose up -d mysql
   ```

2. **配置环境变量**
   ```bash
   cd ../studyagent-backend
   export DB_HOST=localhost
   export DB_PORT=13306
   export DB_USERNAME=studyagent
   export DB_PASSWORD=studyagent2024
   ```

3. **启动应用**
   ```bash
   cd agent-start
   SPRING_PROFILES_ACTIVE=local mvn spring-boot:run
   ```

### Docker 环境

1. **确保 MySQL 已启动**
   ```bash
   cd docker
   docker-compose ps mysql
   ```

2. **启动 SpringBoot 后端**
   ```bash
   cd ../studyagent-backend
   docker-compose up -d
   ```

3. **查看日志**
   ```bash
   docker-compose logs -f springboot-backend
   ```

## 📝 配置文件说明

- `application.yml` - 基础配置
- `application-local.yml` - 本地环境（端口 13306）
- `application-docker.yml` - Docker 环境（服务名 mysql）
- `application-dev.yml` - 开发环境
- `application-prod.yml` - 生产环境

## ✅ 验证

访问健康检查接口：
```bash
curl http://localhost:8080/health
```

如果看到 `"database": "connected"`，说明数据库连接成功！

