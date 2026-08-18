# 公开邮箱留资接口

## 责任边界

Spring Boot 是公开邮箱留资的唯一写入边界。V2 前端调用 `POST /v1/public/email-leads`；Python Agent、Resend 和邮件发送链路均不参与。

请求体：

```json
{
  "email": "student@example.com",
  "source": "/tools/essay-title-generator",
  "companyWebsite": ""
}
```

- `email`：服务端执行 trim 和 lowercase 后校验，最大 254 字符。
- `source`：只允许 `/tools`、`/use-cases` 或 `/tools/<英文 slug>`，不接收 query、hash 或完整 URL。
- `companyWebsite`：蜜罐字段。正常用户必须留空；非空时静默返回 `202`，不访问 Redis 或 MySQL。

首次、重复和蜜罐命中均返回相同的 `202 Accepted` 数据形状。非法邮箱/来源返回 `400`，单 IP 或每日预算超限返回 `429`，Redis 不可用返回 `503`。

## 数据边界

部署 API 前先应用 [`sql/080_email_leads.sql`](../sql/080_email_leads.sql)。`email_leads` 只包含：

- `email_normalized`
- `source_path`（首次来源）
- `created_at`

唯一索引保证同一规范化邮箱最多一行，重复提交不会更新首次来源。当前记录是未验证留资，不是已确认订阅者；表中没有 `consent_version`、订阅状态、确认状态或邮件供应商字段。

## 写入保护

每次合法提交先在 Redis 执行单 IP 短窗口计数，再查询邮箱是否已经存在；已存在的邮箱直接按成功处理，不竞争当日新增名额。只有尚不存在的邮箱才原子预占名额。MySQL 唯一索引若在并发写入时确认重复，则立即归还该名额；数据库写入失败也会尝试归还。进程在预占后异常退出可能让当日计数少量偏高，但不会突破数据库增长上限。

可配置环境变量：

| 环境变量 | 默认值 | 作用 |
| --- | --- | --- |
| `PUBLIC_EMAIL_LEAD_REDIS_KEY_PREFIX` | `public-email-lead:v1` | Redis key 前缀 |
| `PUBLIC_EMAIL_LEAD_IP_WINDOW` | `10m` | 单 IP 计数窗口 |
| `PUBLIC_EMAIL_LEAD_IP_MAX_REQUESTS` | `5` | 单窗口最大请求数 |
| `PUBLIC_EMAIL_LEAD_DAILY_NEW_MAX` | `1000` | 每日最多新增邮箱数 |

Redis 异常时失败关闭，不降级为无保护写库。IP 只以 SHA-256 摘要写入短期 Redis key，不写 MySQL。只有 socket 对端是本机或 RFC1918 私网代理时才读取 Nginx 覆盖写入的 `X-Real-IP`；直接访问后端公共端口时忽略转发头并使用 socket 地址。部署入口必须继续使用 `proxy_set_header X-Real-IP $remote_addr`。

## 隐私与后续使用

Controller 关闭通用请求体日志，业务异常与限流日志不包含邮箱。后端不发送 PostHog 事件。前端成功事件只包含入口和页面路径。

当前没有发信或群发能力。未来开始营销邮件前，需要另行评审邮箱确认、同意记录、退订、保留期限和访问权限，不能直接把现有数据视作已验证订阅名单。
