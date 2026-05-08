package com.studyagent.service.application.verla;

import com.studyagent.service.application.MqOutboxService;
import com.studyagent.service.application.verla.dto.VerlaUploadSignResult;
import com.studyagent.service.domain.file.OssStorageService;
import com.studyagent.service.domain.verla.VerlaAttachment;
import com.studyagent.service.domain.verla.VerlaConversation;
import com.studyagent.service.domain.verla.repo.VerlaConversationRepository;
import com.studyagent.service.domain.verla.repo.VerlaAttachmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerlaAttachmentServiceTest {

    private static final String USER_ID = "user_1";

    @TempDir
    Path tempDir;

    VerlaConversationService conversationService;
    FakeAttachmentRepository attachmentRepository;
    OssStorageService ossStorageService;

    VerlaAttachmentService service;

    @BeforeEach
    void setup() {
        attachmentRepository = new FakeAttachmentRepository();
        conversationService = new VerlaConversationService(new FakeConversationRepository(), null, null);
        ossStorageService = new DisabledOssStorageService();
        service = new VerlaAttachmentService(
                conversationService, attachmentRepository, new MqOutboxService(null, null, null), ossStorageService);
        ReflectionTestUtils.setField(service, "maxBytes", 1024L);
        ReflectionTestUtils.setField(service, "signTtlSeconds", 3600L);
        ReflectionTestUtils.setField(service, "ossKeyPrefix", "verla/v2/attachments");
        ReflectionTestUtils.setField(service, "allowedMimesRaw", "application/pdf,text/plain");
        ReflectionTestUtils.setField(service, "localFallbackEnabled", true);
        ReflectionTestUtils.setField(service, "localRoot", tempDir.toString());
        service.init();
    }

    @Test
    void sign_allows_pre_turn_upload_when_local_fallback_is_enabled() {
        VerlaUploadSignResult result = service.requestSign(
                USER_ID, 74L, "assignment.pdf", "application/pdf", 8L, null, null);

        assertNotNull(result.getObjectId());
        assertTrue(result.getUploadPath().contains(result.getObjectId()));

        VerlaAttachment saved = attachmentRepository.saved.get(0);
        assertEquals(74L, saved.getConversationId());
        assertEquals("pending://" + result.getObjectId(), saved.getStorageUri());
        assertNotNull(saved.getOssKey());
    }

    @Test
    void upload_content_writes_local_file_when_oss_is_unavailable() throws Exception {
        VerlaUploadSignResult result = service.requestSign(
                USER_ID, 74L, "brief.txt", "text/plain", 5L, null, null);
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
        public List<VerlaConversation> findByUserPaged(String userId, int page, int size) {
            return List.of();
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

    private static class FakeAttachmentRepository implements VerlaAttachmentRepository {
        final List<VerlaAttachment> saved = new ArrayList<>();
        VerlaAttachment byObjectId;
        VerlaAttachment lastPatch;

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
            return byObjectId;
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
            lastPatch = patch;
            return patch;
        }
    }

    private static class DisabledOssStorageService implements OssStorageService {
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
            return false;
        }

        @Override
        public byte[] getObjectBytes(String ossKey) {
            return null;
        }

        @Override
        public boolean putBytesAtKey(String ossKey, byte[] content) {
            return false;
        }

        @Override
        public String formatVerlaStorageUri(String ossKey) {
            return null;
        }
    }
}
