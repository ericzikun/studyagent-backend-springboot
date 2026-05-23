package com.studyagent.service.application.verla;

import com.studyagent.common.api.ApiCode;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.common.verla.enums.VerlaAttachmentStatus;
import com.studyagent.service.application.verla.dto.FileChatPanelFileView;
import com.studyagent.service.application.verla.dto.FileChatPanelMessageView;
import com.studyagent.service.application.verla.dto.FileChatPanelView;
import com.studyagent.service.domain.verla.VerlaAttachment;
import com.studyagent.service.domain.verla.VerlaConversation;
import com.studyagent.service.domain.verla.VerlaMessage;
import com.studyagent.service.domain.verla.repo.VerlaAttachmentRepository;
import com.studyagent.service.domain.verla.repo.VerlaConversationRepository;
import com.studyagent.service.domain.verla.repo.VerlaMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VerlaFileChatServiceTest {

    private static final String USER_ID = "user_1";
    private static final Long CONVERSATION_ID = 1001L;

    private FakeConversationRepository conversationRepository;
    private FakeAttachmentRepository attachmentRepository;
    private FakeMessageRepository messageRepository;
    private VerlaFileChatService service;

    @BeforeEach
    void setUp() {
        conversationRepository = new FakeConversationRepository();
        attachmentRepository = new FakeAttachmentRepository();
        messageRepository = new FakeMessageRepository();

        VerlaConversationService conversationService = new VerlaConversationService(
                conversationRepository,
                messageRepository,
                null);
        service = new VerlaFileChatService(conversationService, attachmentRepository, messageRepository);
    }

    @Test
    void getPanel_shouldFallbackToPendingWhenAttachmentIsNotParsed() {
        conversationRepository.conversation = conversation(CONVERSATION_ID, USER_ID);
        attachmentRepository.byObjectId = VerlaAttachment.builder()
                .objectId("obj_123")
                .conversationId(CONVERSATION_ID)
                .filename("calculus homework.pdf")
                .mime("application/pdf")
                .sizeBytes(15820L)
                .status(VerlaAttachmentStatus.PARSING.name())
                .metaJson("""
                        {
                          "fileChat": {
                            "analysisStatus": "READY",
                            "analysisText": "不应该在未解析完成时暴露"
                          }
                        }
                        """)
                .build();

        FileChatPanelView view = service.getPanel(USER_ID, CONVERSATION_ID, "obj_123", null, 20);

        assertThat(view.getFile()).isEqualTo(FileChatPanelFileView.builder()
                .objectId("obj_123")
                .name("calculus homework.pdf")
                .mimeType("application/pdf")
                .sizeBytes(15820L)
                .extractStatus(VerlaAttachmentStatus.PARSING.name())
                .build());
        assertThat(view.getAnalysis().getStatus().name()).isEqualTo("PENDING");
        assertThat(view.getAnalysis().getText()).isEmpty();
        assertThat(view.getSuggestedQuestions()).isEmpty();
        assertThat(view.getMessages()).isEmpty();
        assertThat(view.getNextCursor()).isNull();
    }

    @Test
    void getPanel_shouldReturnAnalysisAndQuestionsBeforeAnyFileChatMessages() {
        conversationRepository.conversation = conversation(CONVERSATION_ID, USER_ID);
        attachmentRepository.byObjectId = VerlaAttachment.builder()
                .objectId("obj_123")
                .conversationId(CONVERSATION_ID)
                .filename("calculus homework.pdf")
                .mime("application/pdf")
                .sizeBytes(15820L)
                .status(VerlaAttachmentStatus.PARSED.name())
                .summary("这是附件解析阶段就准备好的文件说明。")
                .metaJson("""
                        {
                          "fileChat": {
                            "analysisStatus": "READY",
                            "analysisText": "这是附件解析阶段就准备好的文件说明。",
                            "suggestedQuestions": [
                              "帮我比较这四道题",
                              "提取所有格式要求"
                            ],
                            "updatedAt": "2026-05-20T20:00:00"
                          }
                        }
                        """)
                .build();

        FileChatPanelView view = service.getPanel(USER_ID, CONVERSATION_ID, "obj_123", null, 20);

        assertThat(view.getAnalysis().getStatus().name()).isEqualTo("READY");
        assertThat(view.getAnalysis().getText()).isEqualTo("这是附件解析阶段就准备好的文件说明。");
        assertThat(view.getSuggestedQuestions()).containsExactly("帮我比较这四道题", "提取所有格式要求");
        assertThat(view.getMessages()).isEmpty();
        assertThat(view.getNextCursor()).isNull();
    }

    @Test
    void getPanel_shouldReturnPagedMessagesAndReadyAnalysis() {
        conversationRepository.conversation = conversation(CONVERSATION_ID, USER_ID);
        attachmentRepository.byObjectId = VerlaAttachment.builder()
                .objectId("obj_123")
                .conversationId(CONVERSATION_ID)
                .filename("calculus homework.pdf")
                .mime("application/pdf")
                .sizeBytes(15820L)
                .status(VerlaAttachmentStatus.PARSED.name())
                .metaJson("""
                        {
                          "fileChat": {
                            "analysisStatus": "READY",
                            "analysisText": "这是题目文件，适合先比较四道题的难度。",
                            "suggestedQuestions": [
                              "帮我比较这四道题",
                              "提取所有格式要求"
                            ],
                            "updatedAt": "2026-05-20T20:00:00"
                          }
                        }
                        """)
                .build();
        for (long i = 1; i <= 25; i++) {
            messageRepository.save(fileChatMessage(i, CONVERSATION_ID, "obj_123", i % 2 == 0 ? "assistant" : "user",
                    "msg-" + i));
        }
        messageRepository.save(fileChatMessage(99L, CONVERSATION_ID, "obj_other", "user", "other-file"));

        FileChatPanelView view = service.getPanel(USER_ID, CONVERSATION_ID, "obj_123", null, 20);

        assertThat(view.getAnalysis().getStatus().name()).isEqualTo("READY");
        assertThat(view.getAnalysis().getText()).isEqualTo("这是题目文件，适合先比较四道题的难度。");
        assertThat(view.getSuggestedQuestions()).containsExactly("帮我比较这四道题", "提取所有格式要求");
        assertThat(view.getMessages()).hasSize(20);
        assertThat(view.getMessages().get(0)).isEqualTo(FileChatPanelMessageView.builder()
                .messageId(25L)
                .role("user")
                .text("msg-25")
                .createdAt(messageRepository.createdAtById.get(25L))
                .build());
        assertThat(view.getMessages()).extracting(FileChatPanelMessageView::getMessageId)
                .doesNotContain(99L);
        assertThat(view.getNextCursor()).isEqualTo(6L);
    }

    @Test
    void getPanel_shouldReturnOlderMessagesAndNullNextCursorWhenExhausted() {
        conversationRepository.conversation = conversation(CONVERSATION_ID, USER_ID);
        attachmentRepository.byObjectId = VerlaAttachment.builder()
                .objectId("obj_123")
                .conversationId(CONVERSATION_ID)
                .filename("calculus homework.pdf")
                .mime("application/pdf")
                .sizeBytes(15820L)
                .status(VerlaAttachmentStatus.PARSED.name())
                .build();
        for (long i = 1; i <= 5; i++) {
            messageRepository.save(fileChatMessage(i, CONVERSATION_ID, "obj_123", i % 2 == 0 ? "assistant" : "user",
                    "msg-" + i));
        }

        FileChatPanelView view = service.getPanel(USER_ID, CONVERSATION_ID, "obj_123", 4L, 20);

        assertThat(view.getMessages()).extracting(FileChatPanelMessageView::getMessageId)
                .containsExactly(3L, 2L, 1L);
        assertThat(view.getNextCursor()).isNull();
    }

    @Test
    void getPanel_shouldRejectAttachmentOutsideConversation() {
        conversationRepository.conversation = conversation(CONVERSATION_ID, USER_ID);
        attachmentRepository.byObjectId = VerlaAttachment.builder()
                .objectId("obj_123")
                .conversationId(2002L)
                .status(VerlaAttachmentStatus.PARSED.name())
                .build();

        assertThatThrownBy(() -> service.getPanel(USER_ID, CONVERSATION_ID, "obj_123", null, 20))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ApiCode.TASK_NOT_FOUND.getCode());
    }

    private static VerlaConversation conversation(Long id, String userId) {
        return VerlaConversation.builder()
                .id(id)
                .userId(userId)
                .status("active")
                .title("作业辅导")
                .build();
    }

    private static VerlaMessage fileChatMessage(Long id,
                                                Long conversationId,
                                                String objectId,
                                                String role,
                                                String text) {
        return VerlaMessage.builder()
                .id(id)
                .conversationId(conversationId)
                .role(role)
                .textContent(text)
                .metaJson(VerlaFileChatMetadataHelper.writeMessageMeta(
                        com.studyagent.service.application.verla.dto.FileChatMessageMeta.builder()
                                .scene(com.studyagent.service.application.verla.dto.FileChatMessageMeta.SCENE_FILE_CHAT)
                                .objectId(objectId)
                                .build()))
                .createdAt(LocalDateTime.of(2026, 5, 20, 10, 0).plusMinutes(id))
                .build();
    }

    private static final class FakeConversationRepository implements VerlaConversationRepository {
        VerlaConversation conversation;

        @Override
        public VerlaConversation save(VerlaConversation conversation) {
            this.conversation = conversation;
            return conversation;
        }

        @Override
        public VerlaConversation findById(Long id) {
            return conversation != null && id.equals(conversation.getId()) ? conversation : null;
        }

        @Override
        public List<VerlaConversation> findByUserFilteredPaged(String userId, String segmentQueryKey, String conversationStatusDb, int page, int size) {
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

        @Override
        public int updateTitle(Long id, String title) {
            return 0;
        }
    }

    private static final class FakeAttachmentRepository implements VerlaAttachmentRepository {
        VerlaAttachment byObjectId;

        @Override
        public VerlaAttachment save(VerlaAttachment attachment) {
            byObjectId = attachment;
            return attachment;
        }

        @Override
        public VerlaAttachment findById(Long id) {
            return null;
        }

        @Override
        public VerlaAttachment findByObjectId(String objectId) {
            return byObjectId != null && objectId.equals(byObjectId.getObjectId()) ? byObjectId : null;
        }

        @Override
        public List<VerlaAttachment> findByObjectIds(List<String> objectIds) {
            return List.of();
        }

        @Override
        public List<VerlaAttachment> listByConversation(Long conversationId, int limit) {
            return List.of();
        }

        @Override
        public List<VerlaAttachment> listByTurn(Long turnId) {
            return List.of();
        }

        @Override
        public VerlaAttachment updateParseProgress(VerlaAttachment patch) {
            return patch;
        }

        @Override
        public VerlaAttachment updateByObjectIdSelective(VerlaAttachment patch) {
            return patch;
        }
    }

    private static final class FakeMessageRepository implements VerlaMessageRepository {
        private final Map<Long, VerlaMessage> store = new HashMap<>();
        private final Map<Long, LocalDateTime> createdAtById = new HashMap<>();

        @Override
        public VerlaMessage save(VerlaMessage message) {
            store.put(message.getId(), message);
            createdAtById.put(message.getId(), message.getCreatedAt());
            return message;
        }

        @Override
        public VerlaMessage findById(Long id) {
            return store.get(id);
        }

        @Override
        public List<VerlaMessage> findByCursor(Long conversationId, Long cursor, int limit) {
            return List.of();
        }

        @Override
        public List<VerlaMessage> findFileChatByCursor(Long conversationId, String objectId, Long cursor, int limit) {
            List<VerlaMessage> rows = new ArrayList<>(store.values());
            rows.removeIf(message -> !conversationId.equals(message.getConversationId()));
            rows.removeIf(message -> {
                var meta = VerlaFileChatMetadataHelper.readMessageMeta(message.getMetaJson());
                return meta == null || !objectId.equals(meta.getObjectId());
            });
            rows.sort(Comparator.comparing(VerlaMessage::getId).reversed());
            return rows.stream()
                    .filter(message -> cursor == null || message.getId() < cursor)
                    .limit(limit)
                    .toList();
        }
    }
}
