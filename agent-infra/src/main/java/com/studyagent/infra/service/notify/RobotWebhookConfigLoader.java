package com.studyagent.infra.service.notify;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.studyagent.service.config.NotifyConfig;
import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class RobotWebhookConfigLoader {

    private final NotifyConfig notifyConfig;
    private volatile Map<String, RobotEndpoint> targets;

    @PostConstruct
    public void init() {
        if (!notifyConfig.isEnabled()) {
            return;
        }
        targets = loadTargetsOrThrow();
    }

    public boolean hasTarget(String target) {
        return getTargets().containsKey(normalizeTarget(target));
    }

    public RobotEndpoint getEndpoint(String target) {
        String normalizedTarget = normalizeTarget(target);
        RobotEndpoint endpoint = getTargets().get(normalizedTarget);
        if (endpoint == null) {
            throw new IllegalArgumentException("notify target route not found: " + normalizedTarget);
        }
        return endpoint;
    }

    private Map<String, RobotEndpoint> getTargets() {
        Map<String, RobotEndpoint> current = targets;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (targets == null) {
                targets = loadTargetsOrThrow();
            }
            return targets;
        }
    }

    private Map<String, RobotEndpoint> loadTargetsOrThrow() {
        String configFile = resolveConfigFile();
        Map<String, RobotEndpoint> fromFile = loadFromFile(configFile);
        log.info("notify robot config loaded from file: {}, targetCount={}", configFile, fromFile.size());
        return fromFile;
    }

    private String resolveConfigFile() {
        String robotConfigFile = notifyConfig.getRobot() == null ? null : notifyConfig.getRobot().getConfigFile();
        if (StringUtils.isNotBlank(robotConfigFile)) {
            return robotConfigFile.trim();
        }
        throw new IllegalStateException("notify.robot config unavailable: set notify.robot.config-file");
    }

    private Map<String, RobotEndpoint> loadFromFile(String configFile) {
        Path path = Path.of(configFile);
        if (!Files.exists(path) || !Files.isReadable(path)) {
            throw new IllegalStateException("notify robot config-file unreadable: " + configFile);
        }

        try {
            JsonElement loaded = JsonParser.parseString(Files.readString(path));
            if (!loaded.isJsonObject()) {
                throw new IllegalStateException("invalid robot config file format");
            }

            JsonObject rootObject = loaded.getAsJsonObject();
            JsonObject targetsObject = rootObject.getAsJsonObject("targets");
            if (targetsObject == null) {
                throw new IllegalStateException("invalid robot config file: targets missing");
            }

            Map<String, RobotEndpoint> loadedTargets = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : targetsObject.entrySet()) {
                String key = normalizeTargetKey(entry.getKey());
                if (StringUtils.isBlank(key)) {
                    continue;
                }
                if (!entry.getValue().isJsonObject()) {
                    throw new IllegalStateException("invalid robot config file: targets." + key + " invalid");
                }

                JsonObject targetObject = entry.getValue().getAsJsonObject();
                String webhookUrl = toNullableString(targetObject.get("webhook_url"));
                String secret = toNullableString(targetObject.get("secret"));
                if (StringUtils.isBlank(webhookUrl)) {
                    throw new IllegalStateException("invalid robot config file: targets." + key + ".webhook_url missing");
                }
                loadedTargets.put(key, new RobotEndpoint(webhookUrl.trim(), StringUtils.trimToNull(secret)));
            }

            if (!loadedTargets.containsKey("default")) {
                throw new IllegalStateException("invalid robot config file: targets.default missing");
            }
            return Map.copyOf(loadedTargets);
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("failed to load notify robot config file: " + ex.getMessage(), ex);
        }
    }

    private String normalizeTarget(String target) {
        String value = StringUtils.lowerCase(StringUtils.trimToNull(target));
        if (StringUtils.isBlank(value)) {
            throw new IllegalArgumentException("notify target route not found: " + target);
        }
        return value;
    }

    private String normalizeTargetKey(String value) {
        return StringUtils.lowerCase(StringUtils.trimToNull(value));
    }

    private String toNullableString(JsonElement value) {
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    @Getter
    @AllArgsConstructor
    public static class RobotEndpoint {
        private final String webhookUrl;
        private final String secret;
    }
}
