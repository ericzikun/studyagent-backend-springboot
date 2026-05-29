# Assignment 生成 ETA 计算设计文档

> 版本：2025-05-28  
> 涉及模块：`agent-service/AssignmentRuntimeProgressEstimator`、`VerlaInboxService`、`AssignmentRuntimeSnapshotService`

---

## 1. 背景

Assignment 生成阶段耗时可长达 20 分钟，前端需要实时展示：
- 预计剩余时间（`estimatedRemainingSeconds`）
- 完成百分比（`completePercent`）
- 当前步骤标签（`label`）
- Workforce 分拆任务数（`completedTaskCount` / `totalTaskCount`）
- Compose 轮次（`composeCurrentRound` / `composeTotalRounds`）

Python 侧并不强制在每个事件里携带 ETA 字段，Java 侧负责在 Python 未提供时**补充计算**并注入到 SSE 和快照接口中。

---

## 2. Python → Java 数据流

```
Python VerlaWorkforce/AssignmentFlow
  │
  │  RabbitMQ [studyagent.events.s00–s03]
  │  VerlaEventEnvelope { eventType, sessionId, conversationId, payload, ... }
  ▼
Java VerlaEventListener (agent-infra)
  │  写入 verla_event_inbox（dedup by eventSeq）
  ▼
VerlaInboxService.processInbox()
  ├── VerlaEventHandlerDispatcher
  │     └── VerlaWorkforceNodeEventHandler
  │           ├── ASSIGNMENT_AGENT_NODE_UPDATED  →  upsert verla_workforce_tasks
  │           └── ASSIGNMENT_AGENT_NODE_DETAILED →  upsert verla_workforce_task_outputs
  │
  └── toSsePayload()
        └── AssignmentRuntimeProgressEstimator.enrichAssignmentRunPayload()
              └── 注入 ETA 字段后推送 SSE → 前端
```

### 2.1 Python 会发哪些事件

| 事件类型 | 含义 | 是否携带 ETA |
|---|---|---|
| `ASSIGNMENT_AGENT_FLOW_STARTED` | 流程启动 | 否（仅标记时间戳）|
| `ASSIGNMENT_STARTED` | 同上（旧名称兼容）| 否 |
| `ASSIGNMENT_AGENT_NODE_UPDATED` | Workforce 节点状态变更（plan / task-N）| 可选 |
| `ASSIGNMENT_WORKFLOW_NODE_UPDATED` | 同上（旧事件名）| 可选 |
| `ASSIGNMENT_PROGRESS` / `AGENT_PROGRESS` | 通用进度 | 可选 |
| `ASSIGNMENT_AGENT_FLOW_ARTIFACT_UPDATED` | 产出物更新 | 可选 |
| `ASSIGNMENT_AGENT_FLOW_COMPLETED` | 流程完成 | — |
| `ASSIGNMENT_AGENT_FLOW_FAILED` | 流程失败 | — |
| `ASSIGNMENT_AGENT_FLOW_CANCELLED` | 流程取消 | — |

**Python 可选携带的 ETA 字段（任意一种即可被识别）：**

```json
{
  "progress": {
    "estimatedRemainingSeconds": 450,
    "label": "Composing part 2/5"
  }
}
```

Java 会识别以下等价键名（兼容历史格式）：
- `estimatedRemainingSeconds`
- `estRemainingTimeSeconds`
- `estimated_remaining_seconds`
- `est_remaining_time`
- `estimatedTimeRemainingSeconds`

### 2.2 VerlaWorkforceNodeEventHandler 存储内容

`ASSIGNMENT_AGENT_NODE_UPDATED` 触发 `VerlaWorkforceTask` upsert，按 `(session_id, node_id)` 幂等：

| DB 字段 | 来源 | 说明 |
|---|---|---|
| `session_id` | 事件 envelope | 当前 Session |
| `node_id` | `node.id`（`assignment-plan` 或 `task-N`）| 节点唯一键 |
| `node_kind` | `plan` / `task` | 区分规划节点和任务节点 |
| `status` | `node.status` | queued / running / completed / failed |
| `task_name` | `node.taskName` | 任务标题，用于 ETA label |
| `compose_total_rounds` | `node.composeTotalRounds` | Compose 总轮次（plan 节点设置）|
| `conversation_id` | 事件 envelope | 用于跨 session 汇总 |

> 终态（completed / failed）不会被回退到 queued / running。

---

## 3. Java ETA 计算逻辑

### 3.1 核心类

```
AssignmentRuntimeProgressEstimator
  ├── resolveProgress()          ← 快照恢复路径（页面刷新/重连）
  ├── enrichAssignmentRunPayload() ← SSE 实时推送路径
  └── estimateFromEvents()       ← 共用计算核心
```

**输入/输出类型：**

```java
record AssignmentRuntimeProgressEstimate(
    String label,                 // 当前步骤文本
    int estimatedRemainingSeconds,// 剩余秒数
    double completePercent,       // 完成百分比 0–100
    Integer completedTaskCount,   // 已完成任务数（可 null）
    Integer totalTaskCount,       // 总任务数（可 null）
    Integer composeCurrentRound,  // 当前 Compose 轮次（可 null）
    Integer composeTotalRounds    // Compose 总轮次（可 null）
)
```

### 3.2 `resolveProgress` 决策链（快照路径）

```
resolveProgress(recentEvents)
  │
  ├─ 1. 有终态事件？
  │     COMPLETED → { label:"Assignment ready", estimatedRemainingSeconds:0 }
  │     FAILED/CANCELLED → { estimatedRemainingSeconds: null }
  │
  ├─ 2. Python 携带了显式 ETA 且不在 plan-only 阶段？
  │     直接返回 Python 的 progress（label + estimatedRemainingSeconds）
  │
  ├─ 3. Assignment 生成未激活（无 run 标记事件或已见终态）？
  │     返回 Python 显式 progress（可能为 null）
  │
  └─ 4. Java 计算 ETA（estimateFromEvents）并合并进 Python 显式 progress
        merged.putIfAbsent("label", ...)
        merged.put("estimatedRemainingSeconds", ...)
        merged.put("completePercent", ...)
        + workforce metadata
```

### 3.3 `enrichAssignmentRunPayload` 决策链（SSE 实时路径）

```
enrichAssignmentRunPayload(eventType, payload, conversationId, currentEvent)
  │
  ├─ 事件类型不在 ASSIGNMENT_RUN_EVENT_TYPES → 原样返回
  ├─ payload 已含显式 ETA → 原样返回（Python 优先）
  ├─ Assignment 生成未激活 → 原样返回
  └─ 计算 ETA，注入到 payload.progress.*
```

### 3.4 `estimateFromEvents` 三档策略

```
estimateFromEvents(recentEvents)
  │
  ├─ 查询 verla_workforce_tasks 聚合 → WorkforceTaskProgressSnapshot
  │   { totalTaskCount, completedTaskCount, activeTaskCount, composeTotalRounds }
  │
  ├─ 有 task 数据 OR 有 compose 进度数据？
  │   └── estimateFromWorkforceSnapshot()  ← 主路径
  │
  ├─ Plan-only 阶段（任务尚未入库）？
  │   └── estimateFromPlanPhase()          ← 前置阶段
  │
  └─ 兜底：folded AGENT_NODE_UPDATED 节点
      └── estimateFromAgentNodes()         ← 降级路径
```

---

## 4. 三种估算算法详解

### 4.1 Plan 阶段（`estimateFromPlanPhase`）

**触发条件：** `ASSIGNMENT_AGENT_FLOW_STARTED` 已收到，但 `verla_workforce_tasks` 尚无 task 行，也无 compose 进度。

**参数：**

| 常量 | 值 | 含义 |
|---|---|---|
| `PLAN_PHASE_ESTIMATE_SECONDS` | 120 s | Plan 阶段总估算窗口 |
| `PLAN_PHASE_REMAINING_FLOOR_SECONDS` | 15 s | 剩余时间最小值 |
| `SIMULATED_PROGRESS_MAX_PERCENT` | 10% | Plan 阶段最多占总进度的 10% |

**计算：**
```
elapsed = now - flowStartedAt
remaining = max(15, 120 - elapsed)
planProgress = (1 - remaining/120) * 100%   [0–100%]
completePercent = planProgress/100 * 10%     [0–10%]
label = assignment-plan 节点的 taskName，默认 "Make plan"
```

### 4.2 Workforce 阶段（`estimateFromWorkforceSnapshot`）

**触发条件：** `verla_workforce_tasks` 有 task 行（`totalTaskCount > 0`）或有 compose 进度。

整体采用**两段式 50/50 模型**：

#### 阶段一：子任务执行（0% → 50%）

```
weighted = completedTaskCount + (activeTaskCount > 0 ? 0.5 : 0.0)
percent  = min(50%, (weighted / totalTaskCount) * 50%)
```

- 每个运行中的任务贡献 0.5（`RUNNING_NODE_PARTIAL_WEIGHT`）
- 上限 50%

#### 阶段二：Compose 写作（50% → 100%）

触发条件：所有任务完成（`completed >= total && running == 0`）

Compose 总轮次解析（优先级）：
1. 节点标题正则匹配 `"Composing part N/M"` → M
2. DB `compose_total_rounds` 字段
3. 0（无 compose 信息）

```
percent = 50% + (composeCurrentRound / composeTotalRounds) * 50%
```

#### 时间模拟兜底（floor）

任何阶段都以时间模拟进度作为**下界**，防止进度条倒退：

```
elapsed = now - flowStartedAt
effectiveElapsed = min(elapsed, 120)   // 120 s 窗口
simulatedPercent = (effectiveElapsed / 120) * 10%    // 最高 10%
effectivePercent = max(simulatedPercent, workforcePercent)
```

#### 剩余时间

```
TOTAL_ESTIMATED_SECONDS = 1200  (20 分钟)
remainingSeconds = round(1200 * (1 - effectivePercent / 100))
```

### 4.3 AgentNode 降级（`estimateFromAgentNodes`）

**触发条件：** 无 workforce task 数据，且非 plan-only 阶段（有任务节点事件但未入 DB）。

```
weightedCompleted = Σ (1.0 if status==completed, 0.5 if running)
nodePercent = (weightedCompleted / totalNodes) * 100%
effectivePercent = max(nodePercent, simulatedPercent)
remainingSeconds = round(1200 * (1 - effectivePercent / 100))
```

节点状态标准化：

| Python 原始值 | 归一化 |
|---|---|
| `running`, `in_progress`, `progressing` | `running` |
| `completed`, `done`, `succeeded`, `success` | `completed` |
| `failed`, `error`, `cancelled`, `canceled` | `failed` |
| 其他 | `queued` |

---

## 5. Label 解析规则

| 场景 | Label 来源 |
|---|---|
| Plan 阶段 | `assignment-plan` 节点的 `taskName` / `title` / `summary` / `subtitle`，默认 `"Make plan"` |
| Workforce 有任务运行 | DB 中 `status=running` 且 `node_id` 以 `task-` 开头的 `task_name` |
| Compose 阶段 | `"Composing part N/M"` 或 `"Composing assignment"` |
| AgentNode 降级 | 第一个 `status=running` 节点的 `title` / `taskName`，默认 `"Generating assignment"` |
| 已完成 | `"Assignment ready"` |

---

## 6. 两个使用路径

### 6.1 SSE 实时推送路径

```
VerlaInboxService.toSsePayload()
  └── enrichAssignmentRunPayload(eventType, payload, conversationId, currentEvent)
        ├── 从 DB 拉最近 300 条事件（含 currentEvent）
        ├── 调用 estimateFromEvents()
        └── 注入 payload.progress.{ estimatedRemainingSeconds, completePercent, label, ... }
```

**触发时机：** 每次收到属于 `ASSIGNMENT_RUN_EVENT_TYPES` 的事件时（Python 没有带 ETA 的情况下）。

### 6.2 快照恢复路径

```
GET /api/v2/verla/conversation/{id}/assignment-snapshot
  └── AssignmentRuntimeSnapshotService.getSnapshot()
        ├── 查最近 EVENT_SCAN_LIMIT 条事件
        ├── progressEstimator.resolveProgress(recentEvents)
        └── 返回 AssignmentRuntimeSnapshotVO.payload.progress
```

**触发时机：** 前端刷新/重连时调用，用于恢复完整 UI 状态。

---

## 7. 输出字段汇总

最终输出的 `progress` Map 包含：

| 字段 | 类型 | 说明 |
|---|---|---|
| `estimatedRemainingSeconds` | `Integer`（可 null）| 剩余秒数；null 表示失败/取消 |
| `completePercent` | `Double` | 0–100，保留 1 位小数 |
| `label` | `String` | 当前步骤描述 |
| `completedTaskCount` | `Integer`（可选）| 已完成的 task 数 |
| `totalTaskCount` | `Integer`（可选）| 总 task 数 |
| `composeCurrentRound` | `Integer`（可选）| 当前 Compose 轮次 |
| `composeTotalRounds` | `Integer`（可选）| Compose 总轮次 |

---

## 8. 关键常量速查

| 常量 | 值 | 位置 |
|---|---|---|
| `TOTAL_ESTIMATED_SECONDS` | 1200 (20 min) | `AssignmentRuntimeProgressEstimator` |
| `PLAN_PHASE_ESTIMATE_SECONDS` | 120 s | 同上 |
| `PLAN_PHASE_REMAINING_FLOOR_SECONDS` | 15 s | 同上 |
| `SIMULATED_PROGRESS_WINDOW_SECONDS` | 120 s | 同上 |
| `SIMULATED_PROGRESS_MAX_PERCENT` | 10.0% | 同上 |
| `RUNNING_NODE_PARTIAL_WEIGHT` | 0.5 | 同上 |
| `WORKFORCE_PHASE_WEIGHT_PERCENT` | 50.0% | 同上 |
| 快照事件扫描上限 | 500 条 | `AssignmentRuntimeSnapshotService.EVENT_SCAN_LIMIT` |
| SSE 事件拉取上限 | 300 条 | `enrichAssignmentRunPayload` 内部 |

---

## 9. 数据流全图

```
Python（verla_agent）
  │
  │  ASSIGNMENT_AGENT_FLOW_STARTED  →  标记开始时间
  │  ASSIGNMENT_AGENT_NODE_UPDATED  →  plan/task 节点状态
  │       payload.node.{ id, status, taskName, composeTotalRounds }
  │  ASSIGNMENT_PROGRESS            →  可选：显式 ETA
  │       payload.progress.{ estimatedRemainingSeconds, label }
  │  ASSIGNMENT_AGENT_FLOW_COMPLETED / FAILED / CANCELLED
  │
  │  [RabbitMQ sharded queues s00–s03]
  ▼
Java（VerlaEventListener）
  │  写入 verla_event_inbox
  │  VerlaEventCursor 去重推进
  ▼
VerlaInboxService.processInbox()
  │
  ├──[handler dispatch]──────────────────────────────────────
  │   VerlaWorkforceNodeEventHandler
  │     ASSIGNMENT_AGENT_NODE_UPDATED
  │       → upsert verla_workforce_tasks
  │         (session_id, node_id, status, task_name, compose_total_rounds)
  │     ASSIGNMENT_AGENT_NODE_DETAILED
  │       → upsert verla_workforce_task_outputs
  │
  └──[SSE path]─────────────────────────────────────────────
      toSsePayload()
        └── enrichAssignmentRunPayload()
              │
              ├── Python 有显式 ETA？ → 原样推送
              │
              └── Java 计算 ETA（estimateFromEvents）
                    │
                    ├── SELECT aggregateProgressBySession(sessionId)
                    │   → WorkforceTaskProgressSnapshot
                    │     { total, completed, active, composeTotalRounds }
                    │
                    ├── 有 task 数据 → estimateFromWorkforceSnapshot
                    │   Phase1: percent = (completed+0.5*running)/total * 50%
                    │   Phase2: percent = 50% + current/total * 50%
                    │   floor:  max(percent, simulatedPercent[0–10%])
                    │
                    ├── plan-only → estimateFromPlanPhase
                    │   remaining = max(15, 120-elapsed)
                    │   percent   = (1-remaining/120) * 10%
                    │
                    └── fallback → estimateFromAgentNodes
                        percent = (weighted/nodes) * 100%
                        floor: simulatedPercent
                    │
                    └── remainingSeconds = 1200*(1-percent/100)
                        → 注入 payload.progress
                        → VerlaSsePublisher → SSE → 前端

前端刷新/重连时
  GET /assignment-snapshot
    AssignmentRuntimeSnapshotService.getSnapshot()
      └── resolveProgress(recentEvents)  [同算法，取最近 500 条事件]
          → AssignmentRuntimeSnapshotVO.payload.progress
```
