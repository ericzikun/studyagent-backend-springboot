# IDEA 启动配置指南

## 🎯 配置 Spring Boot Profile

在 IDEA 中启动 SpringBoot 后端时，需要指定使用 `local` profile 来加载 `application-local.yml` 配置。

## 📝 配置步骤

### 方法 1: 通过 Run Configuration 配置（推荐）

1. **打开 Run Configuration**
   - 点击 IDEA 右上角的运行配置下拉菜单
   - 选择 `Edit Configurations...`

2. **创建/编辑 Spring Boot 配置**
   - 如果没有配置，点击 `+` → `Spring Boot`
   - 如果已有配置，直接编辑

3. **配置基本信息**
   - **Name**: `StudyAgentApplication`
   - **Main class**: `com.studyagent.start.StudyAgentApplication`
   - **Module**: `agent-start`

4. **配置 Active Profiles**
   - 在 `Active profiles` 或 `VM options` 中输入：
   ```
   -Dspring.profiles.active=local
   ```
   或者
   - 在 `Environment variables` 中添加：
   ```
   SPRING_PROFILES_ACTIVE=local
   ```

5. **配置工作目录**
   - **Working directory**: `$MODULE_DIR$` 或 `$PROJECT_DIR$/studyagent-backend/agent-start`

6. **保存并运行**
   - 点击 `OK` 保存配置
   - 点击运行按钮启动应用

### 方法 2: 通过 Environment Variables 配置

1. **打开 Run Configuration**
   - 点击 `Edit Configurations...`

2. **添加环境变量**
   - 在 `Environment variables` 中添加：
   ```
   SPRING_PROFILES_ACTIVE=local
   DB_HOST=localhost
   DB_PORT=3306
   DB_NAME=studyagent
   DB_USERNAME=root
   DB_PASSWORD=fzk971228
   ```

3. **保存并运行**

### 方法 3: 通过 VM Options 配置

1. **打开 Run Configuration**
   - 点击 `Edit Configurations...`

2. **配置 VM options**
   - 在 `VM options` 中输入：
   ```
   -Dspring.profiles.active=local
   ```

3. **保存并运行**

## 🔍 验证配置

启动后，查看控制台输出，应该看到：

```
The following profiles are active: local
```

或者查看日志文件 `logs/studyagent-backend.log`，应该看到：

```
Active profiles: local
```

## 📋 配置文件说明

| Profile | 配置文件 | 用途 | 数据库配置 |
|---------|---------|------|-----------|
| `local` | `application-local.yml` | 本地开发环境 | `localhost:3306`, `root/fzk971228` |
| `dev` | `application-dev.yml` | 开发环境（调试） | `localhost:13306`, `studyagent/studyagent2024` |
| `docker` | `application-docker.yml` | Docker 环境 | `mysql:3306`, `studyagent/studyagent2024` |
| `prod` | `application-prod.yml` | 生产环境 | 生产数据库配置 |

## ⚠️ 注意事项

1. **Profile 名称必须匹配**
   - 配置文件命名：`application-{profile}.yml`
   - Profile 名称：`{profile}`
   - 例如：`application-local.yml` → Profile: `local`

2. **环境变量优先级**
   - 环境变量 > `application-{profile}.yml` > `application.yml`
   - `.env` 文件中的环境变量会被加载（如果配置了）

3. **数据库配置**
   - 本地 MySQL：使用 `local` profile
   - Docker MySQL：使用 `dev` profile（端口 13306）

## 🚀 快速配置模板

### IDEA Run Configuration JSON（可选）

如果使用 IDEA 的配置文件，可以在 `.idea/runConfigurations/` 目录下创建配置：

```json
{
  "name": "StudyAgentApplication (Local)",
  "type": "SpringBootApplicationConfigurationType",
  "request": "launch",
  "mainClass": "com.studyagent.start.StudyAgentApplication",
  "projectName": "studyagent-backend",
  "args": "",
  "vmArgs": "-Dspring.profiles.active=local",
  "env": {
    "SPRING_PROFILES_ACTIVE": "local",
    "DB_HOST": "localhost",
    "DB_PORT": "3306",
    "DB_NAME": "studyagent",
    "DB_USERNAME": "root",
    "DB_PASSWORD": "fzk971228"
  }
}
```

## ✅ 检查清单

启动前确认：
- [ ] Run Configuration 中设置了 `spring.profiles.active=local`
- [ ] 数据库 MySQL 服务正在运行
- [ ] 数据库 `studyagent` 已创建
- [ ] `.env` 文件中的数据库配置正确（可选）

启动后检查：
- [ ] 控制台显示 `Active profiles: local`
- [ ] 日志中没有数据库连接错误
- [ ] 应用成功启动在 `http://localhost:8080`

