package com.studyagent.service.application.verla.sse;

/**
 * Verla SSE 推送出口（agent-api 侧实现）
 * <p>
 * 由 {@link com.studyagent.service.application.verla.VerlaInboxService} 在事务 commit 后调用，
 * 把已 PROCESSED 的事件实时广播到所有 register 在该 conversation 上的 SseEmitter。\
 * <p>
 * 接口放在 agent-service 是为了不让 service 模块依赖 spring-web；
 * 真正的 emitter 注册 / 广播 / replay 实现在 {@code com.studyagent.api.sse.VerlaSseGateway}。\
 */
public interface VerlaSsePublisher {

    /**
     * 立即把一条事件广播给该 conversation 的所有在线连接。
     * 调用方负责保证：仅在事务提交后才调（避免回滚后的脏推送）。\
     */
    void publish(Long conversationId, VerlaSseEventPayload payload);
}
