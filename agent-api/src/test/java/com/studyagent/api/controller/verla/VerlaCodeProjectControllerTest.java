package com.studyagent.api.controller.verla;

import com.studyagent.service.application.verla.VerlaCodeProjectService;
import com.studyagent.service.application.verla.VerlaCodeProjectService.CodeFile;
import com.studyagent.service.application.verla.VerlaCodeProjectService.CodeProject;
import com.studyagent.service.application.verla.VerlaCodeProjectService.ResolvedFile;
import com.studyagent.service.domain.verla.VerlaArtifact;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class VerlaCodeProjectControllerTest {

    private StubCodeProjectService codeProjectService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        codeProjectService = new StubCodeProjectService();
        mockMvc = MockMvcBuilders.standaloneSetup(new VerlaCodeProjectController(codeProjectService)).build();
    }

    @Test
    void getFile_withDownloadFlag_setsAttachmentDisposition() throws Exception {
        codeProjectService.resolved = new ResolvedFile("main.py", "text/plain",
                "print('hi')".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(get("/v1/verla/conversations/1/code-projects/artifact_1_2_3_code_project/files")
                        .requestAttr("clerkUserId", "user_1")
                        .param("relPath", "src/main.py")
                        .param("download", "1"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/plain"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.containsString("attachment")));

        assertThat(codeProjectService.lastUserId).isEqualTo("user_1");
        assertThat(codeProjectService.lastConversationId).isEqualTo(1L);
        assertThat(codeProjectService.lastProjectUid).isEqualTo("artifact_1_2_3_code_project");
        assertThat(codeProjectService.lastRelPath).isEqualTo("src/main.py");
    }

    @Test
    void getFile_withoutDownloadFlag_hasNoAttachmentDisposition() throws Exception {
        codeProjectService.resolved = new ResolvedFile("main.py", "text/plain",
                "print('hi')".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(get("/v1/verla/conversations/1/code-projects/p1/files")
                        .requestAttr("clerkUserId", "user_1")
                        .param("relPath", "src/main.py"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.CONTENT_DISPOSITION));
    }

    @Test
    void archive_streamsZipWithRootDirPrefixedEntries() throws Exception {
        codeProjectService.project = new CodeProject("todo-app", List.of(
                file("src/main.py", "print('hi')"),
                file("requirements.txt", "flask")));

        MvcResult result = mockMvc.perform(
                        get("/v1/verla/conversations/1/code-projects/p1/archive")
                                .requestAttr("clerkUserId", "user_1"))
                .andExpect(status().isOk())
                .andReturn();

        MvcResult dispatched = mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/zip"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.containsString("todo-app.zip")))
                .andReturn();

        List<String> entries = new ArrayList<>();
        try (ZipInputStream zin = new ZipInputStream(
                new ByteArrayInputStream(dispatched.getResponse().getContentAsByteArray()))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                entries.add(e.getName());
            }
        }
        assertThat(entries).containsExactly("todo-app/src/main.py", "todo-app/requirements.txt");
    }

    private static CodeFile file(String relPath, String body) {
        return new CodeFile(relPath, false, "text",
                VerlaArtifact.builder().bodyOrRef(body).build());
    }

    private static final class StubCodeProjectService extends VerlaCodeProjectService {
        private String lastUserId;
        private Long lastConversationId;
        private String lastProjectUid;
        private String lastRelPath;
        private ResolvedFile resolved;
        private CodeProject project;

        StubCodeProjectService() {
            super(null, null, null, null);
        }

        @Override
        public ResolvedFile resolveFile(String clerkUserId, Long conversationId, String projectUid, String relPath) {
            this.lastUserId = clerkUserId;
            this.lastConversationId = conversationId;
            this.lastProjectUid = projectUid;
            this.lastRelPath = relPath;
            return resolved;
        }

        @Override
        public CodeProject loadProject(String clerkUserId, Long conversationId, String projectUid) {
            this.lastUserId = clerkUserId;
            this.lastConversationId = conversationId;
            this.lastProjectUid = projectUid;
            return project;
        }

        @Override
        public byte[] readBytes(CodeFile file) {
            String body = file.row().getBodyOrRef();
            return body != null ? body.getBytes(StandardCharsets.UTF_8) : null;
        }

        @Override
        public String archiveEntryName(String rootDir, String relPath) {
            return rootDir + "/" + relPath;
        }
    }
}
