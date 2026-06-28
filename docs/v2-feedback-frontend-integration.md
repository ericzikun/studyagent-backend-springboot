# V2 反馈模块 — 前端对接指南

> 后端分支：`feat/v2-feedback-module-adaptation`  
> 接口路径与 1.0 完全一致，V2 仅扩展 `subjectType` / `triggerCode` 枚举与归属校验。  
> 本文档面向 `studyagent-fronted-v2`，不包含前端实现代码。

---

## 1. 基础信息

| 项目 | 说明 |
|------|------|
| Base URL | 与现有 BFF 一致，由 `NEXT_PUBLIC_API_BASE_URL` 决定 |
| 版本前缀 | `/v1` |
| 消费触发 | `POST /v1/feedback/triggers/consume` |
| 提交反馈 | `POST /v1/feedback/submissions` |
| 鉴权 | Bearer Token（Clerk）；后端从 `clerkUserId` 解析当前用户 |
| Content-Type | `application/json` |

HTTP 客户端可写相对路径 `/feedback/triggers/consume`、`/feedback/submissions`，由封装自动拼接 Base URL 与 `/v1` 前缀。

### 1.1 统一响应包络

```json
{
  "meta": {
    "statusCode": 0,
    "statusMsg": "success"
  },
  "data": { }
}
```

业务成功以 `data` 为准；失败时 `meta.statusCode` 非 0。

---

## 2. 调用时序

```text
用户完成可评价动作（检测完成 / 改写完成 / 下载 / 编辑器停留等）
  → POST /v1/feedback/triggers/consume
  → 若 data.shouldPrompt === true：展示弹窗（按 data.configKey 取本地模板）
  → 用户提交 → POST /v1/feedback/submissions（仅需 promptSessionId + score|vote + tags）
```

- 去重键：`clerk_user_id + subject_type + subject_id + trigger_code`，同一组合只弹一次。
- 提交接口**不需要**再传 `subjectId`；归属已在 consume 阶段绑定到 `promptSessionId`。

---

## 3. `POST /v1/feedback/triggers/consume`

### 3.1 请求体

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `triggerCode` | string | 是 | 触发原因，见 §5 |
| `subjectType` | string | 是 | 被评价对象类型，见 §4 |
| `subjectId` | number \| string | 是 | 与 subjectType 对应的主键，见 §4 |
| `sourcePage` | string | 否 | 埋点/排障用，如 `assignment`、`ai_detection`、`humanizer` |

### 3.2 响应 `data`

| 字段 | 类型 | 说明 |
|------|------|------|
| `shouldPrompt` | boolean | `true` = 应弹窗且已创建 session；`false` = 已消费过 |
| `promptSessionId` | string | 仅 `shouldPrompt=true` 时返回，`fps_` 前缀 |
| `triggerCode` | string | 回显 |
| `subjectType` | string | 回显 |
| `subjectId` | number \| string | 回显（与请求一致） |
| `variant` | string | `rating`（1–5 星）或 `thumb`（up/down） |
| `configKey` | string | 前端本地模板 key |
| `configVersion` | number | 模板版本，当前均为 `1` |

### 3.3 前端分支

1. 调用 consume，读取 `data`。
2. `!data || !data.shouldPrompt` → 结束，不弹窗。
3. `data.shouldPrompt` → 保存 `promptSessionId`、`variant`、`configKey`，渲染对应弹窗。
4. 用户提交 → 调用 submissions（§6）。

---

## 4. Subject 类型与 subjectId 格式

### 4.1 `task`（V2 Assignment 编辑器 — 当前已用）

| 字段 | 值 |
|------|-----|
| subjectType | `"task"` |
| subjectId | 推荐 `verla_conversation:{numericId}`（避免与 legacy task 表 ID 撞键）；也可 legacy taskId 数字/Sqids |

**subjectId 示例：**

- `"verla_conversation:101"` — V2 Assignment 推荐写法（与现有 `buildAssignmentTaskFeedbackSubjectId` 一致）
- `"1356"` 或 Sqids 短码 — legacy 1.0 task

**Public id 支持：** `task` 类型下 `verla_conversation:` 前缀后仅支持**纯数字** internal id；`vc_*` 请改用 `subjectType=verla_conversation`（§4.2）。

### 4.2 `verla_conversation`（V2 AI Detection / Humanizer / 原生 Assignment）

| 字段 | 值 |
|------|-----|
| subjectType | `"verla_conversation"` |
| subjectId | Verla 对话 internal id 或 public id |

**subjectId 示例：**

- `101` 或 `"101"` — 纯数字 internal id
- `"vc_FxnXM1kBN"` — public id（推荐，与路由/API 一致）
- `"verla_conversation:101"` — 带前缀数字（兼容写法）

### 4.3 `verla_turn`（V2 Assignment 按轮次评分）

| 字段 | 值 |
|------|-----|
| subjectType | `"verla_turn"` |
| subjectId | `verla_turns.id` 或 `vt_*` public id |

### 4.4 `verla_session`（V2 Assignment 按单次 AGENT 调用评分）

| 字段 | 值 |
|------|-----|
| subjectType | `"verla_session"` |
| subjectId | `verla_sessions.id` 或 `vs_*` public id |

### 4.5 `humanizer_task`（1.0 独立 Humanizer 页 / 旧队列）

| 字段 | 值 |
|------|-----|
| subjectType | `"humanizer_task"` |
| subjectId | `humanizer_tasks.id`（数字） |

1.0 clone 仍使用此组合；V2 AI Writing 工作台请优先用 `verla_conversation`。

---

## 5. Trigger 与模板映射（后端已注册）

### 5.1 V2 Assignment — 星级评分（variant=`rating`，configKey=`task-rating-v1`）

| triggerCode | 推荐 subjectType | 触发时机 | sourcePage 建议 |
|-------------|------------------|----------|-----------------|
| `task_download_first` | `task` 或 `verla_conversation` | 用户首次下载 Assignment 产物 | `assignment` |
| `editor_stay_1min_first` | `task` 或 `verla_conversation` | 用户在编辑器停留 ≥1 分钟（首次） | `assignment` |
| `editor_back_first` | `task` 或 `verla_conversation` | 用户首次从编辑器返回 | `assignment` |
| `editor_copy_first` | `task` 或 `verla_conversation` | 用户首次复制编辑器内容 | `assignment` |
| `verla_turn_complete_first` | `verla_turn` | Assignment 某 turn 首次到达终态（成功） | `assignment` |
| `verla_agent_session_complete_first` | `verla_session` | 某 AGENT session 首次成功完成 | `assignment` |

**V2 当前状态：** 编辑器下载/停留已用 `task` + `verla_conversation:{id}` + `task_download_first` / `editor_stay_1min_first`，后端均已支持。

### 5.2 V2 AI Detection — 点赞点踩（variant=`thumb`，configKey=`detection-thumb-v1`）

| triggerCode | 推荐 subjectType | 触发时机 | sourcePage 建议 |
|-------------|------------------|----------|-----------------|
| `detection_complete_first` | `verla_conversation` | AI Detection 首次成功出结果 | `ai_detection` |

**subjectId：** 当前工作区 `conversationId`（`vc_*` 或数字均可）。

**请求示例：**

```json
{
  "triggerCode": "detection_complete_first",
  "subjectType": "verla_conversation",
  "subjectId": "vc_FxnXM1kBN",
  "sourcePage": "ai_detection"
}
```

### 5.3 V2 AI Humanizer — 点赞点踩（variant=`thumb`，configKey=`humanizer-thumb-v1`）

| triggerCode | 推荐 subjectType | 触发时机 | sourcePage 建议 |
|-------------|------------------|----------|-----------------|
| `humanizer_complete_first` | `verla_conversation` | Humanizer 首次成功出结果 | `humanizer` |

**请求示例：**

```json
{
  "triggerCode": "humanizer_complete_first",
  "subjectType": "verla_conversation",
  "subjectId": "vc_FxnXM1kBN",
  "sourcePage": "humanizer"
}
```

### 5.4 1.0 兼容（仍可用）

| triggerCode | subjectType | configKey |
|-------------|-------------|-----------|
| `detection_complete_first` | `humanizer_task` | `detection-thumb-v1` |
| `humanizer_complete_first` | `humanizer_task` | `humanizer-thumb-v1` |

---

## 6. `POST /v1/feedback/submissions`

### 6.1 请求体

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `promptSessionId` | string | 是 | consume 返回的 `fps_...` |
| `score` | number | 条件 | `variant=rating` 时必填，整数 1–5 |
| `vote` | string | 条件 | `variant=thumb` 时必填，`up` 或 `down` |
| `selectedTagCodes` | string[] | 是 | 可为 `[]`；元素为前端模板 tag code |
| `comment` | string | 否 | 补充说明 |
| `contact` | string | 否 | 用户自愿联系方式 |

### 6.2 variant 互斥规则

- **rating**：必须传 `score`，不要传 `vote`
- **thumb**：必须传 `vote`，不要传 `score`

### 6.3 响应 `data`

```json
{
  "success": true,
  "submissionId": "fsub_xxxxxxxx"
}
```

### 6.4 示例 — Detection thumb 提交

```json
{
  "promptSessionId": "fps_abc123...",
  "vote": "up",
  "selectedTagCodes": ["Accurate detection result", "Lightning fast"],
  "comment": "",
  "contact": ""
}
```

### 6.5 示例 — Assignment rating 提交

```json
{
  "promptSessionId": "fps_def456...",
  "score": 4,
  "selectedTagCodes": ["Strong critical analysis", "Very fast speed"],
  "comment": "Overall good experience",
  "contact": ""
}
```

---

## 7. 错误码

| statusCode | 含义 |
|------------|------|
| 1020 | `promptSessionId` 不存在 |
| 1021 | 该 session 已提交过（幂等） |
| 1022 | 请求参数与 variant 不匹配（如 rating 缺 score） |
| 403 / NO_PERMISSION | subject 不属于当前用户，或 subject 不存在 |

**注意：** 须在业务对象已落库、页面已拿到合法 id **且用户已登录**后再调用 consume，否则可能收到权限错误。

---

## 8. 前端本地模板（configKey）

后端只返回 `configKey`，完整 UI 文案/标签由前端维护。可参考 1.0 `studyagent-clone/src/components/feedback/config.ts`。

| configKey | variant | 适用场景 |
|-----------|---------|----------|
| `task-rating-v1` | rating | Assignment 下载/编辑器/turn 完成 |
| `detection-thumb-v1` | thumb | AI Detection 完成 |
| `humanizer-thumb-v1` | thumb | AI Humanizer 完成 |

### 8.1 `detection-thumb-v1` 标签（thumb）

**up — What stood out?**

- `Accurate detection result`
- `Clear & detailed report`
- `Lightning fast`
- `Saved me a lot of time`

**down — What needs work?**

- `Wrong detection result`
- `Result hard to understand`
- `Way too slow`
- `Crashed or errored`

### 8.2 `humanizer-thumb-v1` 标签（thumb）

**up — What stood out?**

- `Passed AI detection`
- `Preserved my original meaning`
- `Natural & fluent writing`
- `Saved me a lot of time`
- `Easy to edit and re-detect in one place`

**down — What needs work?**

- `Still flagged as AI`
- `Changed my meaning too much`
- `Output sounds unnatural`
- `Heavy editing still needed`

### 8.3 `task-rating-v1` 标签（rating）

按 1–5 分动态展示标签组，详见 clone 配置或 V2 现有 `src/components/feedback/config.ts`。

---

## 9. V2 各功能接入清单

| V2 功能 | 是否后端就绪 | 推荐 consume 参数 | 前端待办 |
|---------|-------------|-------------------|----------|
| Assignment 下载反馈 | ✅ | `task_download_first` + `task` + `verla_conversation:{id}` | 已接入 |
| Assignment 编辑器停留 | ✅ | `editor_stay_1min_first` + `task` + `verla_conversation:{id}` | 已接入 |
| Assignment turn 完成 | ✅ | `verla_turn_complete_first` + `verla_turn` + turnId | 待接入 |
| Assignment AGENT 完成 | ✅ | `verla_agent_session_complete_first` + `verla_session` + sessionId | 待接入 |
| AI Detection 完成 | ✅ | `detection_complete_first` + `verla_conversation` + conversationId | 待接入（需 thumb 弹窗） |
| AI Humanizer 完成 | ✅ | `humanizer_complete_first` + `verla_conversation` + conversationId | 待接入（需 thumb 弹窗） |

### 9.1 AI Detection / Humanizer 建议触发点

在以下时机调用 consume（**仅一次**，后端会去重）：

- Detection：`status` 变为 `detection-result` 且报告/分块已就绪
- Humanizer：`status` 变为 humanizer 终态且结果文本/chunks 已就绪
- 使用当前路由上的 `conversationId` 作为 `subjectId`
- 仅在用户已登录时调用

### 9.2 TypeScript 类型扩展建议

```ts
export type FeedbackSubjectType =
  | "task"
  | "humanizer_task"
  | "verla_conversation"
  | "verla_turn"
  | "verla_session";

export type FeedbackTriggerCode =
  | "task_download_first"
  | "editor_back_first"
  | "editor_copy_first"
  | "editor_stay_1min_first"
  | "detection_complete_first"
  | "humanizer_complete_first"
  | "verla_turn_complete_first"
  | "verla_agent_session_complete_first";

export type FeedbackConfigKey =
  | "task-rating-v1"
  | "detection-thumb-v1"
  | "humanizer-thumb-v1";
```

---

## 10. 与 1.0 的差异摘要

| 维度 | 1.0 | V2 |
|------|-----|-----|
| Assignment subject | `task` + taskId | `task` + `verla_conversation:{id}` 或 `verla_conversation` + `vc_*` |
| Detection/Humanizer subject | `humanizer_task` + taskId | `verla_conversation` + conversationId |
| 接口路径 | 相同 | 相同 |
| 提交 body | 相同 | 相同 |
| 新增 trigger | — | `editor_stay_1min_first`、`verla_turn_complete_first`、`verla_agent_session_complete_first` |

---

## 11. 联调检查项

- [ ] consume 返回的 `configKey` 在前端 registry 中存在
- [ ] thumb 弹窗支持 `vote` + `selectedTagCodes`，rating 弹窗支持 `score`
- [ ] `subjectId` 使用 public id 时与路由/API 一致（`vc_*` / `vt_*` / `vs_*`）
- [ ] 同一 subject + trigger 重复调用 consume 返回 `shouldPrompt: false`
- [ ] 未登录时不调用（或 gracefully 忽略错误）
- [ ] E2E mock 模式可继续本地 mock consume 响应

---

## 12. 参考

- 后端实现：`agent-api/.../FeedbackApplicationService.java`
- 控制器：`agent-api/.../FeedbackController.java`
- 1.0 前端参考：`studyagent-clone/src/components/feedback/`
- V2 设计背景：`docs/V2/V2-反馈接口与会话粒度适配方案.md`
