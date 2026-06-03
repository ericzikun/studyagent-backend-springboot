package com.studyagent.service.application.verla;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.studyagent.common.verla.envelope.VerlaConversationRef;
import com.studyagent.common.verla.envelope.VerlaEventEnvelope;
import com.studyagent.common.verla.envelope.VerlaSessionRef;
import com.studyagent.common.verla.envelope.VerlaTurnRef;
import com.studyagent.common.verla.enums.VerlaSessionKind;
import com.studyagent.service.application.verla.handler.VerlaEventHandlerDispatcher;
import com.studyagent.service.application.verla.sse.VerlaSsePublisher;
import com.studyagent.service.domain.verla.VerlaEventCursor;
import com.studyagent.service.domain.verla.VerlaEventInbox;
import com.studyagent.service.domain.verla.repo.VerlaEventCursorRepository;
import com.studyagent.service.domain.verla.repo.VerlaEventInboxRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/**
 * {@link VerlaInboxService} 核心保序行为单测：
 * <ul>
 *   <li>duplicate — uk_message_id 冲突 → 静默 + 不 dispatch</li>
 *   <li>stale     — seq < expected → markSkipped + 不 dispatch + 不 advance</li>
 *   <li>early     — seq > expected → 留 inbox + 不 dispatch + 不 advance</li>
 *   <li>on-time   — seq == expected → dispatch + markProcessed + advance</li>
 * </ul>
 * 详见 docs/verla-Java侧MVP技术方案.md §8.3 / §11.4。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VerlaInboxServiceTest {

    @Mock
    VerlaEventInboxRepository inboxRepo;
    @Mock
    VerlaEventCursorRepository cursorRepo;
    @Mock
    VerlaEventHandlerDispatcher dispatcher;
    @Mock
    ObjectProvider<VerlaSsePublisher> ssePublisherProvider;
    @Mock
    VerlaSsePublisher ssePublisher;

    @Mock
    AssignmentRuntimeProgressEstimator progressEstimator;

    final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    VerlaInboxService service;

    @BeforeEach
    void setup() {
        when(progressEstimator.enrichAssignmentRunPayload(any(), any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        service = new VerlaInboxService(
                inboxRepo, cursorRepo, dispatcher, meterRegistry, objectMapper,
                ssePublisherProvider, progressEstimator);
        service.init();
    }

    @Test
    void duplicate_messageId_short_circuits() {
        when(inboxRepo.tryInsert(any())).thenReturn(false);

        service.ingest(envelope(9001L, 5L, "AGENT_STARTED"));

        verify(inboxRepo).tryInsert(any());
        verifyNoInteractions(dispatcher);
        verify(cursorRepo, never()).lockOrInit(any(), any(), any());
    }

    @Test
    void stale_seq_marks_skipped_and_no_dispatch() {
        when(inboxRepo.tryInsert(any())).thenAnswer(inv -> {
            VerlaEventInbox r = inv.getArgument(0);
            r.setId(1L);
            return true;
        });
        when(cursorRepo.lockOrInit(eq(9001L), any(), any()))
                .thenReturn(cursor(9001L, /*next*/ 10L, /*last*/ 9L));

        service.ingest(envelope(9001L, 5L, "AGENT_STARTED"));

        verify(inboxRepo).markSkipped(eq(1L), anyString());
        verifyNoInteractions(dispatcher);
        verify(cursorRepo, never()).advance(any(), any(), any());
    }

    @Test
    void early_seq_held_in_inbox() {
        when(inboxRepo.tryInsert(any())).thenAnswer(inv -> {
            VerlaEventInbox r = inv.getArgument(0);
            r.setId(2L);
            return true;
        });
        when(cursorRepo.lockOrInit(eq(9001L), any(), any()))
                .thenReturn(cursor(9001L, 3L, 2L));

        service.ingest(envelope(9001L, 7L, "AGENT_STARTED"));

        verify(inboxRepo, never()).markSkipped(anyLong(), anyString());
        verify(inboxRepo, never()).markProcessed(anyLong());
        verifyNoInteractions(dispatcher);
        verify(cursorRepo, never()).advance(any(), any(), any());
    }

    @Test
    void on_time_drains_one_then_stops_when_no_next() {
        when(inboxRepo.tryInsert(any())).thenAnswer(inv -> {
            VerlaEventInbox r = inv.getArgument(0);
            r.setId(3L);
            return true;
        });
        when(cursorRepo.lockOrInit(eq(9001L), any(), any()))
                .thenReturn(cursor(9001L, 7L, 6L));

        VerlaEventInbox readyRow = sampleInboxRow(3L, 9001L, 7L, "AGENT_STARTED");
        when(inboxRepo.findReady(9001L, 7L)).thenReturn(readyRow);
        when(inboxRepo.findReady(9001L, 8L)).thenReturn(null);

        service.ingest(envelope(9001L, 7L, "AGENT_STARTED"));

        verify(dispatcher, times(1)).dispatch(eq(readyRow), any());
        verify(inboxRepo).markProcessed(3L);
        verify(cursorRepo).advance(eq(9001L), eq(8L), eq(7L));
    }

    @Test
    void on_time_fileChatStreamChunk_publishesRawSsePayload() throws Exception {
        when(ssePublisherProvider.getIfAvailable()).thenReturn(ssePublisher);
        when(inboxRepo.tryInsert(any())).thenAnswer(inv -> {
            VerlaEventInbox r = inv.getArgument(0);
            r.setId(4L);
            return true;
        });
        when(cursorRepo.lockOrInit(eq(9001L), any(), any()))
                .thenReturn(cursor(9001L, 3L, 2L));

        VerlaEventEnvelope env = VerlaEventEnvelope.builder()
                .messageId("evt_9001_3")
                .correlationId("conv:1:turn:1:sess:9001")
                .orderingKey("session:9001")
                .eventType("FILE_CHAT_STREAM_CHUNK")
                .eventSeq(3L)
                .timestamp(Instant.now())
                .conversation(VerlaConversationRef.builder().conversationId(1L).build())
                .turn(VerlaTurnRef.builder().turnId(1L).build())
                .session(VerlaSessionRef.builder().sessionId(9001L).kind(VerlaSessionKind.FILE_CHAT).build())
                .payload(java.util.Map.of("objectId", "obj_123", "delta", "第一段文件回答"))
                .build();
        VerlaEventInbox readyRow = sampleInboxRow(4L, 9001L, 3L, "FILE_CHAT_STREAM_CHUNK");
        readyRow.setPayloadJson("""
                {
                  "messageId": "evt_9001_3",
                  "correlationId": "conv:1:turn:1:sess:9001",
                  "orderingKey": "session:9001",
                  "eventType": "FILE_CHAT_STREAM_CHUNK",
                  "eventSeq": 3,
                  "timestamp": "2026-05-21T00:00:00Z",
                  "conversation": { "conversationId": 1 },
                  "turn": { "turnId": 1 },
                  "session": { "sessionId": 9001, "kind": "FILE_CHAT" },
                  "payload": {
                    "objectId": "obj_123",
                    "delta": "第一段文件回答"
                  }
                }
                """);
        when(inboxRepo.findReady(9001L, 3L)).thenReturn(readyRow);
        when(inboxRepo.findReady(9001L, 4L)).thenReturn(null);

        service.ingest(env);

        verify(ssePublisher).publish(eq(1L), argThat(payload ->
                payload != null
                        && "FILE_CHAT_STREAM_CHUNK".equals(payload.getType())
                        && payload.getPayload() != null
                        && "obj_123".equals(payload.getPayload().get("objectId"))
                        && "第一段文件回答".equals(payload.getPayload().get("delta"))));
    }

    @Test
    void on_time_fileChatCompleted_publishesSsePayload() throws Exception {
        when(ssePublisherProvider.getIfAvailable()).thenReturn(ssePublisher);
        when(inboxRepo.tryInsert(any())).thenAnswer(inv -> {
            VerlaEventInbox r = inv.getArgument(0);
            r.setId(5L);
            return true;
        });
        when(cursorRepo.lockOrInit(eq(9002L), any(), any()))
                .thenReturn(cursor(9002L, 1L, 0L));

        VerlaEventEnvelope env = VerlaEventEnvelope.builder()
                .messageId("evt_9002_1")
                .correlationId("conv:1:turn:1:sess:9002")
                .orderingKey("session:9002")
                .eventType("FILE_CHAT_COMPLETED")
                .eventSeq(1L)
                .timestamp(Instant.now())
                .conversation(VerlaConversationRef.builder().conversationId(1L).build())
                .turn(VerlaTurnRef.builder().turnId(1L).build())
                .session(VerlaSessionRef.builder().sessionId(9002L).kind(VerlaSessionKind.FILE_CHAT).build())
                .payload(java.util.Map.of("objectId", "obj_123", "finalText", "最终回答"))
                .build();
        VerlaEventInbox readyRow = sampleInboxRow(5L, 9002L, 1L, "FILE_CHAT_COMPLETED");
        readyRow.setPayloadJson("""
                {
                  "messageId": "evt_9002_1",
                  "correlationId": "conv:1:turn:1:sess:9002",
                  "orderingKey": "session:9002",
                  "eventType": "FILE_CHAT_COMPLETED",
                  "eventSeq": 1,
                  "timestamp": "2026-05-21T00:00:00Z",
                  "conversation": { "conversationId": 1 },
                  "turn": { "turnId": 1 },
                  "session": { "sessionId": 9002, "kind": "FILE_CHAT" },
                  "payload": {
                    "objectId": "obj_123",
                    "finalText": "最终回答"
                  }
                }
                """);
        when(inboxRepo.findReady(9002L, 1L)).thenReturn(readyRow);
        when(inboxRepo.findReady(9002L, 2L)).thenReturn(null);

        service.ingest(env);

        verify(ssePublisher).publish(eq(1L), argThat(payload ->
                payload != null
                        && "FILE_CHAT_COMPLETED".equals(payload.getType())
                        && payload.getPayload() != null
                        && "obj_123".equals(payload.getPayload().get("objectId"))
                        && "最终回答".equals(payload.getPayload().get("finalText"))));
    }

    @Test
    void codeFileArtifactEvent_isNotPublishedToSse() {
        when(ssePublisherProvider.getIfAvailable()).thenReturn(ssePublisher);
        when(inboxRepo.tryInsert(any())).thenAnswer(inv -> {
            VerlaEventInbox r = inv.getArgument(0);
            r.setId(6L);
            return true;
        });
        when(cursorRepo.lockOrInit(eq(9003L), any(), any()))
                .thenReturn(cursor(9003L, 1L, 0L));

        VerlaEventEnvelope env = artifactEnvelope(9003L, 1L, "assignment_code_file");
        VerlaEventInbox readyRow = sampleInboxRow(6L, 9003L, 1L, "ASSIGNMENT_AGENT_FLOW_ARTIFACT_UPDATED");
        readyRow.setPayloadJson(artifactPayloadJson(9003L, "assignment_code_file"));
        when(inboxRepo.findReady(9003L, 1L)).thenReturn(readyRow);
        when(inboxRepo.findReady(9003L, 2L)).thenReturn(null);

        service.ingest(env);

        verify(ssePublisher, never()).publish(any(), any());
    }

    @Test
    void codeProjectManifestEvent_isPublishedToSse() {
        when(ssePublisherProvider.getIfAvailable()).thenReturn(ssePublisher);
        when(inboxRepo.tryInsert(any())).thenAnswer(inv -> {
            VerlaEventInbox r = inv.getArgument(0);
            r.setId(7L);
            return true;
        });
        when(cursorRepo.lockOrInit(eq(9004L), any(), any()))
                .thenReturn(cursor(9004L, 1L, 0L));

        VerlaEventEnvelope env = artifactEnvelope(9004L, 1L, "assignment_code_project");
        VerlaEventInbox readyRow = sampleInboxRow(7L, 9004L, 1L, "ASSIGNMENT_AGENT_FLOW_ARTIFACT_UPDATED");
        readyRow.setPayloadJson(artifactPayloadJson(9004L, "assignment_code_project"));
        when(inboxRepo.findReady(9004L, 1L)).thenReturn(readyRow);
        when(inboxRepo.findReady(9004L, 2L)).thenReturn(null);

        service.ingest(env);

        verify(ssePublisher).publish(eq(1L), argThat(payload ->
                payload != null
                        && "ASSIGNMENT_AGENT_FLOW_ARTIFACT_UPDATED".equals(payload.getType())
                        && payload.getPayload() != null
                        && "assignment_code_project".equals(payload.getPayload().get("kind"))));
    }

    // -----------------------------------------------------------

    private static VerlaEventEnvelope artifactEnvelope(long sessionId, long seq, String kind) {
        return VerlaEventEnvelope.builder()
                .messageId("evt_" + sessionId + "_" + seq)
                .correlationId("conv:1:turn:1:sess:" + sessionId)
                .orderingKey("session:" + sessionId)
                .eventType("ASSIGNMENT_AGENT_FLOW_ARTIFACT_UPDATED")
                .eventSeq(seq)
                .timestamp(Instant.now())
                .conversation(VerlaConversationRef.builder().conversationId(1L).build())
                .turn(VerlaTurnRef.builder().turnId(1L).build())
                .session(VerlaSessionRef.builder().sessionId(sessionId).kind(VerlaSessionKind.ASSIGNMENT).build())
                .payload(java.util.Map.of("kind", kind, "artifactUid", "artifact_1_1_" + sessionId + "_x"))
                .build();
    }

    private static String artifactPayloadJson(long sessionId, String kind) {
        return """
                {
                  "messageId": "evt_%1$d_1",
                  "correlationId": "conv:1:turn:1:sess:%1$d",
                  "orderingKey": "session:%1$d",
                  "eventType": "ASSIGNMENT_AGENT_FLOW_ARTIFACT_UPDATED",
                  "eventSeq": 1,
                  "timestamp": "2026-05-21T00:00:00Z",
                  "conversation": { "conversationId": 1 },
                  "turn": { "turnId": 1 },
                  "session": { "sessionId": %1$d, "kind": "ASSIGNMENT" },
                  "payload": { "kind": "%2$s", "artifactUid": "artifact_1_1_%1$d_x" }
                }
                """.formatted(sessionId, kind);
    }

    private static VerlaEventEnvelope envelope(long sessionId, long seq, String type) {
        return VerlaEventEnvelope.builder()
                .messageId("evt_" + sessionId + "_" + seq)
                .correlationId("conv:1:turn:1:sess:" + sessionId)
                .orderingKey("session:" + sessionId)
                .eventType(type)
                .eventSeq(seq)
                .timestamp(Instant.now())
                .conversation(VerlaConversationRef.builder().conversationId(1L).build())
                .turn(VerlaTurnRef.builder().turnId(1L).build())
                .session(VerlaSessionRef.builder().sessionId(sessionId).kind(VerlaSessionKind.ASSIGNMENT).build())
                .build();
    }

    private static VerlaEventCursor cursor(long sessionId, long nextExpected, long lastProcessed) {
        return VerlaEventCursor.builder()
                .sessionId(sessionId)
                .conversationId(1L)
                .turnId(1L)
                .nextExpectedSeq(nextExpected)
                .lastProcessedSeq(lastProcessed)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private static VerlaEventInbox sampleInboxRow(long id, long sessionId, long seq, String type) {
        return VerlaEventInbox.builder()
                .id(id)
                .messageId("evt_" + sessionId + "_" + seq)
                .correlationId("conv:1:turn:1:sess:" + sessionId)
                .conversationId(1L)
                .turnId(1L)
                .sessionId(sessionId)
                .eventSeq(seq)
                .eventType(type)
                .payloadJson("{}")
                .status(VerlaEventInbox.STATUS_READY)
                .receivedAt(LocalDateTime.now())
                .build();
    }
}
