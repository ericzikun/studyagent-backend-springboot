package com.studyagent.api.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.api.config.VerlaSseProperties;
import com.studyagent.service.application.verla.sse.VerlaSseEventPayload;
import com.studyagent.service.domain.verla.VerlaEventInbox;
import com.studyagent.service.domain.verla.repo.VerlaEventInboxRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Forces the replay/live registration race at deterministic latch boundaries.
 * The test observes only emitted event ids and does not start Spring or a socket.
 */
class VerlaSseGatewayConcurrencyTest {

    private static final long CONVERSATION_ID = 42L;

    @Test
    void buffersLivePublishUntilReplayFinishesInEventIdOrder() throws Exception {
        VerlaEventInboxRepository inboxRepository = mock(VerlaEventInboxRepository.class);
        CountDownLatch replayQueryEntered = new CountDownLatch(1);
        CountDownLatch allowReplayToContinue = new CountDownLatch(1);
        VerlaEventInbox replayRow = VerlaEventInbox.builder()
                .id(100L)
                .conversationId(CONVERSATION_ID)
                .eventType("AGENT_PROGRESS")
                .status(VerlaEventInbox.STATUS_PROCESSED)
                .build();

        when(inboxRepository.findReplayByConversation(eq(CONVERSATION_ID), eq(99L), eq(200)))
                .thenAnswer(invocation -> {
                    replayQueryEntered.countDown();
                    if (!allowReplayToContinue.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to release replay");
                    }
                    return List.of(replayRow);
                });

        VerlaSseGateway gateway = new VerlaSseGateway(
                inboxRepository,
                new ObjectMapper(),
                new SimpleMeterRegistry(),
                new VerlaSseProperties());

        SseEmitter emitter;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<SseEmitter> registration = executor.submit(
                    () -> gateway.register(CONVERSATION_ID, 99L, "user_1"));
            assertTrue(replayQueryEntered.await(5, TimeUnit.SECONDS));

            gateway.publish(CONVERSATION_ID, VerlaSseEventPayload.builder()
                    .id(101L)
                    .type("ASSIGNMENT_AGENT_FLOW_COMPLETED")
                    .conversationId(CONVERSATION_ID)
                    .build());
            allowReplayToContinue.countDown();
            emitter = registration.get(5, TimeUnit.SECONDS);
        } finally {
            allowReplayToContinue.countDown();
            executor.shutdownNow();
        }

        @SuppressWarnings("unchecked")
        Set<ResponseBodyEmitter.DataWithMediaType> earlySendAttempts =
                (Set<ResponseBodyEmitter.DataWithMediaType>) ReflectionTestUtils.getField(
                        emitter, "earlySendAttempts");
        assertNotNull(earlySendAttempts);
        List<Long> sentEventIds = earlySendAttempts.stream()
                .map(ResponseBodyEmitter.DataWithMediaType::getData)
                .filter(VerlaSseEventPayload.class::isInstance)
                .map(VerlaSseEventPayload.class::cast)
                .map(VerlaSseEventPayload::getId)
                .toList();
        assertEquals(List.of(100L, 101L), sentEventIds);
    }
}
