# Verla SSE 重连回放顺序

`VerlaSseGateway` 对单个连接保证业务事件 ID 单调递增。新连接加入订阅列表后先处于 `replaying`：注册期间产生的 live event 按 inbox ID 暂存，历史回放完成后在同一把连接级锁内按 ID 排序、去重并发送，之后才切换到 live。回放读取或发送失败时关闭连接，让客户端重新连接并用 REST runtime snapshot 恢复，不能跳过缺口继续推送。

浏览器原生 EventSource 重连会保留初始 URL 中的 `lastEventId` query，同时发送它最后收到的 `Last-Event-ID` header。Controller 使用两个有效游标中的较大值，避免从旧 query 游标重复回放。

验证入口：

- `VerlaSseGatewayConcurrencyTest` 用 latch 固定复现 replay 100 与并发 live 101 的竞争，断言发送顺序为 `100, 101`。
- `VerlaSseControllerTest` 覆盖 header/query 游标取较大值及非法 header 降级。
