package com.studyagent.infra.service.notify;

import com.studyagent.service.config.NotifyConfig;
import com.studyagent.service.domain.notify.NotifyMessage;
import com.studyagent.service.domain.notify.NotifySendResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RobotNotifySenderTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldSendFeishuMessageUsingTargetSpecificWebhook() throws IOException {
        Path configFile = tempDir.resolve("robot-webhook-config.json");
        Files.writeString(configFile, """
                {
                  "targets": {
                    "default": {
                      "webhook_url": "https://open.feishu.cn/open-apis/bot/v2/hook/fs_default",
                      "secret": "fs_default_secret"
                    },
                    "assignment": {
                      "webhook_url": "https://open.feishu.cn/open-apis/bot/v2/hook/fs_assignment",
                      "secret": "fs_assignment_secret"
                    }
                  }
                }
                """);

        NotifyConfig config = new NotifyConfig();
        config.setEnabled(true);
        config.setApiToken("notify-token");
        config.getRobot().setConfigFile(configFile.toString());

        RobotWebhookConfigLoader loader = new RobotWebhookConfigLoader(config);
        loader.init();

        List<String> requestedUrls = new ArrayList<>();
        ExchangeFunction exchangeFunction = clientRequest -> {
            requestedUrls.add(clientRequest.url().toString());
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body("{\"code\":0,\"msg\":\"success\"}")
                    .build());
        };

        RobotNotifySender sender = new RobotNotifySender(
                WebClient.builder().exchangeFunction(exchangeFunction).build(),
                loader
        );

        NotifyMessage message = NotifyMessage.builder()
                .eventId("evt_notify_feishu")
                .sourceService("springboot_backend")
                .target("assignment")
                .title("任务执行失败")
                .content("任务 task_9527 执行失败")
                .timestamp("2026-06-29 12:00:00")
                .level("error")
                .contentType("markdown")
                .metadata(Map.of("scene", "task.failed"))
                .build();

        NotifySendResult result = sender.send(message);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getDeliveryId()).startsWith("fs_");
        assertThat(requestedUrls).hasSize(1);
        assertThat(requestedUrls.get(0)).contains("fs_assignment");
    }
}
