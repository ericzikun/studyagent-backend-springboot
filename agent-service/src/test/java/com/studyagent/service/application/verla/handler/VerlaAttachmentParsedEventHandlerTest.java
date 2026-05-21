package com.studyagent.service.application.verla.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.verla.envelope.VerlaConversationRef;
import com.studyagent.common.verla.envelope.VerlaEventEnvelope;
import com.studyagent.common.verla.envelope.VerlaSessionRef;
import com.studyagent.common.verla.envelope.VerlaTurnRef;
import com.studyagent.service.application.verla.VerlaFileChatMetadataHelper;
import com.studyagent.service.domain.verla.VerlaAttachment;
import com.studyagent.service.domain.verla.VerlaConversation;
import com.studyagent.service.domain.verla.VerlaEventInbox;
import com.studyagent.service.domain.verla.repo.VerlaAttachmentRepository;
import com.studyagent.service.domain.verla.repo.VerlaConversationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VerlaAttachmentParsedEventHandlerTest {

    private static final Long CONVERSATION_ID = 1001L;

    private FakeAttachmentRepository attachmentRepository;
    private FakeConversationRepository conversationRepository;
    private VerlaAttachmentParsedEventHandler handler;

    @BeforeEach
    void setUp() {
        attachmentRepository = new FakeAttachmentRepository();
        conversationRepository = new FakeConversationRepository();
        handler = new VerlaAttachmentParsedEventHandler(
                attachmentRepository,
                conversationRepository,
                new ObjectMapper());

        attachmentRepository.saved = VerlaAttachment.builder()
                .id(1L)
                .objectId("obj_123")
                .conversationId(CONVERSATION_ID)
                .status("PARSING")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        conversationRepository.conversation = VerlaConversation.builder()
                .id(CONVERSATION_ID)
                .version(1L)
                .build();
    }

    @Test
    void handle_parsed_shouldPersistSummaryIntoFileChatAnalysisAndQuestions() {
        VerlaEventInbox row = VerlaEventInbox.builder()
                .conversationId(CONVERSATION_ID)
                .turnId(10L)
                .sessionId(20L)
                .eventSeq(3L)
                .build();
        VerlaEventEnvelope env = VerlaEventEnvelope.builder()
                .eventType("ATTACHMENT_PARSED")
                .conversation(VerlaConversationRef.builder().conversationId(CONVERSATION_ID).build())
                .turn(VerlaTurnRef.builder().turnId(10L).build())
                .session(VerlaSessionRef.builder().sessionId(20L).build())
                .payload(java.util.Map.of(
                        "objectId", "obj_123",
                        "status", "PARSED",
                        "summary", "这是当前作业直接相关的题目文件，适合先提取格式要求和比较题目差异。",
                        "suggestedQuestions", List.of(
                                "帮我提取格式要求",
                                "比较四个题目的差异",
                                "我该先选哪一道题"),
                        "meta", java.util.Map.of("page_count", 3)))
                .build();

        handler.handle(row, env);

        VerlaAttachment saved = attachmentRepository.saved;
        assertThat(saved.getSummary()).isEqualTo("这是当前作业直接相关的题目文件，适合先提取格式要求和比较题目差异。");
        assertThat(VerlaFileChatMetadataHelper.readAttachmentState(saved).getAnalysis().getText())
                .isEqualTo("这是当前作业直接相关的题目文件，适合先提取格式要求和比较题目差异。");
        assertThat(VerlaFileChatMetadataHelper.readAttachmentState(saved).getAnalysis().getStatus().name())
                .isEqualTo("READY");
        assertThat(VerlaFileChatMetadataHelper.readAttachmentState(saved).getSuggestedQuestions())
                .containsExactly("帮我提取格式要求", "比较四个题目的差异", "我该先选哪一道题");
        assertThat(conversationRepository.incrementedConversationId).isEqualTo(CONVERSATION_ID);
    }

    private static final class FakeAttachmentRepository implements VerlaAttachmentRepository {
        private VerlaAttachment saved;

        @Override
        public VerlaAttachment save(VerlaAttachment attachment) {
            saved = attachment;
            return saved;
        }

        @Override
        public VerlaAttachment findById(Long id) {
            return saved;
        }

        @Override
        public VerlaAttachment findByObjectId(String objectId) {
            return saved != null && objectId.equals(saved.getObjectId()) ? saved : null;
        }

        @Override
        public List<VerlaAttachment> findByObjectIds(List<String> objectIds) {
            return saved == null ? List.of() : List.of(saved);
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
            saved = VerlaAttachment.builder()
                    .id(saved.getId())
                    .objectId(saved.getObjectId())
                    .conversationId(saved.getConversationId())
                    .turnId(patch.getTurnId())
                    .status(patch.getStatus())
                    .parseProgress(patch.getParseProgress())
                    .parseError(patch.getParseError())
                    .summary(patch.getSummary())
                    .primaryArtifactUid(patch.getPrimaryArtifactUid())
                    .metaJson(patch.getMetaJson())
                    .markdownContent(patch.getMarkdownContent())
                    .imagesJson(patch.getImagesJson())
                    .createdAt(saved.getCreatedAt())
                    .updatedAt(LocalDateTime.now())
                    .build();
            return saved;
        }

        @Override
        public VerlaAttachment updateByObjectIdSelective(VerlaAttachment patch) {
            saved = VerlaAttachment.builder()
                    .id(saved.getId())
                    .objectId(saved.getObjectId())
                    .conversationId(saved.getConversationId())
                    .turnId(saved.getTurnId())
                    .status(patch.getStatus() == null ? saved.getStatus() : patch.getStatus())
                    .parseProgress(patch.getParseProgress() == null ? saved.getParseProgress() : patch.getParseProgress())
                    .parseError(patch.getParseError() == null ? saved.getParseError() : patch.getParseError())
                    .summary(patch.getSummary() == null ? saved.getSummary() : patch.getSummary())
                    .primaryArtifactUid(patch.getPrimaryArtifactUid() == null ? saved.getPrimaryArtifactUid() : patch.getPrimaryArtifactUid())
                    .metaJson(patch.getMetaJson() == null ? saved.getMetaJson() : patch.getMetaJson())
                    .markdownContent(patch.getMarkdownContent() == null ? saved.getMarkdownContent() : patch.getMarkdownContent())
                    .imagesJson(patch.getImagesJson() == null ? saved.getImagesJson() : patch.getImagesJson())
                    .createdAt(saved.getCreatedAt())
                    .updatedAt(LocalDateTime.now())
                    .build();
            return saved;
        }
    }

    private static final class FakeConversationRepository implements VerlaConversationRepository {
        private VerlaConversation conversation;
        private Long incrementedConversationId;

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
            incrementedConversationId = id;
            conversation = VerlaConversation.builder()
                    .id(conversation.getId())
                    .version(conversation.getVersion() == null ? 1L : conversation.getVersion() + 1)
                    .build();
            return 1;
        }
    }
}
