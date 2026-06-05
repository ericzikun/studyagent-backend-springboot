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
     * Assignment finalize（{@code CMD_ASSIGNMENT_CLARIFY} 派发前）：校验并扣减 1 个 {@code task_create}。
     * <p>
     * <b>调用约束</b>：必须在 {@code VerlaTurnOrchestrator.spawnAssignmentClarifySession}
     * 的外层事务内调用（{@code @Transactional(MANDATORY)}），与 outbox 写入原子。
     * 同一 turn 已扣费时仅将既有 ledger 绑定到新 session，不重复消费。
     *
     * @throws com.studyagent.common.exception.InsufficientQuotaException 余额不足，
     *         由 {@code GlobalExceptionHandler} 自动映射成 1.0 协议的 {@code INSUFFICIENT_QUOTA} 响应
     */
    VerlaQuotaConsumeResult consumeForAssignmentRun(VerlaQuotaContext ctx);

    /**
     * Run 派发前：将同 turn 内 finalize 阶段已扣费的 ledger 绑定到 run session，不重复扣费。
     */
    void inheritAssignmentQuotaLedger(Long targetSessionId, Long turnId);

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

    /**
     * Assignment Clarify 流程入口前只读校验：余额不足时抛 {@link com.studyagent.common.exception.InsufficientQuotaException}，
     * 不扣费。真正扣费在 {@link #consumeForAssignmentRun}（{@code assignment/clarify/finalize} 派发前）。
     */
    void assertSufficientForAssignmentRun(String clerkUserId);
}
