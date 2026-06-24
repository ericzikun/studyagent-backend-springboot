package com.studyagent.api.controller.verla;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.infra.entity.verla.VerlaArtifactEntity;
import com.studyagent.infra.mapper.verla.VerlaArtifactMapper;
import com.studyagent.infra.mapper.verla.VerlaEditorContentMapper;
import com.studyagent.service.application.verla.VerlaConversationService;
import com.studyagent.service.domain.verla.VerlaArtifactEditProposal;
import com.studyagent.service.domain.verla.repo.VerlaArtifactEditProposalRepository;
import com.studyagent.service.domain.verla.repo.VerlaConversationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class VerlaArtifactEditProposalControllerTest {

    private static final Long CONVERSATION_ID = 24L;
    private static final String PROPOSAL_ID = "ep_24_1";
    private static final String ARTIFACT_UID = "art_doc";
    private static final String ORIGINAL_TEXT = "Replace this sentence.";
    private static final String PROPOSED_TEXT = "Use this revised sentence.";

    private VerlaArtifactEditProposalRepository proposalRepository;
    private VerlaArtifactMapper artifactMapper;
    private VerlaEditorContentMapper editorContentMapper;
    private VerlaConversationRepository conversationRepository;
    private ObjectMapper objectMapper;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        VerlaConversationService conversationService = mock(VerlaConversationService.class);
        proposalRepository = mock(VerlaArtifactEditProposalRepository.class);
        artifactMapper = mock(VerlaArtifactMapper.class);
        editorContentMapper = mock(VerlaEditorContentMapper.class);
        conversationRepository = mock(VerlaConversationRepository.class);
        objectMapper = new ObjectMapper();
        mockMvc = MockMvcBuilders.standaloneSetup(new VerlaArtifactEditProposalController(
                        conversationService,
                        proposalRepository,
                        artifactMapper,
                        editorContentMapper,
                        conversationRepository,
                        objectMapper))
                .build();
    }

    @Test
    void commit_shouldApplyHunksInsideBodySectionOnly() throws Exception {
        String body = "Intro paragraph.\n\n" + ORIGINAL_TEXT;
        String raw = String.join("\n",
                "metadata duplicate: " + ORIGINAL_TEXT,
                "[--BODY_SECTION--]",
                "",
                body,
                "",
                "[--EVIDENCE_RECORDS--]",
                "{\"quote\":\"" + ORIGINAL_TEXT + "\"}");
        String expected = String.join("\n",
                "metadata duplicate: " + ORIGINAL_TEXT,
                "[--BODY_SECTION--]",
                "",
                "Intro paragraph.",
                "",
                PROPOSED_TEXT,
                "",
                "[--EVIDENCE_RECORDS--]",
                "{\"quote\":\"" + ORIGINAL_TEXT + "\"}");
        VerlaArtifactEntity artifact = givenArtifact(raw);
        givenReviewProposal(List.of(hunk("h1", body.indexOf(ORIGINAL_TEXT), body.length())));

        commitAccepted("h1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.statusCode").value(0))
                .andExpect(jsonPath("$.data.artifacts[0].artifactUid").value(ARTIFACT_UID))
                .andExpect(jsonPath("$.data.artifacts[0].versionNo").value(4));

        assertUpdatedArtifact(artifact, expected);
    }

    @Test
    void commit_shouldKeepPlainMarkdownFallback() throws Exception {
        String raw = "Alpha\n\n" + ORIGINAL_TEXT + "\n\nOmega";
        String expected = "Alpha\n\n" + PROPOSED_TEXT + "\n\nOmega";
        VerlaArtifactEntity artifact = givenArtifact(raw);
        givenReviewProposal(List.of(hunk("h1", raw.indexOf(ORIGINAL_TEXT),
                raw.indexOf(ORIGINAL_TEXT) + ORIGINAL_TEXT.length())));

        commitAccepted("h1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.statusCode").value(0))
                .andExpect(jsonPath("$.data.artifacts[0].versionNo").value(4));

        assertUpdatedArtifact(artifact, expected);
    }

    @Test
    void commit_shouldApplyHunksBeforeEvidenceOnlySection() throws Exception {
        String raw = "\n" + ORIGINAL_TEXT + "\n\n[--EVIDENCE_RECORDS--]\n"
                + "{\"quote\":\"" + ORIGINAL_TEXT + "\"}";
        String expected = "\n" + PROPOSED_TEXT + "\n\n[--EVIDENCE_RECORDS--]\n"
                + "{\"quote\":\"" + ORIGINAL_TEXT + "\"}";
        VerlaArtifactEntity artifact = givenArtifact(raw);
        givenReviewProposal(List.of(hunk("h1", 0, ORIGINAL_TEXT.length())));

        commitAccepted("h1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.statusCode").value(0))
                .andExpect(jsonPath("$.data.artifacts[0].versionNo").value(4));

        assertUpdatedArtifact(artifact, expected);
    }

    private org.springframework.test.web.servlet.ResultActions commitAccepted(String hunkId) throws Exception {
        return mockMvc.perform(post("/v1/verla/conversations/{cid}/artifacts/edit-proposals/{proposalId}/commit",
                        CONVERSATION_ID, PROPOSAL_ID)
                .requestAttr("clerkUserId", "user_1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "decisions": [
                            {
                              "artifactUid": "art_doc",
                              "hunkId": "%s",
                              "status": "accepted"
                            }
                          ]
                        }
                        """.formatted(hunkId)));
    }

    private VerlaArtifactEntity givenArtifact(String rawBody) {
        VerlaArtifactEntity artifact = new VerlaArtifactEntity()
                .setArtifactUid(ARTIFACT_UID)
                .setConversationId(CONVERSATION_ID)
                .setKind("assignment_document_md")
                .setBodyOrRef(rawBody)
                .setVersion(3);
        when(artifactMapper.selectByUid(ARTIFACT_UID)).thenReturn(artifact);
        return artifact;
    }

    private void givenReviewProposal(List<Map<String, Object>> hunks) throws Exception {
        when(proposalRepository.findByProposalId(PROPOSAL_ID)).thenReturn(VerlaArtifactEditProposal.builder()
                .proposalId(PROPOSAL_ID)
                .conversationId(CONVERSATION_ID)
                .state(VerlaArtifactEditProposal.STATE_REVIEWING)
                .targetsJson(objectMapper.writeValueAsString(List.of(Map.of(
                        "artifactUid", ARTIFACT_UID,
                        "editMode", "review",
                        "baseVersionNo", 3))))
                .changesJson(objectMapper.writeValueAsString(Map.of(ARTIFACT_UID, hunks)))
                .build());
    }

    private Map<String, Object> hunk(String id, int from, int to) {
        return Map.of(
                "id", id,
                "anchor", Map.of(
                        "from", from,
                        "to", to,
                        "originalText", ORIGINAL_TEXT),
                "originalText", ORIGINAL_TEXT,
                "proposedText", PROPOSED_TEXT);
    }

    private void assertUpdatedArtifact(VerlaArtifactEntity artifact, String expectedBody) {
        assertThat(artifact.getBodyOrRef()).isEqualTo(expectedBody);
        assertThat(artifact.getVersion()).isEqualTo(4);
        assertThat(artifact.getSizeBytes())
                .isEqualTo((long) expectedBody.getBytes(StandardCharsets.UTF_8).length);
        assertThat(artifact.getStatus()).isEqualTo("READY");
        assertThat(artifact.getUpdatedAt()).isNotNull();
        verify(artifactMapper).updateById(artifact);
        verify(editorContentMapper).delete(any());
        verify(proposalRepository).markState(PROPOSAL_ID, VerlaArtifactEditProposal.STATE_COMMITTED);
        verify(conversationRepository).incrementVersion(CONVERSATION_ID);
    }
}
