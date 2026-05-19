package com.studyagent.service.application.verla;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;

import java.nio.charset.StandardCharsets;

@Slf4j
@RequiredArgsConstructor
public class VerlaRedisCacheInvalidationSubscriber implements MessageListener {

    private final ObjectMapper objectMapper;
    private final VerlaContextQueryService queryService;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String payload = new String(message.getBody(), StandardCharsets.UTF_8);
            VerlaCacheInvalidationMessage invalidation =
                    objectMapper.readValue(payload, VerlaCacheInvalidationMessage.class);
            queryService.handleRemoteInvalidation(invalidation);
        } catch (Exception ex) {
            log.warn("[Verla/ctx] cache invalidation consume failed: {}", ex.toString());
        }
    }
}
