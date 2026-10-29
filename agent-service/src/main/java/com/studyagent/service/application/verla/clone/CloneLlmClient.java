package com.studyagent.service.application.verla.clone;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * OpenAI-compatible 流式客户端。只做 clone 需要的最小子集：
 * messages → delta.content；以及非流式的 JSON 生成（示意图 scene）。
 */
@Slf4j
@Component
public class CloneLlmClient {

    private final Gson gson = new Gson();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final VideoTutorCloneProperties props;
    private final Provider primary;
    private final Provider fallback;

    public CloneLlmClient(VideoTutorCloneProperties props) {
        this.props = props;
        String key = VideoTutorSecretResolver.resolve(props.getApiKey(), props.getModelsYaml(), props.getApiKeyRef(),
                "video-tutor.clone.api-key");
        this.primary = new Provider("primary", props.getBaseUrl(), props.getModel(), key);
        String fallbackKey = VideoTutorSecretResolver.resolve(
                props.getFallbackApiKey(), props.getModelsYaml(), props.getFallbackApiKeyRef(),
                "video-tutor.clone.fallback-api-key");
        this.fallback = new Provider("fallback", props.getFallbackBaseUrl(), props.getFallbackModel(), fallbackKey);
        if (props.isEnabled() && !primary.enabled() && !fallback.enabled()) {
            log.warn("[video-tutor/clone] no LLM key; check models.yaml api_keys.{}", props.getApiKeyRef());
        }
    }

    public boolean isReady() {
        return props.isEnabled() && (primary.enabled() || fallback.enabled());
    }

    public List<Provider> providers() {
        return java.util.stream.Stream.of(primary, fallback).filter(Provider::enabled).toList();
    }

    /**
     * 流式对话。返回实际使用的 provider；如果当前 provider 失败且还没有输出，抛错交给调用方换下一个。
     */
    public Provider streamChat(
            Provider provider,
            List<Map<String, String>> messages,
            Consumer<String> onDelta) throws IOException {
        return streamChat(provider, messages, onDelta, props.getTemperature(), props.getMaxTokens());
    }

    public Provider streamChat(
            Provider provider,
            List<Map<String, String>> messages,
            Consumer<String> onDelta,
            double temperature,
            int maxTokens) throws IOException {
        JsonObject payload = basePayload(provider, messages, true);
        payload.addProperty("max_tokens", maxTokens);
        payload.addProperty("temperature", temperature);
        HttpRequest request = request(provider, payload);
        HttpResponse<InputStream> response = send(request);
        if (response.statusCode() != 200) {
            String body = readSmall(response.body());
            throw new IOException(provider.name + " HTTP " + response.statusCode() + ": " + body);
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith(":")) {
                    continue;
                }
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if (data.equals("[DONE]")) {
                    break;
                }
                try {
                    JsonObject chunk = JsonParser.parseString(data).getAsJsonObject();
                    JsonArray choices = chunk.has("choices") ? chunk.getAsJsonArray("choices") : null;
                    if (choices == null || choices.isEmpty()) {
                        continue;
                    }
                    JsonObject choice = choices.get(0).getAsJsonObject();
                    JsonObject delta = choice.has("delta") ? choice.getAsJsonObject("delta") : null;
                    if (delta != null && delta.has("content") && !delta.get("content").isJsonNull()) {
                        String text = delta.get("content").getAsString();
                        if (!text.isEmpty()) {
                            onDelta.accept(text);
                        }
                    }
                } catch (Exception e) {
                    log.debug("[video-tutor/clone] bad sse chunk: {}", e.getMessage());
                }
            }
        }
        return provider;
    }

    /** 非流式 JSON（示意图 scene）。 */
    public JsonObject chatJson(Provider provider, List<Map<String, String>> messages,
                               double temperature, int maxTokens, int timeoutSeconds) throws IOException {
        JsonObject payload = basePayload(provider, messages, false);
        payload.addProperty("max_tokens", maxTokens);
        payload.addProperty("temperature", temperature);
        HttpRequest request = request(provider, payload, timeoutSeconds);
        HttpResponse<InputStream> response = send(request);
        if (response.statusCode() != 200) {
            throw new IOException(provider.name + " HTTP " + response.statusCode() + ": " + readSmall(response.body()));
        }
        String body;
        try (InputStream in = response.body()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonArray choices = root.has("choices") ? root.getAsJsonArray("choices") : null;
        if (choices == null || choices.isEmpty()) {
            throw new IOException("empty choices");
        }
        JsonObject choice = choices.get(0).getAsJsonObject();
        JsonObject message = choice.has("message") ? choice.getAsJsonObject("message") : null;
        String content = message != null && message.has("content") && !message.get("content").isJsonNull()
                ? message.get("content").getAsString() : "";
        if (content.isBlank()) {
            throw new IOException("empty json response");
        }
        return extractJsonObject(content);
    }

    private JsonObject basePayload(Provider provider, List<Map<String, String>> messages, boolean stream) {
        JsonObject payload = new JsonObject();
        payload.addProperty("model", provider.model);
        JsonArray array = new JsonArray();
        for (Map<String, String> message : messages) {
            JsonObject item = new JsonObject();
            item.addProperty("role", message.get("role"));
            item.addProperty("content", message.get("content"));
            array.add(item);
        }
        payload.add("messages", array);
        payload.addProperty("stream", stream);
        if (provider.baseUrl.contains("openrouter")) {
            JsonObject reasoning = new JsonObject();
            reasoning.addProperty("enabled", props.isReasoningEnabled());
            payload.add("reasoning", reasoning);
        }
        return payload;
    }

    private HttpRequest request(Provider provider, JsonObject payload) {
        return request(provider, payload, props.getTimeoutSeconds());
    }

    private HttpRequest request(Provider provider, JsonObject payload, int timeoutSeconds) {
        return HttpRequest.newBuilder()
                .uri(URI.create(provider.baseUrl.replaceAll("/+$", "") + "/chat/completions"))
                .timeout(Duration.ofSeconds(Math.max(5, timeoutSeconds)))
                .header("Authorization", "Bearer " + provider.apiKey)
                .header("Content-Type", "application/json")
                .header("HTTP-Referer", "https://verla.io")
                .header("X-Title", "Verla Video Tutor")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload)))
                .build();
    }

    private HttpResponse<InputStream> send(HttpRequest request) throws IOException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
    }

    private static String readSmall(InputStream in) {
        try (InputStream stream = in) {
            byte[] bytes = stream.readNBytes(600);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    static JsonObject extractJsonObject(String text) throws IOException {
        String cleaned = text.replaceAll("^```(?:json)?", "").replaceAll("```$", "").trim();
        int start = cleaned.indexOf('{');
        if (start < 0) {
            throw new IOException("no json object in response");
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < cleaned.length(); i += 1) {
            char ch = cleaned.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }
            if (ch == '"') {
                inString = true;
            } else if (ch == '{') {
                depth += 1;
            } else if (ch == '}') {
                depth -= 1;
                if (depth == 0) {
                    return JsonParser.parseString(cleaned.substring(start, i + 1)).getAsJsonObject();
                }
            }
        }
        throw new IOException("unterminated json object");
    }

    public record Provider(String name, String baseUrl, String model, String apiKey) {
        public boolean enabled() {
            return apiKey != null && !apiKey.isBlank();
        }
    }
}
