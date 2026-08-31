package com.studyagent.api.controller.verla;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.api.common.Result;
import com.studyagent.api.dto.verla.request.SaveVerlaHumanizerDocumentEditRequest;
import com.studyagent.api.dto.verla.response.SaveVerlaHumanizerDocumentEditResponseVO;
import com.studyagent.api.dto.verla.response.VerlaHumanizerDocumentEditResponseVO;
import com.studyagent.infra.entity.verla.VerlaArtifactEntity;
import com.studyagent.infra.entity.verla.VerlaEditorContentEntity;
import com.studyagent.infra.mapper.verla.VerlaArtifactMapper;
import com.studyagent.infra.mapper.verla.VerlaEditorContentMapper;
import com.studyagent.service.application.verla.VerlaConversationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VerlaHumanizerDocumentEditControllerTest {

    private static final Long CONVERSATION_ID = 24L;
    private static final String ARTIFACT_UID = "artifact_abc123";
    // A nonblank Clerk id satisfies the controller's ensureLogin precondition.
    private static final String USER_ID = "user_1";

    private VerlaConversationService conversationService;
    private VerlaArtifactMapper artifactMapper;
    private VerlaEditorContentMapper editorContentMapper;
    private VerlaHumanizerDocumentEditController controller;

    @BeforeEach
    void setUp() {
        conversationService = mock(VerlaConversationService.class);
        artifactMapper = mock(VerlaArtifactMapper.class);
        editorContentMapper = mock(VerlaEditorContentMapper.class);
        controller = new VerlaHumanizerDocumentEditController(
                conversationService,
                artifactMapper,
                editorContentMapper,
                new ObjectMapper());
    }

    @Test
    void get_whenNoSavedEdit_shouldReturnExistsFalse() {
        givenOwnedArtifact();
        when(editorContentMapper.selectOne(any())).thenReturn(null);

        Result<VerlaHumanizerDocumentEditResponseVO> result = controller.getDocumentEdit(
                USER_ID, CONVERSATION_ID, ARTIFACT_UID);

        assertThat(result.getData().getExists()).isFalse();
        assertThat(result.getData().getContent()).isNull();
        assertThat(result.getData().getUpdatedAt()).isNull();
        verify(conversationService).getOwned(USER_ID, CONVERSATION_ID);
        verify(artifactMapper, times(1)).selectByUid(ARTIFACT_UID);
    }

    @Test
    void get_whenSavedEdit_shouldReturnContentAndUpdatedAt() {
        givenOwnedArtifact();
        LocalDateTime updatedAt = LocalDateTime.of(2026, 8, 31, 10, 30, 0);
        when(editorContentMapper.selectOne(any())).thenReturn(new VerlaEditorContentEntity()
                .setId(1L)
                .setConversationId(CONVERSATION_ID)
                .setSourceArtifactUid(ARTIFACT_UID)
                .setEditorKind("humanizer")
                .setContentJson("{\"chunks\":[{\"id\":\"chunk-1\",\"status\":\"humanized\"}]}")
                .setUpdatedAt(updatedAt));

        Result<VerlaHumanizerDocumentEditResponseVO> result = controller.getDocumentEdit(
                USER_ID, CONVERSATION_ID, ARTIFACT_UID);

        assertThat(result.getData().getExists()).isTrue();
        assertThat(result.getData().getContent()).containsKey("chunks");
        assertThat(result.getData().getUpdatedAt()).isEqualTo(updatedAt);
    }

    @Test
    void save_whenNoExistingRow_shouldInsertNewRow() {
        givenOwnedArtifact();
        when(editorContentMapper.selectOne(any())).thenReturn(null);
        SaveVerlaHumanizerDocumentEditRequest request = new SaveVerlaHumanizerDocumentEditRequest();
        request.setContent(Map.of("chunks", List.of(Map.of("id", "chunk-1"))));

        Result<SaveVerlaHumanizerDocumentEditResponseVO> result = controller.saveDocumentEdit(
                USER_ID, CONVERSATION_ID, ARTIFACT_UID, request);

        assertThat(result.getData().getSaved()).isTrue();
        assertThat(result.getData().getUpdatedAt()).isNotNull();
        verify(editorContentMapper, times(1)).insert(any(VerlaEditorContentEntity.class));
        verify(editorContentMapper, never()).updateById(any(VerlaEditorContentEntity.class));
        verify(conversationService).touchActivity(USER_ID, CONVERSATION_ID);
    }

    @Test
    void save_whenExistingRow_shouldUpdateRow() {
        givenOwnedArtifact();
        VerlaEditorContentEntity existing = new VerlaEditorContentEntity()
                .setId(7L)
                .setConversationId(CONVERSATION_ID)
                .setSourceArtifactUid(ARTIFACT_UID)
                .setEditorKind("humanizer")
                .setContentJson("{\"chunks\":[]}")
                .setUpdatedAt(LocalDateTime.now());
        when(editorContentMapper.selectOne(any())).thenReturn(existing);
        SaveVerlaHumanizerDocumentEditRequest request = new SaveVerlaHumanizerDocumentEditRequest();
        request.setContent(Map.of("chunks", List.of(Map.of("id", "chunk-2"))));

        Result<SaveVerlaHumanizerDocumentEditResponseVO> result = controller.saveDocumentEdit(
                USER_ID, CONVERSATION_ID, ARTIFACT_UID, request);

        assertThat(result.getData().getSaved()).isTrue();
        verify(editorContentMapper, never()).insert(any(VerlaEditorContentEntity.class));
        verify(editorContentMapper, times(1)).updateById(existing);
        verify(conversationService).touchActivity(USER_ID, CONVERSATION_ID);
    }

    private void givenOwnedArtifact() {
        when(artifactMapper.selectByUid(ARTIFACT_UID)).thenReturn(new VerlaArtifactEntity()
                .setArtifactUid(ARTIFACT_UID)
                .setConversationId(CONVERSATION_ID));
    }
}
