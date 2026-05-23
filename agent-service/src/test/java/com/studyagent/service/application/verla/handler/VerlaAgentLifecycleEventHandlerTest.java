package com.studyagent.service.application.verla.handler;

import com.studyagent.common.verla.enums.VerlaAgentEventType;
import com.studyagent.common.verla.envelope.VerlaConversationRef;
import com.studyagent.common.verla.envelope.VerlaEventEnvelope;
import com.studyagent.common.verla.envelope.VerlaSessionRef;
import com.studyagent.common.verla.envelope.VerlaTurnRef;
import com.studyagent.common.verla.enums.VerlaSessionKind;
import com.studyagent.service.application.verla.VerlaTurnOrchestrator;
import com.studyagent.service.domain.verla.VerlaEventInbox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class VerlaAgentLifecycleEventHandlerTest {

    @Mock
    private VerlaTurnOrchestrator orchestrator;

    @Test
    void supportedTypes_shouldIncludeFileChatLifecycleEvents() {
        VerlaAgentLifecycleEventHandler handler = new VerlaAgentLifecycleEventHandler(orchestrator);

        assertThat(handler.supportedTypes()).contains(
                VerlaAgentEventType.FILE_CHAT_STARTED,
                VerlaAgentEventType.FILE_CHAT_COMPLETED,
                VerlaAgentEventType.FILE_CHAT_FAILED,
                VerlaAgentEventType.FILE_CHAT_CANCELLED
        );
    }

    @Test
    void handle_fileChatCompleted_shouldDelegateToFileChatCallback() {
        VerlaAgentLifecycleEventHandler handler = new VerlaAgentLifecycleEventHandler(orchestrator);
        VerlaEventInbox row = inboxRow(9001L, VerlaAgentEventType.FILE_CHAT_COMPLETED);
        VerlaEventEnvelope env = envelope(9001L, VerlaAgentEventType.FILE_CHAT_COMPLETED, Map.of(
                "finalText", "Here is the comparison."
        ));

        handler.handle(row, env);

        verify(orchestrator).onFileChatCompleted(9001L, env.getPayload());
        verifyNoMoreInteractions(orchestrator);
    }

    @Test
    void handle_fileChatFailed_shouldDelegateToFileChatFailureCallback() {
        VerlaAgentLifecycleEventHandler handler = new VerlaAgentLifecycleEventHandler(orchestrator);
        VerlaEventInbox row = inboxRow(9002L, VerlaAgentEventType.FILE_CHAT_FAILED);
        VerlaEventEnvelope env = envelope(9002L, VerlaAgentEventType.FILE_CHAT_FAILED, Map.of(
                "errorMessage", "File parsing is not ready yet."
        ));

        handler.handle(row, env);

        verify(orchestrator).onFileChatFailed(9002L, env.getPayload());
        verifyNoMoreInteractions(orchestrator);
    }

    @Test
    void handle_fileChatCancelled_shouldDelegateToFileChatCancelCallback() {
        VerlaAgentLifecycleEventHandler handler = new VerlaAgentLifecycleEventHandler(orchestrator);
        VerlaEventInbox row = inboxRow(9003L, VerlaAgentEventType.FILE_CHAT_CANCELLED);
        VerlaEventEnvelope env = envelope(9003L, VerlaAgentEventType.FILE_CHAT_CANCELLED, Map.of());

        handler.handle(row, env);

        verify(orchestrator).onFileChatCancelled(9003L);
        verifyNoMoreInteractions(orchestrator);
    }

    private static VerlaEventInbox inboxRow(Long sessionId, VerlaAgentEventType type) {
        return VerlaEventInbox.builder()
                .id(1L)
                .messageId("evt_" + sessionId)
                .conversationId(1001L)
                .turnId(55L)
                .sessionId(sessionId)
                .eventSeq(1L)
                .eventType(type.name())
                .status(VerlaEventInbox.STATUS_READY)
                .receivedAt(LocalDateTime.now())
                .build();
    }

    private static VerlaEventEnvelope envelope(
            Long sessionId,
            VerlaAgentEventType type,
            Map<String, Object> payload
    ) {
        return VerlaEventEnvelope.builder()
                .messageId("evt_" + sessionId)
                .correlationId("conv:1001:turn:55:sess:" + sessionId)
                .orderingKey("session:" + sessionId)
                .eventType(type.name())
                .eventSeq(1L)
                .timestamp(Instant.now())
                .conversation(VerlaConversationRef.builder().conversationId(1001L).build())
                .turn(TurnRef())
                .session(VerlaSessionRef.builder()
                        .sessionId(sessionId)
                        .kind(VerlaSessionKind.FILE_CHAT)
                        .feature("FILE_CHAT")
                        .build())
                .payload(payload)
                .build();
    }

    private static VerlaTurnRef TurnRef() {
        return VerlaTurnRef.builder().turnId(55L).build();
    }
}
