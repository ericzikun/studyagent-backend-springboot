# Clerk 配置说明

## 配置项

| 配置项 | 环境变量 | 用途 |
|---|---|---|
| `secret-key` | `CLERK_SECRET_KEY` | 后端用户资料 API；未配置 JWT 公钥时也用于 SDK 获取 JWKS |
| `publishable-key` | `CLERK_PUBLISHABLE_KEY` | Clerk 前端实例标识 |
| `jwt-key` | `CLERK_JWT_KEY` | 推荐使用的 PEM JWT 公钥，用于无网络验签 |
| `authorized-parties` | `CLERK_AUTHORIZED_PARTIES` | 允许的 Token `azp` 来源，多个来源以逗号分隔 |
| `api-url` | `CLERK_API_URL` | Clerk Backend API 地址，用于服务器端用户资料查询 |

示例只使用占位值，真实密钥必须通过未提交的环境文件或部署平台 Secret 注入：

```bash
CLERK_SECRET_KEY=sk_live_xxx
CLERK_PUBLISHABLE_KEY=pk_live_xxx
CLERK_JWT_KEY='-----BEGIN PUBLIC KEY-----\n...\n-----END PUBLIC KEY-----'
CLERK_AUTHORIZED_PARTIES='https://verla.io,https://www.verla.io'
```

## 验证方式

后端使用 Clerk 官方 Java SDK 验证 session token 签名，不调用 Frontend API `/me`，
也不信任只经过 Base64 解码的 JWT payload。缺少有效验签配置时，受保护请求失败关闭。

本地使用真实 Clerk 而不是 `auth.dev-bypass` 时，需要把实际本地前端来源加入
`CLERK_AUTHORIZED_PARTIES`，例如 `http://localhost:3000`。

