package com.studyagent.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.quota.FeatureCode;
import com.studyagent.infra.client.humanizer.HumanizerServiceClientImpl;
import com.studyagent.infra.entity.HumanizerTaskEntity;
import com.studyagent.infra.repository.humanizer.HumanizerTaskRepositoryImpl;
import com.studyagent.service.domain.humanizer.HumanizerServiceClient;
import com.studyagent.service.domain.quota.ConsumeResult;
import com.studyagent.service.domain.quota.QuotaDomainService;
import com.studyagent.service.domain.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HumanizerTaskWorkerTest {

    private HumanizerTaskRepositoryImpl repository;
    private HumanizerServiceClient humanizerServiceClient;
    private HumanizerServiceClientImpl humanizerServiceClientImpl;
    private QuotaDomainService quotaDomainService;
    private UserRepository userRepository;
    private HumanizerTaskWorker worker;

    @BeforeEach
    void setUp() {
        repository = mock(HumanizerTaskRepositoryImpl.class);
        humanizerServiceClient = mock(HumanizerServiceClient.class);
        humanizerServiceClientImpl = mock(HumanizerServiceClientImpl.class);
        quotaDomainService = mock(QuotaDomainService.class);
        userRepository = mock(UserRepository.class);
        worker = new HumanizerTaskWorker(
                repository,
                humanizerServiceClient,
                humanizerServiceClientImpl,
                new ObjectMapper(),
                quotaDomainService,
                userRepository,
                2,
                3);
        ReflectionTestUtils.setField(worker, "whitelistUserIds", List.of());
        when(userRepository.findByClerkUserId(anyString())).thenReturn(Optional.empty());
        when(repository.existsHumanizeResultHash(anyString(), anyString())).thenReturn(false);
        when(quotaDomainService.consume(
                eq("user_1"),
                eq(FeatureCode.AI_DETECTION.getCode()),
                eq(1L),
                eq("humanizer_task"),
                eq("77"),
                any(Map.class),
                eq("detection:77:start")))
                .thenReturn(new ConsumeResult(555L));
    }

    @Test
    void processDetectTask_chargesExactlyOncePerRun() {
        HumanizerTaskEntity task = new HumanizerTaskEntity();
        task.setId(77L);
        task.setClerkUserId("user_1");
        task.setTaskType("DETECT");
        task.setInputText("First sentence. Second sentence.");
        task.setStatus("PROCESSING");
        task.setCompletedSentences(0);
        task.setConsumedWords(0);

        when(humanizerServiceClientImpl.detectAIStream(eq(task.getInputText()), eq(false)))
                .thenReturn(Flux.just(
                        "{\"index\":1,\"fullSentence\":\"First sentence has several words\",\"sentence\":\"First sentence\",\"total\":2}",
                        "{\"index\":2,\"fullSentence\":\"Second sentence also has several words\",\"sentence\":\"Second sentence\",\"total\":2}",
                        "{\"totalChunks\":2,\"probability\":0.2,\"label\":\"Human Written\",\"elapsed_seconds\":1.2}"
                ));

        ReflectionTestUtils.invokeMethod(worker, "processDetectTask", task);

        verify(quotaDomainService).consume(
                eq("user_1"),
                eq(FeatureCode.AI_DETECTION.getCode()),
                eq(1L),
                eq("humanizer_task"),
                eq("77"),
                any(Map.class),
                eq("detection:77:start"));
        verify(quotaDomainService, never()).consume(
                eq("user_1"),
                eq(FeatureCode.AI_DETECTION.getCode()),
                eq(5L),
                anyString(),
                anyString(),
                any());
        verify(quotaDomainService, never()).consume(
                eq("user_1"),
                eq(FeatureCode.AI_DETECTION.getCode()),
                eq(6L),
                anyString(),
                anyString(),
                any());
    }
}
