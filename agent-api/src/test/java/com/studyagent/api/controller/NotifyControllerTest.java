package com.studyagent.api.controller;

import com.studyagent.api.common.Result;
import com.studyagent.api.dto.request.NotifyEventRequest;
import com.studyagent.api.dto.response.NotifyEventResponse;
import com.studyagent.service.application.NotifyApplicationService;
import com.studyagent.service.config.NotifyConfig;
import com.studyagent.service.domain.notify.NotifySendResult;
import com.studyagent.service.domain.notify.NotifySender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NotifyControllerTest {

    private NotifyApplicationService notifyApplicationService;
    private NotifyController notifyController;

    @BeforeEach
    void setUp() {
        NotifyConfig config = new NotifyConfig();
        config.setEnabled(true);
        config.setApiToken("notify-token");
        config.setDefaultEnv("test");

        NotifySender sender = message -> NotifySendResult.builder()
                .success(true)
                .deliveryId("dt_1")
                .build();

        notifyApplicationService = new NotifyApplicationService(config, sender);
        notifyController = new NotifyController(notifyApplicationService);
    }

    @Test
    void shouldReturnSuccessResultWhenDispatchOk() {
        Result<NotifyEventResponse> result = notifyController.notifyEvent(baseRequest(), "notify-token");

        assertThat(result.getMeta().getStatusCode()).isEqualTo(0);
        assertThat(result.getData().getEventId()).isNotBlank();
        assertThat(result.getData().getStatus()).isEqualTo("sent");
        assertThat(result.getData().getDeliveryId()).isEqualTo("dt_1");
    }

    @Test
    void shouldReturnErrorResultWhenDispatchFailed() {
        Result<NotifyEventResponse> result = notifyController.notifyEvent(baseRequest(), "wrong-token");

        assertThat(result.getMeta().getStatusCode()).isEqualTo(4001);
        assertThat(result.getData().getStatus()).isEqualTo("rejected");
        assertThat(result.getData().getError()).isNotNull();
        assertThat(result.getData().getError().getType()).isEqualTo("AUTH_ERROR");
    }

    @Test
    void shouldReturnValidationErrorWhenTargetMissing() {
        NotifyEventRequest request = baseRequest().toBuilder()
                .target(" ")
                .build();

        Result<NotifyEventResponse> result = notifyController.notifyEvent(request, "notify-token");

        assertThat(result.getMeta().getStatusCode()).isEqualTo(4000);
        assertThat(result.getData().getStatus()).isEqualTo("rejected");
        assertThat(result.getData().getError()).isNotNull();
        assertThat(result.getData().getError().getType()).isEqualTo("VALIDATION_ERROR");
    }

    private NotifyEventRequest baseRequest() {
        return NotifyEventRequest.builder()
                .sourceService("springboot_backend")
                .target("default")
                .title("系统通知")
                .content("服务启动完成")
                .build();
    }
}
