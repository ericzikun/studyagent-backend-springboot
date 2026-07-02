package com.studyagent.infra.service.notify;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.studyagent.service.domain.notify.NotifyMessage;
import com.studyagent.service.domain.notify.NotifySendResult;
import com.studyagent.service.domain.notify.NotifySender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class RobotNotifySender implements NotifySender {

    private final WebClient webClient;
    private final RobotWebhookConfigLoader configLoader;

    @Override
    public boolean supportsTarget(String target) {
        return configLoader.hasTarget(target);
    }

    @Override
    public NotifySendResult send(NotifyMessage message) {
        RobotWebhookConfigLoader.RobotEndpoint endpoint = configLoader.getEndpoint(message.getTarget());
        Map<String, Object> payload = buildFeishuPayload(message, endpoint.getSecret());

        try {
            String responseBody = webClient.post()
                    .uri(endpoint.getWebhookUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(10));

            if (!isFeishuSuccess(responseBody)) {
                log.warn("feishu send rejected: target={}, response={}", message.getTarget(), responseBody);
                return NotifySendResult.builder()
                        .success(false)
                        .errorMessage("feishu api rejected message")
                        .retryable(false)
                        .build();
            }

            return NotifySendResult.builder()
                    .success(true)
                    .deliveryId(generateDeliveryId())
                    .retryable(false)
                    .build();
        } catch (Exception ex) {
            log.error("feishu send error: target={}, message={}", message.getTarget(), ex.getMessage(), ex);
            return NotifySendResult.builder()
                    .success(false)
                    .errorMessage(ex.getMessage())
                    .retryable(true)
                    .build();
        }
    }

    private Map<String, Object> buildFeishuPayload(NotifyMessage message, String secret) {
        String contentType = StringUtils.defaultIfBlank(message.getContentType(), "markdown");
        Map<String, Object> payload = new LinkedHashMap<>();

        if ("text".equalsIgnoreCase(contentType)) {
            payload.put("msg_type", "text");
            payload.put("content", Map.of("text", buildTextMessage(message)));
        } else {
            payload.put("msg_type", "post");
            payload.put("content", Map.of(
                    "post", Map.of(
                            "zh_cn", Map.of(
                                    "title", buildMarkdownTitle(message),
                                    "content", buildFeishuPostLines(message)
                            )
                    )
            ));
        }

        if (StringUtils.isNotBlank(secret)) {
            long timestamp = System.currentTimeMillis() / 1000;
            payload.put("timestamp", String.valueOf(timestamp));
            payload.put("sign", buildFeishuSign(timestamp, secret));
        }
        return payload;
    }

    private List<List<Map<String, String>>> buildFeishuPostLines(NotifyMessage message) {
        List<List<Map<String, String>>> lines = new ArrayList<>();
        for (String line : buildMarkdownMessage(message).split("\n")) {
            if (StringUtils.isBlank(line)) {
                continue;
            }
            lines.add(List.of(Map.of("tag", "text", "text", line)));
        }
        return lines;
    }

    private String buildMarkdownTitle(NotifyMessage message) {
        return "【通知级别：" + resolveLevelLabel(message.getLevel()) + "】" + StringUtils.defaultString(message.getTitle());
    }

    private String buildMarkdownMessage(NotifyMessage message) {
        String levelLabel = resolveLevelLabel(message.getLevel());
        StringBuilder builder = new StringBuilder();
        builder.append("#### ")
                .append(StringUtils.defaultString(message.getTitle()))
                .append("\n\n")
                .append("- 通知级别：").append(levelLabel).append("\n")
                .append("- 来源服务：").append(StringUtils.defaultString(message.getSourceService())).append("\n")
                .append("- 触发时间：").append(StringUtils.defaultString(message.getTimestamp())).append("\n")
                .append("- 通知内容：").append(StringUtils.defaultString(message.getContent())).append("\n");

        if (message.getMetadata() != null && !message.getMetadata().isEmpty()) {
            builder.append("\n补充信息：\n");
            for (Map.Entry<String, Object> entry : message.getMetadata().entrySet()) {
                builder.append("- ")
                        .append(resolveMetadataDisplayKey(entry.getKey()))
                        .append("：")
                        .append(String.valueOf(entry.getValue()))
                        .append("\n");
            }
        }
        return builder.toString();
    }

    private String buildTextMessage(NotifyMessage message) {
        String levelLabel = resolveLevelLabel(message.getLevel());
        StringBuilder builder = new StringBuilder();
        builder.append("[通知级别：")
                .append(levelLabel)
                .append("] ")
                .append(StringUtils.defaultString(message.getTitle()))
                .append("\n来源服务：").append(StringUtils.defaultString(message.getSourceService()))
                .append("\n触发时间：").append(StringUtils.defaultString(message.getTimestamp()))
                .append("\n通知内容：").append(StringUtils.defaultString(message.getContent()));

        if (message.getMetadata() != null && !message.getMetadata().isEmpty()) {
            builder.append("\n补充信息：");
            for (Map.Entry<String, Object> entry : message.getMetadata().entrySet()) {
                builder.append("\n- ")
                        .append(resolveMetadataDisplayKey(entry.getKey()))
                        .append("：")
                        .append(String.valueOf(entry.getValue()));
            }
        }
        return builder.toString();
    }

    private String resolveLevelLabel(String rawLevel) {
        String level = StringUtils.lowerCase(StringUtils.defaultIfBlank(rawLevel, "info"));
        return switch (level) {
            case "warn" -> "警告";
            case "error" -> "错误";
            case "critical" -> "紧急";
            default -> "信息";
        };
    }

    private String resolveMetadataDisplayKey(String rawKey) {
        String key = StringUtils.lowerCase(StringUtils.defaultString(rawKey));
        return switch (key) {
            case "scene" -> "业务场景";
            case "env" -> "运行环境";
            case "operator" -> "触发人";
            case "service" -> "服务模块";
            default -> StringUtils.defaultString(rawKey);
        };
    }

    private boolean isFeishuSuccess(String responseBody) {
        if (StringUtils.isBlank(responseBody)) {
            return false;
        }
        try {
            JsonObject jsonObject = JsonParser.parseString(responseBody).getAsJsonObject();
            if (jsonObject.has("code")) {
                return jsonObject.get("code").getAsInt() == 0;
            }
            if (jsonObject.has("StatusCode")) {
                return jsonObject.get("StatusCode").getAsInt() == 0;
            }
            return false;
        } catch (Exception ex) {
            return false;
        }
    }

    private String buildFeishuSign(long timestamp, String secret) {
        try {
            String stringToSign = timestamp + "\n" + secret;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(stringToSign.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal());
        } catch (Exception ex) {
            throw new IllegalStateException("failed to sign feishu request", ex);
        }
    }

    private String generateDeliveryId() {
        return "fs_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
