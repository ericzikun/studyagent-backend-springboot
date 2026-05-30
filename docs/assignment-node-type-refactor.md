# Assignment 节点类型显式化重构设计文档

> 版本：2026-05-30  
> 涉及模块：`verla_agent/assignment_callback`、`agent-common/VerlaWorkforceNodeUpdatedPayload`、`agent-service/VerlaWorkforceNodeEventHandler`、`agent-service/AssignmentRuntimeProgressEstimator`、`agent-infra/VerlaWorkforceTaskRepositoryImpl`

---

## 1. 背景与动机

### 1.1 原有实现的问题

Java 侧识别 `ASSIGNMENT_AGENT_NODE_UPDATED` 节点类型，依赖以下文本约定：

| 推断逻辑 | 位置 | 问题 |
|---|---|---|
| `id == "assignment-plan"` 判 plan 节点 | `VerlaWorkforceNodeEventHandler`、`AssignmentRuntimeProgressEstimator` | ID 命名约定变化即破坏 |
| `id.startsWith("task-")` 判 task 节点 | 同上 + `VerlaWorkforceTaskRepositoryImpl` | 同上 |
| 正则匹配 `"Composing part N/M"` 标题解析 compose 轮次 | `AssignmentRuntimeProgressEstimator.COMPOSE_PART_TITLE` | **实际不工作**：`on_part_progress` 只发 DETAIL 事件，不发 NODE_UPDATED，regex 永远匹配不到 |

### 1.2 改造目标

1. Python 在每个节点事件中显式携带 `nodeType` 字段（`"plan"` / `"task"` / `"compose"`），Java 直接读取，不再推断。
2. 修复 compose 当前轮次追踪：Python `on_part_progress` 额外发一个 `NODE_UPDATED`，携带 `composeCurrentRound` / `composeTotalRounds`。
3. 所有原 id-based 推断保留为向后兼容兜底，不影响旧事件处理。

---

## 2. 新增协议字段

### 2.1 Python → Java 节点 payload 新增字段

`ASSIGNMENT_AGENT_NODE_UPDATED` 事件的 `payload.node` 对象新增：

| 字段 | 类型 | 值域 | 说明 |
|---|---|---|---|
| `nodeType` | `string` | `"plan"` / `"task"` / `"compose"` | 节点语义类型，所有节点均携带 |
| `composeTotalRounds` | `integer`（可选）| ≥ 1 | compose 总轮次；`plan` 和 `compose` 节点携带 |
| `composeCurrentRound` | `integer`（可选）| ≥ 1 | 当前已完成轮次；`compose` 节点携带 |

### 2.2 新增 `compose-progress` 节点

Python 在每个 compose part 完成时（`on_part_progress`），除原有 DETAIL 事件外，额外发一个 NODE_UPDATED：

```json
{
  "node": {
    "id": "compose-progress",
    "nodeType": "compose",
    "taskName": "Composing part 3/10",
    "status": "running",
    "composeCurrentRound": 3,
    "composeTotalRounds": 10
  }
}
```

此节点不入库（Java handler 识别后跳过 DB upsert），仅用于 ETA 计算器通过 `foldAgentNodes` 读取 compose 进度。

---

## 3. 各节点 `nodeType` 发送时机

| 节点类型 | `nodeType` | 发送时机 | `id` |
|---|---|---|---|
| plan | `"plan"` | `log_task_decomposed`（running→completed）、`emit_compose_total` | `"assignment-plan"` |
| task | `"task"` | `log_task_started`（running）、`log_task_completed`、`log_task_failed` | `"task-{camel_task_id}"` |
| compose 进度 | `"compose"` | `on_part_progress`（每个 part 完成后）| `"compose-progress"` |

plan 节点在 `emit_compose_total` 时还携带 `composeTotalRounds=total_parts`，供 Java 直接写入 DB。

---

## 4. 改动范围

### 4.1 Python — `assignment_callback.py`

#### `_node_payload()` 签名扩展

```python
@classmethod
def _node_payload(
    cls,
    *,
    node_id: str,
    task_name: str,
    status: AssignmentCanvasNodeStatus,
    node_type: str = "",           # 新增
    task_agent: str = "",
    content: str = "",
    steps: list[dict[str, Any]] | None = None,
    compose_current_round: int | None = None,  # 新增
    compose_total_rounds: int | None = None,   # 新增
) -> dict[str, Any]:
```

生成的 node dict 中 `nodeType` 始终存在（空字符串表示未指定）；`composeCurrentRound` / `composeTotalRounds` 仅在非 None 时写入。

#### 各调用处改动

| 方法 | 改动 |
|---|---|
| `_emit_plan_node` | 加 `node_type="plan"` |
| `emit_compose_total` | 加 `node_type="plan"`，加 `compose_total_rounds=total_parts` |
| `log_task_started` | 加 `node_type="task"` |
| `log_task_completed` | 加 `node_type="task"` |
| `log_task_failed` | 加 `node_type="task"` |
| `on_part_progress` | 保留原 DETAIL emit；**新增** NODE_UPDATED emit（id=`"compose-progress"`, node_type=`"compose"`, composeCurrentRound, composeTotalRounds）；仅在 `part_index` 和 `total_parts` 均为 `int` 且 `total_parts > 0` 时发送 |

---

### 4.2 Java — `VerlaWorkforceNodeUpdatedPayload.Node`（agent-common）

```java
public static class Node {
    private String id;
    private String nodeType;           // 新增："plan" | "task" | "compose"
    private String taskName;
    private String taskAgent;
    private String status;
    private String content;
    private List<Map<String, Object>> steps;
    private Double processingTimeSeconds;
    private Integer composeCurrentRound;  // 新增
    private Integer composeTotalRounds;   // 新增
}
```

---

### 4.3 Java — `VerlaWorkforceNodeEventHandler`（agent-service）

#### 节点类型判定逻辑替换

```java
// 之前
boolean isPlan = PLAN_NODE_ID.equals(node.getId());
if (!isPlan && !node.getId().startsWith("task-")) { return; }

// 之后
boolean isPlan = "plan".equalsIgnoreCase(node.getNodeType())
    || PLAN_NODE_ID.equals(node.getId());          // id 兜底
boolean isTask = "task".equalsIgnoreCase(node.getNodeType())
    || (node.getId() != null && node.getId().startsWith("task-")); // id 兜底
if (!isPlan && !isTask) { return; }  // compose 节点不入库，直接跳过
```

#### `buildTaskPatch` 变更

- 签名加 `isTask` 参数
- task 分支：camelTaskId 提取兜底处理（`nodeType=="task"` 时 id 可能无 `"task-"` 前缀）
- plan 分支：优先读 `node.getComposeTotalRounds()` 写入 `planTaskCount`；字段为空时兜底解析 `compose-part-*` steps（向后兼容）

---

### 4.4 Java — `AssignmentRuntimeProgressEstimator`（agent-service）

#### 新增辅助方法

```java
// 判断 foldAgentNodes 中的节点是否为 plan（优先 nodeType，兜底 id）
private boolean isPlanNode(Map<String, Object> node)

// 判断节点是否为 compose 进度节点（优先 nodeType，兜底 id=="compose-progress"）
private boolean isComposeNode(Map<String, Object> node)
```

#### 各处替换

| 原逻辑 | 新逻辑 |
|---|---|
| `"assignment-plan".equals(node.get("id"))` | `isPlanNode(node)` |
| `resolveComposeTotalRounds`：仅靠 regex 解析标题 | 优先读 compose/plan 节点的 `composeTotalRounds` 字段；regex 降为兜底 |
| `resolveComposeCurrentRound`：仅靠 regex 解析标题 | 优先读 compose 节点的 `composeCurrentRound` 字段；regex 降为兜底 |
| `resolveWorkforceLabel`：`nodeId.startsWith("task-")` 二次过滤 | 删除，`nodeKind=="task"` 已由 handler 保证 |

---

### 4.5 Java — `VerlaWorkforceTaskRepositoryImpl`（agent-infra）

`aggregateProgressBySession` 中删除冗余的 `nodeId.startsWith("task-")` 过滤行。  
保留 `nodeKind == "task"` 过滤，语义不变。

---

## 5. 向后兼容性

所有 id-based 推断保留为兜底路径。旧版 Python（未携带 `nodeType`）发来的事件，Java 仍能正确处理：

| 旧事件特征 | Java 处理路径 |
|---|---|
| `nodeType` 缺失或空 | 兜底走 `id` 推断 |
| 无 `composeTotalRounds` 字段 | 兜底解析 `compose-part-*` steps |
| 无 `composeCurrentRound` 字段 | 兜底走 `COMPOSE_PART_TITLE` regex |

---

## 6. compose 轮次追踪修复说明

### 修复前（broken）

```
on_part_progress
  └── _emit_detail()  ← ASSIGNMENT_AGENT_NODE_DETAILED
                         detail_chunk: [{type:"edit_file", name:"Composed part 3/10"}]

AssignmentRuntimeProgressEstimator.COMPOSE_PART_TITLE regex
  └── foldAgentNodes()  ← 只折叠 NODE_UPDATED 事件
      → regex 永远匹配不到任何节点标题
      → composeCurrentRound 始终为 0
```

### 修复后

```
on_part_progress
  ├── _emit_detail()   ← ASSIGNMENT_AGENT_NODE_DETAILED（保持不变）
  └── _emit_node()     ← ASSIGNMENT_AGENT_NODE_UPDATED
                          { id:"compose-progress", nodeType:"compose",
                            composeCurrentRound:3, composeTotalRounds:10 }

foldAgentNodes()
  └── 折叠到 "compose-progress" 节点，最新状态 composeCurrentRound=3

resolveComposeCurrentRound()
  └── isComposeNode(node) → 读 composeCurrentRound 字段 → 3  ✓
```

---

## 7. 数据流全图（更新后）

```
Python（verla_agent/assignment_callback.py）
  │
  │  NODE_UPDATED { node.id="assignment-plan", nodeType="plan", composeTotalRounds=N }
  │    → plan 阶段：规划中 (running)
  │    → compose 开始：completed + steps + composeTotalRounds
  │
  │  NODE_UPDATED { node.id="task-{id}", nodeType="task", status=running/completed/failed }
  │    → 子任务生命周期
  │
  │  NODE_UPDATED { node.id="compose-progress", nodeType="compose",
  │                 composeCurrentRound=N, composeTotalRounds=M }
  │    → 每个 part 完成后（on_part_progress 新增）
  │
  │  NODE_DETAILED { id="task-{id}", detailChunk=[...] }
  │    → on_part_progress 原有 DETAIL（保持不变）
  │
  │  [RabbitMQ sharded queues s00–s03]
  ▼
VerlaWorkforceNodeEventHandler
  │
  ├── nodeType=="plan" (或 id=="assignment-plan")
  │     → upsert verla_workforce_tasks (nodeKind=plan)
  │     → planTaskCount = composeTotalRounds 字段 (兜底: compose-part-* steps 数量)
  │
  ├── nodeType=="task" (或 id.startsWith("task-"))
  │     → upsert verla_workforce_tasks (nodeKind=task)
  │
  └── nodeType=="compose" / 其他
        → 跳过，不入库
              │
              ▼
AssignmentRuntimeProgressEstimator（ETA 计算）
  │
  ├── foldAgentNodes(recentEvents)
  │   → 折叠所有 NODE_UPDATED 节点，含 "compose-progress"
  │
  ├── isPlanNode(node)      ← 优先 nodeType=="plan"，兜底 id=="assignment-plan"
  ├── isComposeNode(node)   ← 优先 nodeType=="compose"，兜底 id=="compose-progress"
  │
  ├── resolveComposeTotalRounds
  │   1. compose/plan 节点的 composeTotalRounds 字段
  │   2. COMPOSE_PART_TITLE regex（兜底）
  │   3. DB plan_task_count（兜底）
  │
  └── resolveComposeCurrentRound
      1. compose 节点的 composeCurrentRound 字段  ← 新增，首次可用
      2. COMPOSE_PART_TITLE regex（兜底）
```

---

## 8. 关键文件速查

| 文件 | 语言 | 改动类型 |
|---|---|---|
| `verla_agent/app/services/assignment_flow/assignment_callback.py` | Python | `_node_payload` 扩展；各节点加 `nodeType`；`on_part_progress` 新增 NODE_UPDATED |
| `agent-common/.../payload/VerlaWorkforceNodeUpdatedPayload.java` | Java | `Node` 新增 `nodeType`、`composeCurrentRound`、`composeTotalRounds` |
| `agent-service/.../handler/VerlaWorkforceNodeEventHandler.java` | Java | isPlan/isTask 判定改用 `nodeType`；compose 节点跳过入库 |
| `agent-service/.../AssignmentRuntimeProgressEstimator.java` | Java | 新增 `isPlanNode`/`isComposeNode`；compose 轮次改读显式字段 |
| `agent-infra/.../VerlaWorkforceTaskRepositoryImpl.java` | Java | 删除冗余 `nodeId.startsWith("task-")` 过滤 |
