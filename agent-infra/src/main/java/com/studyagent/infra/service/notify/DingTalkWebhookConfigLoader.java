package com.studyagent.infra.service.notify;

import com.studyagent.service.config.NotifyConfig;
import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class DingTalkWebhookConfigLoader {

    private final NotifyConfig notifyConfig;
    private volatile DingTalkEndpoint endpoint;

    @PostConstruct
    public void init() {
        if (!notifyConfig.isEnabled()) {
            return;
        }
        endpoint = loadEndpointOrThrow();
    }

    public DingTalkEndpoint getDefaultEndpoint() {
        DingTalkEndpoint current = endpoint;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (endpoint == null) {
                endpoint = loadEndpointOrThrow();
            }
            return endpoint;
        }
    }

    private DingTalkEndpoint loadEndpointOrThrow() {
        NotifyConfig.DingTalk dingTalk = notifyConfig.getDingtalk();
        String configFile = dingTalk == null ? null : dingTalk.getConfigFile();

        if (StringUtils.isNotBlank(configFile)) {
            DingTalkEndpoint fromFile = loadFromFile(configFile.trim());
            log.info("notify dingtalk config loaded from file: {}", configFile.trim());
            return fromFile;
        }

        throw new IllegalStateException("notify.dingtalk config unavailable: set notify.dingtalk.config-file");
    }

    @SuppressWarnings("unchecked")
    private DingTalkEndpoint loadFromFile(String configFile) {
        Path path = Path.of(configFile);
        if (!Files.exists(path) || !Files.isReadable(path)) {
            throw new IllegalStateException("notify.dingtalk.config-file unreadable: " + configFile);
        }

        try (InputStream inputStream = new FileInputStream(configFile)) {
            Yaml yaml = new Yaml();
            Object loaded = yaml.load(inputStream);
            if (!(loaded instanceof Map<?, ?> rootMap)) {
                throw new IllegalStateException("invalid dingtalk config file format");
            }

            Object targetsObj = rootMap.get("targets");
            if (!(targetsObj instanceof Map<?, ?> targetsMap)) {
                throw new IllegalStateException("invalid dingtalk config file: targets missing");
            }

            Object defaultObj = targetsMap.get("default");
            if (!(defaultObj instanceof Map<?, ?> defaultMap)) {
                throw new IllegalStateException("invalid dingtalk config file: targets.default missing");
            }

            String url = toNullableString(defaultMap.get("url"));
            String secret = toNullableString(defaultMap.get("secret"));
            if (StringUtils.isBlank(url)) {
                throw new IllegalStateException("invalid dingtalk config file: targets.default.url missing");
            }
            return new DingTalkEndpoint(url.trim(), StringUtils.trimToNull(secret));
        } catch (Exception ex) {
            throw new IllegalStateException("failed to load notify dingtalk config file: " + ex.getMessage(), ex);
        }
    }

    private String toNullableString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    @Getter
    @AllArgsConstructor
    public static class DingTalkEndpoint {
        private final String url;
        private final String secret;
    }
}
