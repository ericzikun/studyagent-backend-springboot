package com.studyagent.infra.service.notify;

import com.studyagent.service.config.NotifyConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DingTalkWebhookConfigLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldLoadEndpointFromConfigFile() throws IOException {
        Path configFile = tempDir.resolve("dingtalk-webhook-config.yml");
        Files.writeString(configFile, """
                targets:
                  default:
                    url: \"https://oapi.dingtalk.com/robot/send?access_token=test_token\"
                    secret: \"SEC_test_secret\"
                """);

        NotifyConfig config = new NotifyConfig();
        config.setEnabled(true);
        config.setApiToken("notify-token");
        config.getDingtalk().setConfigFile(configFile.toString());

        DingTalkWebhookConfigLoader loader = new DingTalkWebhookConfigLoader(config);
        loader.init();

        DingTalkWebhookConfigLoader.DingTalkEndpoint endpoint = loader.getDefaultEndpoint();
        assertThat(endpoint.getUrl()).contains("access_token=test_token");
        assertThat(endpoint.getSecret()).isEqualTo("SEC_test_secret");
    }

    @Test
    void shouldFailWhenConfigFileMissing() {
        NotifyConfig config = new NotifyConfig();
        config.setEnabled(true);
        config.setApiToken("notify-token");

        DingTalkWebhookConfigLoader loader = new DingTalkWebhookConfigLoader(config);
        assertThatThrownBy(loader::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("notify.dingtalk.config-file");
    }
}
