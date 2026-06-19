package com.studyagent.api.service;

import com.studyagent.api.dto.response.HumanizerSubmitResult;
import com.studyagent.api.dto.response.HumanizerTaskResponse;
import com.studyagent.common.quota.FeatureCode;
import com.studyagent.infra.entity.HumanizerTaskEntity;
import com.studyagent.infra.repository.humanizer.HumanizerTaskRepositoryImpl;
import com.studyagent.service.application.verla.HumanizerTaskNameDispatcher;
import com.studyagent.service.domain.humanizer.HumanizerServiceClient;
import com.studyagent.service.domain.quota.ConsumeResult;
import com.studyagent.service.domain.quota.QuotaBalance;
import com.studyagent.service.domain.quota.QuotaDomainService;
import com.studyagent.service.domain.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HumanizerApplicationServiceTest {

    private HumanizerTaskRepositoryImpl repository;
    private QuotaDomainService quotaDomainService;
    private UserRepository userRepository;
    private HumanizerServiceClient humanizerServiceClient;
    private HumanizerTaskNameDispatcher humanizerTaskNameDispatcher;
    private PaymentResumeContextService paymentResumeContextService;
    private HumanizerApplicationService service;

    @BeforeEach
    void setUp() {
        repository = mock(HumanizerTaskRepositoryImpl.class);
        quotaDomainService = mock(QuotaDomainService.class);
        userRepository = mock(UserRepository.class);
        humanizerServiceClient = mock(HumanizerServiceClient.class);
        humanizerTaskNameDispatcher = mock(HumanizerTaskNameDispatcher.class);
        paymentResumeContextService = mock(PaymentResumeContextService.class);
        service = new HumanizerApplicationService(
                repository,
                quotaDomainService,
                userRepository,
                humanizerServiceClient,
                humanizerTaskNameDispatcher,
                paymentResumeContextService);
        ReflectionTestUtils.setField(service, "whitelistUserIds", List.of());
        when(userRepository.findByClerkUserId(anyString())).thenReturn(Optional.empty());
        when(repository.countQueueAhead(anyString(), any())).thenReturn(0);
        doAnswer(invocation -> {
            HumanizerTaskEntity entity = invocation.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(100L);
            }
            return null;
        }).when(repository).insert(any(HumanizerTaskEntity.class));
    }

    @Test
    void submitHumanize_chargesExactlyOnePerRun() {
        when(quotaDomainService.canConsume("user_1", FeatureCode.HUMANIZER.getCode(), 1L))
                .thenReturn(true);
        when(quotaDomainService.consume(
                eq("user_1"),
                eq(FeatureCode.HUMANIZER.getCode()),
                eq(1L),
                eq("humanizer_task"),
                eq("100"),
                any(),
                eq("humanizer:100:start")))
                .thenReturn(new ConsumeResult(321L));

        HumanizerSubmitResult result = service.submitTask(
                "user_1",
                "HUMANIZE",
                "This is a fairly long humanizer text with many words.",
                "HUMANIZER_PAGE");

        assertThat(result.quotaConsumed()).isTrue();
        assertThat(result.response().getStatus()).isEqualTo("PENDING");

        ArgumentCaptor<HumanizerTaskEntity> insertCaptor = ArgumentCaptor.forClass(HumanizerTaskEntity.class);
        ArgumentCaptor<HumanizerTaskEntity> updateCaptor = ArgumentCaptor.forClass(HumanizerTaskEntity.class);
        ArgumentCaptor<Map<String, Object>> bizCaptor = ArgumentCaptor.forClass(Map.class);
        verify(repository).insert(insertCaptor.capture());
        verify(repository).updateById(updateCaptor.capture());
        verify(quotaDomainService).canConsume("user_1", FeatureCode.HUMANIZER.getCode(), 1L);
        verify(quotaDomainService).consume(
                eq("user_1"),
                eq(FeatureCode.HUMANIZER.getCode()),
                eq(1L),
                eq("humanizer_task"),
                eq("100"),
                bizCaptor.capture(),
                eq("humanizer:100:start"));
        assertThat(insertCaptor.getValue().getStatus()).isEqualTo("CHARGING");
        assertThat(updateCaptor.getValue().getStatus()).isEqualTo("PENDING");
        assertThat(updateCaptor.getValue().getQuotaLedgerId()).isEqualTo(321L);
        assertThat(bizCaptor.getValue())
                .containsEntry("task_type", "HUMANIZE")
                .containsEntry("task_id", 100L)
                .containsEntry("charged_mode", "per_run");
    }

    @Test
    void submitHumanize_marksQuotaExhausted_whenConsumeFailsAfterInsert() {
        when(quotaDomainService.canConsume("user_1", FeatureCode.HUMANIZER.getCode(), 1L))
                .thenReturn(true);
        doThrow(new RuntimeException("quota race")).when(quotaDomainService).consume(
                eq("user_1"),
                eq(FeatureCode.HUMANIZER.getCode()),
                eq(1L),
                eq("humanizer_task"),
                eq("100"),
                any(),
                eq("humanizer:100:start"));

        HumanizerSubmitResult result = service.submitTask(
                "user_1",
                "HUMANIZE",
                "This is a fairly long humanizer text with many words.",
                "HUMANIZER_PAGE");

        ArgumentCaptor<HumanizerTaskEntity> insertCaptor = ArgumentCaptor.forClass(HumanizerTaskEntity.class);
        ArgumentCaptor<HumanizerTaskEntity> updateCaptor = ArgumentCaptor.forClass(HumanizerTaskEntity.class);
        verify(repository).insert(insertCaptor.capture());
        verify(repository).updateById(updateCaptor.capture());

        assertThat(result.quotaConsumed()).isFalse();
        assertThat(result.response().getStatus()).isEqualTo("QUOTA_EXHAUSTED");
        assertThat(insertCaptor.getValue().getStatus()).isEqualTo("CHARGING");
        assertThat(updateCaptor.getValue().getStatus()).isEqualTo("QUOTA_EXHAUSTED");
    }

    @Test
    void submitHumanize_returnsResumeToken_whenQuotaExhaustedAtSubmission() {
        when(quotaDomainService.canConsume("user_1", FeatureCode.HUMANIZER.getCode(), 1L))
                .thenReturn(false);
        when(paymentResumeContextService.createHumanizerResumeContext(
                "user_1",
                "humanizer_start",
                100L,
                "humanizer:100:start"))
                .thenReturn("resume_humanize_100");

        HumanizerSubmitResult result = service.submitTask(
                "user_1",
                "HUMANIZE",
                "This is a fairly long humanizer text with many words.",
                "HUMANIZER_PAGE");

        assertThat(result.quotaConsumed()).isFalse();
        assertThat(result.response().getStatus()).isEqualTo("QUOTA_EXHAUSTED");
        assertThat(result.response().getResumeToken()).isEqualTo("resume_humanize_100");
        verify(paymentResumeContextService).createHumanizerResumeContext(
                "user_1",
                "humanizer_start",
                100L,
                "humanizer:100:start");
    }

    @Test
    void submitDetect_returnsResumeToken_whenQuotaExhaustedAtSubmission() {
        when(humanizerServiceClient.splitSentences(anyString()))
                .thenReturn(HumanizerServiceClient.SplitSentencesResult.builder()
                        .code(500)
                        .build());
        when(quotaDomainService.getUserQuota("user_1", FeatureCode.AI_DETECTION.getCode()))
                .thenReturn(balance(0L));
        when(paymentResumeContextService.createHumanizerResumeContext(
                "user_1",
                "detection_start",
                100L,
                "humanizer:100:start"))
                .thenReturn("resume_detect_100");

        HumanizerSubmitResult result = service.submitTask(
                "user_1",
                "DETECT",
                "This detect request still only costs one launch.",
                "HUMANIZER_PAGE");

        assertThat(result.quotaConsumed()).isFalse();
        assertThat(result.response().getStatus()).isEqualTo("QUOTA_EXHAUSTED");
        assertThat(result.response().getResumeToken()).isEqualTo("resume_detect_100");
        verify(paymentResumeContextService).createHumanizerResumeContext(
                "user_1",
                "detection_start",
                100L,
                "humanizer:100:start");
    }

    @Test
    void submitDetect_allowsSingleAvailableCount_evenWhenFirstChunkHasManyWords() {
        when(humanizerServiceClient.splitSentences(anyString()))
                .thenReturn(HumanizerServiceClient.SplitSentencesResult.builder()
                        .code(200)
                        .totalChunks(2)
                        .totalWords(9)
                        .chunks(List.of(
                                HumanizerServiceClient.ChunkInfo.builder()
                                        .index(1)
                                        .sentence("A long first sentence")
                                        .wordCount(9)
                                        .build()))
                        .build());
        when(quotaDomainService.getUserQuota("user_1", FeatureCode.AI_DETECTION.getCode()))
                .thenReturn(balance(1L));

        HumanizerSubmitResult result = service.submitTask(
                "user_1",
                "DETECT",
                "This detect request still only costs one launch.",
                "HUMANIZER_PAGE");

        assertThat(result.quotaConsumed()).isFalse();
        assertThat(result.response().getStatus()).isEqualTo("PENDING");
        verify(quotaDomainService, never()).consume(
                anyString(), anyString(), anyLong(), anyString(), any(), any());
    }

    @Test
    void resumeHumanize_chargesExactlyOnePerRun() {
        HumanizerTaskEntity exhausted = new HumanizerTaskEntity();
        exhausted.setId(1L);
        exhausted.setClerkUserId("user_1");
        exhausted.setTaskType("HUMANIZE");
        exhausted.setStatus("QUOTA_EXHAUSTED");
        exhausted.setTotalWords(9);

        HumanizerTaskEntity resumed = new HumanizerTaskEntity();
        resumed.setId(1L);
        resumed.setClerkUserId("user_1");
        resumed.setTaskType("HUMANIZE");
        resumed.setStatus("PENDING");

        when(repository.findById(1L)).thenReturn(exhausted, resumed);
        when(quotaDomainService.getUserQuota("user_1", FeatureCode.HUMANIZER.getCode()))
                .thenReturn(balance(1L));
        when(quotaDomainService.consume(
                eq("user_1"),
                eq(FeatureCode.HUMANIZER.getCode()),
                eq(1L),
                eq("humanizer_task"),
                eq("1"),
                any(),
                eq("humanizer:1:start")))
                .thenReturn(new ConsumeResult(999L));

        HumanizerTaskResponse response = service.resumeTask(1L, "user_1");

        assertThat(response.getStatus()).isEqualTo("PENDING");
        verify(quotaDomainService).consume(
                eq("user_1"),
                eq(FeatureCode.HUMANIZER.getCode()),
                eq(1L),
                eq("humanizer_task"),
                eq("1"),
                any(),
                eq("humanizer:1:start"));
    }

    @Test
    void getTask_mapsChargingToPending_forExternalResponse() {
        HumanizerTaskEntity charging = new HumanizerTaskEntity();
        charging.setId(1L);
        charging.setClerkUserId("user_1");
        charging.setTaskType("HUMANIZE");
        charging.setInputText("hello world");
        charging.setStatus("CHARGING");
        charging.setCreatedAt(com.studyagent.common.datetime.DateTimeFormats.now());

        when(repository.findById(1L)).thenReturn(charging);

        HumanizerTaskResponse response = service.getTask(1L, "user_1");

        assertThat(response.getStatus()).isEqualTo("PENDING");
        assertThat(response.getProgress()).isEqualTo(0);
    }

    @Test
    void cancelTask_allowsChargingTask() {
        HumanizerTaskEntity charging = new HumanizerTaskEntity();
        charging.setId(1L);
        charging.setClerkUserId("user_1");
        charging.setTaskType("HUMANIZE");
        charging.setStatus("CHARGING");
        charging.setCreatedAt(com.studyagent.common.datetime.DateTimeFormats.now());

        HumanizerTaskEntity cancelled = new HumanizerTaskEntity();
        cancelled.setId(1L);
        cancelled.setClerkUserId("user_1");
        cancelled.setTaskType("HUMANIZE");
        cancelled.setStatus("CANCELLED");
        cancelled.setCreatedAt(com.studyagent.common.datetime.DateTimeFormats.now());

        when(repository.findById(1L)).thenReturn(charging, cancelled);
        when(repository.cancelTask(1L)).thenReturn(true);

        HumanizerTaskResponse response = service.cancelTask(1L, "user_1");

        assertThat(response.getStatus()).isEqualTo("CANCELLED");
        verify(repository, times(1)).cancelTask(1L);
    }

    private QuotaBalance balance(long totalAvailable) {
        return new QuotaBalance(
                FeatureCode.HUMANIZER.getCode(),
                "Humanizer",
                "count",
                totalAvailable,
                1L,
                null,
                0L,
                null,
                0L,
                List.of(),
                0L,
                totalAvailable);
    }
}
