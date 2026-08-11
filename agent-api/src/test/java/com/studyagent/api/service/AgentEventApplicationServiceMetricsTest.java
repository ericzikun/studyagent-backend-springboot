package com.studyagent.api.service;

import com.studyagent.common.event.AgentEventRequest;
import com.studyagent.common.event.AgentEventType;
import com.studyagent.infra.entity.TaskEntity;
import com.studyagent.infra.repository.event.SubTaskEntityRepository;
import com.studyagent.infra.repository.event.TaskActivityEntityRepository;
import com.studyagent.infra.repository.event.TaskAgentEntityRepository;
import com.studyagent.infra.repository.event.TaskEntityRepository;
import com.studyagent.infra.repository.event.TaskOutputEntityRepository;
import com.studyagent.service.domain.quota.QuotaDomainService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentEventApplicationServiceMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @AfterEach
    void closeRegistry() {
        registry.close();
    }

    @Test
    void repositoryCommitRecordsSuccessInsteadOfHttpAcceptance() {
        TaskEntityRepository taskRepository = mock(TaskEntityRepository.class);
        TaskEntity task = new TaskEntity();
        task.setId(7L);
        when(taskRepository.findById(7L)).thenReturn(Optional.of(task));
        AgentEventApplicationService service = service(taskRepository);

        service.processEventAsync(request("evt-success", AgentEventType.TASK_STARTED.name()));

        assertMetric("success", "task_started", 1);
        assertMetric("error", "task_started", 0);
    }

    @Test
    void repositoryExceptionRecordsErrorAndAllowsRetry() {
        TaskEntityRepository taskRepository = mock(TaskEntityRepository.class);
        when(taskRepository.findById(7L))
                .thenThrow(new IllegalStateException("db unavailable"));
        AgentEventApplicationService service = service(taskRepository);

        service.processEventAsync(request("evt-error", AgentEventType.TASK_STARTED.name()));
        service.processEventAsync(request("evt-error", AgentEventType.TASK_STARTED.name()));

        assertMetric("error", "task_started", 2);
        assertMetric("duplicate", "task_started", 0);
    }

    @Test
    void duplicateAndUnknownUseBoundedLabels() {
        TaskEntityRepository taskRepository = mock(TaskEntityRepository.class);
        TaskEntity task = new TaskEntity();
        task.setId(7L);
        when(taskRepository.findById(7L)).thenReturn(Optional.of(task));
        AgentEventApplicationService service = service(taskRepository);

        AgentEventRequest known = request("evt-dup", AgentEventType.TASK_STARTED.name());
        service.processEventAsync(known);
        service.processEventAsync(known);
        service.processEventAsync(request("evt-unknown", "NEW_DYNAMIC_EVENT"));

        assertMetric("success", "task_started", 1);
        assertMetric("duplicate", "task_started", 1);
        assertMetric("ignored", "unknown", 1);
    }

    private AgentEventApplicationService service(TaskEntityRepository taskRepository) {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return new AgentEventApplicationService(
                taskRepository,
                mock(SubTaskEntityRepository.class),
                mock(TaskAgentEntityRepository.class),
                mock(TaskActivityEntityRepository.class),
                mock(TaskOutputEntityRepository.class),
                mock(QuotaDomainService.class),
                mock(EmailNotificationService.class),
                transactionManager,
                new LegacyAgentEventMetrics(registry));
    }

    private static AgentEventRequest request(String eventId, String type) {
        return AgentEventRequest.builder()
                .eventId(eventId)
                .eventType(type)
                .taskId(7L)
                .timestamp(Instant.now())
                .payload(Map.of())
                .build();
    }

    private void assertMetric(String result, String eventType, double expected) {
        assertEquals(expected, registry.counter(
                "legacy.agent.event.processing",
                "result", result,
                "event_type", eventType).count());
    }
}
