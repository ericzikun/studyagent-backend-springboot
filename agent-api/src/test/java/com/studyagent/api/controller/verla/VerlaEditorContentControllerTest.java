package com.studyagent.api.controller.verla;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.api.common.Result;
import com.studyagent.api.dto.verla.request.SaveVerlaEditorContentRequest;
import com.studyagent.api.dto.verla.response.SaveVerlaEditorContentResponseVO;
import com.studyagent.api.dto.verla.response.VerlaEditorContentResponseVO;
import com.studyagent.infra.entity.verla.VerlaArtifactEntity;
import com.studyagent.infra.entity.verla.VerlaEditorContentEntity;
import com.studyagent.infra.mapper.verla.VerlaArtifactMapper;
import com.studyagent.infra.mapper.verla.VerlaEditorContentMapper;
import com.studyagent.infra.mapper.verla.VerlaEditorContentVersionMapper;
import com.studyagent.service.application.verla.VerlaConversationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VerlaEditorContentControllerTest {

    private static final Long CONVERSATION_ID = 24L;
    private static final String ARTIFACT_UID = "art_doc";
    // A nonblank Clerk id satisfies the controller's ensureLogin precondition.
    private static final String USER_ID = "user_1";
    private static final String ARTIFACT_SUMMARY = "Quarterly project report";

    private VerlaArtifactMapper artifactMapper;
    private VerlaEditorContentMapper editorContentMapper;
    private VerlaEditorContentController controller;

    @BeforeEach
    void setUp() {
        VerlaConversationService conversationService = mock(VerlaConversationService.class);
        artifactMapper = mock(VerlaArtifactMapper.class);
        editorContentMapper = mock(VerlaEditorContentMapper.class);
        VerlaEditorContentVersionMapper versionMapper = mock(VerlaEditorContentVersionMapper.class);
        controller = new VerlaEditorContentController(
                conversationService,
                artifactMapper,
                editorContentMapper,
                versionMapper,
                new ObjectMapper());
    }

    @Test
    void getDocument_shouldRepairLegacyUntitledFromOwnedArtifactSummary() {
        VerlaEditorContentEntity editorContent = new VerlaEditorContentEntity()
                .setId(91L)
                .setConversationId(CONVERSATION_ID)
                .setSourceArtifactUid(ARTIFACT_UID)
                .setEditorKind("document")
                .setTitle("  uNtItLeD  ")
                .setContentJson("{\"body\":\"Draft\"}");
        givenArtifact(ARTIFACT_SUMMARY);
        when(editorContentMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(editorContent);
        when(editorContentMapper.update(
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.any())).thenReturn(1);

        Result<VerlaEditorContentResponseVO> result = controller.getEditorContent(
                USER_ID, CONVERSATION_ID, ARTIFACT_UID, "document");

        assertThat(result.getData().getTitle()).isEqualTo(ARTIFACT_SUMMARY);
        assertThat(editorContent.getTitle()).isEqualTo(ARTIFACT_SUMMARY);
        verify(editorContentMapper).update(
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.any());
        verify(editorContentMapper, never()).updateById(editorContent);
        verify(artifactMapper, times(1)).selectByUid(ARTIFACT_UID);
    }

    @Test
    void getDocument_shouldNotRepairLegacyTitleWhenContentCannotBeParsed() {
        VerlaEditorContentEntity editorContent = new VerlaEditorContentEntity()
                .setId(94L)
                .setConversationId(CONVERSATION_ID)
                .setSourceArtifactUid(ARTIFACT_UID)
                .setEditorKind("document")
                .setTitle("Untitled")
                .setContentJson("not-json");
        givenArtifact(ARTIFACT_SUMMARY);
        when(editorContentMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(editorContent);

        Result<VerlaEditorContentResponseVO> result = controller.getEditorContent(
                USER_ID, CONVERSATION_ID, ARTIFACT_UID, "document");

        assertThat(result.getData().getParseError()).isTrue();
        assertThat(result.getData().getTitle()).isEqualTo("Untitled");
        verify(editorContentMapper, never()).update(
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.any());
        verify(editorContentMapper, never()).updateById(editorContent);
    }

    @Test
    void getNonDocument_shouldNotRepairUntitledTitle() {
        VerlaEditorContentEntity editorContent = new VerlaEditorContentEntity()
                .setId(92L)
                .setConversationId(CONVERSATION_ID)
                .setSourceArtifactUid(ARTIFACT_UID)
                .setEditorKind("slides")
                .setTitle(" Untitled ")
                .setContentJson("{\"slides\":[]}");
        givenArtifact(ARTIFACT_SUMMARY);
        when(editorContentMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(editorContent);

        Result<VerlaEditorContentResponseVO> result = controller.getEditorContent(
                USER_ID, CONVERSATION_ID, ARTIFACT_UID, "slides");

        assertThat(result.getData().getTitle()).isEqualTo(" Untitled ");
        verify(editorContentMapper, never()).updateById(editorContent);
    }

    @Test
    void getDocument_shouldNotReplaceAnExplicitTitle() {
        VerlaEditorContentEntity editorContent = new VerlaEditorContentEntity()
                .setId(93L)
                .setConversationId(CONVERSATION_ID)
                .setSourceArtifactUid(ARTIFACT_UID)
                .setEditorKind("document")
                .setTitle("User selected title")
                .setContentJson("{\"body\":\"Draft\"}");
        givenArtifact(ARTIFACT_SUMMARY);
        when(editorContentMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(editorContent);

        Result<VerlaEditorContentResponseVO> result = controller.getEditorContent(
                USER_ID, CONVERSATION_ID, ARTIFACT_UID, "document");

        assertThat(result.getData().getTitle()).isEqualTo("User selected title");
        verify(editorContentMapper, never()).updateById(editorContent);
    }

    @Test
    void saveNewDocument_shouldUseArtifactSummaryWhenTitleIsBlank() {
        givenArtifact(ARTIFACT_SUMMARY);
        when(editorContentMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(null);
        SaveVerlaEditorContentRequest request = new SaveVerlaEditorContentRequest();
        request.setTitle("  ");
        request.setContent(Map.of("body", "Draft"));

        Result<SaveVerlaEditorContentResponseVO> result = controller.saveEditorContent(
                USER_ID, CONVERSATION_ID, ARTIFACT_UID, "document", request);

        assertThat(result.getData().getTitle()).isEqualTo(ARTIFACT_SUMMARY);
        verify(artifactMapper, times(1)).selectByUid(ARTIFACT_UID);
    }

    @Test
    void saveNewDocument_shouldPreserveExplicitUserTitle() {
        givenArtifact(ARTIFACT_SUMMARY);
        when(editorContentMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(null);
        SaveVerlaEditorContentRequest request = new SaveVerlaEditorContentRequest();
        request.setTitle("  User selected title  ");
        request.setContent(Map.of("body", "Draft"));

        Result<SaveVerlaEditorContentResponseVO> result = controller.saveEditorContent(
                USER_ID, CONVERSATION_ID, ARTIFACT_UID, "document", request);

        assertThat(result.getData().getTitle()).isEqualTo("User selected title");
    }

    private void givenArtifact(String summary) {
        when(artifactMapper.selectByUid(ARTIFACT_UID)).thenReturn(new VerlaArtifactEntity()
                .setArtifactUid(ARTIFACT_UID)
                .setConversationId(CONVERSATION_ID)
                .setSummary(summary));
    }
}
