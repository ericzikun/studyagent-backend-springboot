package com.studyagent.service.application.verla.tts;

import com.google.gson.JsonObject;
import com.studyagent.service.application.verla.VideoTutorDefaultSecrets;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Video Tutor 的 Java 直连 CosyVoice TTS。
 *
 * 为什么是流：clone 的体验是"开口即出声"，整句合成完再返回会把首声拖到 2–5s。
 * 这里在拿到第一块 PCM 后才开始 HTTP 响应，既能尽早出声，也能让建连/task-failed
 * 仍然是一个干净的 502，而不是浏览器拿到 200 后一片静音。
 *
 * 协议与 Python 侧 tts.py 同源：run-task → continue-task → finish-task，
 * 二进制帧就是 PCM；参数 format=pcm / sample_rate=24000。
 */
@Slf4j
@Service
public class VideoTutorTtsService {

    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern MATH = Pattern.compile("\\$[^$]*\\$");
    private static final Pattern MARK = Pattern.compile("[*_`#>]+");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final byte[] EMPTY = new byte[0];

    private final VideoTutorTtsProperties props;
    private final HttpClient http;
    private final Semaphore slots;
    private final String apiKey;

    public VideoTutorTtsService(VideoTutorTtsProperties props) {
        this.props = props;
        this.slots = new Semaphore(Math.max(1, props.getMaxConcurrency()));
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, props.getConnectTimeoutSeconds())))
                .build();
        this.apiKey = resolveApiKey(props);
        if (props.isEnabled() && apiKey.isBlank()) {
            log.warn("[video-tutor/tts] enabled but no api key; TTS endpoint will return 503. "
                    + "Set verla.video-tutor.tts.api-key or models-yaml/api-key-ref.");
        }
    }

    public boolean isReady() {
        return props.isEnabled() && !apiKey.isBlank();
    }

    public String voice() {
        return props.getVoice();
    }

    public int sampleRate() {
        return props.getSampleRate();
    }

    /**
     * 打开一条流。只有拿到第一块音频才返回；任何失败抛 {@link TtsUnavailableException}。
     * 调用方负责在响应结束时 {@link Stream#close()}。
     */
    public Stream open(String rawText, Double rawSpeed) {
        if (!isReady()) {
            throw new TtsUnavailableException("tts not configured");
        }
        String text = toSpoken(rawText, props.getMaxChars());
        if (text.isBlank()) {
            throw new TtsUnavailableException("empty text");
        }
        double speed = rawSpeed == null ? 1.0 : rawSpeed;
        if (!(speed >= 0.5 && speed <= 2.0)) {
            speed = 1.0;
        }
        boolean acquire = false;
        try {
            acquire = slots.tryAcquire(Math.max(1, props.getConnectTimeoutSeconds() + 5L), TimeUnit.SECONDS);
            if (!acquire) {
                throw new TtsUnavailableException("tts busy");
            }
            TtsUnavailableException last = null;
            for (int attempt = 0; attempt < 2; attempt += 1) {
                Session session = new Session();
                try {
                    WebSocket ws = http.newWebSocketBuilder()
                            .header("Authorization", "bearer " + apiKey)
                            .header("X-DashScope-DataInspection", props.getDataInspection())
                            .connectTimeout(Duration.ofSeconds(Math.max(1, props.getConnectTimeoutSeconds())))
                            .buildAsync(URI.create(props.getWsUrl()), session)
                            .get(Math.max(1, props.getConnectTimeoutSeconds()), TimeUnit.SECONDS);
                    session.attach(ws);
                    String taskId = UUID.randomUUID().toString().replace("-", "");
                    ws.sendText(runTask(taskId, speed), true).join();
                    if (!session.awaitStarted(props.getConnectTimeoutSeconds())) {
                        session.abort();
                        throw new TtsUnavailableException("task not started: " + session.failure());
                    }
                    ws.sendText(continueTask(taskId, text), true).join();
                    ws.sendText(finishTask(taskId), true).join();
                    byte[] first = session.awaitFirst(props.getFirstAudioTimeoutSeconds());
                    log.info("[video-tutor/tts] stream open chars={} bytes={} voice={}",
                            text.length(), first.length, props.getVoice());
                    Stream stream = new Stream(first, session, slots, props.getReadTimeoutSeconds());
                    acquire = false;
                    return stream;
                } catch (TtsUnavailableException e) {
                    last = e;
                } catch (Exception e) {
                    session.abort();
                    last = new TtsUnavailableException(
                            e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "" : e.getMessage()));
                }
                if (attempt == 0) {
                    log.info("[video-tutor/tts] retry once chars={} reason={}", text.length(),
                            last == null ? "unknown" : last.getMessage());
                }
            }
            throw last == null ? new TtsUnavailableException("tts failed") : last;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TtsUnavailableException("interrupted");
        } finally {
            if (acquire) {
                slots.release();
            }
        }
    }

    private String runTask(String taskId, double speed) {
        JsonObject parameters = new JsonObject();
        parameters.addProperty("text_type", "PlainText");
        parameters.addProperty("voice", props.getVoice());
        parameters.addProperty("format", "pcm");
        parameters.addProperty("sample_rate", props.getSampleRate());
        if (Math.abs(speed - 1.0) > 0.01) {
            parameters.addProperty("rate", Math.round(speed * 100.0) / 100.0);
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("task_group", "audio");
        payload.addProperty("task", "tts");
        payload.addProperty("function", "SpeechSynthesizer");
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

    private String continueTask(String taskId, String text) {
        JsonObject input = new JsonObject();
        input.addProperty("text", text);
        JsonObject payload = new JsonObject();
        payload.add("input", input);
        JsonObject header = new JsonObject();
        header.addProperty("action", "continue-task");
        header.addProperty("task_id", taskId);
        header.addProperty("streaming", "duplex");
        JsonObject body = new JsonObject();
        body.add("header", header);
        body.add("payload", payload);
        return body.toString();
    }

    private String finishTask(String taskId) {
        JsonObject payload = new JsonObject();
        payload.add("input", new JsonObject());
        JsonObject header = new JsonObject();
        header.addProperty("action", "finish-task");
        header.addProperty("task_id", taskId);
        header.addProperty("streaming", "duplex");
        JsonObject body = new JsonObject();
        body.add("header", header);
        body.add("payload", payload);
        return body.toString();
    }

    static String toSpoken(String raw, int maxChars) {
        String text = raw == null ? "" : raw;
        text = TAG.matcher(text).replaceAll(" ");
        text = MATH.matcher(text).replaceAll(" 公式 ");
        text = MARK.matcher(text).replaceAll("");
        text = text.replace("\\", " ").replace("/", " ");
        text = SPACES.matcher(text).replaceAll(" ").trim();
        if (maxChars > 0 && text.length() > maxChars) {
            text = text.substring(0, maxChars);
        }
        return text;
    }

    private static String resolveApiKey(VideoTutorTtsProperties props) {
        String direct = props.getApiKey() == null ? "" : props.getApiKey().trim();
        if (!direct.isEmpty()) {
            return direct;
        }
        String envPath = System.getenv("MODELS_YAML");
        if (envPath != null && !envPath.isBlank()) {
            String fromEnvPath = readKey(Path.of(envPath.trim()), props.getApiKeyRef());
            if (!fromEnvPath.isBlank()) {
                return fromEnvPath;
            }
        }
        if (props.getModelsYaml() != null && !props.getModelsYaml().isBlank()) {
            String fromConfigured = readKey(Path.of(props.getModelsYaml().trim()), props.getApiKeyRef());
            if (!fromConfigured.isBlank()) {
                return fromConfigured;
            }
        }
        for (String candidate : List.of(
                "/app/verla_agent/app/models/models.yaml",
                "../verla_agent/app/models/models.yaml",
                "./app/models/models.yaml")) {
            String found = readKey(Path.of(candidate), props.getApiKeyRef());
            if (!found.isBlank()) {
                return found;
            }
        }
        String preset = VideoTutorDefaultSecrets.get("video-tutor.tts.api-key");
        if (!preset.isBlank()) {
            log.info("[video-tutor/tts] using temporary default key");
            return preset;
        }
        return "";
    }

    private static String readKey(Path path, String ref) {
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
                log.info("[video-tutor/tts] loaded key ref={} from {}", ref, path);
                return text.trim();
            }
        } catch (Exception e) {
            log.warn("[video-tutor/tts] read models.yaml failed path={} err={}", path, e.getMessage());
        }
        return "";
    }

    /** 一次已打开的音频流；close() 必须被调用（释放并发槽并断开上游）。 */
    public static final class Stream implements AutoCloseable {
        private final byte[] first;
        private final Session session;
        private final Semaphore slots;
        private final int readTimeoutSeconds;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private Stream(byte[] first, Session session, Semaphore slots, int readTimeoutSeconds) {
            this.first = first;
            this.session = session;
            this.slots = slots;
            this.readTimeoutSeconds = readTimeoutSeconds;
        }

        public void writeTo(OutputStream out) throws IOException {
            try {
                out.write(first);
                out.flush();
                while (true) {
                    Chunk chunk;
                    try {
                        chunk = session.next(readTimeoutSeconds);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    if (chunk == null) {
                        log.warn("[video-tutor/tts] stream stalled");
                        break;
                    }
                    if (chunk.end || chunk.failed) {
                        break;
                    }
                    if (chunk.data.length == 0) {
                        continue;
                    }
                    out.write(chunk.data);
                    out.flush();
                }
            } finally {
                close();
            }
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                session.abort();
                slots.release();
            }
        }
    }

    public static final class TtsUnavailableException extends RuntimeException {
        public TtsUnavailableException(String message) {
            super(message);
        }
    }

    private record Chunk(byte[] data, boolean end, boolean failed) {
    }

    private static final Chunk END = new Chunk(EMPTY, true, false);
    private static final Chunk FAILED = new Chunk(EMPTY, false, true);

    private static final class Session implements WebSocket.Listener {
        private final BlockingQueue<Chunk> queue = new LinkedBlockingQueue<>(256);
        private final CountDownLatch started = new CountDownLatch(1);
        private final AtomicReference<String> failure = new AtomicReference<>("");
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private volatile WebSocket socket;

        void attach(WebSocket socket) {
            this.socket = socket;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            this.socket = webSocket;
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
                    String message = header.has("error_message") ? header.get("error_message").getAsString() : "task-failed";
                    failure.set(message);
                    started.countDown();
                    queue.offer(FAILED);
                } else if ("task-finished".equals(name)) {
                    queue.offer(END);
                }
            } catch (Exception e) {
                log.debug("[video-tutor/tts] bad event {}: {}", text, e.getMessage());
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            if (bytes.length > 0) {
                try {
                    queue.put(new Chunk(bytes, false, false));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed.set(true);
            started.countDown();
            queue.offer(END);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            failure.set(error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage()));
            closed.set(true);
            started.countDown();
            queue.offer(FAILED);
        }

        boolean awaitStarted(int seconds) throws InterruptedException {
            return started.await(Math.max(1, seconds), TimeUnit.SECONDS) && failure.get().isEmpty();
        }

        byte[] awaitFirst(int seconds) throws InterruptedException, TtsUnavailableException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(1, seconds));
            while (true) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    abort();
                    throw new TtsUnavailableException("first audio timeout");
                }
                Chunk chunk = queue.poll(left, TimeUnit.NANOSECONDS);
                if (chunk == null) {
                    abort();
                    throw new TtsUnavailableException("first audio timeout");
                }
                if (chunk.failed || chunk.end) {
                    abort();
                    throw new TtsUnavailableException("tts ended before audio: " + failure.get());
                }
                if (chunk.data.length > 0) {
                    return chunk.data;
                }
            }
        }

        Chunk next(int seconds) throws InterruptedException {
            return queue.poll(Math.max(1, seconds), TimeUnit.SECONDS);
        }

        String failure() {
            return failure.get();
        }

        void abort() {
            WebSocket ws = socket;
            if (ws != null) {
                try {
                    ws.abort();
                } catch (Exception ignored) {
                    // 已经关闭
                }
            }
        }
    }
}
