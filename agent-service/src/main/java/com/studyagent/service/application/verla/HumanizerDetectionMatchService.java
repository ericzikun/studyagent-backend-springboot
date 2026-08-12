package com.studyagent.service.application.verla;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.service.domain.verla.AiWritingHumanizerResult;
import com.studyagent.service.domain.verla.VerlaArtifact;
import com.studyagent.service.domain.verla.VerlaConversation;
import com.studyagent.service.domain.verla.repo.AiWritingHumanizerResultRepository;
import com.studyagent.service.domain.verla.repo.VerlaArtifactRepository;
import com.studyagent.service.domain.verla.repo.VerlaConversationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * V2：Humanizer 结果入库 + Detection 粘贴匹配。
 * <p>
 * 匹配顺序：前缀 hash 快路径 → 近 {@link #RECENT_LIMIT} 条全文包含/Jaccard。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HumanizerDetectionMatchService {

    public static final String ARTIFACT_KIND_HUMANIZER_RESULT = "humanizer_result";
    public static final String ARTIFACT_KIND_HUMANIZER_CHUNK = "humanizer_result_chunk";

    static final int HASH_PREFIX_CHARS = 200;
    static final int RECENT_LIMIT = 30;
    static final int MIN_MATCH_CHARS = 200;
    static final double CONTAIN_MIN_RATIO = 0.60;
    static final double JACCARD_THRESHOLD = 0.70;

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final AiWritingHumanizerResultRepository humanizerResultRepository;
    private final VerlaArtifactRepository artifactRepository;
    private final VerlaConversationRepository conversationRepository;
    private final ObjectMapper objectMapper;

    /**
     * Humanizer 汇总产物落库后写入匹配索引（幂等）。
     */
    public void recordFromHumanizerArtifact(VerlaArtifact saved, Map<String, Object> meta) {
        if (saved == null || saved.getArtifactUid() == null || saved.getArtifactUid().isBlank()) {
            return;
        }
        if (!ARTIFACT_KIND_HUMANIZER_RESULT.equalsIgnoreCase(blankToEmpty(saved.getKind()))) {
            return;
        }
        try {
            String clerkUserId = resolveClerkUserId(saved.getConversationId());
            if (clerkUserId == null) {
                log.warn("[HumanizerMatch] skip record: no user for cid={} uid={}",
                        saved.getConversationId(), saved.getArtifactUid());
                return;
            }
            String fullText = resolveFullHumanizerText(saved, meta);
            if (fullText == null || fullText.isBlank()) {
                log.warn("[HumanizerMatch] skip record: empty text uid={}", saved.getArtifactUid());
                return;
            }
            String normalized = normalizeText(fullText);
            String hash = sha256Prefix(normalized);
            if (hash == null) {
                return;
            }
            humanizerResultRepository.insertIgnoreByArtifactUid(AiWritingHumanizerResult.builder()
                    .clerkUserId(clerkUserId)
                    .conversationId(saved.getConversationId())
                    .sessionId(saved.getSessionId())
                    .artifactUid(saved.getArtifactUid())
                    .resultHash(hash)
                    .resultText(fullText)
                    .createdAt(LocalDateTime.now())
                    .build());
            log.info("[HumanizerMatch] recorded uid={} userId={} chars={} hash={}",
                    saved.getArtifactUid(), clerkUserId, fullText.length(), hash.substring(0, 8));
        } catch (Exception e) {
            log.warn("[HumanizerMatch] record failed uid={}: {}",
                    saved.getArtifactUid(), e.getMessage());
        }
    }

    /**
     * Detection 输入是否命中该用户近期 Humanizer 结果。
     */
    public boolean matchesHumanizerHistory(String clerkUserId, String detectionInput) {
        if (clerkUserId == null || clerkUserId.isBlank()
                || detectionInput == null || detectionInput.isBlank()) {
            return false;
        }
        String normalizedInput = normalizeText(detectionInput);
        if (normalizedInput.length() < MIN_MATCH_CHARS) {
            // 过短文本不走模糊匹配，但仍允许精确前缀 hash（短文整篇）
            String hash = sha256Prefix(normalizedInput);
            return hash != null && humanizerResultRepository.existsByUserAndHash(clerkUserId, hash);
        }

        String hash = sha256Prefix(normalizedInput);
        if (hash != null && humanizerResultRepository.existsByUserAndHash(clerkUserId, hash)) {
            log.info("[HumanizerMatch] hash hit userId={}", clerkUserId);
            return true;
        }

        List<AiWritingHumanizerResult> recent =
                humanizerResultRepository.listRecentByUser(clerkUserId, RECENT_LIMIT);
        for (AiWritingHumanizerResult row : recent) {
            if (row == null || row.getResultText() == null) {
                continue;
            }
            String normalizedResult = normalizeText(row.getResultText());
            if (normalizedResult.isEmpty()) {
                continue;
            }
            if (isContainmentMatch(normalizedInput, normalizedResult)
                    || isJaccardMatch(normalizedInput, normalizedResult)) {
                log.info("[HumanizerMatch] fuzzy hit userId={} artifactUid={}",
                        clerkUserId, row.getArtifactUid());
                return true;
            }
        }
        return false;
    }

    static boolean isContainmentMatch(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return false;
        }
        String shorter = a.length() <= b.length() ? a : b;
        String longer = a.length() <= b.length() ? b : a;
        if (shorter.length() < MIN_MATCH_CHARS) {
            return false;
        }
        if ((double) shorter.length() / (double) longer.length() < CONTAIN_MIN_RATIO) {
            return false;
        }
        return longer.contains(shorter);
    }

    static boolean isJaccardMatch(String a, String b) {
        if (a == null || b == null || a.length() < MIN_MATCH_CHARS || b.length() < MIN_MATCH_CHARS) {
            return false;
        }
        // 字符 trigram：对中英混排 / 无空格中文都比空白分词稳
        Set<String> ta = charShingles(a, 3);
        Set<String> tb = charShingles(b, 3);
        if (ta.isEmpty() || tb.isEmpty()) {
            return false;
        }
        int intersection = 0;
        for (String t : ta) {
            if (tb.contains(t)) {
                intersection++;
            }
        }
        int union = ta.size() + tb.size() - intersection;
        if (union <= 0) {
            return false;
        }
        return ((double) intersection / (double) union) >= JACCARD_THRESHOLD;
    }

    static String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        String n = Normalizer.normalize(text, Normalizer.Form.NFC);
        n = n.replace('\r', '\n');
        n = n.replace('\u00A0', ' ');
        // 连续空白（含换行）压成单空格，提升粘贴/删空行命中率
        n = n.replaceAll("\\s+", " ").trim();
        return n;
    }

    static String sha256Prefix(String normalized) {
        if (normalized == null || normalized.isEmpty()) {
            return null;
        }
        try {
            String prefix = normalized.length() <= HASH_PREFIX_CHARS
                    ? normalized
                    : normalized.substring(0, HASH_PREFIX_CHARS);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(prefix.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private String resolveFullHumanizerText(VerlaArtifact saved, Map<String, Object> meta) {
        boolean truncated = meta != null && Boolean.TRUE.equals(meta.get("truncated"));
        String body = saved.getBodyOrRef();
        if (!truncated && body != null && !body.isBlank()) {
            return body;
        }
        String fromChunks = reconstructFromChunks(saved.getSessionId(), meta);
        if (fromChunks != null && !fromChunks.isBlank()) {
            return fromChunks;
        }
        return body;
    }

    private String reconstructFromChunks(Long sessionId, Map<String, Object> meta) {
        if (sessionId == null) {
            return null;
        }
        List<VerlaArtifact> arts = artifactRepository.findBySession(sessionId);
        if (arts == null || arts.isEmpty()) {
            return null;
        }
        List<ChunkPiece> pieces = new ArrayList<>();
        String joinWith = meta != null && meta.get("joinWith") instanceof String s ? s : null;
        for (VerlaArtifact a : arts) {
            if (a == null || !ARTIFACT_KIND_HUMANIZER_CHUNK.equalsIgnoreCase(blankToEmpty(a.getKind()))) {
                continue;
            }
            Map<String, Object> chunkMeta = parseMeta(a.getMetaJson());
            int idx = 0;
            if (chunkMeta != null && chunkMeta.get("chunkIndex") instanceof Number n) {
                idx = n.intValue();
            }
            if (joinWith == null && chunkMeta != null && chunkMeta.get("joinWith") instanceof String j) {
                joinWith = j;
            }
            String body = a.getBodyOrRef();
            if (body == null || body.isBlank()) {
                continue;
            }
            pieces.add(new ChunkPiece(idx, body));
        }
        if (pieces.isEmpty()) {
            return null;
        }
        pieces.sort(Comparator.comparingInt(ChunkPiece::index));
        if (joinWith == null) {
            joinWith = "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pieces.size(); i++) {
            if (i > 0) {
                sb.append(joinWith);
            }
            sb.append(pieces.get(i).body());
        }
        return sb.toString();
    }

    private String resolveClerkUserId(Long conversationId) {
        if (conversationId == null) {
            return null;
        }
        VerlaConversation conv = conversationRepository.findById(conversationId);
        if (conv == null || conv.getUserId() == null || conv.getUserId().isBlank()) {
            return null;
        }
        return conv.getUserId();
    }

    private Map<String, Object> parseMeta(String metaJson) {
        if (metaJson == null || metaJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(metaJson, MAP_TYPE);
        } catch (Exception e) {
            return null;
        }
    }

    static Set<String> charShingles(String normalized, int n) {
        Set<String> out = new HashSet<>();
        if (normalized == null || normalized.isEmpty()) {
            return out;
        }
        String s = normalized.toLowerCase(Locale.ROOT);
        if (s.length() <= n) {
            out.add(s);
            return out;
        }
        for (int i = 0; i <= s.length() - n; i++) {
            out.add(s.substring(i, i + n));
        }
        return out;
    }

    private static String blankToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    private record ChunkPiece(int index, String body) {}
}
