package com.studyagent.service.application.demo.note;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.service.application.demo.DemoNoteService;
import com.studyagent.service.domain.demo.note.NoteConversation;
import com.studyagent.service.domain.file.OssStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * 播客音频预生成：extras 落库后异步合成 WAV、上传 OSS，并把 manifest 写回会话行。
 * <p>前端打开播客 tab 时直接播 OSS/签名 URL；合成失败只把状态置 FAILED，
 * 前端会回退到按需的 /blog/tts 接口，不影响笔记主流程。
 */
@Slf4j
@Service
public class NoteBlogAudioPrefetchService {

    private static final String OSS_PREFIX = "verla/v2/note-blog-audio";
    private static final int OSS_WORKERS = 2;

    private final NoteBlogTtsService tts;
    private final OssStorageService oss;
    private final DemoNoteService demoNoteService;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor = Executors.newFixedThreadPool(OSS_WORKERS, runnable -> {
        Thread thread = new Thread(runnable, "note-blog-audio");
        thread.setDaemon(true);
        return thread;
    });
    /** 同一会话同一 blog 内容只允许一个任务并发跑。 */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public NoteBlogAudioPrefetchService(
            NoteBlogTtsService tts,
            OssStorageService oss,
            DemoNoteService demoNoteService,
            ObjectMapper objectMapper) {
        this.tts = tts;
        this.oss = oss;
        this.demoNoteService = demoNoteService;
        this.objectMapper = objectMapper;
    }

    /** extras 事务提交后再起任务，避免后台线程读到未提交的 blog_md。 */
    public void schedule(NoteConversation conversation, String blogMd) {
        if (conversation == null || conversation.getId() == null
                || blogMd == null || blogMd.isBlank()) {
            return;
        }
        long demoId = conversation.getId();
        String sourceHash = sha256(blogMd);
        String key = demoId + ":" + sourceHash;
        if (!inFlight.add(key)) {
            return;
        }
        Runnable task = () -> {
            try {
                prefetch(demoId, sourceHash, blogMd);
            } finally {
                inFlight.remove(key);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            submit(task);
                        }
                    });
        } else {
            submit(task);
        }
    }

    private void submit(Runnable task) {
        try {
            executor.execute(task);
        } catch (RejectedExecutionException e) {
            log.warn("[note/blog-audio] prefetch queue full; falling back to on-demand TTS");
        }
    }

    private void prefetch(long demoId, String sourceHash, String blogMd) {
        try {
            if (!tts.isReady()) {
                demoNoteService.markBlogAudioFailed(demoId, sourceHash, "tts unavailable");
                return;
            }
            NoteBlogTtsService.CachedPayload cached = tts.cache(demoId, blogMd);
            List<Map<String, Object>> segments = new ArrayList<>(cached.segments().size());
            for (NoteBlogTtsService.CachedSegment segment : cached.segments()) {
                String url = uploadToOss(demoId, sourceHash, segment);
                if (url == null) {
                    url = tts.signedUrl(demoId, segment.fileName());
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("index", segment.index());
                item.put("paragraphIndex", segment.paragraphIndex());
                item.put("text", segment.text());
                item.put("url", url);
                item.put("durationMs", segment.durationMs());
                segments.add(item);
            }
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("status", "READY");
            manifest.put("voice", cached.voice());
            manifest.put("totalDurationMs", cached.totalDurationMs());
            manifest.put("sourceHash", sourceHash);
            manifest.put("segments", segments);
            demoNoteService.markBlogAudioReady(
                    demoId, sourceHash, objectMapper.writeValueAsString(manifest));
            log.info("[note/blog-audio] ready demoId={} segments={} totalMs={}",
                    demoId, segments.size(), cached.totalDurationMs());
        } catch (Exception e) {
            log.warn("[note/blog-audio] prefetch failed demoId={}: {}", demoId, e.getMessage());
            try {
                demoNoteService.markBlogAudioFailed(demoId, sourceHash, e.getMessage());
            } catch (Exception inner) {
                log.warn("[note/blog-audio] failed to persist FAILED status demoId={}", demoId);
            }
        }
    }

    /** 上传单段 WAV；OSS 未启用或失败时返回 null，由调用方退回本地签名 URL。 */
    private String uploadToOss(long demoId, String sourceHash,
                               NoteBlogTtsService.CachedSegment segment) {
        if (!oss.isEnabled()) {
            return null;
        }
        try {
            byte[] bytes = tts.readCached(demoId, segment.fileName());
            String ossKey = OSS_PREFIX + "/" + demoId + "/" + sourceHash.substring(0, 16)
                    + "/" + segment.fileName();
            if (!oss.putBytesAtKey(ossKey, bytes)) {
                return null;
            }
            return oss.getOssUrl(ossKey);
        } catch (Exception e) {
            log.warn("[note/blog-audio] OSS upload failed demoId={} file={}: {}",
                    demoId, segment.fileName(), e.getMessage());
            return null;
        }
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("sha-256 unavailable", e);
        }
    }
}
