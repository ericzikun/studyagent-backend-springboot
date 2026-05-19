package com.studyagent.api.controller.verla;

import com.studyagent.api.common.Result;
import com.studyagent.api.dto.verla.response.MessagePageVO;
import com.studyagent.api.dto.verla.response.VerlaMessageVO;
import com.studyagent.api.dto.verla.response.VerlaSessionContextVO;
import com.studyagent.service.application.verla.VerlaContextQueryService;
import com.studyagent.service.application.verla.dto.VerlaConversationContextView;
import com.studyagent.service.application.verla.dto.VerlaSessionContextQueryOptions;
import com.studyagent.service.application.verla.dto.VerlaSessionContextView;
import com.studyagent.service.config.VerlaContextCacheProperties;
import com.studyagent.service.domain.verla.VerlaConversation;
import com.studyagent.service.domain.verla.VerlaMessage;
import com.studyagent.service.domain.verla.VerlaSession;
import com.studyagent.service.domain.verla.VerlaToolCall;
import com.studyagent.service.domain.verla.VerlaTurn;
import com.studyagent.service.domain.verla.repo.VerlaConversationRepository;
import com.studyagent.service.domain.verla.repo.VerlaMessageRepository;
import com.studyagent.service.domain.verla.repo.VerlaSessionRepository;
import com.studyagent.service.domain.verla.repo.VerlaTurnRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class VerlaInternalControllerTest {

    private StubVerlaContextQueryService contextQueryService;
    private FakeConversationRepository conversationRepository;
    private FakeMessageRepository messageRepository;
    private VerlaInternalController controller;

    @BeforeEach
    void setUp() {
        contextQueryService = new StubVerlaContextQueryService();
        conversationRepository = new FakeConversationRepository();
        messageRepository = new FakeMessageRepository();
        controller = new VerlaInternalController(
                contextQueryService,
                null,
                conversationRepository,
                new NoopTurnRepository(),
                new NoopSessionRepository(),
                messageRepository);
    }

    @Test
    void getSessionContext_shouldPassV2FlagsAndVersionsToService() {
        contextQueryService.sessionView = sessionView();

        Result<VerlaSessionContextVO> result = controller.getSessionContext(
                9001L, 8L, 3L, true, true, false, 25, 60);

        assertThat(contextQueryService.lastSessionId).isEqualTo(9001L);
        assertThat(contextQueryService.lastConvVersion).isEqualTo(8L);
        assertThat(contextQueryService.lastTurnVersion).isEqualTo(3L);
        assertThat(contextQueryService.lastOptions.isIncludeTrace()).isTrue();
        assertThat(contextQueryService.lastOptions.isIncludeToolSummaries()).isTrue();
        assertThat(contextQueryService.lastOptions.isIncludeArtifacts()).isFalse();
        assertThat(contextQueryService.lastOptions.getMessageLimit()).isEqualTo(25);
        assertThat(contextQueryService.lastOptions.getTraceLimit()).isEqualTo(60);

        assertThat(result.getMeta().getStatusCode()).isEqualTo(0);
        assertThat(result.getData().getConvVersion()).isEqualTo(8L);
        assertThat(result.getData().getRecentMessages()).hasSize(1);
        assertThat(result.getData().getRecentToolCalls()).hasSize(1);
        assertThat(result.getData().getCacheHitLayer()).isEqualTo("conv");
    }

    @Test
    void getConversationContext_shouldPreferLimitOverMessageLimit() {
        contextQueryService.conversationView = conversationView();

        var result = controller.getConversationContext(
                1001L, 8L, 499L, true, true, false, 20, 30, 60);

        assertThat(contextQueryService.lastConversationId).isEqualTo(1001L);
        assertThat(contextQueryService.lastConvVersion).isEqualTo(8L);
        assertThat(contextQueryService.lastBeforeMessageId).isEqualTo(499L);
        assertThat(contextQueryService.lastOptions.getMessageLimit()).isEqualTo(30);
        assertThat(contextQueryService.lastOptions.getTraceLimit()).isEqualTo(60);
        assertThat(contextQueryService.lastOptions.isIncludeTrace()).isTrue();
        assertThat(contextQueryService.lastOptions.isIncludeToolSummaries()).isTrue();
        assertThat(contextQueryService.lastOptions.isIncludeArtifacts()).isFalse();

        assertThat(result.getMeta().getStatusCode()).isEqualTo(0);
        assertThat(result.getData().getConvVersion()).isEqualTo(8L);
        assertThat(result.getData().getNextCursor()).isEqualTo(500L);
        assertThat(result.getData().getLatestTurn().getTurnId()).isEqualTo(55L);
    }

    @Test
    void getRecentMessages_shouldClampLimitAndReturnCursorPage() {
        conversationRepository.conversation = conversation();
        messageRepository.page = List.of(message(501L));

        Result<MessagePageVO> result = controller.getRecentMessages(1001L, 600L, 1000);

        assertThat(messageRepository.lastConversationId).isEqualTo(1001L);
        assertThat(messageRepository.lastBeforeId).isEqualTo(600L);
        assertThat(messageRepository.lastLimit).isEqualTo(100);
        assertThat(result.getMeta().getStatusCode()).isEqualTo(0);
        assertThat(result.getData().getNextCursor()).isEqualTo(501L);
        assertThat(result.getData().getItems()).extracting(VerlaMessageVO::getMessageId)
                .containsExactly(501L);
    }

    private VerlaSessionContextView sessionView() {
        return VerlaSessionContextView.builder()
                .conversation(conversation())
                .turn(turn())
                .session(session())
                .upstreamSessions(List.of(session()))
                .recentMessages(List.of(message(501L)))
                .recentToolCalls(List.of(toolCall()))
                .traceIncluded(true)
                .cacheHitLayer("conv")
                .build();
    }

    private VerlaConversationContextView conversationView() {
        return VerlaConversationContextView.builder()
                .conversation(conversation())
                .latestTurn(turn())
                .recentMessages(List.of(message(501L)))
                .recentToolCalls(List.of(toolCall()))
                .traceIncluded(true)
                .nextCursor(500L)
                .cacheHitLayer("conv")
                .build();
    }

    private VerlaConversation conversation() {
        return VerlaConversation.builder()
                .id(1001L)
                .title("作业辅导")
                .primaryIntent("ASSIGNMENT")
                .workspaceJson("{}")
                .version(8L)
                .build();
    }

    private VerlaTurn turn() {
        return VerlaTurn.builder()
                .id(55L)
                .conversationId(1001L)
                .status("RUNNING_AGENT")
                .resolvedIntent("ASSIGNMENT")
                .resolvedSlotsJson("{}")
                .build();
    }

    private VerlaSession session() {
        return VerlaSession.builder()
                .id(9001L)
                .conversationId(1001L)
                .turnId(55L)
                .kind("ASSIGNMENT")
                .status("RUNNING")
                .resultJson("{\"intent\":\"assignment\"}")
                .build();
    }

    private VerlaToolCall toolCall() {
        return VerlaToolCall.builder()
                .toolCallId("call_1")
                .toolName("parse_pdf")
                .visibility("INTERNAL")
                .status("SUCCESS")
                .build();
    }

    private VerlaMessage message(Long id) {
        return VerlaMessage.builder()
                .id(id)
                .conversationId(1001L)
                .turnId(55L)
                .role("assistant")
                .textContent("这里是上一轮总结")
                .createdAt(LocalDateTime.now())
                .build();
    }

    private static class StubVerlaContextQueryService extends VerlaContextQueryService {
        private Long lastSessionId;
        private Long lastConversationId;
        private Long lastConvVersion;
        private Long lastTurnVersion;
        private Long lastBeforeMessageId;
        private VerlaSessionContextQueryOptions lastOptions;
        private VerlaSessionContextView sessionView;
        private VerlaConversationContextView conversationView;

        StubVerlaContextQueryService() {
            super(null, null, null, null, null, null,
                    new SimpleMeterRegistry(),
                    new VerlaContextCacheProperties(),
                    Optional.empty());
        }

        @Override
        public VerlaSessionContextView getSessionContext(Long sessionId, Long convVersion, Long turnVersion,
                                                         VerlaSessionContextQueryOptions options) {
            this.lastSessionId = sessionId;
            this.lastConvVersion = convVersion;
            this.lastTurnVersion = turnVersion;
            this.lastOptions = options;
            return sessionView;
        }

        @Override
        public VerlaConversationContextView getConversationContext(Long conversationId, Long convVersion,
                                                                   Long beforeMessageId,
                                                                   VerlaSessionContextQueryOptions options) {
            this.lastConversationId = conversationId;
            this.lastConvVersion = convVersion;
            this.lastBeforeMessageId = beforeMessageId;
            this.lastOptions = options;
            return conversationView;
        }
    }

    private static class FakeConversationRepository implements VerlaConversationRepository {
        private VerlaConversation conversation;

        @Override
        public VerlaConversation save(VerlaConversation conversation) {
            this.conversation = conversation;
            return conversation;
        }

        @Override
        public VerlaConversation findById(Long id) {
            return conversation;
        }

        @Override
        public List<VerlaConversation> findByUserFilteredPaged(String userId, String segmentQueryKey,
                                                               String conversationStatusDb, int page, int size) {
            return List.of();
        }

        @Override
        public long countByUserFiltered(String userId, String segmentQueryKey, String conversationStatusDb) {
            return 0;
        }

        @Override
        public int touchOnNewTurn(Long id, Long turnId) {
            return 0;
        }

        @Override
        public int incrementVersion(Long id) {
            return 0;
        }
    }

    private static class FakeMessageRepository implements VerlaMessageRepository {
        private Long lastConversationId;
        private Long lastBeforeId;
        private Integer lastLimit;
        private List<VerlaMessage> page = List.of();

        @Override
        public VerlaMessage save(VerlaMessage message) {
            return message;
        }

        @Override
        public VerlaMessage findById(Long id) {
            return null;
        }

        @Override
        public List<VerlaMessage> findByCursor(Long conversationId, Long cursor, int limit) {
            this.lastConversationId = conversationId;
            this.lastBeforeId = cursor;
            this.lastLimit = limit;
            return page;
        }
    }

    private static class NoopTurnRepository implements VerlaTurnRepository {
        @Override
        public VerlaTurn save(VerlaTurn turn) {
            return turn;
        }

        @Override
        public VerlaTurn findById(Long id) {
            return null;
        }

        @Override
        public VerlaTurn findByIdForUpdate(Long id) {
            return null;
        }

        @Override
        public List<VerlaTurn> findRecentByConversation(Long conversationId, int limit) {
            return List.of();
        }
    }

    private static class NoopSessionRepository implements VerlaSessionRepository {
        @Override
        public VerlaSession save(VerlaSession session) {
            return session;
        }

        @Override
        public VerlaSession findById(Long id) {
            return null;
        }

        @Override
        public VerlaSession findByIdForUpdate(Long id) {
            return null;
        }

        @Override
        public List<VerlaSession> findByTurn(Long turnId) {
            return List.of();
        }

        @Override
        public List<VerlaSession> findCompletedSiblings(Long turnId, Long excludeSessionId) {
            return List.of();
        }

        @Override
        public VerlaSession findByCorrelationId(String correlationId) {
            return null;
        }
    }
}
