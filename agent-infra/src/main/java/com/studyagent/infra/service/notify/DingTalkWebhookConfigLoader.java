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
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class DingTalkWebhookConfigLoader {

    private final NotifyConfig notifyConfig;
    // 从外部 config file 加载的 route 缓存。
    // 运行期不做 hot reload，修改 route 后需要重启服务生效。
    private volatile Map<String, DingTalkEndpoint> endpoints;

    @PostConstruct
    public void init() {
        if (!notifyConfig.isEnabled()) {
            return;
        }
        endpoints = loadEndpointsOrThrow();
    }

    public DingTalkEndpoint getDefaultEndpoint() {
        return getEndpoint("default");
    }

    public DingTalkEndpoint getEndpoint(String target) {
        String normalizedTarget = normalizeTarget(target);
        Map<String, DingTalkEndpoint> current = getEndpoints();
        DingTalkEndpoint endpoint = current.get(normalizedTarget);
        if (endpoint == null) {
            // 由上层转换成业务校验错误响应。
            throw new IllegalArgumentException("notify target route not found: " + normalizedTarget);
        }
        return endpoint;
    }

    public boolean hasEndpoint(String target) {
        String normalizedTarget = normalizeTarget(target);
        return getEndpoints().containsKey(normalizedTarget);
    }

    private Map<String, DingTalkEndpoint> getEndpoints() {
        Map<String, DingTalkEndpoint> current = endpoints;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (endpoints == null) {
                // 兜底 lazy load：正常流程会在启动时 init() 完成加载。
                endpoints = loadEndpointsOrThrow();
            }
            return endpoints;
        }
    }

    private String normalizeTarget(String target) {
        String value = StringUtils.lowerCase(StringUtils.trimToNull(target));
        if (StringUtils.isBlank(value)) {
            throw new IllegalArgumentException("notify target route not found: " + target);
        }
        return value;
    }

    private Map<String, DingTalkEndpoint> loadEndpointsOrThrow() {
        NotifyConfig.DingTalk dingTalk = notifyConfig.getDingtalk();
        String configFile = dingTalk == null ? null : dingTalk.getConfigFile();

        if (StringUtils.isNotBlank(configFile)) {
            Map<String, DingTalkEndpoint> fromFile = loadFromFile(configFile.trim());
            log.info("notify dingtalk config loaded from file: {}, targetCount={}", configFile.trim(), fromFile.size());
            return fromFile;
        }

        throw new IllegalStateException("notify.dingtalk config unavailable: set notify.dingtalk.config-file");
    }

    @SuppressWarnings("unchecked")
    private Map<String, DingTalkEndpoint> loadFromFile(String configFile) {
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

            Map<String, DingTalkEndpoint> loadedTargets = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : targetsMap.entrySet()) {
                String key = normalizeTargetKey(entry.getKey());
                if (StringUtils.isBlank(key)) {
                    continue;
                }

                if (!(entry.getValue() instanceof Map<?, ?> targetMap)) {
                    throw new IllegalStateException("invalid dingtalk config file: targets." + key + " invalid");
                }

                String url = toNullableString(targetMap.get("url"));
                String secret = toNullableString(targetMap.get("secret"));
                if (StringUtils.isBlank(url)) {
                    throw new IllegalStateException("invalid dingtalk config file: targets." + key + ".url missing");
                }

                loadedTargets.put(key, new DingTalkEndpoint(url.trim(), StringUtils.trimToNull(secret)));
            }

            // 保持与历史 single-route 实现兼容：必须存在 default。
            if (!loadedTargets.containsKey("default")) {
                throw new IllegalStateException("invalid dingtalk config file: targets.default missing");
            }

            return Map.copyOf(loadedTargets);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to load notify dingtalk config file: " + ex.getMessage(), ex);
        }
    }

    private String normalizeTargetKey(Object value) {
        // 统一转为 lowercase，避免调用方大小写不一致导致 route 误判。
        return StringUtils.lowerCase(StringUtils.trimToNull(toNullableString(value)));
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
