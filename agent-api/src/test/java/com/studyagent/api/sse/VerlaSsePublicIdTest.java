package com.studyagent.api.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.api.config.VerlaSseProperties;
import com.studyagent.api.dto.verla.response.VerlaMessageVO;
import com.studyagent.common.verla.id.VerlaPublicIdMapper;
import com.studyagent.service.application.verla.sse.VerlaSseEventPayload;
import com.studyagent.service.domain.verla.VerlaEventInbox;
import com.studyagent.service.domain.verla.VerlaMessage;
import com.studyagent.service.domain.verla.repo.VerlaEventInboxRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VerlaSsePublicIdTest {
    @Test
    void liveAndReplayUseRestIdentityWithoutChangingCursorOrInternalPayload() throws Exception {
        var repository = mock(VerlaEventInboxRepository.class);
        when(repository.findReplayByConversation(42L, 99L, 200)).thenReturn(List.of(
                VerlaEventInbox.builder().id(100L).conversationId(42L).turnId(7L)
                        .sessionId(12345L).eventType("ASSIGNMENT_INIT_COMPLETED")
                        .payloadJson("{\"payload\":{\"content\":\"Previous analysis\"}}")
                        .build()));
        var mapper = new ObjectMapper();
        var gateway = new VerlaSseGateway(repository, mapper, new SimpleMeterRegistry(), new VerlaSseProperties());
        var emitter = gateway.register(42L, 99L, "test-user");
        var live = VerlaSseEventPayload.builder().id(101L).conversationId(42L).turnId(7L)
                .sessionId(12345L).type("ASSIGNMENT_INIT_COMPLETED")
                .payload(Map.of("content", "Previous analysis")).build();
        gateway.publish(42L, live);
        // Duplicate delivery must still be rejected by the unchanged numeric cursor.
        gateway.publish(42L, live);
        var frames = frames(emitter);
        assertEquals(List.of(100L, 101L), frames.stream().map(VerlaSseEventVO::id).toList());
        var message = VerlaMessageVO.from(VerlaMessage.builder()
                .id(9L).turnId(7L).sourceSessionId(12345L).build());
        for (var frame : frames) {
            var json = mapper.readTree(mapper.writeValueAsString(frame));
            assertEquals(VerlaPublicIdMapper.conversation(42L), json.get("conversationId").asText());
            assertEquals(message.getTurnId(), json.get("turnId").asText());
            assertEquals(message.getSourceSessionId(), json.get("sessionId").asText());
            assertTrue(json.get("id").isIntegralNumber());
            assertEquals("Previous analysis", json.get("payload").get("content").asText());
        }
        assertEquals(12345L, live.getSessionId());
        gateway.closeAll();
    }

    @Test
    void missingSessionIsNotInvented() throws Exception {
        var json = new ObjectMapper().valueToTree(VerlaSseEventVO.from(
                VerlaSseEventPayload.builder().id(100L).conversationId(42L).build()));
        assertFalse(json.has("sessionId"));
        assertFalse(json.has("turnId"));
        assertEquals(100L, json.get("id").asLong());
        assertNull(VerlaMessageVO.from(VerlaMessage.builder().id(9L).role("user").build()).getSourceSessionId());
    }

    @SuppressWarnings("unchecked")
    private List<VerlaSseEventVO> frames(SseEmitter emitter) {
        var attempts = (Set<ResponseBodyEmitter.DataWithMediaType>) ReflectionTestUtils.getField(emitter, "earlySendAttempts");
        assertNotNull(attempts);
        return attempts.stream().map(ResponseBodyEmitter.DataWithMediaType::getData)
                .filter(VerlaSseEventVO.class::isInstance).map(VerlaSseEventVO.class::cast).toList();
    }
}
