package com.studyagent.service.application.demo.note;

import com.studyagent.service.application.verla.tts.VideoTutorTtsService;
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
import java.util.List;
import java.util.UUID;
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

    private final VideoTutorTtsService tts;
    private final NoteBlogAudioProperties props;
    private final String secret;

    public NoteBlogTtsService(VideoTutorTtsService tts, NoteBlogAudioProperties props) {
        this.tts = tts;
        this.props = props;
        this.secret = props.getSecret() == null || props.getSecret().isBlank()
                ? UUID.randomUUID().toString().replace("-", "")
                : props.getSecret().trim();
        if (props.isEnabled() && (props.getSecret() == null || props.getSecret().isBlank())) {
            log.warn("[note/blog-tts] secret is empty; using an ephemeral one (URLs invalid after restart)");
        }
    }

    public boolean isReady() {
        return props.isEnabled() && tts.isReady();
    }

    public record Segment(int index, int paragraphIndex, String text, String url, long durationMs) {
    }

    public record Payload(List<Segment> segments, long totalDurationMs, String voice) {
    }

    public Payload synthesize(long conversationId, String blogMd) {
        if (!isReady()) {
            throw new TtsUnavailable("语音服务暂不可用，请稍后重试");
        }
        List<String> paragraphs = splitParagraphs(blogMd);
        if (paragraphs.isEmpty()) {
            throw new TtsUnavailable("博客还没有可朗读的内容");
        }
        int sampleRate = tts.sampleRate();
        String voice = tts.voice();
        Path dir = storageDir().resolve(String.valueOf(conversationId));
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new TtsUnavailable("语音缓存目录不可写");
        }

        List<Segment> segments = new ArrayList<>();
        long total = 0;
        for (int paragraphIndex = 0; paragraphIndex < paragraphs.size(); paragraphIndex += 1) {
            if (segments.size() >= props.getMaxSegments()) {
                break;
            }
            for (String chunk : chunkText(paragraphs.get(paragraphIndex))) {
                if (segments.size() >= props.getMaxSegments()) {
                    break;
                }
                String fileName = sha256(chunk + "|" + voice + "|" + sampleRate) + ".wav";
                Path file = dir.resolve(fileName);
                long durationMs;
                if (Files.isRegularFile(file) && Files.isReadable(file)) {
                    durationMs = readDurationMs(file, sampleRate);
                } else {
                    durationMs = synthesizeToFile(chunk, file, sampleRate);
                }
                segments.add(new Segment(
                        segments.size(), paragraphIndex, chunk,
                        signedUrl(conversationId, fileName), durationMs));
                total += durationMs;
            }
        }
        if (segments.isEmpty()) {
            throw new TtsUnavailable("博客还没有可朗读的内容");
        }
        log.info("[note/blog-tts] synthesized convId={} segments={} totalMs={}",
                conversationId, segments.size(), total);
        return new Payload(segments, total, voice);
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

    private long synthesizeToFile(String text, Path file, int sampleRate) {
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        try (VideoTutorTtsService.Stream stream = tts.open(text, 1.0)) {
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

    private List<String> splitParagraphs(String markdown) {
        String body = markdown == null ? "" : markdown.replace("```", "\n");
        List<String> result = new ArrayList<>();
        for (String block : BLANK_LINES.split(body)) {
            String text = toSpoken(block);
            if (!text.isBlank()) {
                result.add(text);
            }
        }
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
