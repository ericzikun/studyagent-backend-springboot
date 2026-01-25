# Clerk Token 验证修复说明

## 🐛 问题

调用 `verifyToken` 方法时返回 401 Unauthorized：
```
org.springframework.web.reactive.function.client.WebClientResponseException$Unauthorized: 
401 Unauthorized from GET https://api.clerk.dev/v1/users/me
```

## 🔍 原因分析

1. **错误的 API Endpoint**: 使用了 Backend API 的 `/users/me` endpoint
   - Backend API (`https://api.clerk.dev/v1/users/me`) 需要 **Secret Key** 认证
   - 不能使用 **Session Token** 来调用

2. **正确的验证方式**: 应该使用 Frontend API 的 `/v1/me` endpoint
   - Frontend API (`https://<your-app>.clerk.accounts.dev/v1/me`) 接受 **Session Token**
   - 这是验证用户 session token 的正确方式

## ✅ 已修复

### 1. 修改验证逻辑

**文件**: `agent-infra/src/main/java/com/studyagent/infra/client/clerk/ClerkClientImpl.java`

**修改内容**:
- ✅ 优先使用 Frontend API 的 `/v1/me` endpoint（使用 session token）
- ✅ 完善用户信息提取逻辑（邮箱、显示名称、头像等）
- ✅ 添加错误处理和日志

### 2. 添加配置项

**文件**: `agent-start/src/main/resources/application.yml`

**新增配置**:
```yaml
clerk:
  frontend-api-url: ${CLERK_FRONTEND_API_URL:}
```

## 🔧 配置步骤

### 1. 获取 Frontend API URL

从 Clerk Dashboard 获取你的 Frontend API URL：
- 格式：`https://<your-app>.clerk.accounts.dev`
- 例如：`https://splendid-roughy-33.clerk.accounts.dev`

### 2. 配置环境变量

#### 方式 1: 使用 `.env` 文件（推荐）

在 `studyagent-backend/.env` 文件中添加：

```bash
# Clerk Frontend API URL（用于验证 session token）
CLERK_FRONTEND_API_URL=https://splendid-roughy-33.clerk.accounts.dev
```

#### 方式 2: 使用 `application-local.yml`

在 `agent-start/src/main/resources/application-local.yml` 中添加：

```yaml
clerk:
  frontend-api-url: https://splendid-roughy-33.clerk.accounts.dev
```

#### 方式 3: 使用系统环境变量

```bash
export CLERK_FRONTEND_API_URL=https://splendid-roughy-33.clerk.accounts.dev
```

### 3. 验证配置

重启 SpringBoot 后端后，检查日志：

```
Frontend API 验证成功，用户 ID: user_xxxxx
```

## 📝 配置示例

### 完整的 `.env` 配置

```bash
# Clerk 配置
CLERK_SECRET_KEY=sk_test_xxxxx
CLERK_PUBLISHABLE_KEY=pk_test_xxxxx
CLERK_API_URL=https://api.clerk.dev/v1
CLERK_FRONTEND_API_URL=https://splendid-roughy-33.clerk.accounts.dev
```

### 完整的 `application-local.yml` 配置

```yaml
clerk:
  secret-key: ${CLERK_SECRET_KEY:sk_test_xxx}
  publishable-key: ${CLERK_PUBLISHABLE_KEY:pk_test_xxx}
  api-url: ${CLERK_API_URL:https://api.clerk.dev/v1}
  frontend-api-url: ${CLERK_FRONTEND_API_URL:https://splendid-roughy-33.clerk.accounts.dev}
```

## 🔄 验证流程

### 1. Token 验证流程

```
前端发送请求
  ↓
Authorization: Bearer <session_token>
  ↓
SpringBoot 后端接收
  ↓
调用 ClerkClientImpl.verifyToken()
  ↓
使用 Frontend API: https://<your-app>.clerk.accounts.dev/v1/me
  ↓
返回用户信息
```

### 2. API 调用示例

**Frontend API** (正确 ✅):
```http
GET https://splendid-roughy-33.clerk.accounts.dev/v1/me
Authorization: Bearer <session_token>
```

**Backend API** (错误 ❌):
```http
GET https://api.clerk.dev/v1/users/me
Authorization: Bearer <session_token>  # ❌ 需要 Secret Key，不是 Session Token
```

## ⚠️ 注意事项

1. **Frontend API vs Backend API**:
   - Frontend API: 用于验证 session token，接受用户 token
   - Backend API: 用于服务器端操作，需要 Secret Key

2. **配置优先级**:
   - 环境变量 > application.yml > 默认值
   - 如果 `CLERK_FRONTEND_API_URL` 未配置，验证会失败

3. **错误处理**:
   - 如果 Frontend API 不可用，会记录警告日志
   - 建议始终配置 Frontend API URL

## 🚀 测试

### 1. 测试 Token 验证

```bash
# 使用有效的 session token 测试
curl -X GET "http://localhost:8080/v1/auth/me" \
  -H "Authorization: Bearer <your_session_token>"
```

### 2. 检查日志

查看日志文件 `logs/studyagent-backend.log`，应该看到：
```
Frontend API 验证成功，用户 ID: user_xxxxx
```

## 📚 参考

- [Clerk Frontend API Documentation](https://clerk.com/docs/reference/frontend-api)
- [Clerk Backend API Documentation](https://clerk.com/docs/reference/backend-api)
- Python 后端实现参考：`curve-master/backend/app/services/clerk_service.py`

