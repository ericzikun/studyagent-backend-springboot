package com.studyagent.service.application.verla.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.verla.enums.VerlaAgentEventType;
import com.studyagent.common.verla.envelope.VerlaConversationRef;
import com.studyagent.common.verla.envelope.VerlaEventEnvelope;
import com.studyagent.common.verla.envelope.VerlaSessionRef;
import com.studyagent.common.verla.envelope.VerlaTurnRef;
import com.studyagent.service.domain.verla.VerlaConversation;
import com.studyagent.service.domain.verla.VerlaEventInbox;
import com.studyagent.service.domain.verla.VerlaToolCall;
import com.studyagent.service.domain.verla.repo.VerlaConversationRepository;
import com.studyagent.service.domain.verla.repo.VerlaToolCallRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class VerlaToolCallEventHandlerTest {

    private static final Long CONVERSATION_ID = 1001L;
    private static final Long TURN_ID = 55L;
    private static final Long SESSION_ID = 1328L;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FakeToolCallRepository toolCallRepository = new FakeToolCallRepository();
    private final FakeConversationRepository conversationRepository = new FakeConversationRepository();
    private final VerlaToolCallEventHandler handler = new VerlaToolCallEventHandler(
            toolCallRepository,
            conversationRepository,
            objectMapper);

    @Test
    void handle_largeToolInput_shouldPersistValidTruncatedJson() throws Exception {
        String largeContent = "x".repeat(17_147);
        VerlaEventEnvelope env = envelope(Map.of(
                "toolCallId", "call_large_write_file",
                "agentName", "Coding Expert",
                "toolName", "aio_sandbox_write_file",
                "status", "SUCCEEDED",
                "visibility", "INTERNAL",
                "nodeId", "task-1.3",
                "toolInput", Map.of(
                        "path", "/home/gem/studyagent/coding/371-457-1328/tool_manager.py",
                        "append", false,
                        "content", largeContent
                ),
                "toolOutput", Map.of("result", "{\"success\": true}")
        ));

        handler.handle(inboxRow(), env);

        VerlaToolCall saved = toolCallRepository.saved;
        assertThat(saved.getToolInputJson()).contains("[truncated]");
        assertThatCode(() -> objectMapper.readTree(saved.getToolInputJson()))
                .doesNotThrowAnyException();
        assertThat(objectMapper.readTree(saved.getToolInputJson()).path("_truncated").asBoolean())
                .isTrue();
        assertThat(conversationRepository.incrementedConversationId).isNull();
    }

    private static VerlaEventInbox inboxRow() {
        return VerlaEventInbox.builder()
                .id(1L)
                .messageId("evt_large_tool")
                .conversationId(CONVERSATION_ID)
                .turnId(TURN_ID)
                .sessionId(SESSION_ID)
                .eventSeq(37L)
                .eventType(VerlaAgentEventType.AGENT_TOOL_CALL_RECORDED.name())
                .status(VerlaEventInbox.STATUS_READY)
                .receivedAt(LocalDateTime.now())
                .build();
    }

    private static VerlaEventEnvelope envelope(Map<String, Object> payload) {
        return VerlaEventEnvelope.builder()
                .messageId("evt_large_tool")
                .correlationId("conv:1001:turn:55:sess:1328")
                .orderingKey("session:1328")
                .eventType(VerlaAgentEventType.AGENT_TOOL_CALL_RECORDED.name())
                .eventSeq(37L)
                .timestamp(Instant.now())
                .conversation(VerlaConversationRef.builder().conversationId(CONVERSATION_ID).build())
                .turn(VerlaTurnRef.builder().turnId(TURN_ID).build())
                .session(VerlaSessionRef.builder().sessionId(SESSION_ID).build())
                .payload(payload)
                .build();
    }

    private static final class FakeToolCallRepository implements VerlaToolCallRepository {
        private VerlaToolCall saved;

        @Override
        public VerlaToolCall findByCallId(String toolCallId) {
            return saved != null && toolCallId.equals(saved.getToolCallId()) ? saved : null;
        }

        @Override
        public List<VerlaToolCall> listByTurn(Long turnId, int limit) {
            return List.of();
        }

        @Override
        public List<VerlaToolCall> listBySession(Long sessionId, int limit) {
            return List.of();
        }

        @Override
        public List<VerlaToolCall> listVisibleByConversation(Long conversationId, int limit) {
            return List.of();
        }

        @Override
        public VerlaToolCall upsertByCallId(VerlaToolCall toolCall) {
            saved = toolCall;
            return saved;
        }
    }

    private static final class FakeConversationRepository implements VerlaConversationRepository {
        private Long incrementedConversationId;

        @Override
        public VerlaConversation save(VerlaConversation conversation) {
            return conversation;
        }

        @Override
        public VerlaConversation findById(Long id) {
            return VerlaConversation.builder().id(id).version(1L).build();
        }

        @Override
        public List<VerlaConversation> findByUserFilteredPaged(
                String userId,
                String segmentQueryKey,
                String conversationStatusDb,
                int page,
                int size) {
            return List.of();
        }

        @Override
        public long countByUserFiltered(
                String userId,
                String segmentQueryKey,
                String conversationStatusDb) {
            return 0;
        }

        @Override
        public int touchOnNewTurn(Long id, Long turnId) {
            return 0;
        }

        @Override
        public int incrementVersion(Long id) {
            incrementedConversationId = id;
            return 1;
        }

        @Override
        public int updateTitle(Long id, String title) {
            return 0;
        }
    }
}
