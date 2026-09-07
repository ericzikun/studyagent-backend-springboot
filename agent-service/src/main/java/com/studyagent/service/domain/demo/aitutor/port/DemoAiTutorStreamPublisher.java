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
}
