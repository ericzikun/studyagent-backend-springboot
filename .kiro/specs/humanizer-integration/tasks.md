# 实现计划：Humanizer 服务集成

## 概述

将 Python Flask Humanizer/AI检测服务集成到 Spring Boot DDD 后端，按照分层架构从底层（infra）到上层（api）逐步实现，每步都可验证。

## 任务

- [x] 1. 配置层与数据模型
  - [x] 1.1 在 `application.yml` 中新增 humanizer-service 配置项
    - 新增 `humanizer-service.url`（默认 `http://localhost:9000`）
    - 新增 `humanizer-service.rate-limit.detect-stream`（默认 10）
    - 新增 `humanizer-service.rate-limit.process`（默认 5）
    - _Requirements: 5.4, 4.1, 4.2_
  - [x] 1.2 在 agent-service 领域层创建 `HumanizerResult` 领域模型
    - 创建 `agent-service/src/main/java/com/studyagent/service/domain/humanizer/HumanizerResult.java`
    - 包含 code、msg、result、elapsedSeconds 字段
    - _Requirements: 2.2_
  - [x] 1.3 在 agent-service 领域层定义 `HumanizerServiceClient` 接口
    - 创建 `agent-service/src/main/java/com/studyagent/service/domain/humanizer/HumanizerServiceClient.java`
    - 声明 `Flux<String> detectAIStream(String text)` 方法
    - 声明 `HumanizerResult humanize(String text)` 方法
    - _Requirements: 5.1_
  - [x] 1.4 在 agent-api 层创建请求/响应 DTO
    - 创建 `HumanizerDetectRequest`（含 @NotBlank text 字段）
    - 创建 `HumanizerProcessRequest`（含 @NotBlank text 字段）
    - 创建 `HumanizerProcessResponse`（含 result、elapsedSeconds 字段）
    - _Requirements: 1.5, 2.3_

- [x] 2. 基础设施层实现（agent-infra）
  - [x] 2.1 创建 `HumanizerWebClientConfig` 独立 WebClient 配置
    - 创建 `agent-infra/src/main/java/com/studyagent/infra/config/HumanizerWebClientConfig.java`
    - 配置连接超时 5 秒、读取超时 5 分钟、响应超时 5 分钟
    - 使用 `@Bean("humanizerWebClient")` 命名区分现有 WebClient
    - _Requirements: 6.1, 6.2, 6.3_
  - [x] 2.2 实现 `HumanizerServiceClientImpl`
    - 创建 `agent-infra/src/main/java/com/studyagent/infra/client/humanizer/HumanizerServiceClientImpl.java`
    - 注入 `@Qualifier("humanizerWebClient")` 的独立 WebClient
    - 实现 `detectAIStream`：使用 WebClient 消费 Python `/predict_stream` 的 SSE 流
    - 实现 `humanize`：同步调用 Python `/process`，解析响应为 HumanizerResult
    - 处理 Python 服务不可达、超时、错误响应等异常场景
    - _Requirements: 5.2, 1.1, 2.1, 2.4, 2.5, 1.6_
  - [ ]* 2.3 编写 `HumanizerServiceClientImpl` 响应解析的属性测试
    - **Property 5: Process 响应包装保持数据完整**
    - **Property 6: Process 错误传播**
    - **Validates: Requirements 2.2, 2.4**

- [x] 3. 限流组件
  - [x] 3.1 创建 `RateLimitExceededException` 异常类
    - 创建 `agent-common` 或 `agent-api` 中的异常类
    - 包含端点名称和限流信息
    - _Requirements: 4.3_
  - [x] 3.2 实现 `HumanizerRateLimiter` 全局限流器
    - 创建 `agent-api/src/main/java/com/studyagent/api/service/HumanizerRateLimiter.java`
    - 基于 ConcurrentLinkedDeque 实现滑动窗口算法
    - 从配置读取限流阈值
    - 提供 `checkDetectStreamLimit()` 和 `checkProcessLimit()` 方法
    - _Requirements: 4.1, 4.2, 4.4_
  - [ ]* 3.3 编写限流器属性测试
    - **Property 3: 限流器在窗口内强制执行请求上限**
    - **Property 4: 限流器滑动窗口过期**
    - **Validates: Requirements 4.1, 4.2, 4.3, 4.4**
  - [x] 3.4 在 `GlobalExceptionHandler` 中新增 `RateLimitExceededException` 处理
    - 返回 429 状态码和描述性错误信息
    - _Requirements: 4.3_

- [ ] 4. Checkpoint - 确保基础组件编译通过
  - 确保所有测试通过，如有问题请向用户确认。

- [x] 5. API 层与 SSE 代理（agent-api）
  - [x] 5.1 创建 `HumanizerApplicationService` 应用服务
    - 创建 `agent-api/src/main/java/com/studyagent/api/service/HumanizerApplicationService.java`
    - 注入 `HumanizerServiceClient`
    - 实现 `SseEmitter detectAIStream(String text)` 方法：消费 Flux 并通过 SseEmitter 转发 SSE 事件
    - 实现 `HumanizerProcessResponse humanize(String text)` 方法：调用领域接口并转换为响应 DTO
    - 处理 SSE 事件解析（从原始 SSE 行中提取 event name 和 data）
    - _Requirements: 1.2, 1.3, 1.4, 1.6, 2.2_
  - [ ]* 5.2 编写 SSE 事件解析的属性测试
    - **Property 1: SSE 事件透明转发**
    - **Validates: Requirements 1.2, 1.3, 1.4**
  - [ ]* 5.3 编写空文本验证的属性测试
    - **Property 2: 空文本输入验证**
    - **Validates: Requirements 1.5, 2.3**
  - [x] 5.4 创建 `HumanizerController`
    - 创建 `agent-api/src/main/java/com/studyagent/api/controller/HumanizerController.java`
    - `@RequestMapping("/v1/humanizer")`
    - `POST /detect-stream`：接收 HumanizerDetectRequest，调用限流器，返回 SseEmitter
    - `POST /process`：接收 HumanizerProcessRequest，调用限流器，返回 Result<HumanizerProcessResponse>
    - 使用 `@RequestAttribute("clerkUserId")` 获取认证用户 ID
    - 使用 `@Valid` 触发请求参数验证
    - _Requirements: 5.3, 3.1, 3.2, 1.1, 2.1_

- [x] 6. 认证集成验证
  - [x] 6.1 确认 `/v1/humanizer/**` 路径被现有 AuthInterceptor 覆盖
    - 检查 `WebConfig` 中的拦截器路径配置，确认 `/**` 模式已覆盖新路径
    - 无需修改 WebConfig（`/**` 已包含 `/v1/humanizer/**`，且不在排除列表中）
    - _Requirements: 3.1, 3.2, 3.3_

- [ ] 7. Final Checkpoint - 确保所有组件集成完成
  - 确保所有测试通过，如有问题请向用户确认。

## 备注

- 标记 `*` 的任务为可选任务，可跳过以加速 MVP 交付
- 每个任务引用了具体的需求编号以确保可追溯性
- 属性测试使用 jqwik 库，每个测试至少运行 100 次迭代
- Python 服务（humanizer_aidetect/）无需修改，仅作为参考
- 认证无需额外代码，现有 AuthInterceptor 的 `/**` 路径模式已覆盖新端点
