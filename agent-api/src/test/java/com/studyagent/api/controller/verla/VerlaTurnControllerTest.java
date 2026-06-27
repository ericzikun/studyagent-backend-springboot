package com.studyagent.api.controller.verla;

import com.studyagent.api.dto.verla.response.VerlaArtifactVO;
import com.studyagent.api.service.legacy.LegacyTaskAdapter;
import com.studyagent.common.verla.id.LegacyConversationIdCodec;
import com.studyagent.service.application.verla.VerlaConversationService;
import com.studyagent.service.domain.verla.repo.VerlaArtifactRepository;
import com.studyagent.service.domain.verla.repo.VerlaSessionRepository;
import com.studyagent.service.domain.verla.repo.VerlaTurnRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VerlaTurnControllerTest {

    private VerlaConversationService conversationService;
    private VerlaArtifactRepository artifactRepository;
    private LegacyTaskAdapter legacyTaskAdapter;
    private VerlaTurnController controller;

    @BeforeEach
    void setUp() {
        conversationService = mock(VerlaConversationService.class);
        artifactRepository = mock(VerlaArtifactRepository.class);
        legacyTaskAdapter = mock(LegacyTaskAdapter.class);
        controller = new VerlaTurnController(
                conversationService,
                mock(VerlaTurnRepository.class),
                mock(VerlaSessionRepository.class),
                artifactRepository,
                legacyTaskAdapter);
    }

    @Test
    void listArtifactsOfConversation_shouldUseLegacyAdapterForLegacyConversation() {
        long legacyTaskId = 24L;
        long legacyConversationId = LegacyConversationIdCodec.encode(legacyTaskId);
        VerlaArtifactVO artifact = VerlaArtifactVO.builder()
                .artifactUid("legacy_task_24_out_1")
                .source("LEGACY_1_0")
                .build();
        when(legacyTaskAdapter.buildArtifacts(legacyTaskId)).thenReturn(List.of(artifact));

        List<VerlaArtifactVO> artifacts = controller.listArtifactsOfConversation(
                "user_1",
                legacyConversationId).getData();

        assertThat(artifacts).containsExactly(artifact);
        verify(legacyTaskAdapter).requireOwnedCompleted("user_1", legacyTaskId);
        verify(legacyTaskAdapter).buildArtifacts(legacyTaskId);
        verify(conversationService, never()).getOwned("user_1", legacyConversationId);
        verify(artifactRepository, never()).findByConversation(legacyConversationId);
    }
}
