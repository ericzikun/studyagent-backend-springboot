package com.studyagent.service.application.verla;

import com.studyagent.common.exception.BusinessException;
import com.studyagent.service.domain.file.OssStorageService;
import com.studyagent.service.domain.verla.VerlaArtifact;
import com.studyagent.service.domain.verla.VerlaConversation;
import com.studyagent.service.domain.verla.VerlaEditorAsset;
import com.studyagent.service.domain.verla.repo.VerlaArtifactRepository;
import com.studyagent.service.domain.verla.repo.VerlaConversationRepository;
import com.studyagent.service.domain.verla.repo.VerlaEditorAssetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;

class VerlaEditorAssetServiceTest {

    private static final String USER_ID = "user_1";

    @TempDir
    Path tempDir;

    FakeEditorAssetRepository assetRepository;
    FakeArtifactRepository artifactRepository;
    VerlaEditorAssetService service;

    @BeforeEach
    void setup() {
        assetRepository = new FakeEditorAssetRepository();
        artifactRepository = new FakeArtifactRepository();
        VerlaConversationService conversationService =
                new VerlaConversationService(new FakeConversationRepository(), null, null, new com.fasterxml.jackson.databind.ObjectMapper());
        OssStorageService ossStorageService = new DisabledOssStorageService();
        service = new VerlaEditorAssetService(
                conversationService,
                assetRepository,
                artifactRepository,
                ossStorageService);
        ReflectionTestUtils.setField(service, "maxBytes", 1024L);
        ReflectionTestUtils.setField(service, "signTtlSeconds", 3600L);
        ReflectionTestUtils.setField(service, "editorAssetKeyPrefix", "studyagent/document_editor_images");
        ReflectionTestUtils.setField(service, "allowedMimesRaw", "image/png,image/jpeg");
        ReflectionTestUtils.setField(service, "localFallbackEnabled", true);
        ReflectionTestUtils.setField(service, "localRoot", tempDir.toString());
        service.init();
    }

    @Test
    void requestSign_persists_artifact_uid_when_owned_artifact_is_provided() {
        artifactRepository.byUid = VerlaArtifact.builder()
                .artifactUid("artifact_doc_1")
                .conversationId(74L)
                .build();

        var result = service.requestSign(
                USER_ID,
                74L,
                "artifact_doc_1",
                "diagram.png",
                "image/png",
                8L,
                "document",
                "inline_image");

        assertNotNull(result.getAssetId());
        assertEquals("artifact_doc_1", assetRepository.saved.getArtifactUid());
        assertEquals(74L, assetRepository.saved.getConversationId());
    }

    @Test
    void requestSign_rejects_artifact_from_another_conversation() {
        artifactRepository.byUid = VerlaArtifact.builder()
                .artifactUid("artifact_doc_2")
                .conversationId(999L)
                .build();

        assertThrows(BusinessException.class, () ->
                service.requestSign(
                        USER_ID,
                        74L,
                        "artifact_doc_2",
                        "diagram.png",
                        "image/png",
                        8L,
                        "document",
                        "inline_image"));
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
        public List<VerlaConversation> findByUserFilteredPaged(String userId, String segmentQueryKey,
                                                               String conversationStatusDb, int page, int size) {
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

    private static class FakeEditorAssetRepository implements VerlaEditorAssetRepository {
        VerlaEditorAsset saved;

        @Override
        public VerlaEditorAsset save(VerlaEditorAsset asset) {
            saved = asset;
            return asset;
        }

        @Override
        public VerlaEditorAsset findByAssetId(String assetId) {
            return saved;
        }

        @Override
        public VerlaEditorAsset updateByAssetIdSelective(VerlaEditorAsset patch) {
            return patch;
        }
    }

    private static class FakeArtifactRepository implements VerlaArtifactRepository {
        VerlaArtifact byUid;

        @Override
        public VerlaArtifact findById(Long id) {
            return null;
        }

        @Override
        public VerlaArtifact findByUid(String artifactUid) {
            return byUid;
        }

        @Override
        public List<VerlaArtifact> findByConversation(Long conversationId) {
            return List.of();
        }

        @Override
        public List<VerlaArtifact> findBySession(Long sessionId) {
            return List.of();
        }

        @Override
        public List<VerlaArtifact> findByUids(List<String> artifactUids) {
            return List.of();
        }

        @Override
        public VerlaArtifact upsertByUid(VerlaArtifact artifact) {
            byUid = artifact;
            return artifact;
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
