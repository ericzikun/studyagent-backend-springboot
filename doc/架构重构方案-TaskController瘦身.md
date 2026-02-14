# TaskController 架构重构方案

## 一、现状问题

1. **Controller 过于臃肿**：尤其是 `getTaskDetail` 约 340 行，包含数据查询、组装、权限校验等所有逻辑
2. **职责混合**：Controller 直接依赖 Mapper（Infra 层），违反分层架构
3. **难以测试**：业务逻辑与 HTTP 层耦合，难以独立单测
4. **难以复用**：列表转换、详情组装等逻辑只能在 Controller 内使用

## 二、目标架构

```
┌─────────────────────────────────────────────────────────────────┐
│  Controller (API 层)                                             │
│  - 参数校验、登录态校验                                            │
│  - 调用 ApplicationService                                       │
│  - 将 Result 封装为 HTTP 响应                                     │
│  - 薄薄一层，每个接口 < 20 行                                      │
└───────────────────────────┬─────────────────────────────────────┘
                            │
┌───────────────────────────▼─────────────────────────────────────┐
│  TaskApplicationService (应用层)                                  │
│  - 编排用例流程                                                   │
│  - 权限校验（isAdmin、任务归属）                                   │
│  - 调用 Query 接口获取数据                                         │
│  - 调用 PythonBackendClient 获取队列信息等                         │
│  - 返回领域/应用层 DTO                                             │
└───────────────────────────┬─────────────────────────────────────┘
                            │
┌───────────────────────────▼─────────────────────────────────────┐
│  Query/Repository (领域接口 + Infra 实现)                          │
│  - TaskDetailReader：组装任务详情                                 │
│  - TaskActivityReader：分页查询活动日志                            │
│  - TaskOutputReader：获取输出文件内容（下载用）                     │
└─────────────────────────────────────────────────────────────────┘
```

## 三、重构清单

| 接口 | 现状 | 目标 |
|------|------|------|
| `getTaskDetail` | Controller 340+ 行，直接查 Mapper | ApplicationService.getTaskDetail → Controller 仅封装 |
| `getTaskList` (POST/GET) | Controller 做队列查询、列表转换 | ApplicationService.getTaskList 返回完整列表结果 |
| `getTaskActivities` | Controller 做权限校验 + 分页查询 | ApplicationService.getTaskActivities |
| `rateTask` | Controller 做权限校验 | 已部分在 Service，统一挪入 |
| `clarifyTask` | Controller 做参数组装 + 转发 | ApplicationService.clarifyTask |
| `downloadOutput` | Controller 做查询 + 权限(缺失) + 封装 | ApplicationService.downloadOutput（含权限校验） |

## 四、新增/修改的类

### 4.1 agent-service 模块

| 类 | 路径 | 说明 |
|----|------|------|
| `TaskDetailDTO` | `service/application/dto/` | 任务详情数据传输对象，与 TaskDetailResponse 结构一致 |
| `TaskDetailReader` | `service/domain/task/` | 接口：加载任务详情（仅读） |
| `GetTaskDetailRequest` | `service/application/request/` | 请求：taskId, clerkUserId |
| `TaskListResult` | `service/application/dto/` | 任务列表结果：items + total + pageNo + pageSize |
| `TaskActivitiesPageResult` | `service/application/dto/` | 活动日志分页结果 |
| `DownloadOutputResult` | `service/application/dto/` | 下载结果：bytes + filename + contentType |

### 4.2 agent-infra 模块

| 类 | 路径 | 说明 |
|----|------|------|
| `TaskDetailReaderImpl` | `infra/repository/task/` | 实现 TaskDetailReader，使用 Mapper 组装数据 |
| `TaskActivityReaderImpl` | `infra/repository/task/` | 分页查询活动日志（或直接扩展现有 Repository） |

### 4.3 TaskApplicationService 新增方法

```java
// 任务详情（含权限校验）
TaskDetailDTO getTaskDetail(GetTaskDetailRequest request);

// 任务列表（含队列信息、列表项转换）
TaskListResult getTaskList(GetTaskListRequest request);

// 活动日志分页
TaskActivitiesPageResult getTaskActivities(Long taskId, String clerkUserId, int pageNo, int pageSize);

// 追问（转发 Python）
ClarifyTaskResult clarifyTask(ClarifyTaskRequest request);

// 下载输出（含权限校验）
DownloadOutputResult downloadOutput(Long outputId, String clerkUserId);
```

## 五、依赖调整

- `TaskApplicationService` 新增依赖：`TaskDetailReader`、`UserRepository`
- `TaskController` 移除：`TaskMapper`、`TaskAgentMapper`、`SubTaskMapper`、`TaskActivityMapper`、`TaskOutputMapper`、`TaskFileMapper`、`FileMapper`
- `TaskController` 保留：`TaskApplicationService`、`UserRepository`（若用于未登录校验可保留，或统一由拦截器处理）

## 六、实施步骤（分阶段）

### 阶段 1：getTaskDetail 瘦身（优先级最高）✅ 已完成

1. 创建 `TaskDetailDTO` 及其嵌套类（与 TaskDetailResponse 结构一致）
2. 创建 `TaskDetailReader` 接口
3. 创建 `TaskDetailReaderImpl`（从 Controller 搬迁逻辑）
4. 在 `TaskApplicationService` 中实现 `getTaskDetail`
5. 精简 `TaskController.getTaskDetail` 为 10 行以内

### 阶段 2：任务列表瘦身 ✅ 已完成

1. 创建 `TaskListResult`、`TaskListItemDTO`，将 `convertToTaskListItemResponse`、`fetchQueueInfoBatch` 迁入 ApplicationService
2. `getTaskList` 和 `getTaskListByGet` 共用 Service 方法，Controller 仅做参数组装

### 阶段 3：活动日志、追问、下载

1. 活动日志：Service 封装 `getTaskActivities`
2. 追问：Service 封装 `clarifyTask`
3. 下载：Service 封装 `downloadOutput`，并补充权限校验

## 七、重构后 Controller 示例

```java
@PostMapping("/detail")
public Result<TaskDetailResponse> getTaskDetail(
        @Valid @RequestBody TaskDetailRequest request,
        @RequestAttribute(value = "clerkUserId", required = false) String clerkUserId) {
    if (clerkUserId == null || clerkUserId.isEmpty()) {
        return Result.error("User not logged in");
    }
    GetTaskDetailRequest appRequest = GetTaskDetailRequest.builder()
            .taskId(request.getTaskId())
            .clerkUserId(clerkUserId)
            .build();
    TaskDetailDTO dto = taskApplicationService.getTaskDetail(appRequest);
    return Result.success(TaskDetailConverter.toResponse(dto));
}
```

## 八、注意事项

1. **异常处理**：ApplicationService 中权限不足、任务不存在等抛业务异常，由 `GlobalExceptionHandler` 统一转换为 `Result.error(code, msg)`
2. **调试日志**：迁移时可将大量 `log.info` 改为 `log.debug`，减少生产噪音
3. **向后兼容**：响应结构不变，前端无需改动
