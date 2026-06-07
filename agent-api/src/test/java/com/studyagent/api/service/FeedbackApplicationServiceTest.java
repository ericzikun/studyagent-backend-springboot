package com.studyagent.api.service;

import com.studyagent.api.dto.response.ConsumeTriggerResponse;
import com.studyagent.infra.entity.FeedbackPromptSessionEntity;
import com.studyagent.infra.entity.verla.VerlaConversationEntity;
import com.studyagent.infra.mapper.FeedbackPromptSessionMapper;
import com.studyagent.infra.mapper.FeedbackSubmissionMapper;
import com.studyagent.infra.mapper.HumanizerTaskMapper;
import com.studyagent.infra.mapper.TaskMapper;
import com.studyagent.infra.mapper.verla.VerlaConversationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeedbackApplicationServiceTest {

    private FeedbackPromptSessionMapper promptSessionMapper;
    private TaskMapper taskMapper;
    private VerlaConversationMapper verlaConversationMapper;
    private FeedbackApplicationService service;

    @BeforeEach
    void setUp() {
        promptSessionMapper = mock(FeedbackPromptSessionMapper.class);
        taskMapper = mock(TaskMapper.class);
        verlaConversationMapper = mock(VerlaConversationMapper.class);
        service = new FeedbackApplicationService(
                promptSessionMapper,
                mock(FeedbackSubmissionMapper.class),
                taskMapper,
                mock(HumanizerTaskMapper.class),
                verlaConversationMapper);
    }

    @Test
    void consumeTriggerShouldAcceptOwnedVerlaConversationTaskSubject() {
        when(promptSessionMapper.selectOne(any())).thenReturn(null);
        when(verlaConversationMapper.selectById(101L)).thenReturn(new VerlaConversationEntity()
                .setId(101L)
                .setUserId("user_1")
                .setStatus("active"));

        ConsumeTriggerResponse response = service.consumeTrigger(
                "user_1",
                "task_download_first",
                "task",
                "verla_conversation:101",
                "/tasks/101/artifacts/document/edit");

        assertThat(response.isShouldPrompt()).isTrue();
        assertThat(response.getTriggerCode()).isEqualTo("task_download_first");
        assertThat(response.getSubjectType()).isEqualTo("task");
        assertThat(response.getSubjectId()).isEqualTo("verla_conversation:101");
        assertThat(response.getPromptSessionId()).startsWith("fps_");

        ArgumentCaptor<FeedbackPromptSessionEntity> sessionCaptor =
                ArgumentCaptor.forClass(FeedbackPromptSessionEntity.class);
        verify(promptSessionMapper).insert(sessionCaptor.capture());
        FeedbackPromptSessionEntity storedSession = sessionCaptor.getValue();
        assertThat(storedSession.getClerkUserId()).isEqualTo("user_1");
        assertThat(storedSession.getSubjectType()).isEqualTo("task");
        assertThat(storedSession.getSubjectId()).isEqualTo("verla_conversation:101");
        assertThat(storedSession.getTriggerCode()).isEqualTo("task_download_first");
        assertThat(storedSession.getConfigKey()).isEqualTo("task-rating-v1");
    }
}
