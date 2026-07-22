# Clerk Token 验签说明

## 认证边界

Spring Boot 所有调用 `ClerkClient.verifyToken()` 的路径统一使用 Clerk Java SDK
验证 session token。系统只信任通过以下检查的 claims：

- 当前 Clerk 实例的 JWT 签名；
- Token 有效期和生效时间；
- 配置存在时的 authorized parties；
- 必须存在的用户标识 `sub`。

系统不再解码并信任未验签 JWT payload，也不会在 SDK、JWKS 或网络异常时降级认证。

## 推荐配置

从 Clerk Dashboard 的 API keys 页面取得 JWT 公钥，并通过部署平台注入：

```bash
CLERK_SECRET_KEY=sk_live_xxx
CLERK_PUBLISHABLE_KEY=pk_live_xxx
CLERK_JWT_KEY='-----BEGIN PUBLIC KEY-----\n...\n-----END PUBLIC KEY-----'
CLERK_AUTHORIZED_PARTIES='https://verla.io,https://www.verla.io'
```

`CLERK_JWT_KEY` 支持真实换行和字面量 `\n`。配置公钥后验签不依赖 Clerk 网络；
未配置公钥时，SDK 使用 `CLERK_SECRET_KEY` 获取并缓存 JWKS。

不要把真实 Secret Key、JWT 或用户 session token 写入仓库、日志和 URL 示例。

## SSE 兼容

迁移期间 `GET /v1/verla/conversations/{cid}/events` 仍可从 `access_token` 查询参数
取得 Token。该 Token 只是在拦截器中的提取位置不同，后续与 Authorization header
中的 Token 使用完全相同的签名验证。

## 验证重点

- 使用真实有效 Clerk Token 调用普通受保护 API 和 Verla SSE，应正常通过。
- 篡改 payload、使用其他密钥签名或使用过期 Token，应返回 401。
- JWT 公钥配置错误或 JWKS 不可用时，应返回认证服务不可用，且不得建立 SSE 连接。

