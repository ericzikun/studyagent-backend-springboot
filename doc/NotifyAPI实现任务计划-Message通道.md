# Notify API（Message 通道）Task Plan

> 关联方案文档：`doc/NotifyAPI实现方案-Message通道.md`
>
> 用途：用于跟踪 Notify API 的实际实现进度，开发完成并通过验收后勾选对应任务。
>
> 范围：本计划仅覆盖 `message` 通道，不包含 `alert` 通道。

## 0. 里程碑（Milestone）

- [x] M1：完成接口契约与配置骨架（可启动、可接收请求）
- [x] M2：完成钉钉发送主链路（local 环境可真实发消息）
- [x] M3：完成鉴权/幂等/限流（具备基础治理能力）
- [ ] M4：完成三环境接入（local/test/online）与回归验收

## 0.1 策略变更记录（2026-04-03）

- metadata 能力从“值类型受限 + 展示白名单”调整为“支持复杂值类型 + 暂不启用白名单”
- 变更目标：满足业务方大字段/复杂结构透传需求，同时保留 `sanitizeMetadata` 作为统一治理入口
- 本文后续新增的未勾选任务为本次策略调整待实现项

## 1. 实现前准备

- [x] 1.1 确认最终接口路径：`POST /api/v1/notify/events`
- [x] 1.2 确认 `Notify API` 采用 `X-Notify-Token`，不复用 `Authorization Bearer`
- [x] 1.3 确认返回结构与现有 `Result<T>` 风格兼容（`meta.statusCode/statusMsg/traceId + data`）
- [x] 1.4 确认本期只实现 `message` 通道，文档中所有 `alert` 相关内容不进入开发范围
- [x] 1.5 在 `doc/` 中固定本文档为执行清单，后续实现按本文逐项勾选

## 2. 代码结构落位（目录与文件骨架）

- [x] 2.1 新建 API 层请求 DTO：`agent-api/src/main/java/com/studyagent/api/dto/request/NotifyEventRequest.java`
- [x] 2.2 新建 API 层响应 DTO：`agent-api/src/main/java/com/studyagent/api/dto/response/NotifyEventResponse.java`
- [x] 2.3 新建 API 层错误 DTO：`agent-api/src/main/java/com/studyagent/api/dto/response/NotifyErrorResponse.java`
- [x] 2.4 新建 API 控制器：`agent-api/src/main/java/com/studyagent/api/controller/NotifyController.java`
- [x] 2.5 新建应用服务：`agent-service/src/main/java/com/studyagent/service/application/NotifyApplicationService.java`
- [x] 2.6 新建应用层请求模型：`agent-service/src/main/java/com/studyagent/service/application/request/NotifyDispatchRequest.java`
- [x] 2.7 新建应用层结果模型：`agent-service/src/main/java/com/studyagent/service/application/dto/NotifyDispatchResult.java`
- [x] 2.8 新建领域发送接口：`agent-service/src/main/java/com/studyagent/service/domain/notify/NotifySender.java`
- [x] 2.9 新建领域消息模型：`agent-service/src/main/java/com/studyagent/service/domain/notify/NotifyMessage.java`
- [x] 2.10 新建 Infra 发送实现：`agent-infra/src/main/java/com/studyagent/infra/service/notify/DingTalkNotifySender.java`
- [x] 2.11 新建 Infra 配置加载器：`agent-infra/src/main/java/com/studyagent/infra/service/notify/DingTalkWebhookConfigLoader.java`
- [x] 2.12 新建 Notify 配置类：`agent-service/src/main/java/com/studyagent/service/config/NotifyConfig.java`

## 3. 接口契约与枚举实现

- [x] 3.1 在请求 DTO 中实现必填字段校验：`sourceService/title/content`
- [x] 3.2 在请求 DTO 中实现可选字段：`eventId/scene/level/contentType/env/timestamp/metadata`
- [x] 3.3 实现 `sourceService` 枚举值校验：`springboot_backend|python_backend|frontend|humanizer`
- [x] 3.4 实现 `level` 枚举值校验：`info|warn|error|critical`
- [x] 3.5 实现 `contentType` 枚举值校验：`text|markdown`
- [x] 3.6 实现 `env` 枚举值校验：`local|test|online`
- [x] 3.7（旧策略）为 `metadata` 限定值类型（string/number/boolean）并处理非法结构
- [x] 3.8 在响应 DTO 中定义 `status` 枚举：`sent|deduplicated|rejected|failed`
- [x] 3.9 在响应 DTO 中定义 `error.type` 枚举：`VALIDATION_ERROR|AUTH_ERROR|RATE_LIMIT|DUPLICATE_EVENT|DOWNSTREAM_ERROR`
- [x] 3.10 打通错误码映射：`4000/4001/4002/4003/4004/5000`
- [x] 3.11（新策略）放开 `metadata` value 类型，支持复杂 JSON（object/array/string/number/boolean/null）
- [x] 3.12（新策略）更新参数校验逻辑：仅拒绝不可序列化/非法结构，不再按基础类型硬限制

## 4. Web 层接入

- [x] 4.1 在 `NotifyController` 增加 `POST /api/v1/notify/events`
- [x] 4.2 从请求头读取 `X-Notify-Token`
- [x] 4.3 将 API DTO 转换为应用层 `NotifyDispatchRequest`
- [x] 4.4 返回统一 `Result<NotifyEventResponse>`，成功与失败都包含 `traceId`
- [x] 4.5 在 `agent-api/src/main/java/com/studyagent/api/config/WebConfig.java` 放行 `/api/v1/notify/events`（避免被 Clerk 鉴权拦截）
- [ ] 4.6 在 `GlobalExceptionHandler` 中补充 Notify 相关异常映射（参数错误/鉴权失败/下游失败）

## 5. 鉴权实现（X-Notify-Token）

- [x] 5.1 在 `NotifyConfig` 增加 `notify.api-token` 配置读取
- [x] 5.2 在 `NotifyApplicationService` 实现 token 校验逻辑
- [x] 5.3 校验失败返回 `4001`，`status=rejected`，`error.type=AUTH_ERROR`
- [x] 5.4 日志中对 token 脱敏（禁止打印原文）

## 6. 幂等实现（可选但建议默认开启）

- [x] 6.1 定义幂等键：`eventId + sourceService`
- [x] 6.2 约束：仅当请求带 `eventId` 时参与去重
- [x] 6.3 实现 JVM 内存版幂等存储（TTL 可配置）
- [x] 6.4 在请求处理前执行幂等判定
- [x] 6.5 命中去重时返回 `4003`，`status=deduplicated`，不调用钉钉
- [x] 6.6 增加过期清理逻辑，避免内存增长
- [x] 6.7 增加日志字段：`eventId/sourceService/deduplicated=true`

## 7. 限流实现（按服务维度）

- [x] 7.1 在 `NotifyConfig` 增加 `notify.rate-limit.enabled`
- [x] 7.2 在 `NotifyConfig` 增加 `notify.rate-limit.per-service-per-minute`
- [x] 7.3 实现按 `sourceService` 的分钟级计数器限流器
- [x] 7.4 超限时返回 `4002`，`status=rejected`，`error.type=RATE_LIMIT`
- [x] 7.5 在日志中记录限流触发（含 sourceService、阈值、当前计数）

## 8. 钉钉发送实现（message 通道）

- [x] 8.1 在 `DingTalkNotifySender` 实现钉钉机器人 HTTP 调用
- [x] 8.2 实现 `contentType=text` 的消息体构建
- [x] 8.3 实现 `contentType=markdown` 的消息体构建
- [x] 8.4 实现统一消息模板：标题 + 服务 + 时间 + 内容 + metadata 摘要
- [x] 8.5 对 `critical/error` 在标题前增加显式级别标识
- [x] 8.6 对 `content` 增加长度截断（如 1000）
- [x] 8.7（旧策略）对 `metadata` 实现展示白名单与敏感键脱敏
- [x] 8.8 解析钉钉返回值，成功置 `status=sent`
- [x] 8.9 钉钉失败时返回 `5000`，`status=failed`，`error.type=DOWNSTREAM_ERROR`
- [x] 8.10 记录 `deliveryId`（可用时间戳+哈希生成）
- [x] 8.11（新策略）保留 `sanitizeMetadata`，当前阶段不按 key 白名单过滤
- [x] 8.12（新策略）在 `sanitizeMetadata` 增加注释：后续可按治理需求引入白名单过滤

## 9. 三环境配置接入（复用监控看板 secret 文件）

- [x] 9.1 在 `agent-start/src/main/resources/application.yml` 增加 `notify.*` 配置
- [x] 9.2 实现仅 `notify.dingtalk.config-file` 加载逻辑
- [x] 9.3 当 `notify.enabled=true` 且 config-file 不可用时，启动失败（fail-fast）
- [x] 9.4 兼容读取监控同款文件结构：`targets.default.url/secret`
- [x] 9.5 本地直启接入：支持读取 `$HOME/.studyagent-monitoring-secrets/local/dingtalk-webhook-config.yml`
- [x] 9.6 Docker test 接入：支持容器路径 `/etc/studyagent-monitoring-secrets/test/dingtalk-webhook-config.yml`
- [x] 9.7 Docker online 接入：支持容器路径 `/etc/studyagent-monitoring-secrets/online/dingtalk-webhook-config.yml`
- [x] 9.8 `local` 不通过 Docker 启动，`docker-compose-local.yml` 配置不适用（按团队约定跳过）
- [ ] 9.9 在测试/线上 compose（实际部署文件）补充挂载和环境变量说明（`docker-compose-test1.yml` 已补，online 待补）
- [x] 9.10 在 `studyagent-backend-springboot/.env` 增加本地示例变量（仅路径，不提交密钥）

## 10. 日志与可观测

- [x] 10.1 每次请求记录：`traceId,eventId,sourceService,scene,level,status`
- [x] 10.2 失败日志记录：`error.type,error.detail,retryable`
- [x] 10.3 内容日志脱敏：不打印完整 `content` 与敏感 metadata
- [x] 10.4 增加接口耗时日志（入口到投递完成）
- [x] 10.5 增加关键告警日志（鉴权失败、限流、下游失败）

## 11. 测试任务（必须可勾选验收）

### 11.1 测试基础设施

- [x] 11.1.1 为相关模块补充测试依赖（`spring-boot-starter-test`）
- [x] 11.1.2 新建 API 层测试目录：`agent-api/src/test/java/...`
- [x] 11.1.3 新建 Service 层测试目录：`agent-service/src/test/java/...`
- [x] 11.1.4 新建 Infra 层测试目录：`agent-infra/src/test/java/...`

### 11.2 单元测试

- [x] 11.2.1 DTO 校验测试：缺失必填/枚举非法触发正确错误码
- [x] 11.2.2 鉴权测试：token 正确/错误/缺失
- [x] 11.2.3 幂等测试：有 eventId 的重复请求命中去重；无 eventId 不去重
- [x] 11.2.4 限流测试：按 `sourceService` 维度触发阈值
- [x] 11.2.5 钉钉消息构建测试：text/markdown、level 标识、截断逻辑
- [x] 11.2.6 配置读取测试：config-file 正常加载与缺失失败
- [x] 11.2.7（新策略）metadata 复杂类型测试：object/array/nested 结构可通过并稳定展示
- [x] 11.2.8（新策略）metadata 无白名单过滤测试：自定义 key 不被静默丢弃（仍保留敏感键脱敏）

### 11.3 集成测试（本地联调）

- [x] 11.3.1 正常请求可发送到钉钉（local 机器人）
- [ ] 11.3.2 缺字段返回 `4000`
- [ ] 11.3.3 非法枚举返回 `4004`
- [ ] 11.3.4 token 错误返回 `4001`
- [ ] 11.3.5 重复 eventId 返回 `4003`
- [ ] 11.3.6 高频请求返回 `4002`
- [ ] 11.3.7 模拟钉钉失败返回 `5000`

## 12. 文档与交付

- [x] 12.1 更新 `doc/NotifyAPI实现方案-Message通道.md` 中“已实现状态”与实际一致
- [x] 12.2 新增“服务负责人接入示例（curl + Java/Python）”文档段落
- [x] 12.3 新增“三环境配置说明（local 直启 / test-online Docker）”文档段落
- [x] 12.4 增加常见问题：token 错误、配置路径错误、钉钉签名错误
- [x] 12.5 增加回滚方案说明：`notify.enabled=false` 一键关闭

## 13. 最终验收清单（发布前）

- [ ] 13.1 代码评审完成（API/Service/Infra）
- [ ] 13.2 单测与集成测试均通过
- [ ] 13.3 local/test/online 三环境配置确认完成
- [ ] 13.4 生产密钥未入库（仅路径入库）
- [ ] 13.5 发布后首条真实通知验证通过

## 14. 可选后续（本期不做）

- [ ] 14.1 Redis 版幂等与限流（替换 JVM 内存版）
- [ ] 14.2 引入异步投递队列（削峰与重试）
- [ ] 14.3 引入 `alert` 通道并对接 Alertmanager API
- [ ] 14.4 引入告警治理能力（静默、恢复、抑制、升级）
