package com.studyagent.service.domain.demo.aitutor.port;

/**
 * AI Tutor SSE 流发布端口：verla_agent(AITUTOR_*) 事件 -> 本项目内的 SseEmitter 实现。
 */
public interface DemoAiTutorStreamPublisher {
    void register(Long conversationId, Object emitter);

    void publish(Long conversationId, String eventName, String dataJson);

    void onChunk(Long conversationId, String content);

    void onArtifactCommit(Long conversationId, String contentMd);

    void complete(Long conversationId);

    /** 会话是否已收到任何 python 事件（用于兜底判定） */
    boolean hasActivity(Long conversationId);

    /** 标记该会话回退 mock：忽略后续 python 事件并移除在途 emitter */
    void markFallback(Long conversationId);
}
