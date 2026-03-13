# AI Detection & Humanizer 接口文档

## PRD 实现状态（2026-03-08）

### ✅ 后端已实现

| PRD 要求 | 实现方式 |
|------|------|
| Detection 逐块扣费 | Worker 每检测完一个 sentence chunk，按该句 word 数调 `quotaDomainService.consume()` 扣费 |
| Detection 前置校验 | 提交时只校验 `余额 >= 1 word`，不预扣（由 Worker 逐块扣） |
| QUOTA_EXHAUSTED 中途暂停 | Worker 扣费失败时将任务状态设为 `QUOTA_EXHAUSTED`，记录已完成句数和已消耗 words |
| 充值后续跑（Resume） | `POST /v1/humanizer/tasks/{id}/resume`，校验余额后改回 PENDING，Worker 从断点继续 |
| Humanizer 一次性扣费 | 提交时按总 words 一次性扣完，余额不够直接拒绝（statusCode=1011） |
| Humanizer 失败退款 | 任务最终 FAILED 时通过 `quotaDomainService.refund()` 退还 |
| admin/白名单免扣费 | `isAdmin` 或 `whitelistUserIds` 中的用户跳过所有额度校验 |
| `totalWords` / `consumedWords` | 任务响应中返回，前端可展示扣费进度 |

### ❌ 后端待实现

| 功能 | 说明 |
|------|------|
| 删除任务接口 | `DELETE /v1/humanizer/tasks/{id}` 尚未实现 |
| Task History 完善 | 列表接口已有，但可能需要更多筛选/排序参数 |

---

## 认证

所有接口需要 Clerk Token：
```
Authorization: Bearer <clerk_token>
```

Base URL 由环境变量 `NEXT_PUBLIC_API_BASE_URL` 决定，前端通过 `/v1/` 前缀访问。

---

## 1. 提交 AI 检测任务

```
POST /v1/humanizer/detect
```

请求：
```json
{ "text": "要检测的文本（最大 60000 字符）" }
```

成功响应：
```json
{
  "meta": { "statusCode": 0, "statusMsg": "success", "traceId": "xxx", "quotaConsumed": false },
  "data": {
    "id": 123,
    "taskId": 123,
    "taskType": "DETECT",
    "status": "PENDING",
    "estimatedSeconds": 47,
    "queuePosition": 3,
    "totalWords": 500,
    "consumedWords": 0
  }
}
```

> `quotaConsumed` 对 DETECT 始终为 `false`（不预扣，由 Worker 逐块扣）。

额度不足响应（statusCode=1011）：
```json
{
  "meta": { "statusCode": 1011, "statusMsg": "Insufficient quota. Please recharge to continue.", "traceId": "xxx" },
  "data": {
    "featureCode": "ai_detection",
    "featureName": "AI Detection",
    "quotaUnit": "words",
    "freeBalance": 0,
    "freePeriodTotal": 1000,
    "paidBalance": 0,
    "totalAvailable": 0
  }
}
```

---

## 2. 提交 Humanize 改写任务

```
POST /v1/humanizer/process
```

请求：
```json
{ "text": "要改写的文本（最大 60000 字符）" }
```

成功响应：
```json
{
  "meta": { "statusCode": 0, "statusMsg": "success", "traceId": "xxx", "quotaConsumed": true },
  "data": {
    "id": 456,
    "taskId": 456,
    "taskType": "HUMANIZE",
    "status": "PENDING",
    "estimatedSeconds": 52,
    "queuePosition": 0,
    "totalWords": 200,
    "consumedWords": 0
  }
}
```

> `quotaConsumed` 对 HUMANIZE 为 `true`（提交时一次性扣完）。

额度不足响应同上（statusCode=1011），`data.featureCode` 为 `"humanizer"`。

---

## 3. 查询任务详情（轮询）

```
GET /v1/humanizer/tasks/{id}
```

### 状态流转

```
PENDING → PROCESSING → COMPLETED
                    ↘ FAILED
                    ↘ QUOTA_EXHAUSTED → (resume) → PENDING → ...
```

| 状态 | 含义 | 前端处理 |
|------|------|----------|
| PENDING | 排队中 | 显示 `queuePosition` 和 `estimatedSeconds` |
| PROCESSING | 处理中 | DETECT: 展示逐句进度（`completedSentences/totalSentences`），HUMANIZE: loading |
| COMPLETED | 完成 | 展示最终结果 |
| FAILED | 失败 | 展示 `errorMessage` |
| QUOTA_EXHAUSTED | 余额耗尽暂停（仅 DETECT） | 已分析块正常显示，未分析块置灰，提示充值续跑 |


### DETECT 处理中响应

```json
{
  "meta": { "statusCode": 0, "statusMsg": "success", "traceId": "xxx" },
  "data": {
    "id": 123,
    "taskType": "DETECT",
    "status": "PROCESSING",
    "totalSentences": 10,
    "completedSentences": 6,
    "sentencesJson": "[{\"index\":1,\"total\":10,\"sentence\":\"...\",\"fullSentence\":\"...\",\"probability\":0.87,\"label\":\"AI\",\"weight\":42}, ...]",
    "estimatedSeconds": 7,
    "queuePosition": 0,
    "totalWords": 500,
    "consumedWords": 310,
    "createdAt": "2026-03-08 15:30:00"
  }
}
```

### DETECT 完成响应

```json
{
  "meta": { "statusCode": 0, "statusMsg": "success", "traceId": "xxx" },
  "data": {
    "id": 123,
    "taskType": "DETECT",
    "status": "COMPLETED",
    "probability": 0.85,
    "label": "AI Generated",
    "totalSentences": 10,
    "completedSentences": 10,
    "sentencesJson": "[...]",
    "elapsedSeconds": 8.5,
    "totalWords": 500,
    "consumedWords": 500,
    "createdAt": "2026-03-08 15:30:00"
  }
}
```

### DETECT QUOTA_EXHAUSTED 响应

```json
{
  "meta": { "statusCode": 0, "statusMsg": "success", "traceId": "xxx" },
  "data": {
    "id": 123,
    "taskType": "DETECT",
    "status": "QUOTA_EXHAUSTED",
    "totalSentences": 10,
    "completedSentences": 6,
    "sentencesJson": "[...前6句结果...]",
    "totalWords": 500,
    "consumedWords": 310,
    "errorMessage": "Quota exhausted at sentence 7",
    "createdAt": "2026-03-08 15:30:00"
  }
}
```

### HUMANIZE 完成响应

```json
{
  "meta": { "statusCode": 0, "statusMsg": "success", "traceId": "xxx" },
  "data": {
    "id": 456,
    "taskType": "HUMANIZE",
    "status": "COMPLETED",
    "resultText": "改写后的完整文本...",
    "elapsedSeconds": 45.2,
    "totalWords": 200,
    "consumedWords": 0,
    "createdAt": "2026-03-08 15:30:00"
  }
}
```

> 注意：`HumanizerTaskResponse` 使用 `@JsonInclude(NON_NULL)`，值为 null 的字段不会出现在响应中。

### HumanizerTaskResponse 完整字段

| 字段 | 类型 | 说明 |
|------|------|------|
| id | Long | 任务 ID |
| taskId | Long | 同 id（兼容前端） |
| taskType | String | `DETECT` 或 `HUMANIZE` |
| status | String | `PENDING` / `PROCESSING` / `COMPLETED` / `FAILED` / `QUOTA_EXHAUSTED` |
| probability | Double | 整体 AI 概率 0~1（DETECT） |
| label | String | `AI Generated` 或 `Human Written`（DETECT） |
| sentencesJson | String | 逐句检测结果 JSON 数组字符串（DETECT） |
| totalSentences | Integer | 总句子数（DETECT） |
| completedSentences | Integer | 已完成句子数（DETECT） |
| resultText | String | 改写后文本（HUMANIZE） |
| elapsedSeconds | Double | 处理耗时（秒） |
| estimatedSeconds | Integer | 预估剩余时间（秒），PENDING/PROCESSING 时返回 |
| queuePosition | Integer | 排队位置（前面几个任务），PENDING 时返回 |
| errorMessage | String | 错误信息（FAILED/QUOTA_EXHAUSTED） |
| totalWords | Integer | 输入文本总 word 数 |
| consumedWords | Integer | 已扣费 word 数（DETECT 逐块扣费进度） |
| createdAt | String | 创建时间 `yyyy-MM-dd HH:mm:ss` |

---

## 4. 续跑任务（Resume）

```
POST /v1/humanizer/tasks/{id}/resume
```

无请求体。仅适用于 `QUOTA_EXHAUSTED` 状态的 DETECT 任务。

成功响应：
```json
{
  "meta": { "statusCode": 0, "statusMsg": "success", "traceId": "xxx" },
  "data": {
    "id": 123,
    "taskId": 123,
    "taskType": "DETECT",
    "status": "PENDING",
    "totalSentences": 10,
    "completedSentences": 6,
    "sentencesJson": "[...前6句结果...]",
    "totalWords": 500,
    "consumedWords": 310,
    "estimatedSeconds": 14,
    "queuePosition": 0,
    "createdAt": "2026-03-08 15:30:00"
  }
}
```

调用后任务从断点继续（Worker 从 `completedSentences` 位置恢复），前端重新轮询。

错误情况：
- 余额仍不足 → `statusCode: 1011`（同上，带 `InsufficientQuotaResponse`）
- 任务不是 QUOTA_EXHAUSTED 状态 → `statusCode: 1002`（Illegal state）
- 任务不存在或不属于当前用户 → `statusCode: 1001`（Parameter error）

---

## 5. 查询任务历史列表

```
GET /v1/humanizer/tasks?taskType=DETECT&page=1&size=10
```

| 参数 | 必填 | 说明 |
|------|------|------|
| taskType | 否 | `DETECT` 或 `HUMANIZE`，不传查全部 |
| page | 否 | 页码，从 1 开始，默认 1 |
| size | 否 | 每页条数，默认 10 |

成功响应：
```json
{
  "meta": { "statusCode": 0, "statusMsg": "success", "traceId": "xxx" },
  "data": {
    "items": [
      {
        "id": 123,
        "taskType": "DETECT",
        "status": "COMPLETED",
        "inputTextPreview": "In today's fast-paced world, technology has bec...",
        "probability": 0.85,
        "label": "AI Generated",
        "totalSentences": 10,
        "completedSentences": 10,
        "resultTextPreview": null,
        "elapsedSeconds": 8.5,
        "errorMessage": null,
        "createdAt": "2026-03-08 15:30:00"
      }
    ],
    "page": 1,
    "size": 10,
    "total": 25,
    "totalPages": 3
  }
}
```

> 列表接口返回精简字段（`inputTextPreview` / `resultTextPreview` 只取前 50 字符），不含 `sentencesJson` 等大字段。

---

## 6. 额度查询

### 6.1 查询所有功能额度

```
GET /v1/quota/balance
```

响应：
```json
{
  "meta": { "statusCode": 0, "statusMsg": "success", "traceId": "xxx" },
  "data": {
    "items": [
      {
        "feature_code": "ai_detection",
        "feature_name": "AI Detection",
        "quota_unit": "words",
        "free_quota": { "balance": 800, "period_total": 1000, "period_end": "2026-04-01T00:00" },
        "paid_quota": { "balance": 5000 },
        "total_available": 5800
      },
      {
        "feature_code": "humanizer",
        "feature_name": "Humanizer",
        "quota_unit": "words",
        "free_quota": { "balance": 500, "period_total": 500, "period_end": "2026-04-01T00:00" },
        "paid_quota": { "balance": 10000 },
        "total_available": 10500
      }
    ]
  }
}
```

### 6.2 查询单个功能额度

```
GET /v1/quota/balance?feature_code=ai_detection
```

响应：
```json
{
  "meta": { "statusCode": 0, "statusMsg": "success", "traceId": "xxx" },
  "data": {
    "feature_code": "ai_detection",
    "feature_name": "AI Detection",
    "quota_unit": "words",
    "free_quota": { "balance": 800, "period_total": 1000, "period_end": "2026-04-01T00:00" },
    "paid_quota": { "balance": 5000 },
    "total_available": 5800
  }
}
```

前端计算可用额度：
```javascript
// 方式1：直接用 total_available
const quotaDetection = data.total_available;

// 方式2：分别取
const dFree = data.free_quota.balance;
const dPaid = data.paid_quota.balance;
const quotaDetection = dFree + dPaid;
```

---

## 7. 额度流水查询

```
GET /v1/quota/ledger?page=1&page_size=20&feature_code=ai_detection
```

| 参数 | 必填 | 说明 |
|------|------|------|
| page | 否 | 页码，从 1 开始，默认 1 |
| page_size | 否 | 每页条数，默认 20，最大 100 |
| feature_code | 否 | 功能类型，不传查全部 |

响应：
```json
{
  "meta": { "statusCode": 0, "statusMsg": "success", "traceId": "xxx" },
  "data": {
    "items": [
      {
        "id": 1001,
        "ledgerNo": "LD20260308153000001",
        "ledgerType": "CONSUME",
        "amount": -15,
        "sourceType": "humanizer_task",
        "sourceId": "123",
        "displayText": "AI Detection - 15 words",
        "freeBalanceAfter": 785,
        "paidBalanceAfter": 5000,
        "createdAt": "2026-03-08T15:30:00"
      }
    ],
    "total": 50,
    "page": 1,
    "pageSize": 20
  }
}
```

---

## 8. 支付相关

```
POST /v1/payment/checkout
```

通过 `SKUPurchaseModal` 组件触发，传入 `productCategory`：
- `"ai_detection"` — AI 检测额度包
- `"humanizer"` — Humanizer 额度包

---

## DETECT vs HUMANIZE 扣费对比

| | AI Detection | Humanizer |
|---|---|---|
| 提交校验 | 余额 >= 1 word | 余额 >= 任务总 words |
| 扣费时机 | Worker 逐句扣（每句按 word 数） | 提交时一次性扣 |
| meta.quotaConsumed | `false` | `true` |
| 中途余额不足 | `QUOTA_EXHAUSTED`，可 resume 续跑 | 不会发生（已预扣） |
| 失败退款 | 不退（已逐句消耗） | 退还（通过 `quotaLedgerId`） |
| feature_code | `ai_detection` | `humanizer` |

---

## sentencesJson 格式

```json
[
  {
    "index": 1,
    "total": 10,
    "sentence": "In today's fast-paced...",
    "fullSentence": "In today's fast-paced world, technology has become...",
    "probability": 0.87,
    "label": "AI",
    "weight": 42
  }
]
```

| 字段 | 类型 | 说明 |
|------|------|------|
| index | int | 句子序号（从 1 开始） |
| total | int | 总句子数 |
| sentence | String | 摘要（前 80 字符） |
| fullSentence | String | 完整句子 |
| probability | double | AI 概率 0~1 |
| label | String | `"AI"` 或 `"Human"` |
| weight | int | 权重（字符数） |

---

## 错误码

| statusCode | 含义 | 触发场景 |
|------|------|------|
| 0 | 成功 | — |
| 401 | 未认证 | Token 缺失/过期/无效 |
| 429 | 限流 | detect 10次/分钟，process 5次/分钟 |
| 1001 | 参数错误 | text 为空、任务不存在等 |
| 1002 | 状态异常 | resume 非 QUOTA_EXHAUSTED 任务 |
| 1010 | 每日次数超限 | 任务提交次数达上限 |
| 1011 | 额度不足 | AI Detection/Humanizer 余额不够，`data` 中返回 `InsufficientQuotaResponse` |
| 9999 | 业务/系统错误 | 其他未分类错误 |
| 500 | 服务端错误 | 内部异常 |

### 1011 额度不足响应 data 结构

```json
{
  "featureCode": "ai_detection",
  "featureName": "AI Detection",
  "quotaUnit": "words",
  "freeBalance": 0,
  "freePeriodTotal": 1000,
  "paidBalance": 0,
  "totalAvailable": 0
}
```

前端可根据 `featureCode` 决定弹出哪个 SKU 购买弹窗（`ai_detection` 或 `humanizer`）。

---

## 废弃接口

- ~~POST /v1/humanizer/detect-stream~~ → 改用 detect + 轮询
- ~~POST /v1/humanizer/process-stream~~ → 改用 process + 轮询
- ~~DELETE /v1/humanizer/tasks/{id}~~ → 尚未实现
