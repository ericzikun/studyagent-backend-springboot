package com.studyagent.service.application.verla.stt;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.studyagent.service.application.verla.VideoTutorDefaultSecrets;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 浏览器麦克风 → Java → DashScope fun-asr-realtime 的实时转写。
 *
 * 浏览器只看到 Java 的 /video-tutor/stt（partial/final JSON），厂商 key 不出后端。
 * 协议与 clone 的 server_py/stt.py 一致：run-task → 持续推 16k PCM16 binary →
 * result-generated 文本；task-finished/task-failed 收口。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoTutorSttHandler extends AbstractWebSocketHandler {

    private static final int MAX_PENDING_FRAMES = 300;

    private final VideoTutorSttProperties props;
    private final ExecutorService executor = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "video-tutor-stt");
        thread.setDaemon(true);
        return thread;
    });
    private volatile String apiKey;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long cid = (Long) session.getAttributes().get("conversationId");
        String userId = (String) session.getAttributes().get("clerkUserId");
        if (cid == null || userId == null || !isReady()) {
            send(session, Map.of("type", "error", "message", "语音识别不可用"));
            closeQuietly(session);
            return;
        }
        AsrSession asr = new AsrSession(session, props, apiKey(), hints());
        session.getAttributes().put("asr", asr);
        executor.submit(asr::start);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        AsrSession asr = (AsrSession) session.getAttributes().get("asr");
        if (asr != null) {
            ByteBuffer payload = message.getPayload();
            byte[] pcm = new byte[payload.remaining()];
            payload.get(pcm);
            asr.feed(pcm);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            JsonObject json = JsonParser.parseString(message.getPayload()).getAsJsonObject();
            String type = json.has("type") ? json.get("type").getAsString() : "";
            AsrSession asr = (AsrSession) session.getAttributes().get("asr");
            if (asr == null) return;
            if ("reset".equals(type)) {
                AsrSession next = new AsrSession(session, props, apiKey(), hints());
                session.getAttributes().put("asr", next);
                asr.close();
                executor.submit(next::start);
            } else if ("finish".equals(type)) {
                asr.finish();
            }
        } catch (Exception e) {
            log.debug("[video-tutor/stt] bad client message: {}", e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        AsrSession asr = (AsrSession) session.getAttributes().get("asr");
        if (asr != null) asr.close();
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.info("[video-tutor/stt] transport error: {}", exception.getMessage());
        AsrSession asr = (AsrSession) session.getAttributes().get("asr");
        if (asr != null) asr.close();
        closeQuietly(session);
    }

    private boolean isReady() {
        return props.isEnabled() && !apiKey().isBlank();
    }

    private String apiKey() {
        String cached = apiKey;
        if (cached != null) return cached;
        synchronized (this) {
            if (apiKey != null) return apiKey;
            String key = props.getApiKey() == null ? "" : props.getApiKey().trim();
            if (key.isEmpty()) {
                key = resolveFromModelsYaml();
            }
            if (key.isEmpty()) key = VideoTutorDefaultSecrets.get("video-tutor.stt.api-key");
            if (key.isEmpty()) key = VideoTutorDefaultSecrets.get("video-tutor.tts.api-key");
            apiKey = key;
            if (key.isBlank()) {
                log.warn("[video-tutor/stt] no api key; STT disabled");
            }
            return apiKey;
        }
    }

    private String resolveFromModelsYaml() {
        String envPath = System.getenv("MODELS_YAML");
        if (envPath != null && !envPath.isBlank()) {
            String found = readKey(Path.of(envPath.trim()));
            if (!found.isBlank()) return found;
        }
        if (props.getModelsYaml() != null && !props.getModelsYaml().isBlank()) {
            String found = readKey(Path.of(props.getModelsYaml().trim()));
            if (!found.isBlank()) return found;
        }
        for (String candidate : List.of(
                "/app/verla_agent/app/models/models.yaml",
                "../verla_agent/app/models/models.yaml",
                "./app/models/models.yaml")) {
            String found = readKey(Path.of(candidate));
            if (!found.isBlank()) return found;
        }
        return "";
    }

    private String readKey(Path path) {
        if (!Files.isReadable(path)) return "";
        try (InputStream in = Files.newInputStream(path)) {
            Object loaded = new Yaml().load(in);
            if (!(loaded instanceof Map<?, ?> root)) return "";
            Object keys = root.get("api_keys");
            if (!(keys instanceof Map<?, ?> map)) return "";
            Object value = map.get(props.getApiKeyRef());
            return value instanceof String text ? text.trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private List<String> hints() {
        String raw = props.getLanguageHints() == null ? "zh,en" : props.getLanguageHints();
        List<String> hints = java.util.Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .limit(2)
                .toList();
        return hints.isEmpty() ? List.of("zh") : hints;
    }

    private static void send(WebSocketSession session, Map<String, Object> payload) {
        if (!session.isOpen()) return;
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(new com.google.gson.Gson().toJson(payload)));
            }
        } catch (Exception ignored) {
            // 客户端已断开
        }
    }

    private static void closeQuietly(WebSocketSession session) {
        try {
            session.close();
        } catch (Exception ignored) {
            // already closed
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private static final class AsrSession implements WebSocket.Listener {
        private final WebSocketSession client;
        private final VideoTutorSttProperties props;
        private final String key;
        private final List<String> hints;
        private final LinkedBlockingQueue<byte[]> pending = new LinkedBlockingQueue<>();
        private final CountDownLatch started = new CountDownLatch(1);
        private final java.util.concurrent.atomic.AtomicReference<String> failure =
                new java.util.concurrent.atomic.AtomicReference<>("");
        private volatile WebSocket vendor;
        private volatile boolean ready;
        private volatile boolean closed;
        private volatile String taskId = "";

        AsrSession(WebSocketSession client, VideoTutorSttProperties props, String key, List<String> hints) {
            this.client = client;
            this.props = props;
            this.key = key;
            this.hints = hints;
        }

        void start() {
            if (closed) return;
            try {
                this.taskId = UUID.randomUUID().toString().replace("-", "");
                this.vendor = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(Math.max(1, props.getConnectTimeoutSeconds())))
                        .build()
                        .newWebSocketBuilder()
                        .header("Authorization", "bearer " + key)
                        .header("X-DashScope-DataInspection", "enable")
                        .connectTimeout(Duration.ofSeconds(Math.max(1, props.getConnectTimeoutSeconds())))
                        .buildAsync(URI.create(props.getWsUrl()), this)
                        .get(Math.max(1, props.getConnectTimeoutSeconds()), TimeUnit.SECONDS);
                vendor.sendText(runTask(), true).join();
                if (!started.await(Math.max(1, props.getTaskStartTimeoutSeconds()), TimeUnit.SECONDS)
                        || !failure.get().isEmpty()) {
                    throw new IllegalStateException(failure.get().isEmpty() ? "task-started timeout" : failure.get());
                }
                ready = true;
                send(client, Map.of("type", "ready"));
                for (byte[] frame = pending.poll(); frame != null; frame = pending.poll()) {
                    forward(frame);
                }
                log.info("[video-tutor/stt] ready hints={} model={}", hints, props.getModel());
            } catch (Exception e) {
                closed = true;
                log.info("[video-tutor/stt] start failed: {}", e.getMessage());
                send(client, Map.of("type", "error", "message", e.getMessage() == null ? "识别器启动失败" : e.getMessage()));
                closeQuietly(client);
            }
        }

        void feed(byte[] pcm) {
            if (closed || pcm == null || pcm.length == 0) return;
            if (!ready) {
                if (pending.size() >= MAX_PENDING_FRAMES) pending.poll();
                pending.offer(pcm);
                return;
            }
            forward(pcm);
        }

        private void forward(byte[] pcm) {
            WebSocket ws = vendor;
            if (ws == null || closed) return;
            try {
                ws.sendBinary(ByteBuffer.wrap(pcm), true);
            } catch (Exception e) {
                closed = true;
            }
        }

        void clearPending() {
            pending.clear();
        }

        void finish() {
            WebSocket ws = vendor;
            if (ws == null || closed) return;
            try {
                ws.sendText(finishTask(), true);
            } catch (Exception ignored) {
                // closed
            }
        }

        void close() {
            closed = true;
            WebSocket ws = vendor;
            if (ws != null) {
                try {
                    ws.abort();
                } catch (Exception ignored) {
                    // closed
                }
            }
            started.countDown();
        }

        private String runTask() {
            JsonObject parameters = new JsonObject();
            parameters.addProperty("format", "pcm");
            parameters.addProperty("sample_rate", 16000);
            parameters.add("language_hints", new com.google.gson.Gson().toJsonTree(hints));
            parameters.addProperty("punctuation_prediction_enabled", true);
            parameters.addProperty("inverse_text_normalization_enabled", true);
            parameters.addProperty("max_sentence_silence", 600);
            JsonObject payload = new JsonObject();
            payload.addProperty("task_group", "audio");
            payload.addProperty("task", "asr");
            payload.addProperty("function", "recognition");
            payload.addProperty("model", props.getModel());
            payload.add("parameters", parameters);
            payload.add("input", new JsonObject());
            JsonObject header = new JsonObject();
            header.addProperty("action", "run-task");
            header.addProperty("task_id", taskId);
            header.addProperty("streaming", "duplex");
            JsonObject body = new JsonObject();
            body.add("header", header);
            body.add("payload", payload);
            return body.toString();
        }

        private String finishTask() {
            JsonObject header = new JsonObject();
            header.addProperty("action", "finish-task");
            header.addProperty("task_id", taskId);
            JsonObject body = new JsonObject();
            body.add("header", header);
            body.add("payload", new JsonObject());
            return body.toString();
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            this.vendor = webSocket;
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            String text = data == null ? "" : data.toString();
            try {
                JsonObject event = JsonParser.parseString(text).getAsJsonObject();
                JsonObject header = event.has("header") ? event.getAsJsonObject("header") : null;
                String name = header != null && header.has("event") ? header.get("event").getAsString() : "";
                if ("task-started".equals(name)) {
                    started.countDown();
                } else if ("task-failed".equals(name)) {
                    failure.set(header.has("error_message")
                            ? header.get("error_message").getAsString() : "识别失败");
                    started.countDown();
                    send(client, Map.of("type", "error", "message", failure.get()));
                    closed = true;
                } else if ("task-finished".equals(name)) {
                    send(client, Map.of("type", "closed", "reason", "finished"));
                    closed = true;
                } else if ("result-generated".equals(name)) {
                    JsonObject payload = event.has("payload") ? event.getAsJsonObject("payload") : null;
                    JsonObject output = payload != null && payload.has("output")
                            ? payload.getAsJsonObject("output") : null;
                    JsonObject sentence = output != null && output.has("sentence")
                            ? output.getAsJsonObject("sentence") : null;
                    String sentenceText = sentence != null && sentence.has("text")
                            ? sentence.get("text").getAsString().trim() : "";
                    if (!sentenceText.isEmpty()) {
                        boolean end = sentence.has("sentence_end") && sentence.get("sentence_end").getAsBoolean();
                        send(client, Map.of(
                                "type", end ? "final" : "partial",
                                "text", sentenceText
                        ));
                    }
                }
            } catch (Exception e) {
                log.debug("[video-tutor/stt] bad vendor event: {}", e.getMessage());
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed = true;
            started.countDown();
            send(client, Map.of("type", "closed", "reason", reason == null ? "closed" : reason));
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closed = true;
            started.countDown();
            failure.set(error.getMessage() == null ? "vendor error" : error.getMessage());
            send(client, Map.of("type", "error", "message", failure.get()));
        }
    }
}
