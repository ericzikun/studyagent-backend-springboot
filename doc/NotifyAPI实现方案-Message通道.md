# Notify API 实现方案（Message 通道）

> metadata 策略更新（2026-04-03，已实现）：
> 1) 已放开 `metadata` value 类型限制，支持复杂 JSON 结构（object/array 等）。  
> 2) 保留 `sanitizeMetadata` 统一处理流程。  
> 3) 当前不启用 key 白名单过滤，后续可在 `sanitizeMetadata` 位置按治理需要扩展。

## 1. 背景与目标

当前监控链路 `Prometheus -> Alertmanager -> webhook-dingtalk -> DingTalk` 主要用于时序指标告警（宕机、资源、成功率/失败率阈值）。

新增需求是给各服务负责人提供一条自助通知能力：

1. 服务在业务事件发生时主动调用统一接口。
2. 系统将通知一次性发送到钉钉机器人。
3. 优先保证易接入、可快速上线，不引入复杂告警治理。

本方案只实现 `message` 通道，不实现 `alert` 通道。

## 2. 范围与非目标

### 2.1 本期范围（In Scope）

1. 在 Spring Boot 提供统一通知入口 `Notify API`。
2. 支持业务事件单点触发，直接发送到钉钉。
3. 提供最小安全能力：服务鉴权、基本限流、可选幂等。
4. 提供结构化日志与基础可观测字段，便于排障。

### 2.2 非目标（Out of Scope）

1. 不实现 Alertmanager 注入逻辑（不做 alert 通道）。
2. 不做复杂告警治理（静默、恢复、抑制、升级）。
3. 不做多通道通知（邮件、短信、飞书等）。
4. 不做租户级复杂模板系统。

## 3. 为什么放在 Spring Boot

建议将 Notify API 放在 `studyagent-backend-springboot`，理由：

1. Spring Boot 是当前主业务入口，服务边界清晰，易统一治理。
2. 现有架构已存在“外部事件 -> Spring Boot 接收 -> 应用层处理”的模式，可复用分层习惯。
3. `studyagent-monitoring` 目录定位是监控编排与配置，不适合承载业务通知 API。

## 4. 总体架构

```text
业务服务A/B/C
    |
    | HTTP POST /api/v1/notify/events
    v
Spring Boot Notify API
    |- 鉴权(token)
    |- 参数校验
    |- 可选幂等检查(eventId)
    |- 基础限流
    v
DingTalk Client
    |
    | DingTalk Robot Webhook API
    v
钉钉群消息
```

## 5. 接口设计

### 5.1 Endpoint

`POST /api/v1/notify/events`

### 5.2 请求头

1. `Content-Type: application/json`
2. `X-Notify-Token: <token>`（服务鉴权，必填）

### 5.3 请求体（通用模板 + 示例）

`Notify API` 是通用消息入口，不绑定支付场景。  
最小必填字段确实只有 3 个（`sourceService/title/content`），但生产建议额外传 `eventId/scene/level/contentType/env` 以便治理和排障。

#### 5.3.1 通用请求模板

```json
{
  "eventId": "<string, optional, 1-64，建议唯一，如 evt_20260401_10001>",
  "sourceService": "<enum, required, springboot_backend|python_backend|frontend|humanizer>",
  "scene": "<string, optional, 1-64，如 order.shipped>",
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

#### 5.3.2 字段取值规范

1. `eventId`
   - 可选，建议传；用于幂等去重。
   - 未传时由服务端生成并在响应中返回。
2. `sourceService`
   - 必填枚举：`springboot_backend`、`python_backend`、`frontend`、`humanizer`。
3. `scene`
   - 可选；建议使用 `domain.action` 风格（如 `payment.success`、`task.failed`）。
4. `title`
   - 必填；用于钉钉消息标题，建议简短明确。
5. `content`
   - 必填；正文内容，建议避免敏感信息明文。
6. `level`
   - 可选枚举：`info`、`warn`、`error`、`critical`。
7. `contentType`
   - 可选枚举：`text`、`markdown`，默认 `markdown`。
8. `env`
   - 可选枚举：`local`、`test`、`online`，默认取配置 `notify.default-env`（默认 `online`）。
9. `timestamp`
   - 可选；推荐 ISO-8601，未传则服务端填充为 `yyyy-MM-dd HH:mm:ss`（UTC+8）。
10. `metadata`
   - 可选；通用业务扩展字段。
   - 支持复杂 JSON 值类型（`object/array/string/number/boolean/null`）。
   - 请求进入发送前统一经过 `sanitizeMetadata` 处理。

#### 5.3.3 调用示例

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
  "eventId": "evt_20260401_10001",
  "sourceService": "python_backend",
  "scene": "task.failed",
  "title": "任务执行失败",
  "content": "任务 task_9527 执行失败，错误码 E_AGENT_TIMEOUT",
  "level": "error",
  "contentType": "markdown",
  "env": "online",
  "timestamp": "2026-04-01T15:20:30+08:00",
  "metadata": {
    "taskId": "task_9527",
    "errorCode": "E_AGENT_TIMEOUT",
    "operator": "scheduler"
  }
}
```

### 5.4 响应体（当前实现）

#### 5.4.1 统一响应模板

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

#### 5.4.2 成功响应示例

```json
{
  "meta": {
    "statusCode": 0,
    "statusMsg": "success",
    "traceId": "trace_20260401_a1b2c3"
  },
  "data": {
    "eventId": "evt_20260401_10001",
    "sourceService": "python_backend",
    "scene": "task.failed",
    "level": "error",
    "contentType": "markdown",
    "env": "online",
    "status": "sent",
    "deliveryId": "dt_7f0f9b...",
    "error": null
  }
}
```

#### 5.4.3 失败响应示例

```json
{
  "meta": {
    "statusCode": 4001,
    "statusMsg": "invalid notify token",
    "traceId": "trace_20260401_d4e5f6"
  },
  "data": {
    "eventId": "evt_20260401_10001",
    "sourceService": "python_backend",
    "status": "rejected",
    "error": {
      "type": "AUTH_ERROR",
      "detail": "X-Notify-Token is invalid",
      "retryable": false
    }
  }
}
```

#### 5.4.4 枚举字段一览（定稿）

请求体枚举：

1. `sourceService`：`springboot_backend` | `python_backend` | `frontend` | `humanizer`
2. `level`：`info` | `warn` | `error` | `critical`
3. `contentType`：`text` | `markdown`
4. `env`：`local` | `test` | `online`

响应体枚举：

1. `data.status`：`sent` | `deduplicated` | `rejected` | `failed`
2. `data.error.type`：`VALIDATION_ERROR` | `AUTH_ERROR` | `RATE_LIMIT` | `DUPLICATE_EVENT` | `DOWNSTREAM_ERROR`

建议错误码：

1. `4000` 参数校验失败
2. `4001` 鉴权失败
3. `4002` 触发频率过高（限流）
4. `4003` 重复事件（幂等命中）
5. `4004` 枚举值非法（如 `sourceService/level/contentType/env` 非法）
6. `5000` 下游钉钉调用失败

## 6. 钉钉消息格式

使用机器人 Markdown 消息，建议模板：

```text
【通知级别：<信息|警告|错误|紧急>】<title>

来源服务：<sourceService>
触发时间：<timestamp>
通知内容：<content>

补充信息：
- 业务场景：<scene>
- 运行环境：<env>
- k1: v1
- k2: v2
```

示例（`python_backend` 任务失败）：

```text
【通知级别：错误】任务执行失败

来源服务：python_backend
触发时间：2026-04-02T14:36:20+08:00
通知内容：任务 task_9527 执行失败，错误码 E_AGENT_TIMEOUT

补充信息：
- 业务场景：task.failed
- 运行环境：online
- taskId: task_9527
- errorCode: E_AGENT_TIMEOUT
```

说明：

1. 所有级别都在标题前加显式中文标识，方便群内快速识别。
2. `content` 建议截断上限（如 1000 字符），避免超长失败。
3. `metadata` 统一经 `sanitizeMetadata` 输出；当前不启用白名单过滤，后续可在该位置按治理需要引入白名单。

## 7. 安全与治理（最小版本）

### 7.1 鉴权

1. 使用 `X-Notify-Token`。
2. 服务端配置 `NOTIFY_API_TOKEN` 或按服务配置 token map。
3. 鉴权失败直接返回 `4001`。

### 7.2 幂等（可选但建议）

1. 使用 `eventId + sourceService` 作为幂等键。
2. 在内存缓存或 Redis 记录短 TTL（例如 10 分钟）。
3. 命中重复时返回 `4003`，避免重复刷屏。

### 7.3 限流（建议必做）

1. 按 `sourceService` 维度限流（例如 60 次/分钟）。
2. 超限返回 `4002` 并记录告警日志。

## 8. 工程落点（Spring Boot 分层）

### 8.1 `agent-api` 层

1. 新增 `NotifyController`：接收与校验请求。
2. 新增 DTO：
   - `NotifyEventRequest`
   - `NotifyEventResponse`
   - `NotifyErrorResponse`
   - 复用现有 `Result<T>` 统一响应包装（`meta + data`）

### 8.2 `agent-service` 层

1. 新增 `NotifyApplicationService`：编排鉴权、幂等、限流、投递。
2. 定义领域接口 `NotifySender`（抽象发送行为）。

### 8.3 `agent-infra` 层

1. 实现 `DingTalkNotifySender`：调用钉钉 webhook。
2. 新增 `DingTalkWebhookConfigLoader`：从 `notify.dingtalk.config-file` 加载机器人配置。

## 9. 配置项建议

### 9.1 基础配置（`application.yml`）

在 `application.yml` 增加（推荐）：

```yaml
notify:
  enabled: ${NOTIFY_ENABLED:false}
  api-token: ${NOTIFY_API_TOKEN:}
  default-env: ${NOTIFY_DEFAULT_ENV:online}
  dingtalk:
    config-file: ${NOTIFY_DINGTALK_CONFIG_FILE:}
  idempotency:
    enabled: true
    ttl-seconds: 600
  rate-limit:
    enabled: true
    per-service-per-minute: 60
```

字段说明（各配置项意义）：

1. `notify.enabled`
   - 是否启用 Notify API 功能总开关。
   - `false` 时接口可直接拒绝请求或返回未启用状态。
2. `notify.api-token`
   - 服务间调用鉴权 token，对应请求头 `X-Notify-Token`。
3. `notify.dingtalk.config-file`
   - 钉钉机器人配置文件路径（唯一配置来源）。
   - 文件结构可复用监控看板使用的 `dingtalk-webhook-config.yml`，读取 `targets.default.url/secret`。
4. `notify.idempotency.enabled`
   - 是否启用幂等去重能力（基于 `eventId + sourceService`）。
5. `notify.idempotency.ttl-seconds`
   - 去重窗口时长（秒），示例 `600` 即 10 分钟。
6. `notify.rate-limit.enabled`
   - 是否启用限流保护。
7. `notify.rate-limit.per-service-per-minute`
   - 按 `sourceService` 的每分钟请求上限，防止异常刷屏。

配置约束建议：

1. 必须配置 `notify.dingtalk.config-file`，并确保文件可读。
2. 文件读取 `targets.default.url/secret`。
3. `notify.enabled=true` 且 `config-file` 不可用时，启动失败（fail-fast）。

### 9.2 三环境钉钉机器人接入（沿用监控看板方式）

你当前监控看板已采用“仓库外密钥文件”方案，本接口建议直接复用同一套文件：

1. `local`：`$HOME/.studyagent-monitoring-secrets/local/dingtalk-webhook-config.yml`
2. `test`：`/etc/studyagent-monitoring-secrets/test/dingtalk-webhook-config.yml`
3. `online`：`/etc/studyagent-monitoring-secrets/online/dingtalk-webhook-config.yml`

### 9.3 启动方式差异（本地直启 vs 测试/线上 Docker）

1. 本地（Spring Boot 直启）
   - 进程直接读取宿主机文件，无需容器挂载。
   - 示例：

```bash
export SPRING_PROFILES_ACTIVE=local
export NOTIFY_DINGTALK_CONFIG_FILE="$HOME/.studyagent-monitoring-secrets/local/dingtalk-webhook-config.yml"
mvn spring-boot:run
```

2. 测试/线上（Docker 启动）
   - 容器内看不到宿主机文件，必须把密钥文件挂载到容器内路径，再配置 `NOTIFY_DINGTALK_CONFIG_FILE` 指向该容器内路径。
   - 示例（test）：

```yaml
services:
  springboot-backend:
    environment:
      SPRING_PROFILES_ACTIVE: docker
      NOTIFY_DINGTALK_CONFIG_FILE: /etc/studyagent-monitoring-secrets/test/dingtalk-webhook-config.yml
    volumes:
      - /etc/studyagent-monitoring-secrets/test/dingtalk-webhook-config.yml:/etc/studyagent-monitoring-secrets/test/dingtalk-webhook-config.yml:ro
```

3. 线上同理（把 `test` 路径替换为 `online` 路径）。

## 10. 日志与排障

每次请求至少记录：

1. `traceId`
2. `eventId`
3. `sourceService`
4. `scene`
5. `level`
6. 处理结果（sent/deduplicated/rejected/failed）
7. 失败原因（鉴权失败、参数错误、钉钉失败等）

日志脱敏要求：

1. 不记录完整 token。
2. `content` 可按长度截断。
3. `metadata` 对敏感键（如手机号、邮箱）做掩码。

## 11. 测试清单

1. 正常请求能成功发送钉钉。
2. 缺少必填字段时返回参数错误。
3. token 错误时拒绝请求。
4. 相同 `eventId` 重复提交命中幂等。
5. 高频请求触发限流。
6. 任一枚举字段非法（`sourceService/level/contentType/env`）触发 `4004`。
7. 钉钉返回失败时，接口正确返回并记录错误。
8. 所有响应都包含 `meta.traceId`，失败响应包含 `data.error.retryable`。
9. `metadata` 支持复杂 JSON 值类型，并在 `sanitizeMetadata` 后可稳定展示（无白名单丢弃）。

## 12. 分阶段上线建议

### 阶段 1（当前）

1. 仅 `message` 通道。
2. 接口尽量简单，先保障“触发一次，钉钉收到一次”。

### 阶段 2（未来可选）

1. 引入 `alert` 通道。
2. 由通知服务分流到 Alertmanager API。
3. 增加恢复通知、静默、分组、重复抑制。

## 13. 结论

本期建议采用最小可行方案：

1. `Notify API` 落在 Spring Boot。
2. 只实现 `message` 通道直达钉钉。
3. 保留基础安全和防刷能力（鉴权 + 幂等 + 限流）。
4. 后续再按业务复杂度决定是否演进到 `alert` 治理通道。

## 14. 服务负责人接入示例（curl + Java + Python）

### 14.1 curl 示例

```bash
curl -X POST "http://localhost:8080/api/v1/notify/events" \
  -H "Content-Type: application/json" \
  -H "X-Notify-Token: ${NOTIFY_API_TOKEN}" \
  -d '{
    "eventId": "evt_20260402_10001",
    "sourceService": "springboot_backend",
    "scene": "payment.success",
    "title": "支付成功通知",
    "content": "用户 user_123 完成支付，金额 99 元",
    "level": "info",
    "contentType": "markdown",
    "env": "test",
    "metadata": {
      "orderId": "ord_001",
      "bizType": "payment"
    }
  }'
```

### 14.2 Java 示例（Spring RestTemplate）

```java
String url = "http://localhost:8080/api/v1/notify/events";
HttpHeaders headers = new HttpHeaders();
headers.setContentType(MediaType.APPLICATION_JSON);
headers.set("X-Notify-Token", System.getenv("NOTIFY_API_TOKEN"));

Map<String, Object> body = new HashMap<>();
body.put("sourceService", "springboot_backend");
body.put("title", "系统通知");
body.put("content", "服务启动完成");
body.put("level", "info");
body.put("contentType", "text");
body.put("env", "local");

RestTemplate restTemplate = new RestTemplate();
HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
```

### 14.3 Python 示例（requests）

```python
import os
import requests

url = "http://localhost:8080/api/v1/notify/events"
headers = {
    "Content-Type": "application/json",
    "X-Notify-Token": os.getenv("NOTIFY_API_TOKEN", "")
}
payload = {
    "sourceService": "python_backend",
    "scene": "task.failed",
    "title": "任务失败",
    "content": "任务 task_9527 执行失败，错误码 E_TIMEOUT",
    "level": "error",
    "contentType": "markdown",
    "env": "online",
    "metadata": {
        "taskId": "task_9527",
        "errorCode": "E_TIMEOUT"
    }
}
resp = requests.post(url, headers=headers, json=payload, timeout=5)
print(resp.status_code, resp.text)
```

## 15. 常见问题与回滚

### 15.1 常见问题

1. token 错误（`4001`）
   - 现象：返回 `invalid notify token`。
   - 排查：确认请求头 `X-Notify-Token` 与服务端 `NOTIFY_API_TOKEN` 一致。

2. 配置路径错误（启动失败）
   - 现象：`notify.enabled=true` 且启动时报 `notify.dingtalk.config-file unreadable`。
   - 排查：确认 `NOTIFY_DINGTALK_CONFIG_FILE` 指向存在且可读文件；Docker 场景需确认挂载生效。

3. 钉钉签名错误（下游失败）
   - 现象：返回 `5000`，日志出现 `dingtalk api rejected message`。
   - 排查：确认 `dingtalk-webhook-config.yml` 中的 `targets.default.secret` 与 `url` 配套，且未混用其他机器人密钥。

### 15.2 回滚方案

最快回滚方式：

1. 设置 `NOTIFY_ENABLED=false`。
2. 重启 Spring Boot 服务。

影响范围：

1. Notify API 请求将被拒绝，不再投递到钉钉。
2. 其他业务接口不受影响。

## 16. 当前实现状态（截至 2026-04-02）

已完成：

1. `POST /api/v1/notify/events` 主链路（鉴权、参数校验、幂等、限流、钉钉投递）。
2. `notify.dingtalk.config-file` 唯一路径加载与 fail-fast。
3. `local/test/online` 三环境路径约定与本地直启/Docker 配置说明。
4. 单测覆盖：鉴权、枚举、幂等、限流、消息构建、配置读取校验。

未完成（后续验收项）：

1. 本地真实机器人联调（`local` 真发消息）与 test/online 发布验收。
2. `alert` 通道及告警治理能力（静默/恢复/抑制/升级）。
