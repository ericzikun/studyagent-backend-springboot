package com.studyagent.infra.mq.aitutor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.infra.mq.VerlaRabbitConfig;
import com.studyagent.infra.mq.RabbitMQConfig;
import com.studyagent.service.domain.demo.aitutor.AiTutorConversation;
import com.studyagent.service.domain.demo.aitutor.AiTutorDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 向 verla_agent 派发 cmd.aitutor.chat 命令（envelope 与现有 Verla 命令协议一致）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DemoAiTutorCommandDispatcher {

    public static final String AITUTOR_CHAT_ROUTING_KEY = "cmd.aitutor.chat";

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    public void dispatch(String clerkUserId, AiTutorConversation conv, String message,
                         AiTutorDocument document) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("commandId", UUID.randomUUID().toString());
        envelope.put("type", "cmd.aitutor.chat");
        envelope.put("conversationId", conv.getId());
        envelope.put("clerkUserId", clerkUserId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", message);
        payload.put("paperTitle", conv.getTitle() == null ? "" : conv.getTitle());
        payload.put("documentContentMd", document == null ? "" : document.getContentMd());
        envelope.put("payload", payload);
        try {
            String body = objectMapper.writeValueAsString(envelope);
            rabbitTemplate.convertAndSend(RabbitMQConfig.COMMAND_EXCHANGE, AITUTOR_CHAT_ROUTING_KEY, body);
            log.info("[AI-Tutor] dispatched cmd.aitutor.chat convId={}", conv.getId());
        } catch (Exception ex) {
            log.error("[AI-Tutor] dispatch failed: {}", ex.getMessage());
        }
    }
}
