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
     * 标记消息已成功发送
     */
    void markAsSent(Long id);

    /**
     * 更新重试状态并设置下次重试时间
     * 
     * @param id           消息ID
     * @param errorMessage 错误信息
     * @param nextRetryAt  下次重试时间
     */
    void markForRetry(Long id, String errorMessage, LocalDateTime nextRetryAt);

    /**
     * 标记消息彻底发送失败（重试次数耗尽）
     * 
     * @param id           消息ID
     * @param errorMessage 错误信息
     */
    void markAsFailed(Long id, String errorMessage);
}
