# 修复 MyBatis-Plus 兼容性问题

## 问题描述

启动时出现错误：
```
java.lang.IllegalArgumentException: Invalid value type for attribute 'factoryBeanObjectType': java.lang.String
```

这是 MyBatis-Plus 3.5.3-3.5.5 与 Spring Boot 3.2.0 的兼容性问题。

## 解决方案

### 1. 升级 MyBatis-Plus 版本

已升级到 `3.5.7`，该版本修复了与 Spring Boot 3.2+ 的兼容性问题。

### 2. 移动 @MapperScan 到主启动类

将 `@MapperScan` 从 `MyBatisConfig` 移动到 `StudyAgentApplication` 主类中，避免配置冲突。

### 3. 清理并重新构建

**重要**: 必须清理 Maven 缓存并重新构建：

```bash
# 1. 清理项目
cd studyagent-backend
mvn clean

# 2. 删除 MyBatis-Plus 的本地缓存（可选，但推荐）
rm -rf ~/.m2/repository/com/baomidou/mybatis-plus

# 3. 重新构建
mvn install -U

# 4. 启动应用
cd agent-start
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run
```

## 如果问题仍然存在

### 方案1: 使用最新版本

如果 3.5.7 仍有问题，可以尝试最新版本：

```xml
<mybatis-plus.version>3.5.9</mybatis-plus.version>
```

### 方案2: 降级 Spring Boot（不推荐）

如果必须使用旧版本 MyBatis-Plus，可以降级 Spring Boot：

```xml
<spring-boot.version>3.1.5</spring-boot.version>
```

### 方案3: 检查依赖冲突

```bash
mvn dependency:tree | grep mybatis
```

确保没有版本冲突。

## 验证

启动成功后，应该能看到：

```
Started StudyAgentApplication in X.XXX seconds
```

访问健康检查：

```bash
curl http://localhost:8080/health
```

