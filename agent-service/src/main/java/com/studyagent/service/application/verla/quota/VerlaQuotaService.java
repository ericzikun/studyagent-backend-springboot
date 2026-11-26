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
     * 是否豁免扣费（admin / Quota VIP / env 白名单 / 总开关关闭）。
     * <p>
     * 调用方可在派发前预先判断，方便日志/调试；门面方法本身也会内部判断。
     */
    boolean isQuotaExempt(String clerkUserId);

    /**
     * 商业化豁免（admin / Quota VIP / env 白名单）。
     * <p>与 {@link #isQuotaExempt(String)} 的区别：这里不受全局配额开关影响，
     * 用于「笔记是否允许免费会员使用」这类会员准入判断。
     */
    boolean isCommercialExempt(String clerkUserId);

    /**
     * 知识图预占：成功则扣 1 次并绑定 session；余额不足返回
     * {@link NoteInfographicReservation.Status#INSUFFICIENT}，<b>不抛异常</b>，
     * 由调用方决定是跳过图片（首轮）还是提示升级（重试）。
     */
    NoteInfographicReservation reserveForNoteInfographic(VerlaQuotaContext ctx);

    /**
     * 知识图预占；{@code throwIfInsufficient=true} 时余额不足抛
     * {@link com.studyagent.common.exception.InsufficientQuotaException}，供重试端点触发升级弹窗。
     */
    NoteInfographicReservation reserveForNoteInfographic(VerlaQuotaContext ctx, boolean throwIfInsufficient);

    /**
     * Assignment Clarify 流程入口前只读校验：余额不足时抛 {@link com.studyagent.common.exception.InsufficientQuotaException}，
     * 不扣费。真正扣费在 {@link #consumeForAssignmentRun}（{@code assignment/clarify/finalize} 派发前）。
     * 同 turn 已扣费时直接放行，与 {@link #consumeForAssignmentRun} 的 ledger 复用规则一致。
     */
    void assertSufficientForAssignmentRun(String clerkUserId, Long turnId);

    /**
     * Simulation 成功会话的使用流水（仅写入展示用 consume 流水，不扣减任何余额）。
     * 豁免账号（admin / Quota VIP / 白名单）同样记录，保证使用记录完整。
     *
     * @param clerkUserId 会话归属用户
     * @param sessionId   verla agent session id（幂等键来源）
     * @param title       会话标题（可为空）
     * @return 写入的 quota_ledger.id；失败返回 null，不阻断 agent 终态
     */
    Long recordSimulationUsage(String clerkUserId, Long sessionId, String title);
}
