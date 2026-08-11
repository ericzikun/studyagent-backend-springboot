package com.studyagent.service.domain.quota;

import java.util.List;
import java.util.Map;

/**
 * 额度领域服务
 * 负责额度查询、消费扣减、失败回滚
 */
public interface QuotaDomainService {

    /**
     * 查询用户某功能点的额度（免费+付费），必要时刷新免费周期
     */
    QuotaBalance getUserQuota(String clerkUserId, String featureCode);

    /**
     * 查询用户所有功能点的额度
     *
     * @return 所有启用功能的额度列表，按 display_order 排序
     */
    List<QuotaBalance> getAllUserQuotas(String clerkUserId);

    /**
     * 检查是否有足够额度
     */
    boolean canConsume(String clerkUserId, String featureCode, long amount);

    /**
     * 扣减额度：先扣免费，再扣付费
     *
     * @param clerkUserId 用户ID
     * @param featureCode 功能编码
     * @param amount      扣减数量
     * @param sourceType  来源类型 task/order/manual
     * @param sourceId    关联ID 如 task_id
     * @param bizContext  业务上下文
     * @return 消费结果，含 ledger_id 用于失败时回滚
     */
    default ConsumeResult consume(
            String clerkUserId,
            String featureCode,
            long amount,
            String sourceType,
            String sourceId,
            Map<String, Object> bizContext
    ) {
        return consume(clerkUserId, featureCode, amount, sourceType, sourceId, bizContext, null);
    }

    ConsumeResult consume(
            String clerkUserId,
            String featureCode,
            long amount,
            String sourceType,
            String sourceId,
            Map<String, Object> bizContext,
            String idempotencyKey
    );

    /**
     * 根据原消费流水回滚额度
     *
     * @param ledgerId 消费流水ID
     * @param reason   回滚原因 如 task_failed
     */
    boolean refund(long ledgerId, String reason);

    /**
     * 根据任务ID回滚额度（查找该任务的 consume 流水并回滚）
     *
     * @param taskId 任务ID
     * @param reason 回滚原因
     */
    void refundByTaskId(long taskId, String reason);

    /**
     * 分页查询用户额度流水
     *
     * @param clerkUserId 用户ID
     * @param featureCode 功能编码（可选，为空则查全部）
     * @param page        页码（从 1 开始）
     * @param pageSize    每页条数
     */
    QuotaLedgerPageResult getLedgerPage(String clerkUserId, String featureCode, int page, int pageSize);
}
