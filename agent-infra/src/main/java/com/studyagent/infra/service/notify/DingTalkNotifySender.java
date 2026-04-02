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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class DingTalkNotifySender implements NotifySender {

    private final WebClient webClient;
    private final DingTalkWebhookConfigLoader configLoader;

    @Override
    public NotifySendResult send(NotifyMessage message) {
        DingTalkWebhookConfigLoader.DingTalkEndpoint endpoint = configLoader.getDefaultEndpoint();
        String webhookUrl = buildSignedWebhookUrl(endpoint.getUrl(), endpoint.getSecret());
        Map<String, Object> payload = buildPayload(message);

        try {
            String responseBody = webClient.post()
                    .uri(webhookUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(10));

            if (!isDingTalkSuccess(responseBody)) {
                log.warn("dingtalk send rejected: response={}", responseBody);
                return NotifySendResult.builder()
                        .success(false)
                        .errorMessage("dingtalk api rejected message")
                        .retryable(false)
                        .build();
            }

            return NotifySendResult.builder()
                    .success(true)
                    .deliveryId(generateDeliveryId())
                    .retryable(false)
                    .build();
        } catch (Exception ex) {
            log.error("dingtalk send error: {}", ex.getMessage(), ex);
            return NotifySendResult.builder()
                    .success(false)
                    .errorMessage(ex.getMessage())
                    .retryable(true)
                    .build();
        }
    }

    private Map<String, Object> buildPayload(NotifyMessage message) {
        String contentType = StringUtils.defaultIfBlank(message.getContentType(), "markdown");
        if ("text".equalsIgnoreCase(contentType)) {
            Map<String, Object> text = new LinkedHashMap<>();
            text.put("content", buildTextMessage(message));

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("msgtype", "text");
            payload.put("text", text);
            return payload;
        }

        Map<String, Object> markdown = new LinkedHashMap<>();
        markdown.put("title", buildMarkdownTitle(message));
        markdown.put("text", buildMarkdownMessage(message));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("msgtype", "markdown");
        payload.put("markdown", markdown);
        return payload;
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

    private boolean isDingTalkSuccess(String responseBody) {
        if (StringUtils.isBlank(responseBody)) {
            return false;
        }
        try {
            JsonObject jsonObject = JsonParser.parseString(responseBody).getAsJsonObject();
            if (!jsonObject.has("errcode")) {
                return false;
            }
            return jsonObject.get("errcode").getAsInt() == 0;
        } catch (Exception ex) {
            return false;
        }
    }

    private String buildSignedWebhookUrl(String originUrl, String secret) {
        if (StringUtils.isBlank(secret)) {
            return originUrl;
        }

        try {
            long timestamp = System.currentTimeMillis();
            String stringToSign = timestamp + "\n" + secret;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signData = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
            String sign = URLEncoder.encode(Base64.getEncoder().encodeToString(signData), StandardCharsets.UTF_8);

            StringBuilder builder = new StringBuilder(originUrl);
            builder.append(originUrl.contains("?") ? "&" : "?")
                    .append("timestamp=").append(timestamp)
                    .append("&sign=").append(sign);
            return builder.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("failed to sign dingtalk request", ex);
        }
    }

    private String generateDeliveryId() {
        return "dt_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
