package com.studyagent.infra.service.notify;

import com.studyagent.service.config.NotifyConfig;
import com.studyagent.service.domain.notify.NotifyMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DingTalkNotifySenderTest {

    private DingTalkNotifySender sender;

    @BeforeEach
    void setUp() {
        NotifyConfig config = new NotifyConfig();
        config.setEnabled(false);
        sender = new DingTalkNotifySender(WebClient.builder().build(), new DingTalkWebhookConfigLoader(config));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldBuildMarkdownPayloadWithLevelPrefix() throws Exception {
        NotifyMessage message = NotifyMessage.builder()
                .sourceService("springboot_backend")
                .title("任务执行失败")
                .content("任务 task_9527 执行失败")
                .timestamp("2026-04-02T10:00:00+08:00")
                .level("error")
                .contentType("markdown")
                .metadata(Map.of("scene", "task.failed"))
                .build();

        Map<String, Object> payload = (Map<String, Object>) invokeBuildPayload(message);
        Map<String, Object> markdown = (Map<String, Object>) payload.get("markdown");

        assertThat(payload.get("msgtype")).isEqualTo("markdown");
        assertThat(markdown.get("title")).isEqualTo("【通知级别：错误】任务执行失败");
        assertThat(String.valueOf(markdown.get("text"))).contains("- 来源服务：springboot_backend");
        assertThat(String.valueOf(markdown.get("text"))).contains("- 业务场景：task.failed");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldBuildTextPayloadWhenContentTypeText() throws Exception {
        NotifyMessage message = NotifyMessage.builder()
                .sourceService("python_backend")
                .title("系统通知")
                .content("服务启动完成")
                .timestamp("2026-04-02T10:00:00+08:00")
                .level("info")
                .contentType("text")
                .build();

        Map<String, Object> payload = (Map<String, Object>) invokeBuildPayload(message);
        Map<String, Object> text = (Map<String, Object>) payload.get("text");

        assertThat(payload.get("msgtype")).isEqualTo("text");
        assertThat(String.valueOf(text.get("content"))).contains("[通知级别：信息] 系统通知");
        assertThat(String.valueOf(text.get("content"))).contains("来源服务：python_backend");
    }

    private Object invokeBuildPayload(NotifyMessage message) throws Exception {
        Method method = DingTalkNotifySender.class.getDeclaredMethod("buildPayload", NotifyMessage.class);
        method.setAccessible(true);
        return method.invoke(sender, message);
    }
}
