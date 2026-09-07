package com.studyagent.api.sse.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.service.application.demo.DemoAiTutorService;
import com.studyagent.service.domain.demo.aitutor.port.DemoAiTutorStreamPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI Tutor SSE 桥接实现：verla_agent 事件 -> 在途 SseEmitter。
 * 每个会话一个 emitter + 文本缓冲（chunk 累积为 assistant 消息，回合结束落库）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DemoAiTutorStreamPublisherImpl implements DemoAiTutorStreamPublisher {

    private final DemoAiTutorService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private record Slot(SseEmitter emitter, StringBuilder buffer) {
    }

    private final Map<Long, Slot> slots = new ConcurrentHashMap<>();

    @Override
    public void register(Long conversationId, Object emitter) {
        SseEmitter em = (SseEmitter) emitter;
        slots.put(conversationId, new Slot(em, new StringBuilder()));
        em.onCompletion(() -> slots.remove(conversationId));
        em.onTimeout(() -> slots.remove(conversationId));
        em.onError(e -> slots.remove(conversationId));
    }

    @Override
    public void publish(Long conversationId, String eventName, String dataJson) {
        Slot slot = slots.get(conversationId);
        if (slot == null) {
            return;
        }
        try {
            slot.emitter().send(SseEmitter.event().name(eventName).data(dataJson));
        } catch (IOException | IllegalStateException ex) {
            slots.remove(conversationId);
        }
    }

    @Override
    public void onChunk(Long conversationId, String content) {
        Slot slot = slots.get(conversationId);
        if (slot == null) {
            return;
        }
        slot.buffer().append(content);
        try {
            String json = objectMapper.writeValueAsString(Map.of("type", "chunk", "content", content));
            slot.emitter().send(SseEmitter.event().name("message").data(json));
        } catch (IOException | IllegalStateException ex) {
            slots.remove(conversationId);
        }
    }

    @Override
    public void onArtifactCommit(Long conversationId, String contentMd) {
        Slot slot = slots.get(conversationId);
        if (slot == null) {
            return;
        }
        try {
            // Java 侧持久化 ai 版本并取真实版本号
            var doc = service.saveAiUpdate(conversationId, contentMd);
            String begin = objectMapper.writeValueAsString(Map.of("type", "begin", "versionNo", doc.getBaseVersion()));
            String commit = objectMapper.writeValueAsString(Map.of("type", "commit", "versionNo", doc.getBaseVersion()));
            slot.emitter().send(SseEmitter.event().name("artifact").data(begin));
            slot.emitter().send(SseEmitter.event().name("artifact").data(commit));
        } catch (IOException | IllegalStateException ex) {
            slots.remove(conversationId);
        }
    }

    @Override
    public void complete(Long conversationId) {
        Slot slot = slots.remove(conversationId);
        if (slot == null) {
            return;
        }
        try {
            String buf = slot.buffer().toString();
            if (!buf.isBlank()) {
                service.appendMessage(conversationId, "assistant", "text", buf);
            }
            slot.emitter().send(SseEmitter.event().name("auto_saved")
                    .data(objectMapper.writeValueAsString(Map.of("savedAt", java.time.LocalDateTime.now().toString()))));
            slot.emitter().send(SseEmitter.event().data("[DONE]"));
        } catch (IOException | IllegalStateException ignored) {
        } finally {
            try {
                slot.emitter().complete();
            } catch (Exception ignored) {
            }
        }
    }
}
