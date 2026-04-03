# Notify API 接口文档

更新时间：2026-04-03  
接口版本：v1（Message 通道）

> metadata 策略说明（当前实现）：
> 1) `metadata` value 类型已放开，支持复杂 JSON 结构（object/array 等）。  
> 2) 保留 `sanitizeMetadata` 统一处理流程。  
> 3) 当前不启用 metadata key 白名单过滤；后续可在 `sanitizeMetadata` 位置按治理需要补充白名单逻辑。

## 1. 适用范围

本文档面向用于接入统一通知接口 `Notify API`，在业务事件发生时主动向钉钉发送通知。

当前只支持 `message` 通道（触发直达钉钉），不包含 Alertmanager 治理能力。

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
- token 按环境隔离（`local/test/online` 各自独立）。

## 3.2 三环境token 位置

- `local`：本地 `.env` 注入 `NOTIFY_API_TOKEN`
- `test/online`：由部署环境 docker_compose.yml注入位于同目录下 `.env` 的 `NOTIFY_API_TOKEN`
- 禁止把真实 token 提交到 GitHub 仓库

## 4. 请求规范

## 4.1 请求体（通用模板）

```json
{
  "eventId": "<string, optional, 1-64，建议唯一，如 evt_20260402_10001>",
  "sourceService": "<enum, required, springboot_backend|python_backend|frontend|humanizer>",
  "scene": "<string, optional, 1-64，如 payment.success>",
  "title": "<string, required, 1-80>",
  "content": "<string, required, 1-2000>",
  "level": "<enum, optional, info|warn|error|critical，默认 info>",
  "contentType": "<enum, optional, text|markdown，默认 markdown>",
  "env": "<enum, optional, local|test|online，默认取 notify.default-env>",
  "timestamp": "<string, optional, ISO-8601 格式>",
  "metadata": {
    "<key:string>": "<value:any-json>"
  }
}
```

## 4.2 字段取值规范

1. `eventId`
   - 可选，建议传；用于幂等去重（去重键：`sourceService + eventId`）。
   - 未传时由服务端生成并在响应中返回。
2. `sourceService`
   - 必填枚举：`springboot_backend`、`python_backend`、`frontend`、`humanizer`。
3. `scene`
   - 可选；建议使用 `domain.action` 风格（如 `payment.success`、`task.failed`）。
4. `title`
   - 必填；用于钉钉消息标题，长度 1-80。
5. `content`
   - 必填；正文长度 1-2000。
   - 发送到钉钉时会截断到 1000 字符，建议调用方控制内容长度。
6. `level`
   - 可选枚举：`info`、`warn`、`error`、`critical`；默认 `info`。
7. `contentType`
   - 可选枚举：`text`、`markdown`；默认 `markdown`。
8. `env`
   - 可选枚举：`local`、`test`、`online`。
   - 未传时默认取 `notify.default-env`（默认 `online`）。
9. `timestamp`
   - 可选；推荐 ISO-8601，未传则服务端填充为 `yyyy-MM-dd HH:mm:ss`（UTC+8）。
10. `metadata`
   - 可选；通用业务扩展字段。
   - 支持复杂 JSON 值类型（`object/array/string/number/boolean/null`）。
   - 进入发送前统一经过 `sanitizeMetadata` 处理（脱敏、结构标准化）。

## 4.3 调用示例

最小示例（快速接入）：

```json
{
  "sourceService": "springboot_backend",
  "title": "系统通知",
  "content": "服务启动完成"
}
```

完整示例（推荐生产）：

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

## 4.4 metadata 展示规则

- 请求可带任意业务 `metadata` 字段，不按白名单丢弃 key。
- `metadata` 仍统一经过 `sanitizeMetadata`，用于标准化输出与后续治理扩展。
- 代码中会保留注释说明：后续可在 `sanitizeMetadata` 位置引入白名单过滤策略。
- 敏感键（如 `phone/mobile/email/token/secret/password/accesskey`）仍按脱敏规则处理。

## 5. 响应规范

## 5.1 统一响应模板

```json
{
  "meta": {
    "statusCode": "<number, 0 表示成功>",
    "statusMsg": "<string>",
    "traceId": "<string, 服务端生成，用于排障>"
  },
  "data": {
    "...": "..."
  }
}
```

## 5.2 成功响应示例

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

## 5.3 失败响应示例（token 错误）

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

## 5.4 枚举字段与错误码

### 5.4.1 请求体枚举

1. `sourceService`：`springboot_backend` | `python_backend` | `frontend` | `humanizer`
2. `level`：`info` | `warn` | `error` | `critical`
3. `contentType`：`text` | `markdown`
4. `env`：`local` | `test` | `online`

### 5.4.2 响应体枚举

1. `data.status`：`sent` | `deduplicated` | `rejected` | `failed`
2. `data.error.type`：`VALIDATION_ERROR` | `AUTH_ERROR` | `RATE_LIMIT` | `DUPLICATE_EVENT` | `DOWNSTREAM_ERROR`

### 5.4.3 `meta.statusCode`

- `0`：成功
- `4000`：参数校验失败
- `4001`：鉴权失败
- `4002`：限流
- `4003`：幂等去重命中
- `4004`：枚举值非法
- `5000`：下游失败或内部错误

### 5.4.4 HTTP 状态码说明

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

## 8. 对接检查清单

- [ ] 已拿到本环境 `X-Notify-Token`
- [ ] `sourceService` 使用合法枚举值
- [ ] 生产场景已接入稳定 `eventId`（支持幂等重试）
- [ ] 已按规范处理 `4001/4002/5000`
- [ ] 已记录 `traceId` 用于排障
