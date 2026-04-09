package com.studyagent.service.application;

import com.studyagent.service.application.dto.NotifyDispatchResult;
import com.studyagent.service.application.request.NotifyDispatchRequest;
import com.studyagent.service.config.NotifyConfig;
import com.studyagent.service.domain.notify.NotifyMessage;
import com.studyagent.service.domain.notify.NotifySendResult;
import com.studyagent.service.domain.notify.NotifySender;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotifyApplicationService {

    private static final Set<String> SOURCE_SERVICE_SET = Set.of("springboot_backend", "python_backend", "frontend", "humanizer");
    private static final Set<String> LEVEL_SET = Set.of("info", "warn", "error", "critical");
    private static final Set<String> CONTENT_TYPE_SET = Set.of("text", "markdown");
    private static final Set<String> ENV_SET = Set.of("local", "test", "online");
    private static final int TARGET_MAX_LENGTH = 64;
    private static final int TITLE_MAX_LENGTH = 80;
    private static final int CONTENT_MAX_LENGTH = 2000;
    private static final int SEND_CONTENT_MAX_LENGTH = 1000;
    private static final DateTimeFormatter TIMESTAMP_DISPLAY_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final NotifyConfig notifyConfig;
    private final NotifySender notifySender;

    private final ConcurrentHashMap<String, Long> idempotencyCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> rateLimitCounters = new ConcurrentHashMap<>();

    @PostConstruct
    public void validateBootConfig() {
        if (!notifyConfig.isEnabled()) {
            return;
        }
        if (StringUtils.isBlank(notifyConfig.getApiToken())) {
            throw new IllegalStateException("notify.api-token is required when notify.enabled=true");
        }
    }

    public NotifyDispatchResult dispatch(NotifyDispatchRequest request, String notifyToken) {
        String eventId = normalizeEventId(request.getEventId());
        String sourceService = null;
        String scene = safeTrim(request.getScene());
        String target = null;
        String level = null;
        String contentType = null;
        String env = null;

        try {
            if (!notifyConfig.isEnabled()) {
                return buildError(4000, "notify api disabled", eventId, safeTrim(request.getSourceService()), scene,
                        safeLowerOrDefault(request.getLevel(), "info"), safeLowerOrDefault(request.getContentType(), "markdown"),
                        safeLowerOrDefault(request.getEnv(), notifyConfig.getDefaultEnv()), "rejected", "VALIDATION_ERROR",
                        "notify.enabled is false", false);
            }

            sourceService = validateSourceService(request.getSourceService());
            target = validateTarget(request.getTarget());
            level = validateEnumWithDefault(request.getLevel(), LEVEL_SET, "level", "info");
            contentType = validateEnumWithDefault(request.getContentType(), CONTENT_TYPE_SET, "contentType", "markdown");
            env = validateEnv(request.getEnv());

            String title = validateRequiredText(request.getTitle(), "title", TITLE_MAX_LENGTH);
            String content = validateRequiredText(request.getContent(), "content", CONTENT_MAX_LENGTH);

            if (!StringUtils.equals(StringUtils.trimToEmpty(notifyToken), StringUtils.trimToEmpty(notifyConfig.getApiToken()))) {
                return buildError(4001, "invalid notify token", eventId, sourceService, scene, level, contentType, env,
                        "rejected", "AUTH_ERROR", "X-Notify-Token is invalid", false);
            }

            if (!notifySender.supportsTarget(target)) {
                return buildError(4004, "invalid enum value", eventId, sourceService, scene, level, contentType, env,
                        "rejected", "VALIDATION_ERROR", "target is invalid", false);
            }

            RateLimitDecision rateLimitDecision = checkRateLimit(sourceService);
            if (rateLimitDecision.exceeded()) {
                return buildError(4002, "notify request rate limit exceeded", eventId, sourceService, scene, level, contentType, env,
                        "rejected", "RATE_LIMIT",
                        String.format("rate limit exceeded for sourceService, currentCount=%d, limit=%d",
                                rateLimitDecision.currentCount(), rateLimitDecision.limit()),
                        true);
            }

            if (isDuplicateEvent(eventId, sourceService)) {
                return buildError(4003, "duplicate event", eventId, sourceService, scene, level, contentType, env,
                        "deduplicated", "DUPLICATE_EVENT", "eventId within idempotency window", false);
            }

            Map<String, Object> validatedMetadata = validateMetadata(request.getMetadata());
            Map<String, Object> sanitizedMetadata = sanitizeMetadata(validatedMetadata, scene, env);

            NotifyMessage message = NotifyMessage.builder()
                    .eventId(eventId)
                    .sourceService(sourceService)
                    .scene(scene)
                    .target(target)
                    .title(title)
                    .content(truncateForSend(content))
                    .level(level)
                    .contentType(contentType)
                    .env(env)
                    .timestamp(resolveTimestamp(request.getTimestamp()))
                    .metadata(sanitizedMetadata)
                    .build();

            NotifySendResult sendResult = notifySender.send(message);
            if (sendResult == null || !sendResult.isSuccess()) {
                String detail = sendResult == null ? "dingtalk sender returned null" : StringUtils.defaultIfBlank(sendResult.getErrorMessage(), "dingtalk send failed");
                boolean retryable = sendResult != null && sendResult.isRetryable();
                return buildError(5000, "dingtalk send failed", eventId, sourceService, scene, level, contentType, env,
                        "failed", "DOWNSTREAM_ERROR", detail, retryable);
            }

            NotifyDispatchResult.NotifyDispatchData data = NotifyDispatchResult.NotifyDispatchData.builder()
                    .eventId(eventId)
                    .sourceService(sourceService)
                    .scene(scene)
                    .level(level)
                    .contentType(contentType)
                    .env(env)
                    .status("sent")
                    .deliveryId(sendResult.getDeliveryId())
                    .error(null)
                    .build();

            log.info("notify dispatch success: eventId={}, sourceService={}, scene={}, target={}, level={}, contentType={}, env={}, status=sent",
                    eventId, sourceService, scene, target, level, contentType, env);
            return NotifyDispatchResult.builder()
                    .code(0)
                    .message("ok")
                    .data(data)
                    .build();
        } catch (NotifyValidationException ex) {
            return buildError(ex.code, ex.message, eventId, sourceService, scene, level, contentType, env,
                    "rejected", ex.errorType, ex.detail, false);
        } catch (Exception ex) {
            log.error("notify dispatch internal error: eventId={}, sourceService={}, message={}", eventId, sourceService, ex.getMessage(), ex);
            return buildError(5000, "notify internal error", eventId, sourceService, scene,
                    defaultValue(level, "info"), defaultValue(contentType, "markdown"), defaultValue(env, safeDefaultEnv()),
                    "failed", "DOWNSTREAM_ERROR", ex.getMessage(), true);
        }
    }

    private String validateSourceService(String sourceService) {
        String value = safeLower(sourceService);
        if (StringUtils.isBlank(value)) {
            throw new NotifyValidationException(4000, "invalid request", "sourceService is required", "VALIDATION_ERROR");
        }
        if (!SOURCE_SERVICE_SET.contains(value)) {
            throw new NotifyValidationException(4004, "invalid enum value", "sourceService is invalid", "VALIDATION_ERROR");
        }
        return value;
    }

    private String validateTarget(String target) {
        String value = safeLower(target);
        if (StringUtils.isBlank(value)) {
            throw new NotifyValidationException(4000, "invalid request", "target is required", "VALIDATION_ERROR");
        }
        if (value.length() > TARGET_MAX_LENGTH) {
            throw new NotifyValidationException(4000, "invalid request", "target exceeds max length", "VALIDATION_ERROR");
        }
        return value;
    }

    private String validateEnv(String env) {
        String value = StringUtils.isBlank(env) ? safeDefaultEnv() : safeLower(env);
        if (!ENV_SET.contains(value)) {
            throw new NotifyValidationException(4004, "invalid enum value", "env is invalid", "VALIDATION_ERROR");
        }
        return value;
    }

    private String validateEnumWithDefault(String rawValue, Set<String> allowSet, String field, String defaultValue) {
        String value = StringUtils.isBlank(rawValue) ? defaultValue : safeLower(rawValue);
        if (!allowSet.contains(value)) {
            throw new NotifyValidationException(4004, "invalid enum value", field + " is invalid", "VALIDATION_ERROR");
        }
        return value;
    }

    private String validateRequiredText(String rawValue, String field, int maxLength) {
        String value = safeTrim(rawValue);
        if (StringUtils.isBlank(value)) {
            throw new NotifyValidationException(4000, "invalid request", field + " is required", "VALIDATION_ERROR");
        }
        if (value.length() > maxLength) {
            throw new NotifyValidationException(4000, "invalid request", field + " exceeds max length", "VALIDATION_ERROR");
        }
        return value;
    }

    private Map<String, Object> validateMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        return metadata;
    }

    private Map<String, Object> sanitizeMetadata(Map<String, Object> metadata, String scene, String env) {
        Map<String, Object> output = new LinkedHashMap<>();
        if (StringUtils.isNotBlank(scene)) {
            output.put("scene", scene);
        }
        if (StringUtils.isNotBlank(env)) {
            output.put("env", env);
        }

        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            String key = safeTrim(entry.getKey());
            if (StringUtils.isBlank(key)) {
                continue;
            }
            // Intentionally keep all metadata keys for now.
            // Future governance can introduce key-level whitelist filtering here if needed.
            output.put(key, sanitizeMetadataValue(key, entry.getValue()));
        }
        return output;
    }

    private Object sanitizeMetadataValue(String key, Object value) {
        if (value == null) {
            return null;
        }

        if (isSensitiveKey(key)) {
            return "***";
        }

        if (value instanceof Map<?, ?> mapValue) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : mapValue.entrySet()) {
                String childKey = safeTrim(String.valueOf(entry.getKey()));
                if (StringUtils.isBlank(childKey)) {
                    continue;
                }
                sanitized.put(childKey, sanitizeMetadataValue(childKey, entry.getValue()));
            }
            return sanitized;
        }

        if (value instanceof Iterable<?> iterableValue) {
            List<Object> sanitized = new ArrayList<>();
            for (Object item : iterableValue) {
                sanitized.add(sanitizeMetadataValue("", item));
            }
            return sanitized;
        }

        if (value.getClass().isArray() && value instanceof Object[] arrayValue) {
            List<Object> sanitized = new ArrayList<>(arrayValue.length);
            for (Object item : arrayValue) {
                sanitized.add(sanitizeMetadataValue("", item));
            }
            return sanitized;
        }

        return value;
    }

    private boolean isSensitiveKey(String key) {
        String value = safeLower(key);
        return value.contains("phone")
                || value.contains("mobile")
                || value.contains("email")
                || value.contains("token")
                || value.contains("secret")
                || value.contains("password")
                || value.contains("accesskey");
    }

    private boolean isDuplicateEvent(String eventId, String sourceService) {
        if (!notifyConfig.getIdempotency().isEnabled() || StringUtils.isBlank(eventId)) {
            return false;
        }

        long now = System.currentTimeMillis();
        long ttlMs = Math.max(1, notifyConfig.getIdempotency().getTtlSeconds()) * 1000L;
        idempotencyCache.entrySet().removeIf(entry -> entry.getValue() <= now);

        String key = sourceService + "#" + eventId;
        AtomicBoolean duplicated = new AtomicBoolean(false);
        idempotencyCache.compute(key, (k, expireAt) -> {
            if (expireAt != null && expireAt > now) {
                duplicated.set(true);
                return expireAt;
            }
            return now + ttlMs;
        });
        if (duplicated.get()) {
            log.warn("notify deduplicated: eventId={}, sourceService={}, deduplicated=true", eventId, sourceService);
        }
        return duplicated.get();
    }

    private RateLimitDecision checkRateLimit(String sourceService) {
        if (!notifyConfig.getRateLimit().isEnabled()) {
            return new RateLimitDecision(false, 0, Math.max(1, notifyConfig.getRateLimit().getPerServicePerMinute()));
        }

        long currentMinute = System.currentTimeMillis() / 60_000;
        int limit = Math.max(1, notifyConfig.getRateLimit().getPerServicePerMinute());

        rateLimitCounters.entrySet().removeIf(entry -> {
            int idx = entry.getKey().lastIndexOf(':');
            if (idx <= 0) {
                return true;
            }
            long minute = Long.parseLong(entry.getKey().substring(idx + 1));
            return minute < currentMinute;
        });

        String counterKey = sourceService + ":" + currentMinute;
        int currentCount = rateLimitCounters.merge(counterKey, 1, Integer::sum);
        boolean exceeded = currentCount > limit;
        if (exceeded) {
            log.warn("notify rate limit triggered: sourceService={}, currentCount={}, limit={}", sourceService, currentCount, limit);
        }
        return new RateLimitDecision(exceeded, currentCount, limit);
    }

    private String resolveTimestamp(String timestamp) {
        if (StringUtils.isNotBlank(timestamp)) {
            String value = timestamp.trim();
            try {
                return OffsetDateTime.parse(value).format(TIMESTAMP_DISPLAY_FORMATTER);
            } catch (Exception ignore) {
                return value;
            }
        }
        return OffsetDateTime.now(ZoneOffset.ofHours(8)).format(TIMESTAMP_DISPLAY_FORMATTER);
    }

    private String truncateForSend(String content) {
        if (content.length() <= SEND_CONTENT_MAX_LENGTH) {
            return content;
        }
        return content.substring(0, SEND_CONTENT_MAX_LENGTH) + "...(truncated)";
    }

    private String normalizeEventId(String eventId) {
        if (StringUtils.isNotBlank(eventId)) {
            String value = eventId.trim();
            if (value.length() > 64) {
                return value.substring(0, 64);
            }
            return value;
        }
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return "evt_" + OffsetDateTime.now(ZoneOffset.ofHours(8)).format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + "_" + suffix;
    }

    private String safeTrim(String value) {
        return StringUtils.trimToNull(value);
    }

    private String safeLower(String value) {
        return StringUtils.lowerCase(StringUtils.trimToEmpty(value));
    }

    private String safeLowerOrDefault(String value, String defaultValue) {
        return StringUtils.isBlank(value) ? defaultValue : safeLower(value);
    }

    private String safeDefaultEnv() {
        String env = safeLowerOrDefault(notifyConfig.getDefaultEnv(), "online");
        return ENV_SET.contains(env) ? env : "online";
    }

    private String defaultValue(String value, String fallback) {
        return StringUtils.isNotBlank(value) ? value : fallback;
    }

    private NotifyDispatchResult buildError(int code,
                                            String message,
                                            String eventId,
                                            String sourceService,
                                            String scene,
                                            String level,
                                            String contentType,
                                            String env,
                                            String status,
                                            String errorType,
                                            String detail,
                                            boolean retryable) {
        NotifyDispatchResult.NotifyErrorData error = NotifyDispatchResult.NotifyErrorData.builder()
                .type(errorType)
                .detail(detail)
                .retryable(retryable)
                .build();

        NotifyDispatchResult.NotifyDispatchData data = NotifyDispatchResult.NotifyDispatchData.builder()
                .eventId(eventId)
                .sourceService(sourceService)
                .scene(scene)
                .level(defaultValue(level, "info"))
                .contentType(defaultValue(contentType, "markdown"))
                .env(defaultValue(env, safeDefaultEnv()))
                .status(status)
                .deliveryId(null)
                .error(error)
                .build();

        log.warn("notify dispatch failed: code={}, eventId={}, sourceService={}, status={}, errorType={}, detail={}, retryable={}",
                code, eventId, sourceService, status, errorType, detail, retryable);
        if ("AUTH_ERROR".equals(errorType) || "RATE_LIMIT".equals(errorType) || "DOWNSTREAM_ERROR".equals(errorType)) {
            log.warn("notify key failure event: eventId={}, sourceService={}, errorType={}, retryable={}",
                    eventId, sourceService, errorType, retryable);
        }

        return NotifyDispatchResult.builder()
                .code(code)
                .message(message)
                .data(data)
                .build();
    }

    private static class NotifyValidationException extends RuntimeException {
        private final int code;
        private final String message;
        private final String detail;
        private final String errorType;

        private NotifyValidationException(int code, String message, String detail, String errorType) {
            this.code = code;
            this.message = message;
            this.detail = detail;
            this.errorType = errorType;
        }
    }

    private record RateLimitDecision(boolean exceeded, int currentCount, int limit) {}
}
