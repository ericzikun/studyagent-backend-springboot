# Notify API 接口文档

更新时间：2026-04-02  
接口版本：v1（Message 通道）

## 1. 适用范围

本文档面向各服务负责人，用于接入统一通知接口 `Notify API`，在业务事件发生时主动向钉钉发送通知。

当前只支持 `message` 通道（直达钉钉），不包含 Alertmanager 治理能力。

## 2. 接口概览

- 方法：`POST`
- 路径：`/api/v1/notify/events`
- 请求头：
  - `Content-Type: application/json`
  - `X-Notify-Token: <固定 token>`
- 响应格式：统一 `meta + data`
- 协议语义：HTTP 层通常返回 `200`，业务结果以 `meta.statusCode` 为准

## 3. 鉴权与环境

## 3.1 X-Notify-Token

- 由服务端校验请求头 `X-Notify-Token`。
- token 错误会返回 `4001`。
- token 必须按环境隔离（`local/test/online` 各自独立）。

## 3.2 三环境建议

- `local`：本地 `.env` 注入 `NOTIFY_API_TOKEN`
- `test/online`：由部署环境（Docker Compose 或平台变量）注入 `NOTIFY_API_TOKEN`
- 禁止把真实 token 提交到 GitHub 仓库

## 4. 请求规范

## 4.1 请求体 JSON

```json
{
  "eventId": "evt_20260402_10001",
  "sourceService": "springboot_backend",
  "scene": "payment.success",
  "title": "支付成功通知",
  "content": "用户 user_123 完成支付，金额 99 元",
  "level": "info",
  "contentType": "markdown",
  "env": "test",
  "timestamp": "2026-04-02T10:00:00+08:00",
  "metadata": {
    "bizType": "payment",
    "orderId": "ord_001",
    "operator": "scheduler"
  }
}
```

## 4.2 字段定义

| 字段 | 必填 | 类型 | 说明 |
|---|---|---|---|
| `eventId` | 否 | string | 建议传。用于幂等键（`sourceService + eventId`）。超过 64 会被截断。 |
| `sourceService` | 是 | enum string | 调用方服务标识：`springboot_backend` \| `python_backend` \| `frontend` \| `humanizer` |
| `scene` | 否 | string | 业务场景，建议 `domain.action`，如 `payment.success` |
| `title` | 是 | string | 标题，1-80 字符 |
| `content` | 是 | string | 正文，1-2000 字符；发送到钉钉时会截断到 1000 字符 |
| `level` | 否 | enum string | `info` \| `warn` \| `error` \| `critical`，默认 `info` |
| `contentType` | 否 | enum string | `text` \| `markdown`，默认 `markdown` |
| `env` | 否 | enum string | `local` \| `test` \| `online`，默认 `notify.default-env`（默认 `online`） |
| `timestamp` | 否 | string | 建议 ISO-8601 |
| `metadata` | 否 | object | 值类型仅允许 `string/number/boolean` |

## 4.3 metadata 展示规则（重要）

- 请求可带 `metadata`，但钉钉展示只保留白名单键：
  - `scene`, `env`, `bizType`, `orderId`, `taskId`, `errorCode`, `operator`, `userId`, `paymentId`, `runId`, `service`, `module`
- 非白名单键不会展示到钉钉消息（会被忽略）。

## 5. 响应规范

## 5.1 统一响应结构

```json
{
  "meta": {
    "statusCode": 0,
    "statusMsg": "success",
    "traceId": "trace_xxx"
  },
  "data": {}
}
```

## 5.2 成功示例

```json
{
  "meta": {
    "statusCode": 0,
    "statusMsg": "success",
    "traceId": "trace_20260402_abc123"
  },
  "data": {
    "eventId": "evt_20260402_10001",
    "sourceService": "springboot_backend",
    "scene": "payment.success",
    "level": "info",
    "contentType": "markdown",
    "env": "test",
    "status": "sent",
    "deliveryId": "dt_1743589700000_ab12cd34",
    "error": null
  }
}
```

## 5.3 失败示例（token 错误）

```json
{
  "meta": {
    "statusCode": 4001,
    "statusMsg": "invalid notify token",
    "traceId": "trace_20260402_def456"
  },
  "data": {
    "eventId": "evt_20260402_10001",
    "sourceService": "springboot_backend",
    "scene": "payment.success",
    "level": "info",
    "contentType": "markdown",
    "env": "test",
    "status": "rejected",
    "deliveryId": null,
    "error": {
      "type": "AUTH_ERROR",
      "detail": "X-Notify-Token is invalid",
      "retryable": false
    }
  }
}
```

## 5.4 状态与错误码

### `data.status`

- `sent`：已发送到钉钉
- `deduplicated`：命中幂等窗口，未重复发送
- `rejected`：请求被拒绝（鉴权/校验/限流）
- `failed`：下游失败（如钉钉调用失败）

### `data.error.type`

- `VALIDATION_ERROR`
- `AUTH_ERROR`
- `RATE_LIMIT`
- `DUPLICATE_EVENT`
- `DOWNSTREAM_ERROR`

### `meta.statusCode`

- `0`：成功
- `4000`：参数校验失败
- `4001`：鉴权失败
- `4002`：限流
- `4003`：幂等去重命中
- `4004`：枚举值非法
- `5000`：下游失败或内部错误

### HTTP 状态码说明

- 调用方应始终解析响应体 `meta.statusCode`，不要只看 HTTP 状态码。
- 典型判定：
  - `meta.statusCode = 0`：成功
  - `meta.statusCode = 4003` 且 `data.status = deduplicated`：幂等命中，通常可按“业务成功”处理
  - 其他非 0：按失败处理并进入对应重试/修复流程

## 6. 调用方最佳实践

## 6.1 幂等（强烈建议）

- 传 `eventId`，并在“同一业务事件重试”时复用同一个 `eventId`。
- 当前去重窗口默认 10 分钟（服务端可配置）。
- 去重键：`sourceService + eventId`。

## 6.2 重试策略

- 仅在以下场景重试：
  - HTTP 超时/网络异常
  - `meta.statusCode=5000` 且 `data.error.retryable=true`
  - `meta.statusCode=4002`（限流），做退避重试
- 不要重试：
  - `4001`（token 错误）
  - `4000/4004`（请求不合法）

建议退避：`1s -> 2s -> 4s`，最多 3 次。

## 6.3 日志与排障

- 调用方应记录：`eventId`、`sourceService`、`meta.traceId`、HTTP 状态码、响应体。
- 线上排障优先使用 `traceId` 对齐服务端日志。

## 6.4 数据安全

- 不要在 `content`/`metadata` 传明文密码、token、secret、手机号、邮箱等敏感数据。
- 仅传业务必要字段，避免通知内容过长和冗余。

## 7. 快速联调（curl）

```bash
curl -X POST "http://localhost:8080/api/v1/notify/events" \
  -H "Content-Type: application/json" \
  -H "X-Notify-Token: ${NOTIFY_API_TOKEN}" \
  -d '{
    "eventId": "evt_20260402_local_001",
    "sourceService": "springboot_backend",
    "scene": "notify.local.test",
    "title": "Notify API本地联调",
    "content": "这是一条本地测试消息",
    "level": "info",
    "contentType": "markdown",
    "env": "local",
    "metadata": {
      "operator": "local_test"
    }
  }'
```

## 8. 对接检查清单（服务负责人）

- [ ] 已拿到本环境 `X-Notify-Token`
- [ ] `sourceService` 使用合法枚举值
- [ ] 生产场景已接入稳定 `eventId`（支持幂等重试）
- [ ] 已按规范处理 `4001/4002/5000`
- [ ] 已记录 `traceId` 用于排障
