package com.studyagent.service.application;

import com.studyagent.service.application.dto.NotifyDispatchResult;
import com.studyagent.service.application.request.NotifyDispatchRequest;
import com.studyagent.service.config.NotifyConfig;
import com.studyagent.service.domain.notify.NotifyMessage;
import com.studyagent.service.domain.notify.NotifySendResult;
import com.studyagent.service.domain.notify.NotifySender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class NotifyApplicationServiceTest {

    private NotifyApplicationService service;

    @BeforeEach
    void setUp() {
        NotifyConfig config = new NotifyConfig();
        config.setEnabled(true);
        config.setApiToken("notify-token");
        config.getIdempotency().setEnabled(true);
        config.getIdempotency().setTtlSeconds(600);
        config.getRateLimit().setEnabled(true);
        config.getRateLimit().setPerServicePerMinute(10);

        NotifySender sender = new NotifySender() {
            @Override
            public NotifySendResult send(NotifyMessage message) {
                return NotifySendResult.builder()
                        .success(true)
                        .deliveryId("dt_test_001")
                        .build();
            }
        };

        service = new NotifyApplicationService(config, sender);
    }

    @Test
    void shouldRejectWhenNotifyTokenInvalid() {
        NotifyDispatchResult result = service.dispatch(baseRequest(), "wrong-token");

        assertThat(result.getCode()).isEqualTo(4001);
        assertThat(result.getData().getStatus()).isEqualTo("rejected");
        assertThat(result.getData().getError()).isNotNull();
        assertThat(result.getData().getError().getType()).isEqualTo("AUTH_ERROR");
    }

    @Test
    void shouldRejectWhenEnumInvalid() {
        NotifyDispatchRequest request = baseRequest().toBuilder()
                .level("panic")
                .build();

        NotifyDispatchResult result = service.dispatch(request, "notify-token");

        assertThat(result.getCode()).isEqualTo(4004);
        assertThat(result.getData().getStatus()).isEqualTo("rejected");
        assertThat(result.getData().getError().getType()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void shouldDeduplicateWhenEventIdRepeated() {
        NotifyDispatchRequest request = baseRequest().toBuilder()
                .eventId("evt_20260402_10001")
                .build();

        NotifyDispatchResult first = service.dispatch(request, "notify-token");
        NotifyDispatchResult second = service.dispatch(request, "notify-token");

        assertThat(first.getCode()).isEqualTo(0);
        assertThat(second.getCode()).isEqualTo(4003);
        assertThat(second.getData().getStatus()).isEqualTo("deduplicated");
    }

    @Test
    void shouldNotDeduplicateWhenEventIdMissing() {
        NotifyDispatchResult first = service.dispatch(baseRequest(), "notify-token");
        NotifyDispatchResult second = service.dispatch(baseRequest(), "notify-token");

        assertThat(first.getCode()).isEqualTo(0);
        assertThat(second.getCode()).isEqualTo(0);
    }

    @Test
    void shouldTriggerRateLimitBySourceService() {
        NotifyConfig config = new NotifyConfig();
        config.setEnabled(true);
        config.setApiToken("notify-token");
        config.getIdempotency().setEnabled(false);
        config.getRateLimit().setEnabled(true);
        config.getRateLimit().setPerServicePerMinute(1);

        NotifySender sender = message -> NotifySendResult.builder().success(true).deliveryId("dt_test_rl").build();
        NotifyApplicationService rateLimitService = new NotifyApplicationService(config, sender);

        NotifyDispatchResult first = rateLimitService.dispatch(baseRequest().toBuilder().eventId("evt_rl_1").build(), "notify-token");
        NotifyDispatchResult second = rateLimitService.dispatch(baseRequest().toBuilder().eventId("evt_rl_2").build(), "notify-token");

        assertThat(first.getCode()).isEqualTo(0);
        assertThat(second.getCode()).isEqualTo(4002);
        assertThat(second.getData().getStatus()).isEqualTo("rejected");
        assertThat(second.getData().getError().getType()).isEqualTo("RATE_LIMIT");
    }

    @Test
    void shouldRejectWhenTokenMissing() {
        NotifyDispatchResult result = service.dispatch(baseRequest(), null);

        assertThat(result.getCode()).isEqualTo(4001);
        assertThat(result.getData().getStatus()).isEqualTo("rejected");
        assertThat(result.getData().getError().getType()).isEqualTo("AUTH_ERROR");
    }

    @Test
    void shouldRejectWhenRequiredFieldMissing() {
        NotifyDispatchRequest request = baseRequest().toBuilder()
                .title(" ")
                .build();

        NotifyDispatchResult result = service.dispatch(request, "notify-token");

        assertThat(result.getCode()).isEqualTo(4000);
        assertThat(result.getData().getStatus()).isEqualTo("rejected");
        assertThat(result.getData().getError().getType()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void shouldRejectWhenMetadataTypeInvalid() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("taskId", "task_9527");
        metadata.put("nested", Map.of("k", "v"));

        NotifyDispatchRequest request = baseRequest().toBuilder()
                .metadata(metadata)
                .build();

        NotifyDispatchResult result = service.dispatch(request, "notify-token");

        assertThat(result.getCode()).isEqualTo(4000);
        assertThat(result.getData().getStatus()).isEqualTo("rejected");
        assertThat(result.getData().getError().getType()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void shouldTruncateContentBeforeSend() {
        NotifyConfig config = new NotifyConfig();
        config.setEnabled(true);
        config.setApiToken("notify-token");

        AtomicReference<NotifyMessage> captured = new AtomicReference<>();
        NotifySender sender = message -> {
            captured.set(message);
            return NotifySendResult.builder().success(true).deliveryId("dt_test_truncate").build();
        };
        NotifyApplicationService truncateService = new NotifyApplicationService(config, sender);

        String longContent = "x".repeat(1200);
        NotifyDispatchRequest request = baseRequest().toBuilder()
                .eventId("evt_truncate")
                .content(longContent)
                .build();

        NotifyDispatchResult result = truncateService.dispatch(request, "notify-token");

        assertThat(result.getCode()).isEqualTo(0);
        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().getContent()).hasSize(1014);
        assertThat(captured.get().getContent()).endsWith("...(truncated)");
    }

    @Test
    void shouldFormatGeneratedTimestampAsReadableSeconds() {
        NotifyConfig config = new NotifyConfig();
        config.setEnabled(true);
        config.setApiToken("notify-token");

        AtomicReference<NotifyMessage> captured = new AtomicReference<>();
        NotifySender sender = message -> {
            captured.set(message);
            return NotifySendResult.builder().success(true).deliveryId("dt_test_ts").build();
        };
        NotifyApplicationService timestampService = new NotifyApplicationService(config, sender);

        NotifyDispatchRequest request = baseRequest().toBuilder()
                .timestamp(null)
                .build();

        NotifyDispatchResult result = timestampService.dispatch(request, "notify-token");

        assertThat(result.getCode()).isEqualTo(0);
        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().getTimestamp()).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
        assertThat(captured.get().getTimestamp()).doesNotContain("T");
    }

    private NotifyDispatchRequest baseRequest() {
        return NotifyDispatchRequest.builder()
                .sourceService("springboot_backend")
                .scene("task.failed")
                .title("任务失败")
                .content("任务 task_9527 执行失败")
                .level("error")
                .contentType("markdown")
                .env("test")
                .timestamp("2026-04-02T10:00:00+08:00")
                .metadata(Map.of("taskId", "task_9527", "errorCode", "E_TIMEOUT"))
                .build();
    }
}
