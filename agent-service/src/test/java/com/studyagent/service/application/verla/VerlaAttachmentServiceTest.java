package com.studyagent.service.application.verla;

import com.studyagent.common.api.ApiCode;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.service.application.MqOutboxService;
import com.studyagent.service.application.verla.entitlement.EntitlementService;
import com.studyagent.service.application.verla.dto.VerlaUploadSignResult;
import com.studyagent.service.domain.file.OssStorageService;
import com.studyagent.service.domain.verla.VerlaAttachment;
import com.studyagent.service.domain.verla.VerlaConversation;
import com.studyagent.service.domain.verla.repo.VerlaConversationRepository;
import com.studyagent.service.domain.verla.repo.VerlaAttachmentRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerlaAttachmentServiceTest {

    private static final String USER_ID = "user_1";

    @TempDir
    Path tempDir;

    VerlaConversationService conversationService;
    FakeAttachmentRepository attachmentRepository;
    OssStorageService ossStorageService;
    EntitlementService entitlementService;
    MqOutboxService mqOutboxService;
    SimpleMeterRegistry meterRegistry;

    VerlaAttachmentService service;

    @BeforeEach
    void setup() {
        attachmentRepository = new FakeAttachmentRepository();
        conversationService = new VerlaConversationService(new FakeConversationRepository(), null, null);
        ossStorageService = new DisabledOssStorageService();
        entitlementService = org.mockito.Mockito.mock(EntitlementService.class);
        mqOutboxService = org.mockito.Mockito.mock(MqOutboxService.class);
        meterRegistry = new SimpleMeterRegistry();
        service = new VerlaAttachmentService(
                conversationService, attachmentRepository, mqOutboxService, ossStorageService,
                entitlementService, meterRegistry);
        ReflectionTestUtils.setField(service, "maxBytes", 1024L);
        ReflectionTestUtils.setField(service, "signTtlSeconds", 3600L);
        ReflectionTestUtils.setField(service, "ossKeyPrefix", "verla/v2/attachments");
        ReflectionTestUtils.setField(service, "allowedMimesRaw", "application/pdf,text/plain,image/jpeg");
        ReflectionTestUtils.setField(service, "localFallbackEnabled", true);
        ReflectionTestUtils.setField(service, "localRoot", tempDir.toString());
        service.init();
    }

    @Test
    void sign_allows_pre_turn_upload_when_local_fallback_is_enabled() {
        VerlaUploadSignResult result = service.requestSign(
                USER_ID, 74L, "assignment.pdf", "application/pdf", 8L, null, null, null, null);

        assertNotNull(result.getObjectId());
        assertTrue(result.getUploadPath().contains(result.getObjectId()));

        VerlaAttachment saved = attachmentRepository.saved.get(0);
        assertEquals(74L, saved.getConversationId());
        assertEquals("pending://" + result.getObjectId(), saved.getStorageUri());
        assertEquals("USER_UPLOAD", saved.getAttachmentOrigin());
        assertNotNull(saved.getOssKey());
    }

    @Test
    void sign_rejectsWhenUserUploadLimitReached() {
        org.mockito.Mockito.doThrow(new BusinessException(ApiCode.FILE_LIMIT_REACHED))
                .when(entitlementService).assertCanReserveUserUpload(USER_ID, 74L);

        assertThrows(BusinessException.class,
                () -> service.requestSign(USER_ID, 74L, "assignment.pdf", "application/pdf", 8L,
                        null, null, null, null));
        assertEquals(0, attachmentRepository.saved.size());
    }

    @Test
    void sign_doesNotCountEditorPreviewTowardLimit() {
        service.requestSign(
                USER_ID, 74L, "preview.jpg", "image/jpeg", 8L,
                null, null, "EDITOR_PREVIEW_IMAGE", null);

        org.mockito.Mockito.verify(entitlementService, org.mockito.Mockito.never())
                .assertCanReserveUserUpload(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void internal_sign_marks_agent_output_and_preserves_meta_json() {
        VerlaUploadSignResult result = service.requestSignForInternal(
                USER_ID,
                74L,
                "summary.txt",
                "text/plain",
                12L,
                101L,
                202L,
                "AGENT_OUTPUT",
                "{\"artifactUid\":\"artifact_123\"}");

        assertNotNull(result.getObjectId());

        VerlaAttachment saved = attachmentRepository.saved.get(0);
        assertEquals("AGENT_OUTPUT", saved.getAttachmentOrigin());
        assertEquals("{\"artifactUid\":\"artifact_123\"}", saved.getMetaJson());
    }

    @Test
    void listByConversation_hides_agent_output_attachments_from_user_views() {
        attachmentRepository.conversationAttachments = List.of(
                VerlaAttachment.builder()
                        .objectId("att_user")
                        .conversationId(74L)
                        .userId(USER_ID)
                        .filename("assignment.pdf")
                        .mime("application/pdf")
                        .sizeBytes(8L)
                        .status("PARSED")
                        .attachmentOrigin("USER_UPLOAD")
                        .build(),
                VerlaAttachment.builder()
                        .objectId("att_agent")
                        .conversationId(74L)
                        .userId(USER_ID)
                        .filename("summary.txt")
                        .mime("text/plain")
                        .sizeBytes(12L)
                        .status("PARSED")
                        .attachmentOrigin("AGENT_OUTPUT")
                        .build());

        List<VerlaAttachment> attachments = service.listByConversation(USER_ID, 74L, 50);

        assertEquals(1, attachments.size());
        assertEquals("att_user", attachments.get(0).getObjectId());
    }

    @Test
    void deleteAttachment_releasesSlotForNextSign() {
        VerlaUploadSignResult first = service.requestSign(
                USER_ID, 74L, "assignment.pdf", "application/pdf", 8L, null, null, null, null);

        service.deleteAttachment(USER_ID, first.getObjectId());

        service.requestSign(
                USER_ID, 74L, "second.pdf", "application/pdf", 8L, null, null, null, null);

        assertEquals(first.getObjectId(), attachmentRepository.lastDeletedObjectId);
        assertNotNull(attachmentRepository.saved.get(0).getDeletedAt());
        org.mockito.Mockito.verify(entitlementService, org.mockito.Mockito.times(2))
                .assertCanReserveUserUpload(USER_ID, 74L);
    }

    @Test
    void listByConversation_hides_editor_preview_attachments_from_user_views() {
        attachmentRepository.conversationAttachments = List.of(
                VerlaAttachment.builder()
                        .objectId("att_user")
                        .conversationId(74L)
                        .userId(USER_ID)
                        .filename("assignment.pdf")
                        .mime("application/pdf")
                        .sizeBytes(8L)
                        .status("PARSED")
                        .attachmentOrigin("USER_UPLOAD")
                        .build(),
                VerlaAttachment.builder()
                        .objectId("att_preview")
                        .conversationId(74L)
                        .userId(USER_ID)
                        .filename("preview-document.jpg")
                        .mime("image/jpeg")
                        .sizeBytes(12L)
                        .status("PARSED")
                        .attachmentOrigin("EDITOR_PREVIEW_IMAGE")
                        .build());

        List<VerlaAttachment> attachments = service.listByConversation(USER_ID, 74L, 50);

        assertEquals(1, attachments.size());
        assertEquals("att_user", attachments.get(0).getObjectId());
    }

    @Test
    void listByConversation_hides_document_editor_image_attachments_from_user_views() {
        attachmentRepository.conversationAttachments = List.of(
                VerlaAttachment.builder()
                        .objectId("att_user")
                        .conversationId(74L)
                        .userId(USER_ID)
                        .filename("assignment.pdf")
                        .mime("application/pdf")
                        .sizeBytes(8L)
                        .status("PARSED")
                        .attachmentOrigin("USER_UPLOAD")
                        .build(),
                VerlaAttachment.builder()
                        .objectId("att_doc_image")
                        .conversationId(74L)
                        .userId(USER_ID)
                        .filename("inline-image.png")
                        .mime("image/png")
                        .sizeBytes(12L)
                        .status("PARSED")
                        .attachmentOrigin("DOCUMENT_EDITOR_IMAGE")
                        .build());

        List<VerlaAttachment> attachments = service.listByConversation(USER_ID, 74L, 50);

        assertEquals(1, attachments.size());
        assertEquals("att_user", attachments.get(0).getObjectId());
    }

    @Test
    void upload_content_writes_local_file_when_oss_is_unavailable() throws Exception {
        VerlaUploadSignResult result = service.requestSign(
                USER_ID, 74L, "brief.txt", "text/plain", 5L, null, null, null, null);
        VerlaAttachment pending = VerlaAttachment.builder()
                .objectId(result.getObjectId())
                .conversationId(74L)
                .userId(USER_ID)
                .filename("brief.txt")
                .mime("text/plain")
                .sizeBytes(5L)
                .storageUri("pending://" + result.getObjectId())
                .ossKey("verla/v2/attachments/74/" + result.getObjectId() + "/brief.txt")
                .status("UPLOADED")
                .build();
        attachmentRepository.byObjectId = pending;

        service.uploadContent(
                USER_ID,
                result.getObjectId(),
                result.getUploadToken(),
                new ByteArrayInputStream("hello".getBytes()));

        VerlaAttachment patch = attachmentRepository.lastPatch;
        assertTrue(patch.getStorageUri().startsWith("file:"));
        assertFalse(patch.getChecksumSha256().isBlank());
        assertTrue(Files.exists(tempDir.resolve(pending.getOssKey())));
    }

    @Test
    void finalize_direct_upload_rejects_when_oss_object_is_not_confirmed() {
        DisabledOssStorageService oss = (DisabledOssStorageService) ossStorageService;
        oss.enabled = true;
        VerlaUploadSignResult result = service.requestSign(
                USER_ID, 74L, "direct.txt", "text/plain", 5L, null, null, null, null);

        assertThrows(BusinessException.class,
                () -> service.finalizeUpload(USER_ID, result.getObjectId(), result.getUploadToken(),
                        null, "checksum", true));
    }

    @Test
    void finalize_direct_upload_proceeds_when_oss_object_is_confirmed() {
        DisabledOssStorageService oss = (DisabledOssStorageService) ossStorageService;
        oss.enabled = true;
        oss.objectExists = true;
        VerlaUploadSignResult result = service.requestSign(
                USER_ID, 74L, "direct.txt", "text/plain", 5L, null, null, null, null);

        VerlaAttachment finalized = service.finalizeUpload(USER_ID, result.getObjectId(), result.getUploadToken(),
                null, "checksum", true);

        assertEquals("oss://test/" + finalized.getOssKey(), finalized.getStorageUri());
        assertEquals("checksum", finalized.getChecksumSha256());
    }

    @Test
    void upload_metrics_record_sign_and_finalize_by_channel_and_outcome() {
        service.requestSign(USER_ID, 74L, "external.txt", "text/plain", 5L, null, null, null, null);
        service.requestSignForInternal(USER_ID, 74L, "internal.txt", "text/plain", 5L,
                null, null, "AGENT_OUTPUT", null);
        assertThrows(BusinessException.class,
                () -> service.requestSign(USER_ID, 74L, null, "text/plain", 5L, null, null, null, null));
        assertThrows(BusinessException.class,
                () -> service.requestSignForInternal(USER_ID, 74L, null, "text/plain", 5L,
                        null, null, "AGENT_OUTPUT", null));

        VerlaUploadSignResult externalFailure = service.requestSign(
                USER_ID, 74L, "external-failure.txt", "text/plain", 5L, null, null, null, null);
        VerlaUploadSignResult internalFailure = service.requestSignForInternal(
                USER_ID, 74L, "internal-failure.txt", "text/plain", 5L,
                null, null, "AGENT_OUTPUT", null);
        assertThrows(BusinessException.class,
                () -> service.finalizeUpload(USER_ID, externalFailure.getObjectId(), externalFailure.getUploadToken(),
                        null, null, true));
        assertThrows(BusinessException.class,
                () -> service.finalizeUploadForInternal(internalFailure.getObjectId(), internalFailure.getUploadToken(),
                        null, null, true));

        VerlaUploadSignResult externalSuccess = service.requestSign(
                USER_ID, 74L, "external-success.txt", "text/plain", 5L, null, null, null, null);
        VerlaUploadSignResult internalSuccess = service.requestSignForInternal(
                USER_ID, 74L, "internal-success.txt", "text/plain", 5L,
                null, null, "AGENT_OUTPUT", null);
        markUploaded(externalSuccess.getObjectId());
        service.finalizeUpload(USER_ID, externalSuccess.getObjectId(), externalSuccess.getUploadToken(), null, null, false);
        markUploaded(internalSuccess.getObjectId());
        service.finalizeUploadForInternal(internalSuccess.getObjectId(), internalSuccess.getUploadToken(), null, null, false);

        assertEquals("PARSING", attachmentRepository.findByObjectId(externalSuccess.getObjectId()).getStatus());
        assertEquals("PARSING", attachmentRepository.findByObjectId(internalSuccess.getObjectId()).getStatus());
        org.mockito.Mockito.verify(mqOutboxService, org.mockito.Mockito.times(2))
                .createVerlaCommand(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.anyString());

        assertUploadCount("sign", "external", "success", "none", 3.0);
        assertUploadCount("sign", "internal", "success", "none", 3.0);
        assertUploadCount("sign", "external", "error", "validation", 1.0);
        assertUploadCount("sign", "internal", "error", "validation", 1.0);
        assertUploadCount("finalize", "external", "success", "none", 1.0);
        assertUploadCount("finalize", "internal", "success", "none", 1.0);
        assertUploadCount("finalize", "external", "error", "validation", 1.0);
        assertUploadCount("finalize", "internal", "error", "validation", 1.0);
        assertEquals(8, meterRegistry.find("verla.upload.duration").timers().size());
    }

    private void markUploaded(String objectId) {
        VerlaAttachment attachment = attachmentRepository.findByObjectId(objectId);
        attachment.setStorageUri("file:///tmp/" + objectId);
        attachment.setChecksumSha256("checksum");
    }

    private void assertUploadCount(String operation, String channel, String result, String errorType, double expected) {
        assertEquals(expected, meterRegistry.get("verla.upload")
                .tags("operation", operation, "channel", channel, "result", result, "error_type", errorType)
                .counter().count());
    }

    private static class FakeConversationRepository implements VerlaConversationRepository {
        @Override
        public VerlaConversation save(VerlaConversation conversation) {
            return conversation;
        }

        @Override
        public VerlaConversation findById(Long id) {
            return VerlaConversation.builder().id(id).userId(USER_ID).status("active").build();
        }

        @Override
        public List<VerlaConversation> findByUserFilteredPaged(String userId,
                                                               String segmentQueryKey,
                                                               String conversationStatusDb,
                                                               int page,
                                                               int size) {
            return List.of();
        }

        @Override
        public long countByUserFiltered(String userId, String segmentQueryKey, String conversationStatusDb) {
            return 0L;
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

    private static class FakeAttachmentRepository implements VerlaAttachmentRepository {
        final List<VerlaAttachment> saved = new ArrayList<>();
        VerlaAttachment byObjectId;
        VerlaAttachment lastPatch;
        List<VerlaAttachment> conversationAttachments = List.of();
        String lastDeletedObjectId;

        @Override
        public VerlaAttachment save(VerlaAttachment attachment) {
            saved.add(attachment);
            byObjectId = attachment;
            return attachment;
        }

        @Override
        public VerlaAttachment findById(Long id) {
            return null;
        }

        @Override
        public VerlaAttachment findByObjectId(String objectId) {
            if (byObjectId != null && objectId.equals(byObjectId.getObjectId())) {
                return byObjectId;
            }
            return saved.stream()
                    .filter(item -> objectId.equals(item.getObjectId()))
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public List<VerlaAttachment> findByObjectIds(List<String> objectIds) {
            return List.of();
        }

        @Override
        public List<VerlaAttachment> listByConversation(Long conversationId, int limit) {
            return conversationAttachments;
        }

        @Override
        public List<VerlaAttachment> listByTurn(Long turnId) {
            return List.of();
        }

        @Override
        public long countActiveUserUploadsForConversation(Long conversationId, java.time.LocalDateTime pendingCutoff) {
            return 0;
        }

        @Override
        public VerlaAttachment softDeleteUserUpload(String clerkUserId, String objectId) {
            VerlaAttachment target = findByObjectId(objectId);
            if (target == null) {
                return null;
            }
            target.setDeletedAt(LocalDateTime.now());
            lastDeletedObjectId = objectId;
            return target;
        }

        @Override
        public VerlaAttachment updateParseProgress(VerlaAttachment patch) {
            VerlaAttachment target = findByObjectId(patch.getObjectId());
            if (target != null) {
                target.setStatus(patch.getStatus());
            }
            return patch;
        }

        @Override
        public VerlaAttachment updateByObjectIdSelective(VerlaAttachment patch) {
            lastPatch = patch;
            VerlaAttachment target = findByObjectId(patch.getObjectId());
            if (target != null) {
                if (patch.getStorageUri() != null) {
                    target.setStorageUri(patch.getStorageUri());
                }
                if (patch.getChecksumSha256() != null) {
                    target.setChecksumSha256(patch.getChecksumSha256());
                }
                if (patch.getTurnId() != null) {
                    target.setTurnId(patch.getTurnId());
                }
            }
            return patch;
        }

        @Override
        public int markStaleUploadedAgentOutputsFailed(LocalDateTime cutoff, int batchSize, String reason) {
            return 0;
        }
    }

    private static class DisabledOssStorageService implements OssStorageService {
        private boolean enabled;
        private boolean objectExists;

        @Override
        public void uploadFileAsync(byte[] fileContent, String objectId, String filename) {}

        @Override
        public void uploadLocalFileAsync(String localFilePath, String objectId, String filename) {}

        @Override
        public String uploadFile(byte[] fileContent, String objectId, String filename) {
            return null;
        }

        @Override
        public String getOssUrl(String ossKey) {
            return null;
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public byte[] getObjectBytes(String ossKey) {
            return null;
        }

        @Override
        public boolean objectExists(String ossKey) {
            return objectExists;
        }

        @Override
        public boolean putBytesAtKey(String ossKey, byte[] content) {
            return false;
        }

        @Override
        public String formatVerlaStorageUri(String ossKey) {
            return enabled ? "oss://test/" + ossKey : null;
        }
    }
}
