# 需求文档

## 简介

将独立运行的 Python Flask Humanizer/AI检测服务（端口9000）集成到现有 Spring Boot DDD 后端（端口8080）。Java 后端作为安全代理层，提供 Clerk 认证保护和全局限流，确保 Python 服务不直接暴露到公网。前端通过 Java API 间接调用 Python 服务的 AI 检测（SSE 流式）和文本人性化改写功能。

## 术语表

- **Humanizer_Service**: Python Flask 服务，运行在独立 Docker 容器中（localhost:9000），提供 AI 检测和文本人性化改写功能
- **Java_Proxy**: Spring Boot 后端中新增的代理层，负责认证、限流并将请求转发到 Humanizer_Service
- **AI_Detect_Stream**: SSE（Server-Sent Events）流式 AI 检测接口，逐句分析文本并实时返回每句的 AI 概率
- **Humanizer_Process**: 文本人性化改写接口，通过多步翻译链（EN→JA→ZH→EN）将 AI 生成文本改写为更自然的人类风格
- **Rate_Limiter**: 全局限流组件，基于滑动窗口算法限制接口调用频率
- **SSE_Proxy**: Java 端的 SSE 透传机制，消费 Python 端的 SSE 流并重新发射给前端客户端
- **HumanizerServiceClient**: agent-service 领域层定义的接口，声明与 Humanizer_Service 交互的方法契约

## 需求

### 需求 1：AI 检测 SSE 流式代理

**用户故事：** 作为前端开发者，我希望通过 Java API 调用 AI 检测的 SSE 流式接口，以便在不暴露 Python 服务的前提下实时获取逐句检测结果。

#### 验收标准

1. WHEN 前端发送包含 text 字段的 POST 请求到 `/v1/humanizer/detect-stream`，THE Java_Proxy SHALL 将请求转发到 Humanizer_Service 的 `/predict_stream` 端点并以 SSE 格式返回流式响应
2. WHEN Humanizer_Service 发送 `event: chunk` 事件，THE SSE_Proxy SHALL 将该事件透传给前端，包含 index、total、sentence、fullSentence、probability、label 和 weight 字段
3. WHEN Humanizer_Service 发送 `event: done` 事件，THE SSE_Proxy SHALL 将该事件透传给前端，包含 probability、label、totalChunks 和 elapsed_seconds 字段
4. WHEN Humanizer_Service 发送 `event: error` 事件，THE SSE_Proxy SHALL 将该事件透传给前端，包含 msg 字段
5. IF 请求中 text 字段为空或缺失，THEN THE Java_Proxy SHALL 返回 400 错误码并拒绝转发请求
6. IF Humanizer_Service 不可达或返回非 200 状态码，THEN THE Java_Proxy SHALL 向前端发送 `event: error` 事件并包含描述性错误信息

### 需求 2：文本人性化改写代理

**用户故事：** 作为前端开发者，我希望通过 Java API 调用文本人性化改写接口，以便安全地将 AI 生成文本改写为更自然的风格。

#### 验收标准

1. WHEN 前端发送包含 text 字段的 POST 请求到 `/v1/humanizer/process`，THE Java_Proxy SHALL 将请求转发到 Humanizer_Service 的 `/process` 端点并返回改写结果
2. WHEN Humanizer_Service 返回成功响应（code=200），THE Java_Proxy SHALL 将 data.result 字段包装在标准 Result 响应格式中返回给前端
3. IF 请求中 text 字段为空或缺失，THEN THE Java_Proxy SHALL 返回 400 错误码并拒绝转发请求
4. IF Humanizer_Service 返回错误响应（code≠200），THEN THE Java_Proxy SHALL 返回对应的错误信息给前端
5. WHILE Java_Proxy 等待 Humanizer_Service 响应，THE Java_Proxy SHALL 使用不少于 5 分钟的超时时间以适应多步翻译链的长耗时

### 需求 3：Clerk 认证保护

**用户故事：** 作为系统管理员，我希望新增的 Humanizer 接口受到 Clerk 认证保护，以便只有已登录用户才能使用这些功能。

#### 验收标准

1. WHEN 未携带有效 Clerk Token 的请求访问 `/v1/humanizer/**` 路径，THE Java_Proxy SHALL 返回 401 未授权错误
2. WHEN 携带有效 Clerk Token 的请求访问 `/v1/humanizer/**` 路径，THE Java_Proxy SHALL 正常处理请求并通过 `@RequestAttribute("clerkUserId")` 提供用户标识
3. THE Java_Proxy SHALL 复用现有 AuthInterceptor 的认证逻辑，无需为 Humanizer 接口编写额外的认证代码

### 需求 4：全局限流

**用户故事：** 作为系统管理员，我希望对 Humanizer 接口实施全局限流，以便保护后端 Python 服务不被过多请求压垮。

#### 验收标准

1. THE Rate_Limiter SHALL 对 `/v1/humanizer/process` 端点实施每分钟最多 5 次的全局请求限制
2. THE Rate_Limiter SHALL 对 `/v1/humanizer/detect-stream` 端点实施每分钟最多 10 次的全局请求限制
3. WHEN 请求超过限流阈值，THE Rate_Limiter SHALL 返回 429 状态码并包含描述性错误信息
4. THE Rate_Limiter SHALL 基于滑动窗口算法计算请求频率，确保限流精度

### 需求 5：DDD 架构集成

**用户故事：** 作为后端开发者，我希望新功能遵循现有 DDD 分层架构，以便代码结构一致且易于维护。

#### 验收标准

1. THE Java_Proxy SHALL 在 agent-service 模块的领域层定义 HumanizerServiceClient 接口，声明 `detectAIStream(text)` 和 `humanize(text)` 方法
2. THE Java_Proxy SHALL 在 agent-infra 模块实现 HumanizerServiceClient 接口，使用 WebClient 调用 Humanizer_Service
3. THE Java_Proxy SHALL 在 agent-api 模块新增 HumanizerController，暴露 REST 端点
4. THE Java_Proxy SHALL 在 application.yml 中新增 `humanizer-service.url` 配置项，默认值为 `http://localhost:9000`

### 需求 6：超时与连接管理

**用户故事：** 作为后端开发者，我希望针对 Humanizer 服务配置独立的超时策略，以便长耗时的改写请求不会被过早中断。

#### 验收标准

1. THE Java_Proxy SHALL 为 Humanizer_Service 创建独立的 WebClient 实例，与现有 Python 后端的 WebClient 配置隔离
2. WHEN 调用 `/process` 端点时，THE Java_Proxy SHALL 配置不少于 5 分钟的读取超时和响应超时
3. WHEN 调用 `/predict_stream` 端点时，THE Java_Proxy SHALL 配置适当的 SSE 流式连接超时，确保长时间流式传输不被中断
