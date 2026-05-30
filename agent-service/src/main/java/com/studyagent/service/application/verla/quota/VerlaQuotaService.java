package com.studyagent.service.application.verla.quota;

/**
 * V2 verla 链路 商业化额度门面。
 * <p>
 * 复用 1.0 的 {@code QuotaDomainService}（feature_code = {@code task_create / ai_detection / humanizer}），
 * 在 {@code VerlaTurnOrchestrator} 派发 RabbitMQ 命令前 / agent 终态回调时统一接入。
 * <p>
 * 详细方案见 {@code docs/V2/V2-商业化额度接入技术方案.md}。
 */
public interface VerlaQuotaService {

    /**
     * Assignment Run 派发前：校验并扣减 1 个 {@code task_create}。
     * <p>
     * <b>调用约束</b>：必须在 {@code VerlaTurnOrchestrator.spawnAssignmentRunSession}
     * 的外层事务内调用（{@code @Transactional(MANDATORY)}），与 outbox 写入原子。
     *
     * @throws com.studyagent.common.exception.InsufficientQuotaException 余额不足，
     *         由 {@code GlobalExceptionHandler} 自动映射成 1.0 协议的 {@code INSUFFICIENT_QUOTA} 响应
     */
    VerlaQuotaConsumeResult consumeForAssignmentRun(VerlaQuotaContext ctx);

    /**
     * AI Detection 派发前：按总 words 一次性预扣 {@code ai_detection}。
     * <p>
     * 失败时由 {@link #refundBySessionId(Long, String)} 退款全额。
     */
    VerlaQuotaConsumeResult consumeForDetection(VerlaQuotaContext ctx, String text);

    /**
     * Humanizer 派发前：按总 words 一次性预扣 {@code humanizer}。
     */
    VerlaQuotaConsumeResult consumeForHumanizer(VerlaQuotaContext ctx, String text);

    /**
     * Session 进入终态失败 / 取消时退款。
     * <p>
     * 通过 {@code verla_sessions.quota_ledger_id} 反查；幂等：已经退过、未扣过、不存在均静默跳过。
     *
     * @param sessionId verla agent session id
     * @param reason    退款原因（写入 {@code quota_ledger.biz_context.refund_reason}）
     */
    void refundBySessionId(Long sessionId, String reason);

    /**
     * 是否豁免扣费（admin / 白名单 / 总开关关闭）。
     * <p>
     * 调用方可在派发前预先判断，方便日志/调试；门面方法本身也会内部判断。
     */
    boolean isQuotaExempt(String clerkUserId);
}
