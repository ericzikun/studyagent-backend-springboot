# Humanizer 任务状态说明

本文说明 `humanizer_tasks` 的状态集合、状态流转、对外语义，以及 `CHARGING` 引入后的约束。

## 1. 背景

`humanizer_tasks` 同时承载两类异步任务：

- `DETECT`
- `HUMANIZE`

两者共用一张任务表，但扣费时机不同：

- `DETECT`：提交时只校验额度，真正扣费发生在 Worker 启动时。
- `HUMANIZE`：提交时按次扣费。

为了让 `HUMANIZE` 可以使用 `taskId` 作为业务幂等键，提交流程需要先插入任务，再执行 `consume`。  
因此新增内部状态 `CHARGING`，用来表示：

- 任务已落库
- 任务还未进入可被 Worker 处理的排队态
- 正在完成提交链路中的扣费确认

## 2. 状态集合

当前 `humanizer_tasks.status` 支持以下值：

| 状态 | 是否终态 | 说明 |
| --- | --- | --- |
| `CHARGING` | 否 | 仅 `HUMANIZE` 使用。任务已创建，正在完成提交期扣费，不允许 Worker 抢占。 |
| `PENDING` | 否 | 已进入排队，可被 Worker 抢占。 |
| `PROCESSING` | 否 | Worker 已抢占，正在处理。 |
| `COMPLETED` | 是 | 处理完成。 |
| `FAILED` | 是 | 处理失败，且重试已耗尽。 |
| `QUOTA_EXHAUSTED` | 是 | 额度不足，当前无法继续。 |
| `CANCELLED` | 是 | 已取消。 |

## 3. 状态流转

### 3.1 DETECT

```text
submit
  -> PENDING
Worker claim
  -> PROCESSING
process success
  -> COMPLETED
process fail and retry available
  -> PENDING
process fail and retry exhausted
  -> FAILED
quota insufficient before start / during resume gate
  -> QUOTA_EXHAUSTED
user cancel
  -> CANCELLED
```

说明：

- `DETECT` 不使用 `CHARGING`。
- `DETECT` 的真实扣费发生在 Worker 启动时，幂等键为 `detection:{taskId}:start`。

### 3.2 HUMANIZE

```text
submit
  -> CHARGING
consume success
  -> PENDING
consume fail
  -> QUOTA_EXHAUSTED
Worker claim
  -> PROCESSING
process success
  -> COMPLETED
process fail and retry available
  -> PENDING
process fail and retry exhausted
  -> FAILED
user cancel
  -> CANCELLED
```

说明：

- `HUMANIZE` 提交时幂等键为 `humanizer:{taskId}:start`。
- `CHARGING` 的存在是为了消除“先插任务、后扣费”带来的抢占竞态。

## 4. Worker 约束

Worker 只能处理 `PENDING` 任务：

- `findPendingTasks(...)` 仅查询 `status = 'PENDING'`
- `claimTask(...)` 仅允许 `PENDING -> PROCESSING`

这意味着：

- `CHARGING` 任务不会被 Worker 抢走
- `HUMANIZE` 只有在扣费成功后才进入真正排队态

## 5. 取消语义

取消接口允许以下状态取消：

- `CHARGING`
- `PENDING`
- `PROCESSING`

其中：

- `HUMANIZE` 在 `CHARGING` / `PENDING` 取消，视为“尚未开始处理的排队态取消”
- 如果此时已有 `quotaLedgerId`，则执行退款
- 如果 `CHARGING` 时尚未拿到 `quotaLedgerId`，退款逻辑自然为空操作

## 6. 对外语义

`CHARGING` 是内部保护态，不希望前端因此新增状态分支。

因此服务层对外响应时做语义映射：

- 内部 `CHARGING`
- 对外视作 `PENDING`

这条规则适用于：

- 任务详情
- 任务列表
- 进度计算

换句话说，前端仍然把它理解为“排队中”，但后端内部会用 `CHARGING` 保证提交期扣费与可抢占排队态解耦。

## 7. 为什么不直接用 quotaLedgerId 判定

不能简单把 “`quotaLedgerId == null`” 当作“还不能执行”的条件，原因是：

- admin 用户可能不扣费
- 白名单用户可能不扣费

这类任务合法情况下也可能长期没有 `quotaLedgerId`。  
所以是否允许 Worker 抢占，必须由显式状态控制，而不是由扣费字段倒推。

## 8. 当前实现约定

1. `CHARGING` 只用于 `HUMANIZE submit` 链路。
2. `DETECT` 仍沿用“提交即 `PENDING`，启动时扣费”的模式。
3. 若未来有新的“先建任务、后确认资源”的链路，优先复用显式状态，不复用 `quotaLedgerId` 语义。
