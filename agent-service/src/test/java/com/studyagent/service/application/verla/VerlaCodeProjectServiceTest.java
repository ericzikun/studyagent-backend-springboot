package com.studyagent.service.application.verla;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.service.application.verla.VerlaCodeProjectService.CodeProject;
import com.studyagent.service.application.verla.VerlaCodeProjectService.ResolvedFile;
import com.studyagent.service.domain.verla.VerlaArtifact;
import com.studyagent.service.domain.verla.repo.VerlaArtifactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VerlaCodeProjectServiceTest {

    private static final long CID = 1L;
    private static final long SID = 3L;
    private static final String PROJECT_UID = "artifact_1_2_3_code_project";

    private static final String MANIFEST = """
            {
              "schemaVersion": 1,
              "projectUid": "code_project",
              "rootDir": "todo-app",
              "fileCount": 2,
              "totalBytes": 20,
              "files": [
                {"relPath": "src/main.py", "artifactUid": "code_project_a1b2", "language": "python", "sizeBytes": 11, "binary": false},
                {"relPath": "assets/logo.png", "artifactUid": "code_project_9f3c", "language": null, "sizeBytes": 9, "binary": true}
              ]
            }
            """;

    private VerlaConversationService conversationService;
    private VerlaArtifactRepository artifactRepository;
    private VerlaAttachmentService attachmentService;
    private VerlaCodeProjectService service;

    @BeforeEach
    void setUp() {
        conversationService = mock(VerlaConversationService.class);
        artifactRepository = mock(VerlaArtifactRepository.class);
        attachmentService = mock(VerlaAttachmentService.class);
        service = new VerlaCodeProjectService(
                conversationService, artifactRepository, attachmentService, new ObjectMapper());

        VerlaArtifact manifestRow = VerlaArtifact.builder()
                .artifactUid(PROJECT_UID)
                .conversationId(CID)
                .sessionId(SID)
                .kind(VerlaCodeProjectService.KIND_PROJECT)
                .bodyOrRef(MANIFEST)
                .build();
        when(artifactRepository.findByUid(PROJECT_UID)).thenReturn(manifestRow);

        VerlaArtifact textFile = VerlaArtifact.builder()
                .artifactUid("artifact_1_2_3_code_project_a1b2")
                .conversationId(CID)
                .sessionId(SID)
                .kind(VerlaCodeProjectService.KIND_FILE)
                .mime("text/plain")
                .bodyOrRef("print('hi')")
                .metaJson("{\"projectUid\":\"code_project\",\"relPath\":\"src/main.py\",\"binary\":false}")
                .build();
        VerlaArtifact binaryFile = VerlaArtifact.builder()
                .artifactUid("artifact_1_2_3_code_project_9f3c")
                .conversationId(CID)
                .sessionId(SID)
                .kind(VerlaCodeProjectService.KIND_FILE)
                .mime("image/png")
                .sourceObjectId("att_logo")
                .contentRef("oss://att_logo")
                .metaJson("{\"projectUid\":\"code_project\",\"relPath\":\"assets/logo.png\",\"binary\":true}")
                .build();
        when(artifactRepository.findBySession(SID)).thenReturn(List.of(textFile, binaryFile));
    }

    @Test
    void loadProject_resolvesRootDirAndFilesInManifestOrder() {
        CodeProject project = service.loadProject("user_1", CID, PROJECT_UID);

        assertThat(project.rootDir()).isEqualTo("todo-app");
        assertThat(project.files()).extracting("relPath")
                .containsExactly("src/main.py", "assets/logo.png");
    }

    @Test
    void resolveFile_text_returnsBodyBytes() {
        ResolvedFile file = service.resolveFile("user_1", CID, PROJECT_UID, "src/main.py");

        assertThat(file.filename()).isEqualTo("main.py");
        assertThat(file.mime()).isEqualTo("text/plain");
        assertThat(new String(file.bytes(), StandardCharsets.UTF_8)).isEqualTo("print('hi')");
    }

    @Test
    void resolveFile_binary_loadsAttachmentBytesByObjectId() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G'};
        when(attachmentService.loadAttachmentBytes("att_logo")).thenReturn(png);

        ResolvedFile file = service.resolveFile("user_1", CID, PROJECT_UID, "assets/logo.png");

        assertThat(file.filename()).isEqualTo("logo.png");
        assertThat(file.mime()).isEqualTo("image/png");
        assertThat(file.bytes()).isEqualTo(png);
    }

    @Test
    void resolveFile_rejectsPathTraversal() {
        assertThatThrownBy(() -> service.resolveFile("user_1", CID, PROJECT_UID, "../etc/passwd"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void resolveFile_rejectsAbsolutePath() {
        assertThatThrownBy(() -> service.resolveFile("user_1", CID, PROJECT_UID, "/etc/passwd"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void resolveFile_unknownRelPath_throwsNotFound() {
        assertThatThrownBy(() -> service.resolveFile("user_1", CID, PROJECT_UID, "nope.py"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void loadProject_wrongKind_throwsNotFound() {
        when(artifactRepository.findByUid("artifact_x")).thenReturn(VerlaArtifact.builder()
                .artifactUid("artifact_x")
                .conversationId(CID)
                .kind("assignment_code_file")
                .build());

        assertThatThrownBy(() -> service.loadProject("user_1", CID, "artifact_x"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void loadProject_conversationMismatch_throwsNotFound() {
        assertThatThrownBy(() -> service.loadProject("user_1", 999L, PROJECT_UID))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void archiveEntryName_prefixesRootDir() {
        assertThat(service.archiveEntryName("todo-app", "src/main.py"))
                .isEqualTo("todo-app/src/main.py");
    }

    @Test
    void sanitizeRelPath_normalizesAndStripsRedundantSegments() {
        assertThat(VerlaCodeProjectService.sanitizeRelPath("src//./main.py")).isEqualTo("src/main.py");
        assertThat(VerlaCodeProjectService.sanitizeRelPath("a\\b\\c.txt")).isEqualTo("a/b/c.txt");
    }
}
