package com.studyagent.api.service;

import com.studyagent.api.dto.response.ConsumeTriggerResponse;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.common.verla.id.VerlaPublicIdCodec;
import com.studyagent.common.verla.id.VerlaPublicIdType;
import com.studyagent.infra.entity.FeedbackPromptSessionEntity;
import com.studyagent.infra.entity.HumanizerTaskEntity;
import com.studyagent.infra.entity.verla.VerlaConversationEntity;
import com.studyagent.infra.entity.verla.VerlaSessionEntity;
import com.studyagent.infra.entity.verla.VerlaTurnEntity;
import com.studyagent.infra.mapper.FeedbackPromptSessionMapper;
import com.studyagent.infra.mapper.FeedbackSubmissionMapper;
import com.studyagent.infra.mapper.HumanizerTaskMapper;
import com.studyagent.infra.mapper.TaskMapper;
import com.studyagent.infra.mapper.verla.VerlaConversationMapper;
import com.studyagent.infra.mapper.verla.VerlaSessionMapper;
import com.studyagent.infra.mapper.verla.VerlaTurnMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeedbackApplicationServiceTest {

    private FeedbackPromptSessionMapper promptSessionMapper;
    private TaskMapper taskMapper;
    private HumanizerTaskMapper humanizerTaskMapper;
    private VerlaConversationMapper verlaConversationMapper;
    private VerlaTurnMapper verlaTurnMapper;
    private VerlaSessionMapper verlaSessionMapper;
    private FeedbackApplicationService service;

    @BeforeEach
    void setUp() {
        promptSessionMapper = mock(FeedbackPromptSessionMapper.class);
        taskMapper = mock(TaskMapper.class);
        humanizerTaskMapper = mock(HumanizerTaskMapper.class);
        verlaConversationMapper = mock(VerlaConversationMapper.class);
        verlaTurnMapper = mock(VerlaTurnMapper.class);
        verlaSessionMapper = mock(VerlaSessionMapper.class);
        service = new FeedbackApplicationService(
                promptSessionMapper,
                mock(FeedbackSubmissionMapper.class),
                taskMapper,
                humanizerTaskMapper,
                verlaConversationMapper,
                verlaTurnMapper,
                verlaSessionMapper);
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
        assertThat(response.getVariant()).isEqualTo("rating");
        assertThat(response.getConfigKey()).isEqualTo("task-rating-v1");
        assertThat(response.getSubjectId()).isEqualTo("verla_conversation:101");
    }

    @Test
    void consumeTriggerShouldAcceptEditorStayTriggerForTaskSubject() {
        when(promptSessionMapper.selectOne(any())).thenReturn(null);
        when(verlaConversationMapper.selectById(101L)).thenReturn(new VerlaConversationEntity()
                .setId(101L)
                .setUserId("user_1")
                .setStatus("active"));

        ConsumeTriggerResponse response = service.consumeTrigger(
                "user_1",
                "editor_stay_1min_first",
                "task",
                "verla_conversation:101",
                "/tasks/101/artifacts/document/edit");

        assertThat(response.isShouldPrompt()).isTrue();
        assertThat(response.getTriggerCode()).isEqualTo("editor_stay_1min_first");
        assertThat(response.getConfigKey()).isEqualTo("task-rating-v1");
    }

    @Test
    void consumeTriggerShouldAcceptV2DetectionCompleteOnVerlaConversation() {
        when(promptSessionMapper.selectOne(any())).thenReturn(null);
        String publicId = VerlaPublicIdCodec.encode(VerlaPublicIdType.CONVERSATION, 55L);
        when(verlaConversationMapper.selectById(55L)).thenReturn(new VerlaConversationEntity()
                .setId(55L)
                .setUserId("user_1")
                .setStatus("active")
                .setPrimaryIntent("AI_DETECTION"));

        ConsumeTriggerResponse response = service.consumeTrigger(
                "user_1",
                "detection_complete_first",
                "verla_conversation",
                publicId,
                "ai_detection");

        assertThat(response.isShouldPrompt()).isTrue();
        assertThat(response.getVariant()).isEqualTo("thumb");
        assertThat(response.getConfigKey()).isEqualTo("detection-thumb-v1");
        assertThat(response.getSubjectType()).isEqualTo("verla_conversation");
        assertThat(response.getSubjectId()).isEqualTo(publicId);
    }

    @Test
    void consumeTriggerShouldAcceptV2HumanizerCompleteOnVerlaConversation() {
        when(promptSessionMapper.selectOne(any())).thenReturn(null);
        when(verlaConversationMapper.selectById(66L)).thenReturn(new VerlaConversationEntity()
                .setId(66L)
                .setUserId("user_1")
                .setStatus("active")
                .setPrimaryIntent("AI_HUMANIZER"));

        ConsumeTriggerResponse response = service.consumeTrigger(
                "user_1",
                "humanizer_complete_first",
                "verla_conversation",
                66L,
                "humanizer");

        assertThat(response.isShouldPrompt()).isTrue();
        assertThat(response.getVariant()).isEqualTo("thumb");
        assertThat(response.getConfigKey()).isEqualTo("humanizer-thumb-v1");
    }

    @Test
    void consumeTriggerShouldAcceptVerlaTurnCompleteFirst() {
        when(promptSessionMapper.selectOne(any())).thenReturn(null);
        when(verlaTurnMapper.selectById(7L)).thenReturn(new VerlaTurnEntity()
                .setId(7L)
                .setConversationId(101L));
        when(verlaConversationMapper.selectById(101L)).thenReturn(new VerlaConversationEntity()
                .setId(101L)
                .setUserId("user_1")
                .setStatus("active"));

        String turnPublicId = VerlaPublicIdCodec.encode(VerlaPublicIdType.TURN, 7L);
        ConsumeTriggerResponse response = service.consumeTrigger(
                "user_1",
                "verla_turn_complete_first",
                "verla_turn",
                turnPublicId,
                "assignment");

        assertThat(response.isShouldPrompt()).isTrue();
        assertThat(response.getVariant()).isEqualTo("rating");
        assertThat(response.getConfigKey()).isEqualTo("task-rating-v1");
    }

    @Test
    void consumeTriggerShouldAcceptVerlaAgentSessionCompleteFirst() {
        when(promptSessionMapper.selectOne(any())).thenReturn(null);
        when(verlaSessionMapper.selectById(9L)).thenReturn(new VerlaSessionEntity()
                .setId(9L)
                .setConversationId(101L)
                .setKind("AGENT")
                .setStatus("SUCCEEDED"));
        when(verlaConversationMapper.selectById(101L)).thenReturn(new VerlaConversationEntity()
                .setId(101L)
                .setUserId("user_1")
                .setStatus("active"));

        ConsumeTriggerResponse response = service.consumeTrigger(
                "user_1",
                "verla_agent_session_complete_first",
                "verla_session",
                "9",
                "assignment");

        assertThat(response.isShouldPrompt()).isTrue();
        assertThat(response.getTriggerCode()).isEqualTo("verla_agent_session_complete_first");
    }

    @Test
    void consumeTriggerShouldRejectCrossUserVerlaConversation() {
        when(promptSessionMapper.selectOne(any())).thenReturn(null);
        when(verlaConversationMapper.selectById(55L)).thenReturn(new VerlaConversationEntity()
                .setId(55L)
                .setUserId("other_user")
                .setStatus("active"));

        assertThatThrownBy(() -> service.consumeTrigger(
                "user_1",
                "detection_complete_first",
                "verla_conversation",
                55L,
                "ai_detection"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void consumeTriggerShouldRejectDeletedVerlaConversation() {
        when(promptSessionMapper.selectOne(any())).thenReturn(null);
        when(verlaConversationMapper.selectById(55L)).thenReturn(new VerlaConversationEntity()
                .setId(55L)
                .setUserId("user_1")
                .setStatus("deleted"));

        assertThatThrownBy(() -> service.consumeTrigger(
                "user_1",
                "humanizer_complete_first",
                "verla_conversation",
                55L,
                "humanizer"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void consumeTriggerShouldAcceptLegacyHumanizerTaskSubject() {
        when(promptSessionMapper.selectOne(any())).thenReturn(null);
        HumanizerTaskEntity humanizerTask = new HumanizerTaskEntity();
        humanizerTask.setId(321L);
        humanizerTask.setClerkUserId("user_1");
        when(humanizerTaskMapper.selectById(321L)).thenReturn(humanizerTask);

        ConsumeTriggerResponse response = service.consumeTrigger(
                "user_1",
                "detection_complete_first",
                "humanizer_task",
                321L,
                "humanizer");

        assertThat(response.isShouldPrompt()).isTrue();
        assertThat(response.getVariant()).isEqualTo("thumb");
        assertThat(response.getConfigKey()).isEqualTo("detection-thumb-v1");

        ArgumentCaptor<FeedbackPromptSessionEntity> sessionCaptor =
                ArgumentCaptor.forClass(FeedbackPromptSessionEntity.class);
        verify(promptSessionMapper).insert(sessionCaptor.capture());
        assertThat(sessionCaptor.getValue().getSubjectType()).isEqualTo("humanizer_task");
    }

    @Test
    void consumeTriggerShouldNotPromptWhenAlreadyConsumed() {
        FeedbackPromptSessionEntity existingSession = new FeedbackPromptSessionEntity();
        existingSession.setPromptSessionId("fps_existing");
        existingSession.setClerkUserId("user_1");
        when(promptSessionMapper.selectOne(any())).thenReturn(existingSession);

        ConsumeTriggerResponse response = service.consumeTrigger(
                "user_1",
                "task_download_first",
                "verla_conversation",
                101L,
                "assignment");

        assertThat(response.isShouldPrompt()).isFalse();
        assertThat(response.getPromptSessionId()).isNull();
    }
}
