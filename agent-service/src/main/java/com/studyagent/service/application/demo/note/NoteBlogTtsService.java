package com.studyagent.service.application.demo.note;

import com.studyagent.service.application.verla.tts.VideoTutorTtsService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 博客语音：按自然段调用现有 CosyVoice（Java 直连）合成 WAV 片段，落本地缓存，
 * 返回带 HMAC 签名的音频 URL。PCM 为 24k/mono/s16le，时长按样本数计算。
 */
@Slf4j
@Service
public class NoteBlogTtsService {

    private static final Pattern IMAGE = Pattern.compile("!\\[[^\\]]*\\]\\([^)]*\\)");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)\\]\\([^)]*\\)");
    private static final Pattern HTML = Pattern.compile("<[^>]+>");
    private static final Pattern MATH = Pattern.compile("\\$[^$]*\\$");
    private static final Pattern MARK = Pattern.compile("[#>*_`~]+");
    private static final Pattern LIST = Pattern.compile("(?m)^\\s*([-+*]|\\d+[.)])\\s+");
    private static final Pattern SPACES = Pattern.compile("[ \\t]+");
    private static final Pattern BLANK_LINES = Pattern.compile("\\n\\s*\\n");
    private static final Pattern SENTENCE = Pattern.compile("(?<=[。！？!?；;])");
    private static final Pattern FILE_NAME = Pattern.compile("^[a-f0-9]{64}\\.wav$");
    /** 播客文稿的说话人标记：`**主持人**：……` / `**Host**: ...`。 */
    private static final Pattern SPEAKER_LINE =
            Pattern.compile("^\\s*\\*\\*([^*\\n]{1,24})\\*\\*\\s*[：:]\\s*(.+)$");

    private final VideoTutorTtsService tts;
    private final NoteBlogAudioProperties props;
    private final String secret;
    /**
     * 所有预生成/按需请求共享的合成池：以前每次 cache() 新建 4 线程，
     * 两个预生成任务叠加就是 8 路 CosyVoice 并发，且多副本还会翻倍。
     */
    private final ThreadPoolExecutor synthExecutor;

    public NoteBlogTtsService(VideoTutorTtsService tts, NoteBlogAudioProperties props) {
        this.tts = tts;
        this.props = props;
        this.secret = props.getSecret() == null || props.getSecret().isBlank()
                ? UUID.randomUUID().toString().replace("-", "")
                : props.getSecret().trim();
        if (props.isEnabled() && (props.getSecret() == null || props.getSecret().isBlank())) {
            log.warn("[note/blog-tts] secret is empty; using an ephemeral one (URLs invalid after restart)");
        }
        int concurrency = Math.max(1, props.getSynthConcurrency());
        int queueCapacity = Math.max(1, props.getSynthQueueCapacity());
        this.synthExecutor = new ThreadPoolExecutor(
                concurrency, concurrency, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                runnable -> {
                    Thread thread = new Thread(runnable, "note-blog-tts");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        log.info("[note/blog-tts] synth executor concurrency={} queueCapacity={}",
                concurrency, queueCapacity);
    }

    @PreDestroy
    public void shutdown() {
        synthExecutor.shutdownNow();
    }

    public boolean isReady() {
        return props.isEnabled() && tts.isReady();
    }

    public record Segment(int index, int paragraphIndex, String text, String url, long durationMs,
                          String speaker) {
    }

    /** 文稿里的一个说话回合；speaker 为空表示旧文章/无标记段落。 */
    record Turn(int paragraphIndex, String speaker, String text) {
    }

    /** fullUrl = 整篇拼接后的单轨 WAV；前端优先用它避免逐段切源造成卡顿/自动播放拦截。 */
    public record Payload(List<Segment> segments, long totalDurationMs, String voice,
                          String fullUrl) {
    }

    /** 本地缓存完成后的分段（fileName 用于 OSS 上传与本地签名 URL）。 */
    public record CachedSegment(int index, int paragraphIndex, String text, String fileName,
                                long durationMs, String speaker) {
    }

    public record CachedPayload(List<CachedSegment> segments, long totalDurationMs, String voice,
                                String fullFileName) {
    }

    public Payload synthesize(long conversationId, String blogMd) {
        CachedPayload cached = cache(conversationId, blogMd);
        List<Segment> segments = new ArrayList<>(cached.segments().size());
        for (CachedSegment segment : cached.segments()) {
            segments.add(new Segment(
                    segment.index(), segment.paragraphIndex(), segment.text(),
                    signedUrl(conversationId, segment.fileName()), segment.durationMs(),
                    segment.speaker()));
        }
        String fullUrl = cached.fullFileName() == null
                ? null : signedUrl(conversationId, cached.fullFileName());
        log.info("[note/blog-tts] synthesized convId={} segments={} totalMs={} fullUrl={}",
                conversationId, segments.size(), cached.totalDurationMs(), fullUrl != null);
        return new Payload(segments, cached.totalDurationMs(), cached.voice(), fullUrl);
    }

    /**
     * 合成并落本地缓存（幂等：已存在的 sha 文件直接读时长）。
     * <p>预生成任务在后台线程调用它，再把 WAV 上传 OSS。
     */
    public CachedPayload cache(long conversationId, String blogMd) {
        if (!isReady()) {
            throw new TtsUnavailable("语音服务暂不可用，请稍后重试");
        }
        List<Turn> turns = splitTurns(blogMd);
        if (turns.isEmpty()) {
            throw new TtsUnavailable("播客还没有可朗读的内容");
        }
        int sampleRate = tts.sampleRate();
        String hostVoice = blankToFallback(props.getHostVoice(), tts.voice());
        String guestVoice = blankToFallback(props.getGuestVoice(), hostVoice);

        Map<String, String> speakerVoices = new LinkedHashMap<>();
        boolean firstSpeakerAssigned = false;
        for (Turn turn : turns) {
            String speaker = turn.speaker();
            if (speaker == null || speakerVoices.containsKey(speaker)) {
                continue;
            }
            String role = speakerRole(speaker);
            String voice;
            if ("host".equals(role)) {
                voice = hostVoice;
            } else if ("guest".equals(role)) {
                voice = guestVoice;
            } else {
                // 自定义名字：第一人当主持、第二人当讲解，保证仍是一男一女
                voice = firstSpeakerAssigned ? guestVoice : hostVoice;
            }
            speakerVoices.put(speaker, voice);
            firstSpeakerAssigned = true;
        }

        Path dir = storageDir().resolve(String.valueOf(conversationId));
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new TtsUnavailable("语音缓存目录不可写");
        }

        List<String> chunkTexts = new ArrayList<>();
        List<Integer> chunkParagraphs = new ArrayList<>();
        List<String> chunkVoices = new ArrayList<>();
        List<String> chunkSpeakers = new ArrayList<>();
        List<String> fileNames = new ArrayList<>();
        LinkedHashSet<String> usedVoices = new LinkedHashSet<>();
        for (Turn turn : turns) {
            String voice = turn.speaker() == null
                    ? hostVoice
                    : speakerVoices.getOrDefault(turn.speaker(), hostVoice);
            usedVoices.add(voice);
            for (String chunk : chunkText(turn.text())) {
                if (chunkTexts.size() >= props.getMaxSegments()) {
                    break;
                }
                chunkTexts.add(chunk);
                chunkParagraphs.add(turn.paragraphIndex());
                chunkVoices.add(voice);
                chunkSpeakers.add(turn.speaker());
                fileNames.add(sha256(chunk + "|" + voice + "|" + sampleRate) + ".wav");
            }
            if (chunkTexts.size() >= props.getMaxSegments()) {
                break;
            }
        }

        long[] durations = new long[chunkTexts.size()];
        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < chunkTexts.size(); i += 1) {
            Path file = dir.resolve(fileNames.get(i));
            if (Files.isRegularFile(file) && Files.isReadable(file)) {
                durations[i] = readDurationMs(file, sampleRate);
            } else {
                missing.add(i);
            }
        }
        if (!missing.isEmpty()) {
            List<Future<Long>> futures = new ArrayList<>();
            try {
                for (int index : missing) {
                    Path file = dir.resolve(fileNames.get(index));
                    String chunk = chunkTexts.get(index);
                    String voice = chunkVoices.get(index);
                    futures.add(synthExecutor.submit(
                            () -> synthesizeToFile(chunk, file, sampleRate, voice)));
                }
                for (int i = 0; i < futures.size(); i += 1) {
                    durations[missing.get(i)] = futures.get(i).get();
                }
            } catch (Exception e) {
                for (Future<Long> future : futures) {
                    future.cancel(true);
                }
                log.warn("[note/blog-tts] synth failed convId={}: {}", conversationId, e.getMessage());
                throw new TtsUnavailable("语音合成暂不可用，请稍后重试");
            }
        }

        List<CachedSegment> segments = new ArrayList<>();
        for (int i = 0; i < chunkTexts.size(); i += 1) {
            segments.add(new CachedSegment(
                    i, chunkParagraphs.get(i), chunkTexts.get(i), fileNames.get(i), durations[i],
                    chunkSpeakers.get(i)));
        }
        if (segments.isEmpty()) {
            throw new TtsUnavailable("播客还没有可朗读的内容");
        }
        FullAudio full;
        try {
            full = buildFullAudio(dir, fileNames, sampleRate);
        } catch (IOException e) {
            log.warn("[note/blog-tts] full track build failed: {}", e.getMessage());
            throw new TtsUnavailable("语音合成失败，请稍后重试");
        }
        String voiceLabel = String.join("+", usedVoices);
        log.info("[note/blog-tts] voices convId={} voices={} speakers={}",
                conversationId, voiceLabel, speakerVoices.keySet());
        return new CachedPayload(segments, full.durationMs(), voiceLabel, full.fileName());
    }

    private record FullAudio(String fileName, long durationMs) {
    }

    /**
     * 把所有分段拼成一条单轨 WAV：前端只切一次 src，拖动/连续播放没有段间空隙。
     * 每个分段首尾做 8ms 线性淡入淡出，消除 TTS 分段拼接处的爆音（“滋滋声”）。
     */
    private FullAudio buildFullAudio(Path dir, List<String> fileNames, int sampleRate)
            throws IOException {
        // 每个分段文件名已把音色算进哈希；整轨只需按分段列表+采样率区分。
        String fileName = sha256("full|" + String.join("|", fileNames)
                + "|" + sampleRate) + ".wav";
        Path full = dir.resolve(fileName);
        if (Files.isRegularFile(full) && Files.isReadable(full)) {
            return new FullAudio(fileName, readDurationMs(full, sampleRate));
        }

        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        for (String name : fileNames) {
            byte[] data = extractPcm(Files.readAllBytes(dir.resolve(name)));
            fadeEdges(data, sampleRate);
            pcm.write(data);
        }
        byte[] all = pcm.toByteArray();
        if (all.length == 0) {
            throw new IOException("empty concatenated audio");
        }
        Files.write(full, wav(all, sampleRate));
        log.info("[note/blog-tts] full track built file={} durationMs={}",
                fileName, durationMs(all.length, sampleRate));
        return new FullAudio(fileName, durationMs(all.length, sampleRate));
    }

    private static byte[] extractPcm(byte[] wavBytes) throws IOException {
        if (wavBytes.length < 12) {
            throw new IOException("invalid wav");
        }
        int pos = 12;
        while (pos + 8 <= wavBytes.length) {
            String id = new String(wavBytes, pos, 4, StandardCharsets.US_ASCII);
            int size = ByteBuffer.wrap(wavBytes, pos + 4, 4)
                    .order(ByteOrder.LITTLE_ENDIAN).getInt();
            int dataStart = pos + 8;
            if ("data".equals(id)) {
                int end = Math.min(wavBytes.length, dataStart + size);
                return java.util.Arrays.copyOfRange(wavBytes, dataStart, end);
            }
            pos = dataStart + size + (size & 1);
        }
        throw new IOException("wav has no data chunk");
    }

    /** s16le mono：首尾各 8ms 线性淡入/淡出，抵消分段边界的硬切爆音。 */
    private static void fadeEdges(byte[] pcm, int sampleRate) {
        int samples = pcm.length / 2;
        int fade = Math.min(Math.round(sampleRate * 0.008f), samples / 2);
        if (fade <= 0) {
            return;
        }
        for (int i = 0; i < fade; i += 1) {
            double gain = i / (double) fade;
            scaleSample(pcm, i, gain);
            scaleSample(pcm, samples - 1 - i, gain);
        }
    }

    private static void scaleSample(byte[] pcm, int index, double gain) {
        int offset = index * 2;
        short value = (short) ((pcm[offset] & 0xff) | (pcm[offset + 1] << 8));
        int scaled = (int) Math.round(value * gain);
        scaled = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, scaled));
        pcm[offset] = (byte) (scaled & 0xff);
        pcm[offset + 1] = (byte) ((scaled >> 8) & 0xff);
    }

    /** 读取已缓存的 WAV 字节（预生成任务上传 OSS 用）。 */
    public byte[] readCached(long conversationId, String fileName) throws IOException {
        Path file = resolveAudio(conversationId, fileName);
        if (file == null) {
            throw new IOException("cached audio missing: " + fileName);
        }
        return Files.readAllBytes(file);
    }

    public Path resolveAudio(long conversationId, String fileName) {
        if (!FILE_NAME.matcher(fileName == null ? "" : fileName).matches()) {
            return null;
        }
        Path file = storageDir().resolve(String.valueOf(conversationId)).resolve(fileName);
        return Files.isRegularFile(file) ? file : null;
    }

    public String signedUrl(long conversationId, String fileName) {
        long exp = Instant.now().getEpochSecond() + Math.max(60, props.getSignTtlSeconds());
        String signature = sign(conversationId + "/" + fileName + "/" + exp);
        return "/v1/demo/note/blog-audio/" + conversationId + "/" + fileName
                + "?exp=" + exp + "&sig=" + signature;
    }

    public boolean verify(long conversationId, String fileName, Long exp, String sig) {
        if (exp == null || sig == null || fileName == null) {
            return false;
        }
        if (Instant.now().getEpochSecond() > exp) {
            return false;
        }
        if (!FILE_NAME.matcher(fileName).matches()) {
            return false;
        }
        String expected = sign(conversationId + "/" + fileName + "/" + exp);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                sig.getBytes(StandardCharsets.US_ASCII));
    }

    private long synthesizeToFile(String text, Path file, int sampleRate, String voice) {
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        try (VideoTutorTtsService.Stream stream = tts.openWithVoice(text, 1.0, voice)) {
            stream.writeTo(pcm);
        } catch (VideoTutorTtsService.TtsUnavailableException e) {
            throw new TtsUnavailable("语音合成失败：" + e.getMessage());
        } catch (Exception e) {
            log.warn("[note/blog-tts] synthesis failed: {}", e.getMessage());
            throw new TtsUnavailable("语音合成失败，请稍后重试");
        }
        byte[] raw = pcm.toByteArray();
        if (raw.length == 0) {
            throw new TtsUnavailable("语音合成返回空音频");
        }
        try {
            Files.write(file, wav(raw, sampleRate));
            Files.writeString(file.resolveSibling(file.getFileName() + ".txt"), text);
        } catch (IOException e) {
            throw new TtsUnavailable("语音缓存写入失败");
        }
        return durationMs(raw.length, sampleRate);
    }

    private long readDurationMs(Path file, int sampleRate) {
        try {
            long size = Files.size(file);
            return durationMs(Math.max(0, size - 44), sampleRate);
        } catch (IOException e) {
            return 0;
        }
    }

    private static long durationMs(long pcmBytes, int sampleRate) {
        return Math.round(pcmBytes * 1000.0 / (sampleRate * 2.0));
    }

    private static byte[] wav(byte[] pcm, int sampleRate) {
        ByteBuffer buffer = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(36 + pcm.length);
        buffer.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        buffer.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(16);
        buffer.putShort((short) 1);
        buffer.putShort((short) 1);
        buffer.putInt(sampleRate);
        buffer.putInt(sampleRate * 2);
        buffer.putShort((short) 2);
        buffer.putShort((short) 16);
        buffer.put("data".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(pcm.length);
        buffer.put(pcm);
        return buffer.array();
    }

    private static String blankToFallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    /** 按标签识别角色；自定义名字返回 null，由调用方按出现顺序分配。 */
    static String speakerRole(String label) {
        if (label == null || label.isBlank()) {
            return null;
        }
        String lower = label.toLowerCase(Locale.ROOT);
        if (label.contains("主持") || lower.contains("host") || lower.contains("moderator")) {
            return "host";
        }
        if (label.contains("讲解") || label.contains("嘉賓") || label.contains("嘉宾")
                || lower.contains("guest") || lower.contains("explainer")
                || lower.contains("expert")) {
            return "guest";
        }
        return null;
    }

    /**
     * 把文稿切成说话回合。带 `**说话人**：` 的行各自成回合；没有标记的块
     * （旧文章、标题、过渡段）保留为 speaker=null，走默认音色，保证老会话可用。
     */
    static List<Turn> splitTurns(String markdown) {
        String body = markdown == null ? "" : markdown.replace("```", "\n");
        List<Turn> result = new ArrayList<>();
        int paragraphIndex = 0;
        for (String block : BLANK_LINES.split(body)) {
            String trimmed = block.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String pendingSpeaker = null;
            StringBuilder pending = new StringBuilder();
            for (String line : trimmed.split("\\R")) {
                Matcher matcher = SPEAKER_LINE.matcher(line);
                if (matcher.matches()) {
                    if (!pending.isEmpty()) {
                        result.add(new Turn(paragraphIndex, pendingSpeaker,
                                toSpoken(pending.toString())));
                        pending.setLength(0);
                    }
                    pendingSpeaker = matcher.group(1).trim();
                    pending.append(matcher.group(2));
                } else if (!line.isBlank()) {
                    if (!pending.isEmpty()) {
                        pending.append(' ');
                    }
                    pending.append(line);
                }
            }
            if (!pending.isEmpty()) {
                result.add(new Turn(paragraphIndex, pendingSpeaker, toSpoken(pending.toString())));
            }
            paragraphIndex += 1;
        }
        result.removeIf(turn -> turn.text().isBlank());
        return result;
    }

    private List<String> chunkText(String text) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String sentence : SENTENCE.split(text)) {
            if (current.length() > 0 && current.length() + sentence.length() > props.getMaxCharsPerSegment()) {
                chunks.add(current.toString().trim());
                current.setLength(0);
            }
            if (sentence.length() > props.getMaxCharsPerSegment()) {
                int offset = 0;
                while (offset < sentence.length()) {
                    int end = Math.min(offset + props.getMaxCharsPerSegment(), sentence.length());
                    chunks.add(sentence.substring(offset, end).trim());
                    offset = end;
                }
                continue;
            }
            current.append(sentence);
        }
        if (current.length() > 0) {
            chunks.add(current.toString().trim());
        }
        chunks.removeIf(String::isBlank);
        return chunks;
    }

    private static String toSpoken(String block) {
        String text = IMAGE.matcher(block).replaceAll(" ");
        text = LINK.matcher(text).replaceAll("$1");
        text = HTML.matcher(text).replaceAll(" ");
        text = MATH.matcher(text).replaceAll(" ");
        text = LIST.matcher(text).replaceAll("");
        text = MARK.matcher(text).replaceAll("");
        text = text.replace("|", " ");
        text = SPACES.matcher(text).replaceAll(" ");
        return text.replaceAll("\\s*\\n\\s*", "\n").trim();
    }

    private Path storageDir() {
        Path base = Paths.get(props.getDir());
        if (!base.isAbsolute()) {
            base = Paths.get(System.getProperty("user.dir")).resolve(base);
        }
        try {
            Files.createDirectories(base);
            return base;
        } catch (IOException e) {
            try {
                Path fallback = Files.createTempDirectory("note-blog-audio");
                log.warn("[note/blog-tts] cannot use {}; falling back to {}", base, fallback);
                return fallback;
            } catch (IOException ignored) {
                throw new TtsUnavailable("语音缓存目录不可写");
            }
        }
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("sign failed", e);
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static final class TtsUnavailable extends RuntimeException {
        public TtsUnavailable(String message) {
            super(message);
        }
    }
}
