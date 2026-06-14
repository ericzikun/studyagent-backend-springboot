package com.studyagent.service.domain.mq;

import java.time.LocalDateTime;
import java.util.List;

/**
 * MQ 事务发件箱仓储接口
 */
public interface MqOutboxRepository {

    /**
     * 保存记录（新增或更新）
     */
    MqOutbox save(MqOutbox mqOutbox);

    /**
     * 根据 ID 查询记录
     */
    MqOutbox findById(Long id);

    /**
     * 根据 eventId 查询记录
     */
    MqOutbox findByEventId(String eventId);

    /**
     * 获取一定数量的待发送消息
     * 过滤条件：状态为 UNSENT (0) 且 (nextRetryAt <= 当前时间 或 nextRetryAt 为 null) 且 retryCount
     * < maxRetries
     * 
     * @param limit       最大获取数量
     * @param currentTime 当前时间参考
     * @return 待发送消息列表
     */
    List<MqOutbox> findPendingMessages(int limit, LocalDateTime currentTime);

    /**
     * 原子 claim 一批待发送消息。多实例同时执行时，同一行只能被一个 worker claim。
     * <p>
     * 可 claim 范围：
     * <ul>
     *   <li>UNSENT 且到达 nextRetryAt</li>
     *   <li>SENDING 且 leaseUntil 已过期</li>
     * </ul>
     */
    List<MqOutbox> claimPendingMessages(
            int limit,
            String workerId,
            LocalDateTime currentTime,
            LocalDateTime leaseUntil);

    /**
     * 原子 claim 指定消息，供事务提交后的即时投递路径使用。
     */
    MqOutbox claimMessage(
            Long id,
            String workerId,
            LocalDateTime currentTime,
            LocalDateTime leaseUntil);

    /**
     * 标记消息已成功发送
     */
    void markAsSent(Long id);

    /**
     * 仅当前 claim worker 可以标记发送成功。
     */
    void markAsSent(Long id, String workerId);

    /**
     * 更新重试状态并设置下次重试时间
     * 
     * @param id           消息ID
     * @param errorMessage 错误信息
     * @param nextRetryAt  下次重试时间
     */
    void markForRetry(Long id, String errorMessage, LocalDateTime nextRetryAt);

    /**
     * 仅当前 claim worker 可以释放为待重试。
     */
    void markForRetry(Long id, String workerId, String errorMessage, LocalDateTime nextRetryAt);

    /**
     * 标记消息彻底发送失败（重试次数耗尽）
     * 
     * @param id           消息ID
     * @param errorMessage 错误信息
     */
    void markAsFailed(Long id, String errorMessage);

    /**
     * 仅当前 claim worker 可以标记最终失败。
     */
    void markAsFailed(Long id, String workerId, String errorMessage);

    /**
     * 释放 claim 并回到 UNSENT，不增加 retry_count（用于派发门控暂缓发送）。
     */
    void releaseClaim(Long id, String workerId);

    /**
     * 统计在 FIFO 队列中排在本条 {@code cmd.assignment.run} / retry 之前的 UNSENT 条数。
     * 用于向前端返回 {@code queuePosition}（前方还有几条等待派发）。
     */
    int countDeferredAssignmentRunAhead(Long id, LocalDateTime createdAt);

    /**
     * 统计在 FIFO 队列中排在本条 capability run 之前的 UNSENT 条数（同 action 类型）。
     */
    int countDeferredCapabilityRunAhead(Long id, String action, LocalDateTime createdAt);
}
