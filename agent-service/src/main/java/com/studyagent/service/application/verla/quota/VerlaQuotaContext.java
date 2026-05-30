package com.studyagent.service.application.verla.quota;

import lombok.Builder;

/**
 * V2 verla 链路扣费上下文（不可变）。
 * <p>
 * 由 {@code VerlaTurnOrchestrator} 在派发 RabbitMQ 命令前组装，传入
 * {@link VerlaQuotaService} 做余额校验、扣费、回写 {@code verla_sessions.quota_ledger_id}。
 *
 * @param clerkUserId  会话所属 Clerk 用户 ID
 * @param conversationId conversation id
 * @param turnId       turn id
 * @param sessionId    agent session id（{@code verla_sessions.id}）；refund 索引列
 * @param intent       上层 intent：{@code ASSIGNMENT / AI_DETECTION / AI_HUMANIZER}
 * @param userMessageId 本轮用户消息 ID（用于审计写入 biz_context；可空）
 */
@Builder
public record VerlaQuotaContext(
        String clerkUserId,
        Long conversationId,
        Long turnId,
        Long sessionId,
        String intent,
        Long userMessageId
) {}
