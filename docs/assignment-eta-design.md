# Assignment 生成 ETA 计算设计文档

> 版本：2025-05-30  
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
| `ASSIGNMENT_AGENT_NODE_UPDATED` | Workforce 节点状态变更（plan / task / compose）| 可选 |
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
| `node_id` | `node.id` | 节点唯一键 |
| `node_kind` | `node.nodeType` → `plan` / `task` / `compose` | 见下方分类规则 |
| `status` | `node.status` | queued / running / completed / failed |
| `task_name` | `node.taskName` | 任务标题，用于 ETA label |
| `compose_current_round` | `node.composeCurrentRound` | 当前 Compose 轮次（compose 行写入）|
| `compose_total_rounds` | `node.composeTotalRounds` | Compose 总轮次（plan / compose 行写入）|
| `conversation_id` | 事件 envelope | 用于跨 session 汇总 |

> 终态（completed / failed）不会被回退到 queued / running。

#### node_kind 分类规则

**`nodeType` 字段是唯一分类依据**，`plan` 节点额外保留 `node.id == "assignment-plan"` 的兜底以兼容旧 Python 版本：

```
isPlan    = nodeType=="plan"  ||  id=="assignment-plan"
isTask    = !isPlan  &&  nodeType=="task"
isCompose = !isPlan  &&  !isTask  &&  nodeType=="compose"
```

> **重要**：不再使用 `id.startsWith("task-")` 做 task 类型推断。  
> 旧逻辑中，compose 任务的进度事件（`nodeType="compose"` + `id="task-N"`）会被误判为 task 类型，导致 compose 进度无法入库，Phase 2 ETA 永远无法触发。移除该兜底后，`nodeType` 具有最终解释权。

#### compose 任务节点的双重角色

Compose 是 Workforce plan 分解出来的一个真实子任务，因此它同时经历两种 `node_kind`：

```
log_task_started(compose_task)
  → id="task-{N}", nodeType="task", status=RUNNING
  → DB: kind=task, RUNNING   ← 计入 totalTaskCount

on_compose_total(compose_task)
  → id="task-{N}", nodeType="compose", composeTotalRounds=M
  → DB: 同一行 kind 覆写为 compose   ← 退出 totalTaskCount，进入 compose 统计

on_part_progress(i/M)
  → id="task-{N}", nodeType="compose", composeCurrentRound=i
  → DB: compose 行 composeCurrentRound 递增

log_task_completed(compose_task)
  → id="task-{N}", nodeType="task", status=COMPLETED
  → DB: kind 再次覆写回 task, COMPLETED   ← 重新计入 totalTaskCount（已完成）
```

这一设计使得 `on_compose_total` 触发时 compose 任务自动退出 `activeTaskCount`，从而让 Phase 2 入口条件（`completed >= total && running == 0`）自然满足，无需额外改动 ETA 计算逻辑。

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
  │   { totalTaskCount, completedTaskCount, activeTaskCount,
  │     composeTotalRounds, composeCurrentRound }
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

**触发条件：** `verla_workforce_tasks` 有 task 行（`totalTaskCount > 0`）或有 compose 进度数据。

整体采用**两段式 50/50 模型**：

#### 阶段一：子任务执行（0% → 50%）

```
weighted = completedTaskCount + (activeTaskCount > 0 ? 0.5 : 0.0)
percent  = min(50%, (weighted / totalTaskCount) * 50%)
```

- 每个运行中的任务贡献 0.5（`RUNNING_NODE_PARTIAL_WEIGHT`）
- 上限 50%

#### 阶段二：Compose 写作（50% → 100%）

**触发条件：** `completed >= total && running == 0`

该条件在 compose 任务发出 `on_compose_total` 后**自动满足**：`on_compose_total` 将该行 `node_kind` 从 `task` 覆写为 `compose`，使其退出 `totalTaskCount` / `activeTaskCount` 统计，其余任务此时均已 COMPLETED，故 `running == 0` 成立。

Compose 总轮次解析（优先级）：
1. DB `compose_total_rounds` 字段（compose 行，由 `on_compose_total` 写入）
2. 历史事件折叠节点中 `nodeType=="compose"` 节点的 `composeTotalRounds` 字段
3. 节点标题正则匹配 `"Composing part N/M"` → M（旧版兼容）
4. 0（无 compose 信息）

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
| Workforce 有任务运行 | DB 中 `node_kind=task` 且 `status=running` 的 `task_name` |
| Compose 阶段 | 折叠节点中 `nodeType=="compose"` 的 `taskName`（若格式为 `"Composing part N/M"` 则直接展示），兜底 `"Composing assignment"` |
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

## 9. 补充说明（代码实证）

### 9.1 `ASSIGNMENT_RUN_EVENT_TYPES` 完整列表

Java 在以下 **7 个事件**触发时才会尝试注入 ETA（定义于 `AssignmentRuntimeProgressEstimator`）：

| 事件名 | 说明 |
|---|---|
| `ASSIGNMENT_AGENT_FLOW_STARTED` | 流程启动（新名称）|
| `ASSIGNMENT_STARTED` | 流程启动（旧名称兼容）|
| `ASSIGNMENT_AGENT_NODE_UPDATED` | Workforce 节点状态变更（新名称）|
| `ASSIGNMENT_WORKFLOW_NODE_UPDATED` | 节点状态变更（旧名称兼容）|
| `ASSIGNMENT_PROGRESS` | 通用进度事件 |
| `AGENT_PROGRESS` | 通用进度事件（旧名称）|
| `ASSIGNMENT_AGENT_FLOW_ARTIFACT_UPDATED` | 产出物更新 |

终态事件（`ASSIGNMENT_RUN_TERMINAL_EVENT_TYPES`，触发完成/失败/取消逻辑，不触发 ETA 计算）：
`ASSIGNMENT_AGENT_FLOW_COMPLETED` / `ASSIGNMENT_AGENT_FLOW_FAILED` / `ASSIGNMENT_AGENT_FLOW_CANCELLED`
以及旧名称兼容：`ASSIGNMENT_COMPLETED` / `ASSIGNMENT_FAILED` / `ASSIGNMENT_CANCELLED` / `AGENT_COMPLETED` / `AGENT_FAILED` / `AGENT_CANCELLED`

### 9.2 Python "显式 ETA" 的判定方式

Java 的 `containsExplicitEta()` 检查 `payload.progress`（或 payload 顶层）是否存在以下任一键，且值为 **Number 类型**：

```
estimatedRemainingSeconds
estRemainingTimeSeconds
estimated_remaining_seconds
est_remaining_time
estimatedTimeRemainingSeconds
```

满足条件 → payload 原样返回，**跳过 Java 自行计算**。  
不满足（键不存在或值为 null）→ Java 继续计算并注入。

> **注意**：值为 `null` 不算显式 ETA，仅有键名不够，必须是 Number。

### 9.3 Java 自行计算 ETA 时的数据来源

Java 计算 ETA **不读取 payload 里的进度字段**，而是使用以下三个独立来源：

#### ① DB 聚合（主路径）— `verla_workforce_tasks`

```sql
aggregateProgressBySession(sessionId)
→ WorkforceTaskProgressSnapshot {
    totalTaskCount,       -- node_kind='task' 的行数
    completedTaskCount,   -- node_kind='task' && status='completed'
    activeTaskCount,      -- node_kind='task' && status='running'
    composeTotalRounds,   -- node_kind='compose' 或 'plan' 的 compose_total_rounds
    composeCurrentRound   -- node_kind='compose' 的 compose_current_round（取最大值）
  }
```

由 `VerlaWorkforceNodeEventHandler` 在收到 `ASSIGNMENT_AGENT_NODE_UPDATED` 时写入。  
**注意**：compose 任务在 `on_compose_total` 触发后 `node_kind` 变为 `compose`，不再计入 `totalTaskCount` / `activeTaskCount`，Phase 2 入口条件因此自然满足。

#### ② 历史事件折叠节点（降级路径）— `foldAgentNodes(recentEvents)`

从最近 300 条事件中，把所有 `ASSIGNMENT_AGENT_NODE_UPDATED` 事件按 `node.id` 合并成快照。  
节点类型判断同样以 `nodeType` 为准（`isPlanNode` / `isComposeNode`）：

| 字段 | 用途 |
|---|---|
| `node.nodeType` | 判断节点类型（plan / task / compose）|
| `node.status` | 判断节点状态（running/completed/failed/queued）|
| `node.taskName` / `title` / `summary` / `subtitle` | Label 文本 |
| `node.composeTotalRounds` | compose / plan 节点的总轮次 |
| `node.composeCurrentRound` | compose 节点的当前轮次 |
| `node.title` 匹配 `"Composing part N/M"` | 旧版兼容：解析轮次 |

#### ③ 流程开始时间戳（时间兜底）

取最早的 `ASSIGNMENT_AGENT_FLOW_STARTED` / `ASSIGNMENT_STARTED` 事件的 `receivedAt`，计算已耗时作为进度下界：

```
elapsed = now - flowStartedAt
simulatedPercent = (min(elapsed, 120s) / 120s) * 10%   // 最高 10%
effectivePercent = max(simulatedPercent, workforcePercent)
```

#### 汇总

```
payload 的作用：仅用于判断「Python 有没有给 ETA」

Java 自行计算的数据来源：
  ① DB verla_workforce_tasks    → total / completed / running / compose 轮次
  ② 历史事件折叠节点             → nodeType / status / compose 字段（降级 + label）
  ③ FLOW_STARTED 的 receivedAt  → 时间兜底 simulatedPercent（0–10%）
```

---

## 10. 数据流全图

```
Python（verla_agent）
  │
  │  ASSIGNMENT_AGENT_FLOW_STARTED  →  标记开始时间
  │
  │  ASSIGNMENT_AGENT_NODE_UPDATED (plan)
  │       payload.node.{ id="assignment-plan", nodeType="plan",
  │                      status, taskName, steps }
  │
  │  ASSIGNMENT_AGENT_NODE_UPDATED (task)
  │       payload.node.{ id="task-N", nodeType="task",
  │                      status, taskName }
  │
  │  ASSIGNMENT_AGENT_NODE_UPDATED (compose — on_compose_total / on_part_progress)
  │       payload.node.{ id="task-N", nodeType="compose",   ← 同一 node_id，kind 覆写
  │                      status="running",
  │                      composeCurrentRound, composeTotalRounds }
  │
  │  ASSIGNMENT_PROGRESS  →  可选：显式 ETA
  │       payload.progress.{ estimatedRemainingSeconds, label }
  │
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
  │       → nodeType 决定 node_kind（plan / task / compose）
  │       → upsert verla_workforce_tasks
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
                    │     { total, completed, active,
                    │       composeTotalRounds, composeCurrentRound }
                    │
                    ├── 有 task/compose 数据 → estimateFromWorkforceSnapshot
                    │   Phase1: percent = (completed+0.5*running)/total * 50%
                    │   Phase2: on_compose_total 后 running==0，自动进入
                    │           percent = 50% + current/total * 50%
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
