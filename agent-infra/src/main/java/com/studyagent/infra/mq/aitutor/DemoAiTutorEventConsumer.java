package com.studyagent.infra.mq.aitutor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.service.domain.demo.aitutor.port.DemoAiTutorStreamPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 消费 verla_agent 的 AITUTOR_* 事件（verla.event.aitutor.#）并桥接 SSE / 投影 demo 表。
 * 非终态事件；回合结束由 publisher.complete 落库并收尾。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DemoAiTutorEventConsumer {

    private final DemoAiTutorStreamPublisher publisher;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = DemoAiTutorRabbitConfig.AITUTOR_EVENT_QUEUE)
    public void onEvent(String body) {
        try {
            Map<?, ?> envelope = objectMapper.readValue(body, Map.class);
            String eventType = String.valueOf(envelope.get("eventType"));
            Map<?, ?> payload = asMap(envelope.get("payload"));
            Long conversationId = payload.get("conversationId") == null
                    ? null
                    : Long.valueOf(String.valueOf(payload.get("conversationId")));
            if (conversationId == null) {
                return;
            }
            if (!eventType.startsWith("AITUTOR_")) {
                return;
            }
            switch (eventType) {
                case "AITUTOR_CHAT_CHUNK":
                    publisher.onChunk(conversationId, str(payload.get("content")));
                    break;
                case "AITUTOR_ARTIFACT_COMMIT":
                    publisher.onArtifactCommit(conversationId, str(payload.get("contentMd")));
                    break;
                case "AITUTOR_ARTIFACT_BEGIN":
                case "AITUTOR_ARTIFACT_DELTA":
                    publisher.publish(conversationId, "artifact",
                            objectMapper.writeValueAsString(Map.of(
                                    "type", eventType.substring("AITUTOR_ARTIFACT_".length()).toLowerCase(),
                                    "op", str(payload.get("op")),
                                    "heading", payload.get("heading"),
                                    "content", str(payload.get("content")))));
                    break;
                case "AITUTOR_AGENT_START":
                case "AITUTOR_AGENT_END":
                    publisher.publish(conversationId, "message",
                            objectMapper.writeValueAsString(Map.of(
                                    "type", eventType.substring("AITUTOR_AGENT_".length()).toLowerCase(),
                                    "agent", str(payload.get("agent")),
                                    "goal", payload.get("goal"),
                                    "summary", payload.get("summary"))));
                    break;
                case "AITUTOR_TURN_COMPLETED":
                    publisher.complete(conversationId);
                    break;
                case "AITUTOR_LITERATURE_CARD":
                    publisher.publish(conversationId, "message",
                            objectMapper.writeValueAsString(Map.of("type", "literature_card",
                                    "items", payload.get("items") == null ? List.of() : payload.get("items"))));
                    break;
                default:
                    break;
            }
        } catch (Exception ex) {
            log.warn("[AI-Tutor] consume event failed: {}", ex.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
