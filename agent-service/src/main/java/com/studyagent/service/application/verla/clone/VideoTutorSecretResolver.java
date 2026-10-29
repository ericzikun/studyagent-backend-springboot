package com.studyagent.service.application.verla.clone;

import com.studyagent.service.application.verla.VideoTutorDefaultSecrets;
import lombok.extern.slf4j.Slf4j;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** 从 models.yaml / 环境变量解析供应商密钥；只返回内存字符串，不落盘、不打印值。 */
@Slf4j
final class VideoTutorSecretResolver {

    private VideoTutorSecretResolver() {
    }

    static String resolve(String explicit, String modelsYaml, String ref) {
        return resolve(explicit, modelsYaml, ref, "");
    }

    static String resolve(String explicit, String modelsYaml, String ref, String defaultProperty) {
        String direct = explicit == null ? "" : explicit.trim();
        if (!direct.isEmpty()) {
            return direct;
        }
        String envPath = System.getenv("MODELS_YAML");
        if (envPath != null && !envPath.isBlank()) {
            String found = read(Path.of(envPath.trim()), ref);
            if (!found.isBlank()) {
                return found;
            }
        }
        if (modelsYaml != null && !modelsYaml.isBlank()) {
            String found = read(Path.of(modelsYaml.trim()), ref);
            if (!found.isBlank()) {
                return found;
            }
        }
        for (String candidate : List.of(
                "/app/verla_agent/app/models/models.yaml",
                "../verla_agent/app/models/models.yaml",
                "./app/models/models.yaml")) {
            String found = read(Path.of(candidate), ref);
            if (!found.isBlank()) {
                return found;
            }
        }
        if (defaultProperty != null && !defaultProperty.isBlank()) {
            String preset = VideoTutorDefaultSecrets.get(defaultProperty);
            if (!preset.isBlank()) {
                log.info("[video-tutor/clone] using temporary default key {}", defaultProperty);
                return preset;
            }
        }
        return "";
    }

    private static String read(Path path, String ref) {
        if (path == null || ref == null || ref.isBlank() || !Files.isReadable(path)) {
            return "";
        }
        try (InputStream in = Files.newInputStream(path)) {
            Object loaded = new Yaml().load(in);
            if (!(loaded instanceof Map<?, ?> root)) {
                return "";
            }
            Object keys = root.get("api_keys");
            if (!(keys instanceof Map<?, ?> map)) {
                return "";
            }
            Object value = map.get(ref);
            if (value instanceof String text && !text.isBlank()) {
                log.info("[video-tutor/clone] loaded key ref={} from {}", ref, path);
                return text.trim();
            }
        } catch (Exception e) {
            log.warn("[video-tutor/clone] read models.yaml failed path={} err={}", path, e.getMessage());
        }
        return "";
    }
}
