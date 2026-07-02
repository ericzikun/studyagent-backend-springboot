package com.studyagent.infra.service.notify;

import com.studyagent.service.config.NotifyConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RobotWebhookConfigLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldLoadFeishuEndpointsFromRobotConfigFile() throws IOException {
        Path configFile = tempDir.resolve("robot-webhook-config.json");
        Files.writeString(configFile, """
                {
                  "targets": {
                    "default": {
                      "webhook_url": "https://open.feishu.cn/open-apis/bot/v2/hook/fs_default",
                      "secret": "fs_default_secret"
                    },
                    "payment": {
                      "webhook_url": "https://open.feishu.cn/open-apis/bot/v2/hook/fs_payment",
                      "secret": "fs_payment_secret"
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

        RobotWebhookConfigLoader.RobotEndpoint defaultTarget = loader.getEndpoint("default");
        assertThat(defaultTarget.getWebhookUrl()).contains("fs_default");
        assertThat(defaultTarget.getSecret()).isEqualTo("fs_default_secret");

        assertThat(loader.hasTarget("payment")).isTrue();
        assertThat(loader.getEndpoint("payment").getWebhookUrl()).contains("fs_payment");
    }
}
