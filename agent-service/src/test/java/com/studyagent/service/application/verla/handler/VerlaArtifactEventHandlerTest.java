package com.studyagent.service.application.verla.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.verla.enums.VerlaAgentEventType;
import com.studyagent.common.verla.envelope.VerlaEventEnvelope;
import com.studyagent.service.application.verla.VerlaAttachmentService;
import com.studyagent.service.domain.verla.VerlaArtifact;
import com.studyagent.service.domain.verla.VerlaEventInbox;
import com.studyagent.service.domain.verla.repo.VerlaArtifactRepository;
import com.studyagent.service.domain.verla.repo.VerlaAttachmentRepository;
import com.studyagent.service.domain.verla.repo.VerlaConversationRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class VerlaArtifactEventHandlerTest {

    @Test
    void handle_persists_pptxgenjs_source_without_post_completion_convert_queue() {
        VerlaArtifactRepository artifactRepository = mock(VerlaArtifactRepository.class);
        VerlaAttachmentRepository attachmentRepository = mock(VerlaAttachmentRepository.class);
        VerlaAttachmentService attachmentService = mock(VerlaAttachmentService.class);
        VerlaConversationRepository conversationRepository = mock(VerlaConversationRepository.class);
        when(artifactRepository.upsertByUid(any(VerlaArtifact.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        VerlaArtifactEventHandler handler = new VerlaArtifactEventHandler(
                artifactRepository,
                attachmentRepository,
                attachmentService,
                conversationRepository,
                new ObjectMapper());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("artifactUid", "artifact_11_22_33_slides_source");
        payload.put("kind", "assignment_slides_pptxgenjs");
        payload.put("mime", "text/plain");
        payload.put("status", "READY");
        payload.put("summary", "deck.js");
        payload.put("body", "module.exports = {};");
        payload.put("meta", Map.of("role", "source", "content_kind", "PPT"));

        VerlaEventInbox row = VerlaEventInbox.builder()
                .conversationId(11L)
                .turnId(22L)
                .sessionId(33L)
                .eventSeq(7L)
                .eventType(VerlaAgentEventType.ASSIGNMENT_AGENT_FLOW_ARTIFACT_UPDATED.name())
                .build();
        VerlaEventEnvelope env = VerlaEventEnvelope.builder()
                .eventType(VerlaAgentEventType.ASSIGNMENT_AGENT_FLOW_ARTIFACT_UPDATED.name())
                .payload(payload)
                .build();

        handler.handle(row, env);

        ArgumentCaptor<VerlaArtifact> artifactCaptor = ArgumentCaptor.forClass(VerlaArtifact.class);
        verify(artifactRepository).upsertByUid(artifactCaptor.capture());
        VerlaArtifact saved = artifactCaptor.getValue();
        assertEquals("artifact_11_22_33_slides_source", saved.getArtifactUid());
        assertEquals("assignment_slides_pptxgenjs", saved.getKind());
        assertEquals("deck.js", saved.getSummary());
        verify(conversationRepository).incrementVersion(11L);
        verifyNoInteractions(attachmentRepository, attachmentService);
    }
}
