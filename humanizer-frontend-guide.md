# Humanizer / AI Detect 前端对接指南

## 核心变化

原来的 SSE 流式接口已废弃，改为 **异步队列 + 轮询** 模式：
1. 提交任务 → 立即返回 taskId
2. 前端轮询查状态 → 逐步拿到结果
3. 不会再出现超时/429 错误，所有请求排队处理

---

## 接口列表

### 1. 提交 AI 检测任务

```
POST /v1/humanizer/detect
Authorization: Bearer <clerk_token>
Content-Type: application/json

{ "text": "要检测的文本" }
```

响应（立即返回）：
```json
{
  "meta": { "statusCode": 0, "statusMsg": "success", "traceId": "xxx" },
  "data": {
    "id": 123,
    "taskType": "DETECT",
    "status": "PENDING",
    "estimatedSeconds": 47,
    "queuePosition": 3
  }
}
```

### 2. 提交 Humanize 改写任务

```
POST /v1/humanizer/process
Authorization: Bearer <clerk_token>
Content-Type: application/json

{ "text": "要改写的文本" }
```

响应（立即返回）：
```json
{
  "meta": { "statusCode": 0, "statusMsg": "success", "traceId": "xxx" },
  "data": {
    "id": 456,
    "taskType": "HUMANIZE",
    "status": "PENDING",
    "estimatedSeconds": 52,
    "queuePosition": 0
  }
}
```

限制：文本最大 60000 字符（约 10000 词），超出会返回参数校验错误。

### 3. 查询任务详情（轮询用）

```
GET /v1/humanizer/tasks/{id}
Authorization: Bearer <clerk_token>
```

DETECT 任务响应示例（排队中）：
```json
{
  "data": {
    "id": 123,
    "taskType": "DETECT",
    "status": "PENDING",
    "estimatedSeconds": 120,
    "queuePosition": 5,
    "createdAt": "2026-03-02 21:00:00"
  }
}
```

DETECT 任务响应示例（处理中）：
```json
{
  "data": {
    "id": 123,
    "taskType": "DETECT",
    "status": "PROCESSING",
    "totalSentences": 5,
    "completedSentences": 3,
    "estimatedSeconds": 7,
    "queuePosition": 0,
    "progress": 60,
    "sentencesJson": "[{\"index\":1,\"total\":5,\"sentence\":\"In today's...\",\"fullSentence\":\"In today's fast-paced world...\",\"probability\":0.87,\"label\":\"AI\",\"weight\":42},{\"index\":2,...},{\"index\":3,...}]",
    "createdAt": "2026-03-02 21:00:00"
  }
}
```

DETECT 任务响应示例（完成）：
```json
{
  "data": {
    "id": 123,
    "taskType": "DETECT",
    "status": "COMPLETED",
    "probability": 0.85,
    "label": "AI Generated",
    "totalSentences": 5,
    "completedSentences": 5,
    "sentencesJson": "[...]",
    "elapsedSeconds": 8.5,
    "createdAt": "2026-03-02 21:00:00"
  }
}
```

HUMANIZE 任务响应示例（完成）：
```json
{
  "data": {
    "id": 456,
    "taskType": "HUMANIZE",
    "status": "COMPLETED",
    "resultText": "改写后的完整文本...",
    "elapsedSeconds": 45.2,
    "progress": 100,
    "createdAt": "2026-03-02 21:00:00"
  }
}
```

失败时：
```json
{
  "data": {
    "id": 123,
    "status": "FAILED",
    "errorMessage": "Python service unavailable"
  }
}
```

### 4. 查询任务历史列表（分页）

```
GET /v1/humanizer/tasks?taskType=DETECT&page=1&size=10
Authorization: Bearer <clerk_token>
```

参数：
| 参数 | 必填 | 说明 |
|------|------|------|
| taskType | 否 | `DETECT` 或 `HUMANIZE`，不传查全部 |
| page | 否 | 页码，从 1 开始，默认 1 |
| size | 否 | 每页条数，默认 10 |

响应：
```json
{
  "data": {
    "items": [
      {
        "id": 123,
        "taskType": "DETECT",
        "status": "COMPLETED",
        "inputTextPreview": "In today's fast-paced world, technology has...",
        "probability": 0.85,
        "label": "AI Generated",
        "totalSentences": 5,
        "completedSentences": 5,
        "elapsedSeconds": 8.5,
        "createdAt": "2026-03-02 21:00:00"
      },
      {
        "id": 456,
        "taskType": "HUMANIZE",
        "status": "COMPLETED",
        "inputTextPreview": "The rapid advancement of artificial intellig...",
        "resultTextPreview": "As technology continues to evolve at an unpre...",
        "elapsedSeconds": 45.2,
        "createdAt": "2026-03-02 20:30:00"
      }
    ],
    "page": 1,
    "size": 10,
    "total": 25,
    "totalPages": 3
  }
}
```

列表接口不返回 `sentencesJson`、`inputText`、`resultText` 等大字段，只返回前 50 字符的 preview。点击某条任务后调详情接口拿完整数据。

---

## 前端轮询逻辑

```javascript
async function submitAndPoll(text, type = 'DETECT') {
  const endpoint = type === 'DETECT' ? '/v1/humanizer/detect' : '/v1/humanizer/process';

  // 1. 提交任务
  const submitRes = await fetch(endpoint, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${clerkToken}`,
    },
    body: JSON.stringify({ text }),
  });
  const { data: task } = await submitRes.json();
  const taskId = task.id;

  // 提交时就能拿到预估时间和排队位置
  showEstimate(task.estimatedSeconds, task.queuePosition);

  // 2. 轮询查结果（每 2 秒一次）
  const poll = setInterval(async () => {
    const res = await fetch(`/v1/humanizer/tasks/${taskId}`, {
      headers: { 'Authorization': `Bearer ${clerkToken}` },
    });
    const { data } = await res.json();

    if (data.status === 'PENDING') {
      // 排队中，展示预估等待时间和排队位置
      showWaiting(data.estimatedSeconds, data.queuePosition);
    }

    if (data.status === 'PROCESSING') {
      // 展示预估剩余时间
      showEstimate(data.estimatedSeconds, 0);
      if (type === 'DETECT') {
        // DETECT 可以展示逐句进度
        const sentences = JSON.parse(data.sentencesJson || '[]');
        updateProgress(data.completedSentences, data.totalSentences, sentences);
      }
    }

    if (data.status === 'COMPLETED') {
      clearInterval(poll);
      if (type === 'DETECT') {
        showDetectResult(data.probability, data.label, JSON.parse(data.sentencesJson));
      } else {
        showHumanizeResult(data.resultText);
      }
    }

    if (data.status === 'FAILED') {
      clearInterval(poll);
      showError(data.errorMessage);
    }
  }, 2000);
}
```

---

## status 状态说明

| 状态 | 含义 | 前端处理 |
|------|------|----------|
| PENDING | 排队中，等待处理 | 显示"排队中" |
| PROCESSING | 正在处理 | 显示进度（DETECT 可看 completedSentences/totalSentences） |
| COMPLETED | 完成 | 展示结果 |
| FAILED | 失败 | 展示 errorMessage |

### progress 字段说明

所有任务详情响应（`GET /v1/humanizer/tasks/{id}`）都会返回 `progress` 字段（0~100 整数），前端可直接用于进度条展示：

| 状态 | progress 值 | 说明 |
|------|------------|------|
| PENDING | 0 | 排队中 |
| PROCESSING | 1~99 | DETECT 基于 completedSentences/totalSentences；HUMANIZE 基于已过时间/预估总时间 |
| COMPLETED | 100 | 完成 |
| FAILED | 0 | 失败 |
| QUOTA_EXHAUSTED | 1~99 | 保持暂停时的进度 |

注意：PROCESSING 状态下 progress 最低为 1，不会降到 0（避免用户以为没开始）。HUMANIZE 的 progress 最高到 95（因为是时间估算，不是精确进度）。

---

## sentencesJson 格式（DETECT 专用）

JSON 数组，每个元素是一句的检测结果：

```json
[
  {
    "index": 1,
    "total": 5,
    "sentence": "In today's fast-paced...",
    "fullSentence": "In today's fast-paced world, technology has become an integral part of our daily lives.",
    "probability": 0.87,
    "label": "AI",
    "weight": 42
  },
  {
    "index": 2,
    "total": 5,
    "sentence": "However, many experts...",
    "fullSentence": "However, many experts believe that this reliance on technology may have unintended consequences.",
    "probability": 0.72,
    "label": "AI",
    "weight": 35
  }
]
```

| 字段 | 说明 |
|------|------|
| index | 句子序号（从 1 开始） |
| total | 总句子数 |
| sentence | 句子摘要（前 80 字符） |
| fullSentence | 完整句子 |
| probability | 该句 AI 概率（0~1） |
| label | "AI" 或 "Human" |
| weight | 句子权重（字符数） |

---

## 废弃接口（不要再用）

- ~~POST /v1/humanizer/detect-stream~~ → 改用 POST /v1/humanizer/detect + 轮询
- ~~POST /v1/humanizer/process-stream~~ → 改用 POST /v1/humanizer/process + 轮询


---

# 🔄 2026-03-08 更新：DETECT 逐块扣费 + QUOTA_EXHAUSTED + Resume 续跑

## 背景（PRD 要求）

按 PM 的 PRD（第 7 节"支付弹窗"），AI Detection 和 Humanizer 的扣费逻辑不同：

- **AI Detection**：逐块扣费。每检测完一个句子（chunk）扣一次 words。如果中途余额耗尽，暂停检测，用户充值后可以从断点继续。
- **Humanizer**：一次性扣费。提交时按总 words 一次性扣完。如果余额不够，直接拒绝提交。

## 变更总结

### 1. 提交接口响应新增字段

`POST /v1/humanizer/detect` 和 `POST /v1/humanizer/process` 响应新增：

```json
{
  "meta": {
    "statusCode": 0,
    "statusMsg": "success",
    "traceId": "xxx",
    "quotaConsumed": false
  },
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

| 新字段 | 类型 | 说明 |
|------|------|------|
| meta.quotaConsumed | boolean | 本次请求是否发生了额度扣减。DETECT 始终为 `false`（Worker 逐块扣），HUMANIZE 为 `true`（提交时一次性扣完）。admin/白名单用户始终 `false` |
| data.taskId | number | 同 `id`，兼容前端用 `taskId` 字段的地方 |
| data.totalWords | number | 任务总 word 数 |
| data.consumedWords | number | 已扣费的 word 数（提交时为 0） |

### 2. 新增任务状态：QUOTA_EXHAUSTED

| 状态 | 含义 | 前端处理 |
|------|------|----------|
| PENDING | 排队中 | 显示"排队中" |
| PROCESSING | 正在处理 | 显示进度 |
| COMPLETED | 完成 | 展示结果 |
| FAILED | 失败 | 展示 errorMessage |
| **QUOTA_EXHAUSTED** | **检测中途余额耗尽，暂停等待充值** | **弹出购买弹窗，充值后调 resume 接口** |

DETECT 任务轮询到 `QUOTA_EXHAUSTED` 时的响应示例：

```json
{
  "data": {
    "id": 123,
    "taskId": 123,
    "taskType": "DETECT",
    "status": "QUOTA_EXHAUSTED",
    "totalSentences": 10,
    "completedSentences": 6,
    "sentencesJson": "[...前6句的结果...]",
    "totalWords": 500,
    "consumedWords": 310,
    "errorMessage": "Quota exhausted at sentence 7"
  }
}
```

### 3. 新增接口：续跑任务

```
POST /v1/humanizer/tasks/{id}/resume
Authorization: Bearer <clerk_token>
```

无请求体。

成功响应：
```json
{
  "meta": { "statusCode": 0, "statusMsg": "success" },
  "data": {
    "id": 123,
    "taskId": 123,
    "taskType": "DETECT",
    "status": "PENDING",
    "totalSentences": 10,
    "completedSentences": 6,
    "sentencesJson": "[...前6句的结果...]",
    "totalWords": 500,
    "consumedWords": 310,
    "estimatedSeconds": 14,
    "queuePosition": 0
  }
}
```

调用后任务状态从 `QUOTA_EXHAUSTED` 变回 `PENDING`，Worker 会从第 7 句继续检测。前端继续轮询即可。

错误情况：
- 余额仍然不足 → 返回 `statusCode: 1011`（同提交时的额度不足错误）
- 任务不是 QUOTA_EXHAUSTED 状态 → 返回 `statusCode: 9999`

### 4. DETECT vs HUMANIZE 扣费逻辑对比

| | AI Detection | Humanizer |
|---|---|---|
| 提交时校验 | 余额 >= 1 word 即可 | 余额 >= 任务总 words |
| 扣费时机 | Worker 每检测完一句扣一次 | 提交时一次性扣完 |
| meta.quotaConsumed | `false` | `true` |
| 中途余额不足 | 暂停，状态变 QUOTA_EXHAUSTED | 不会发生（已预扣） |
| 充值后继续 | 调 `/tasks/{id}/resume` | 不需要 |
| 失败退款 | 不退（已扣的是真实消耗） | 退还（quotaLedgerId） |

## 前端轮询逻辑更新

```javascript
async function submitAndPoll(text, type = 'DETECT') {
  const endpoint = type === 'DETECT' ? '/v1/humanizer/detect' : '/v1/humanizer/process';

  // 1. 提交任务
  const submitRes = await fetch(endpoint, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${clerkToken}`,
    },
    body: JSON.stringify({ text }),
  });
  const submitJson = await submitRes.json();

  // 检查额度不足
  if (submitJson.meta.statusCode === 1011) {
    showPurchaseModal(submitJson.data); // 弹购买弹窗
    return;
  }

  const task = submitJson.data;
  const taskId = task.id;

  // 如果发生了额度扣减，刷新余额显示
  if (submitJson.meta.quotaConsumed) {
    refreshQuotaDisplay();
  }

  // 2. 轮询
  const poll = setInterval(async () => {
    const res = await fetch(`/v1/humanizer/tasks/${taskId}`, {
      headers: { 'Authorization': `Bearer ${clerkToken}` },
    });
    const { data } = await res.json();

    if (data.status === 'PROCESSING' && type === 'DETECT') {
      // 实时更新已扣费 words
      updateConsumedWords(data.consumedWords, data.totalWords);
      // 展示逐句进度
      const sentences = JSON.parse(data.sentencesJson || '[]');
      updateProgress(data.completedSentences, data.totalSentences, sentences);
    }

    if (data.status === 'QUOTA_EXHAUSTED') {
      clearInterval(poll);
      // 展示已完成的句子结果（前 N 句有结果，后面的置灰）
      const sentences = JSON.parse(data.sentencesJson || '[]');
      showPartialResult(sentences, data.completedSentences, data.totalSentences);
      // 弹出购买弹窗
      showPurchaseModal({
        taskId: data.id,
        consumedWords: data.consumedWords,
        totalWords: data.totalWords,
        onPurchaseComplete: () => resumeTask(taskId),
      });
    }

    if (data.status === 'COMPLETED') {
      clearInterval(poll);
      refreshQuotaDisplay(); // DETECT 完成后刷新余额
      if (type === 'DETECT') {
        showDetectResult(data.probability, data.label, JSON.parse(data.sentencesJson));
      } else {
        showHumanizeResult(data.resultText);
      }
    }

    if (data.status === 'FAILED') {
      clearInterval(poll);
      showError(data.errorMessage);
    }
  }, 2000);
}

// 续跑任务
async function resumeTask(taskId) {
  const res = await fetch(`/v1/humanizer/tasks/${taskId}/resume`, {
    method: 'POST',
    headers: { 'Authorization': `Bearer ${clerkToken}` },
  });
  const json = await res.json();

  if (json.meta.statusCode === 1011) {
    // 余额还是不够
    showPurchaseModal(json.data);
    return;
  }

  // 续跑成功，重新开始轮询
  submitAndPoll_continue(taskId); // 复用轮询逻辑
}
```

## PRD 中前端需要处理的 UI 状态（DETECT）

按 PRD 第 7 节第 2 条：

1. **余额够** → 正常检测，逐句展示结果，每句实时扣费
2. **检测中途余额耗尽**（`QUOTA_EXHAUSTED`）→ 弹购买弹窗。如果用户关闭弹窗：
   - 文本恢复可编辑状态
   - 已检测的句子正常显示结果
   - 未检测的句子全部置灰
   - 增加一个"付费解锁"按钮，点击重新弹出购买弹窗
3. **充值后继续** → 调 `POST /tasks/{id}/resume`，任务从断点继续，前端重新轮询
