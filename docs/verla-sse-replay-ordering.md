# Verla SSE 重连回放顺序

`VerlaSseGateway` 对单个连接保证业务事件 ID 单调递增。新连接加入订阅列表后先处于 `replaying`：注册期间产生的 live event 按 inbox ID 暂存，历史回放完成后在同一把连接级锁内按 ID 排序、去重并发送，之后才切换到 live。回放读取或发送失败时关闭连接，让客户端重新连接并用 REST runtime snapshot 恢复，不能跳过缺口继续推送。

浏览器原生 EventSource 重连会保留初始 URL 中的 `lastEventId` query，同时发送它最后收到的 `Last-Event-ID` header。Controller 使用两个有效游标中的较大值，避免从旧 query 游标重复回放。

验证入口：

- `VerlaSseGatewayConcurrencyTest` 用 latch 固定复现 replay 100 与并发 live 101 的竞争，断言发送顺序为 `100, 101`。
- `VerlaSseControllerTest` 覆盖 header/query 游标取较大值及非法 header 降级。

## 对外资源身份

SSE 发送出口通过 `VerlaSseEventVO` 将 `conversationId`、`turnId`、`sessionId` 转为与 REST 相同的 `vc_*`、`vt_*`、`vs_*` public ID。实时发送和历史回放经过同一转换，同一执行的 SSE `sessionId` 必须等于 snapshot 消息的 `sourceSessionId`。无来源的 ID 继续省略；REST 用户消息的 `sourceSessionId` 仍可为 null。

转换只发生在 API 输出边界，内部路由、数据库及 inbox 继续使用 Long。SSE `id`、Last-Event-ID、`resumeAfterEventId` 是顺序游标，不能编码为资源 ID；业务 payload 不做递归字符串替换。依赖数字资源 ID 的外部 SSE 消费者需迁移为 public ID 字符串。

`VerlaSsePublicIdTest` 比较实时/回放序列化结果与 `VerlaMessageVO`，并检查空值、内部 Long 不被修改和重复事件去重。跨工程验收见根 OpenSpec `fix-assignment-waiting-output`。
