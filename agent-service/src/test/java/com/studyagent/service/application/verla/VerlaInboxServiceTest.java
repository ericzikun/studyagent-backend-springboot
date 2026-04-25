package com.studyagent.service.application.verla;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
class VerlaInboxServiceTest {

    @Mock
    VerlaEventInboxRepository inboxRepo;
    @Mock
    VerlaEventCursorRepository cursorRepo;
    @Mock
    VerlaEventHandlerDispatcher dispatcher;
    @Mock
    ObjectProvider<VerlaSsePublisher> ssePublisherProvider;

    final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    final ObjectMapper objectMapper = new ObjectMapper();

    VerlaInboxService service;

    @BeforeEach
    void setup() {
        service = new VerlaInboxService(
                inboxRepo, cursorRepo, dispatcher, meterRegistry, objectMapper, ssePublisherProvider);
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

    // -----------------------------------------------------------

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
                .session(VerlaSessionRef.builder().sessionId(sessionId).kind(VerlaSessionKind.AGENT).build())
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
