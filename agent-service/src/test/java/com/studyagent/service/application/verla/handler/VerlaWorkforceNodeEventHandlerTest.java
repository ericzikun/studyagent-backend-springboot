package com.studyagent.service.application.verla.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.verla.enums.VerlaAgentEventType;
import com.studyagent.common.verla.envelope.VerlaEventEnvelope;
import com.studyagent.service.domain.verla.VerlaEventInbox;
import com.studyagent.service.domain.verla.VerlaWorkforceTask;
import com.studyagent.service.domain.verla.VerlaWorkforceTaskOutput;
import com.studyagent.service.domain.verla.repo.VerlaWorkforceTaskOutputRepository;
import com.studyagent.service.domain.verla.repo.VerlaWorkforceTaskRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VerlaWorkforceNodeEventHandlerTest {

    @Mock
    private VerlaWorkforceTaskRepository taskRepository;
    @Mock
    private VerlaWorkforceTaskOutputRepository taskOutputRepository;

    @Test
    void handleNodeDetailedPersistsDurationIntoTaskSnapshot() {
        VerlaWorkforceNodeEventHandler handler = new VerlaWorkforceNodeEventHandler(
                taskRepository,
                taskOutputRepository,
                new ObjectMapper());
        when(taskOutputRepository.upsertBySessionNode(any(VerlaWorkforceTaskOutput.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(taskRepository.upsertBySessionNode(any(VerlaWorkforceTask.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        handler.handle(
                VerlaEventInbox.builder()
                        .conversationId(42L)
                        .turnId(43L)
                        .sessionId(44L)
                        .eventSeq(7L)
                        .build(),
                VerlaEventEnvelope.builder()
                        .eventType(VerlaAgentEventType.ASSIGNMENT_AGENT_NODE_DETAILED.name())
                        .payload(Map.of(
                                "id", "task-research",
                                "status", "COMPLETED",
                                "taskName", "Research",
                                "taskAgent", "Problem Solving Expert",
                                "detailChunk", List.of(Map.of("type", "search")),
                                "contentChunk", "Recovered output",
                                "durationMs", 12_345))
                        .build());

        ArgumentCaptor<VerlaWorkforceTask> taskCaptor = ArgumentCaptor.forClass(VerlaWorkforceTask.class);
        verify(taskRepository).upsertBySessionNode(taskCaptor.capture());

        VerlaWorkforceTask patch = taskCaptor.getValue();
        assertEquals(42L, patch.getConversationId());
        assertEquals(43L, patch.getTurnId());
        assertEquals(44L, patch.getSessionId());
        assertEquals("task-research", patch.getNodeId());
        assertEquals("task", patch.getNodeKind());
        assertEquals("COMPLETED", patch.getStatus());
        assertEquals(12_345, patch.getProcessingTimeMs());
    }
}
