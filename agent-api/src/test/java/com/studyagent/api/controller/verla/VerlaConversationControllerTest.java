package com.studyagent.api.controller.verla;

import com.studyagent.api.common.Result;
import com.studyagent.api.dto.verla.response.VerlaConversationPageVO;
import com.studyagent.api.service.VerlaEditorPreviewService;
import com.studyagent.infra.entity.verla.VerlaArtifactEntity;
import com.studyagent.infra.mapper.verla.VerlaArtifactMapper;
import com.studyagent.service.application.verla.AssignmentRuntimeSnapshotService;
import com.studyagent.service.application.verla.VerlaConversationDashboardStatusService;
import com.studyagent.service.application.verla.VerlaConversationService;
import com.studyagent.service.application.verla.VerlaTurnOrchestrator;
import com.studyagent.service.application.verla.dto.VerlaConversationListSlice;
import com.studyagent.service.domain.verla.VerlaConversation;
import com.studyagent.service.domain.verla.state.ConversationStatus;
import com.studyagent.service.domain.verla.state.IntentLifecycle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VerlaConversationControllerTest {

    private VerlaConversationService conversationService;
    private VerlaConversationDashboardStatusService dashboardStatusService;
    private VerlaArtifactMapper artifactMapper;
    private VerlaConversationController controller;

    @BeforeEach
    void setUp() {
        conversationService = mock(VerlaConversationService.class);
        dashboardStatusService = mock(VerlaConversationDashboardStatusService.class);
        artifactMapper = mock(VerlaArtifactMapper.class);
        controller = new VerlaConversationController(
                conversationService,
                dashboardStatusService,
                mock(VerlaEditorPreviewService.class),
                artifactMapper,
                mock(AssignmentRuntimeSnapshotService.class),
                mock(com.studyagent.service.application.verla.AiWritingRuntimeSnapshotService.class),
                mock(VerlaTurnOrchestrator.class),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                mock(com.studyagent.api.service.legacy.LegacyTaskAdapter.class));
    }

    @Test
    void search_shouldAttachArtifactPreviewKindsForAssignmentConversations() {
        VerlaConversation conversation = assignmentConversation();
        when(conversationService.searchConversations(
                eq("user_1"),
                eq("essay"),
                eq(1),
                eq(20),
                eq(null),
                eq(null)))
                .thenReturn(new VerlaConversationListSlice(List.of(conversation), 1, 1, 20));
        when(dashboardStatusService.resolveAll(List.of(conversation)))
                .thenReturn(Map.of(conversation.getId(), "completed"));
        when(artifactMapper.selectByConversationIds(anyList()))
                .thenReturn(List.of(
                        artifact(conversation.getId(), conversation.getLastTurnId(), "slides_editor_json"),
                        artifact(conversation.getId(), conversation.getLastTurnId(), "document_md"),
                        artifact(conversation.getId(), conversation.getLastTurnId(), "code_python"),
                        artifact(conversation.getId(), conversation.getLastTurnId(), "slides_pptxgenjs")));

        Result<VerlaConversationPageVO> result = controller.search("user_1", "essay", 1, 20, null, null);

        assertThat(result.getData().getRecords()).hasSize(1);
        assertThat(result.getData().getRecords().get(0).getArtifactPreviewKinds())
                .containsExactly("document", "slides", "code");
        verify(artifactMapper).selectByConversationIds(List.of(101L));
    }

    @Test
    void mapperConversationLookup_includesArtifactBodyNeededForPreviewFiltering() {
        assertThat(VerlaArtifactMapper.SELECT_BY_CONVERSATION_IDS_COLUMNS)
                .isEqualTo(VerlaArtifactMapper.ARTIFACT_FULL_COLUMNS)
                .contains("body_or_ref");
    }

    private static VerlaConversation assignmentConversation() {
        LocalDateTime now = LocalDateTime.of(2026, 6, 3, 12, 0);
        return VerlaConversation.builder()
                .id(101L)
                .userId("user_1")
                .title("Essay assignment")
                .status(ConversationStatus.ACTIVE.getDbValue())
                .primaryIntent("ASSIGNMENT")
                .intentLifecycle(IntentLifecycle.COMMITTED.getDbValue())
                .turnCount(3)
                .lastTurnId(201L)
                .lastMessageAt(now)
                .createdAt(now.minusDays(1))
                .updatedAt(now)
                .build();
    }

    private static VerlaArtifactEntity artifact(Long conversationId, Long turnId, String kind) {
        return new VerlaArtifactEntity()
                .setConversationId(conversationId)
                .setTurnId(turnId)
                .setKind(kind)
                .setBodyOrRef("oss://bucket/artifact")
                .setStatus("READY");
    }
}
