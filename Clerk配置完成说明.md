# Clerk 配置完成说明

## ✅ 已完成的配置

### 1. `.env` 文件配置

已更新 `studyagent-backend/.env` 文件，包含完整的 Clerk 配置：

```bash
# Clerk 配置
CLERK_SECRET_KEY=sk_test_QYua2gdS8FOREKaKquqmrEYAs99PdEz00AHJ9ke7kj
CLERK_PUBLISHABLE_KEY=pk_test_c3BsZW5kaWQtcm91Z2h5LTMzLmNsZXJrLmFjY291bnRzLmRldiQ
CLERK_API_URL=https://api.clerk.dev/v1
CLERK_FRONTEND_API_URL=https://splendid-roughy-33.clerk.accounts.dev
```

### 2. `application-local.yml` 配置

已更新 `agent-start/src/main/resources/application-local.yml`，添加了 Clerk 配置：

```yaml
clerk:
  secret-key: ${CLERK_SECRET_KEY:sk_test_xxx}
  publishable-key: ${CLERK_PUBLISHABLE_KEY:pk_test_xxx}
  api-url: ${CLERK_API_URL:https://api.clerk.dev/v1}
  frontend-api-url: ${CLERK_FRONTEND_API_URL:}
```

## 📋 配置说明

### Clerk 配置项说明

| 配置项 | 环境变量 | 说明 | 示例值 |
|--------|---------|------|--------|
| `secret-key` | `CLERK_SECRET_KEY` | Clerk Secret Key（后端使用） | `sk_test_xxx` |
| `publishable-key` | `CLERK_PUBLISHABLE_KEY` | Clerk Publishable Key（前端使用） | `pk_test_xxx` |
| `api-url` | `CLERK_API_URL` | Clerk Backend API URL | `https://api.clerk.dev/v1` |
| `frontend-api-url` | `CLERK_FRONTEND_API_URL` | Clerk Frontend API URL（用于验证 token） | `https://splendid-roughy-33.clerk.accounts.dev` |

### 配置优先级

1. **环境变量** (`.env` 文件) - 最高优先级
2. **application-local.yml** - 本地环境默认值
3. **application.yml** - 全局默认值

## 🔍 验证配置

### 1. 检查环境变量

```bash
cd studyagent-backend
cat .env | grep CLERK
```

应该看到：
```
CLERK_SECRET_KEY=sk_test_QYua2gdS8FOREKaKquqmrEYAs99PdEz00AHJ9ke7kj
CLERK_PUBLISHABLE_KEY=pk_test_c3BsZW5kaWQtcm91Z2h5LTMzLmNsZXJrLmFjY291bnRzLmRldiQ
CLERK_API_URL=https://api.clerk.dev/v1
CLERK_FRONTEND_API_URL=https://splendid-roughy-33.clerk.accounts.dev
```

### 2. 重启 SpringBoot 后端

配置更新后，需要重启后端服务：

```bash
cd studyagent-backend/agent-start
mvn spring-boot:run
```

### 3. 测试 Token 验证

```bash
# 使用有效的 session token 测试
curl -X GET "http://localhost:8080/v1/auth/me" \
  -H "Authorization: Bearer <your_session_token>"
```

### 4. 检查日志

查看日志文件 `logs/studyagent-backend.log`，应该看到：

```
Frontend API 验证成功，用户 ID: user_xxxxx
```

## ⚠️ 注意事项

1. **`.env` 文件安全**:
   - `.env` 文件包含敏感信息，不要提交到 Git
   - 确保 `.env` 在 `.gitignore` 中

2. **配置同步**:
   - 确保 Python 后端和 SpringBoot 后端使用相同的 Clerk 配置
   - Frontend API URL 必须一致

3. **环境变量格式**:
   - 不要有多余的空格
   - URL 不要有尾随斜杠（代码会自动处理）

## 🚀 下一步

配置完成后，Clerk token 验证应该可以正常工作了。如果还有问题，请检查：

1. ✅ `.env` 文件中的配置是否正确
2. ✅ 是否重启了 SpringBoot 后端
3. ✅ 日志中是否有错误信息
4. ✅ Frontend API URL 是否正确（不要有尾随斜杠）

