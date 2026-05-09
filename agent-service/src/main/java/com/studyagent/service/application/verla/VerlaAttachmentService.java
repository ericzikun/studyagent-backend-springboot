package com.studyagent.service.application.verla;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.studyagent.common.api.ApiCode;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.common.verla.enums.VerlaAttachmentStatus;
import com.studyagent.common.verla.enums.VerlaCommandAction;
import com.studyagent.common.verla.enums.VerlaSessionKind;
import com.studyagent.common.verla.envelope.VerlaCommandEnvelope;
import com.studyagent.common.verla.envelope.VerlaConversationRef;
import com.studyagent.common.verla.envelope.VerlaProducerInfo;
import com.studyagent.common.verla.envelope.VerlaSessionRef;
import com.studyagent.common.verla.envelope.VerlaTurnRef;
import com.studyagent.common.verla.util.VerlaCorrelationId;
import com.studyagent.common.verla.util.VerlaPseudoSessionIds;
import com.studyagent.service.application.MqOutboxService;
import com.studyagent.service.application.verla.dto.VerlaUploadSignResult;
import com.studyagent.service.application.verla.util.VerlaAttachmentOssKeys;
import com.studyagent.service.domain.file.OssStorageService;
import com.studyagent.service.domain.verla.VerlaAttachment;
import com.studyagent.service.domain.verla.VerlaConversation;
import com.studyagent.service.domain.verla.repo.VerlaAttachmentRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Verla V2 附件：仅 OSS，不落本地盘；sign → PUT 字节 → finalize → cmd.attachment.parse。
 * <p>
 * HTTP 入口统一为 {@code /v1/verla/v2/uploads/*}；与 legacy {@code /v1/file/*} 无关。
 */
@Slf4j
@Service
public class VerlaAttachmentService {

    private static final String PRODUCER_SERVICE = "java-agent-service";
    private static final String INSTANCE_ID = resolveHostname();
    private static final String DEFAULT_COMMAND_EXCHANGE = "studyagent.command";
    private static final String API_V2_UPLOAD_BASE = "/v1/verla/v2/uploads/";
    public static final String HDR_UPLOAD_TOKEN = "X-Verla-Upload-Token";

    private final VerlaConversationService conversationService;
    private final VerlaAttachmentRepository attachmentRepository;
    private final MqOutboxService mqOutboxService;
    private final OssStorageService ossStorageService;

    @Value("${verla.mq.command-exchange:" + DEFAULT_COMMAND_EXCHANGE + "}")
    private String commandExchange;

    @Value("${verla.attachment.max-bytes:33554432}")
    private long maxBytes;

    @Value("${verla.attachment.sign-ttl-seconds:3600}")
    private long signTtlSeconds;

    /** OSS 对象前缀，与 legacy upload 路径隔离 */
    @Value("${verla.attachment.oss-key-prefix:verla/v2/attachments}")
    private String ossKeyPrefix;

    /**
     * 本地开发兜底：OSS 未配置时仍允许 Dashboard 走 V2 上传链路。
     * 线上保持 false，由 OSS 承载可被 Python 消费的材料。
     */
    @Value("${verla.attachment.local-fallback-enabled:false}")
    private boolean localFallbackEnabled;

    @Value("${verla.attachment.local-root:../storage}")
    private String localRoot;

    /** 逗号分隔 MIME，空表示不校验 */
    @Value("${verla.attachment.allowed-mimes:application/pdf,image/png,image/jpeg,image/webp,text/plain,text/markdown}")
    private String allowedMimesRaw;

    private Set<String> allowedMimes;
    private Cache<String, UploadTicket> uploadTickets;
    /** 上传后短暂保留字节，供 artifact handler 直接读取，避免回读 OSS/文件的可靠性问题。TTL 10 min。 */
    private Cache<String, byte[]> uploadedContentCache;

    public VerlaAttachmentService(VerlaConversationService conversationService,
                                  VerlaAttachmentRepository attachmentRepository,
                                  MqOutboxService mqOutboxService,
                                  OssStorageService ossStorageService) {
        this.conversationService = conversationService;
        this.attachmentRepository = attachmentRepository;
        this.mqOutboxService = mqOutboxService;
        this.ossStorageService = ossStorageService;
    }

    @PostConstruct
    void init() {
        if (StringUtils.hasText(allowedMimesRaw)) {
            allowedMimes = Arrays.stream(allowedMimesRaw.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(s -> s.toLowerCase(Locale.ROOT))
                    .collect(Collectors.toSet());
        } else {
            allowedMimes = Set.of();
        }
        uploadTickets = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(Math.max(60, signTtlSeconds)))
                .maximumSize(20_000)
                .build();
        uploadedContentCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(10))
                .maximumSize(500)
                .build();
        log.info("[Verla/attachment/V2] init maxBytes={}, signTtl={}s, ossEnabled={}, localFallback={}, ossPrefix={}, allowedMimes={}",
                maxBytes, signTtlSeconds, ossStorageService.isEnabled(), localFallbackEnabled, ossKeyPrefix, allowedMimes);
    }

    public static String uploadTokenHeaderName() {
        return HDR_UPLOAD_TOKEN;
    }

    @Transactional
    public VerlaUploadSignResult requestSign(String clerkUserId, long conversationId, String filename,
                                             String mime, long sizeBytes, Long turnId, Long sessionId) {
        requireOssConfigured();
        ensureUser(clerkUserId);
        return requestSignForUser(clerkUserId, conversationId, filename, mime, sizeBytes, turnId, sessionId);
    }

    @Transactional
    public VerlaUploadSignResult requestSignForInternal(String clerkUserId, long conversationId, String filename,
                                                        String mime, long sizeBytes,
                                                        Long turnId, Long sessionId) {
        requireOssConfigured();
        if (!StringUtils.hasText(clerkUserId)) {
            throw new BusinessException(ApiCode.PARAM_ERROR, "clerkUserId required");
        }
        return requestSignForUser(clerkUserId, conversationId, filename, mime, sizeBytes, turnId, sessionId);
    }

    private VerlaUploadSignResult requestSignForUser(String clerkUserId, long conversationId, String filename,
                                                     String mime, long sizeBytes, Long turnId, Long sessionId) {
        if (!StringUtils.hasText(filename)) {
            throw new BusinessException(ApiCode.PARAM_ERROR, "filename required");
        }
        if (!StringUtils.hasText(mime)) {
            throw new BusinessException(ApiCode.PARAM_ERROR, "mime required");
        }
        if (sizeBytes <= 0 || sizeBytes > maxBytes) {
            throw new BusinessException(ApiCode.PARAM_ERROR, "sizeBytes invalid");
        }
        String mimeLc = mime.toLowerCase(Locale.ROOT).trim();
        if (!allowedMimes.isEmpty() && !allowedMimes.contains(mimeLc)) {
            throw new BusinessException(ApiCode.PARAM_ERROR, "mime not allowed: " + mime);
        }

        VerlaConversation conv = conversationService.getOwned(clerkUserId, conversationId);

        String objectId = "att_" + UUID.randomUUID().toString().replace("-", "");
        String uploadToken = UUID.randomUUID().toString().replace("-", "");
        String ossKey = VerlaAttachmentOssKeys.build(ossKeyPrefix, conversationId, objectId, filename.trim());

        LocalDateTime now = LocalDateTime.now();
        VerlaAttachment row = VerlaAttachment.builder()
                .objectId(objectId)
                .conversationId(conversationId)
                .turnId(turnId)
                .sessionId(sessionId)
                .userId(clerkUserId)
                .filename(filename.trim())
                .mime(mimeLc)
                .sizeBytes(sizeBytes)
                .ossKey(ossKey)
                .storageUri("pending://" + objectId)
                .status(VerlaAttachmentStatus.UPLOADED.name())
                .createdAt(now)
                .updatedAt(now)
                .build();
        attachmentRepository.save(row);

        Instant exp = Instant.now().plusSeconds(signTtlSeconds);
        uploadTickets.put(objectId, new UploadTicket(clerkUserId, uploadToken, exp));

        log.info("[Verla/attachment/V2] sign objectId={} conv={} sessionId={} ossKey={}", objectId, conversationId, sessionId, ossKey);

        return VerlaUploadSignResult.builder()
                .objectId(objectId)
                .uploadPath(API_V2_UPLOAD_BASE + objectId + "/content")
                .method("PUT")
                .uploadToken(uploadToken)
                .expiresInSeconds(signTtlSeconds)
                .build();
    }

    /**
     * 接收原始字节，写入 OSS（不落本地）。
     */
    public void uploadContent(String clerkUserId, String objectId, String uploadToken, InputStream rawIn)
            throws IOException {
        requireOssConfigured();
        ensureUser(clerkUserId);
        UploadTicket ticket = requireTicket(objectId, clerkUserId, uploadToken);
        uploadContentForUser(clerkUserId, objectId, rawIn, ticket);
    }

    public void uploadContentForInternal(String objectId, String uploadToken, InputStream rawIn)
            throws IOException {
        requireOssConfigured();
        VerlaAttachment att = getForInternal(objectId);
        UploadTicket ticket = requireTicket(objectId, att.getUserId(), uploadToken);
        uploadContentForUser(att.getUserId(), objectId, rawIn, ticket);
    }

    private void uploadContentForUser(String clerkUserId, String objectId, InputStream rawIn, UploadTicket ticket)
            throws IOException {
        VerlaAttachment att = attachmentRepository.findByObjectId(objectId);
        if (att == null) {
            throw new BusinessException(ApiCode.TASK_NOT_FOUND, "attachment");
        }
        if (!att.getUserId().equals(clerkUserId)) {
            throw new BusinessException(ApiCode.NO_PERMISSION);
        }
        if (!VerlaAttachmentStatus.UPLOADED.name().equals(att.getStatus())) {
            throw new BusinessException(ApiCode.PARAM_ERROR, "attachment not in UPLOADED state");
        }
        if (att.getStorageUri() != null && !att.getStorageUri().startsWith("pending://")) {
            throw new BusinessException(ApiCode.PARAM_ERROR, "already uploaded");
        }
        if (att.getOssKey() == null || att.getOssKey().isBlank()) {
            throw new BusinessException(ApiCode.INTERNAL_ERROR, "ossKey missing on attachment row");
        }

        byte[] body = readFullyLimited(rawIn, att.getSizeBytes());
        String checksum = sha256Hex(body);

        String storageUri;
        if (ossStorageService.isEnabled()) {
            boolean ok = ossStorageService.putBytesAtKey(att.getOssKey(), body);
            if (!ok) {
                throw new BusinessException(ApiCode.INTERNAL_ERROR, "OSS upload failed");
            }
            storageUri = ossStorageService.formatVerlaStorageUri(att.getOssKey());
            if (storageUri == null) {
                throw new BusinessException(ApiCode.INTERNAL_ERROR, "could not build storage URI");
            }
        } else if (localFallbackEnabled) {
            storageUri = writeLocalFallback(att.getOssKey(), body);
        } else {
            throw storageNotConfigured();
        }

        attachmentRepository.updateByObjectIdSelective(VerlaAttachment.builder()
                .objectId(objectId)
                .storageUri(storageUri)
                .checksumSha256(checksum)
                .build());

        uploadedContentCache.put(objectId, body);
        uploadTickets.put(objectId, ticket);
        log.info("[Verla/attachment/V2] content stored objectId={} bytes={} uri={}", objectId, body.length, storageUri);
    }

    @Transactional
    public VerlaAttachment finalizeUpload(String clerkUserId, String objectId, String uploadToken,
                                          Long turnId, String clientChecksumSha256,
                                          boolean skipAttachmentParse) {
        ensureUser(clerkUserId);
        requireTicket(objectId, clerkUserId, uploadToken);
        return finalizeUploadForUser(clerkUserId, objectId, turnId, clientChecksumSha256,
                skipAttachmentParse);
    }

    @Transactional
    public VerlaAttachment finalizeUploadForInternal(String objectId, String uploadToken,
                                                     Long turnId, String clientChecksumSha256,
                                                     boolean skipAttachmentParse) {
        VerlaAttachment att = getForInternal(objectId);
        requireTicket(objectId, att.getUserId(), uploadToken);
        return finalizeUploadForUser(att.getUserId(), objectId, turnId, clientChecksumSha256,
                skipAttachmentParse);
    }

    private VerlaAttachment finalizeUploadForUser(String clerkUserId, String objectId,
                                                  Long turnId, String clientChecksumSha256,
                                                  boolean skipAttachmentParse) {
        VerlaAttachment att = attachmentRepository.findByObjectId(objectId);
        if (att == null) {
            throw new BusinessException(ApiCode.TASK_NOT_FOUND, "attachment");
        }
        conversationService.getOwned(clerkUserId, att.getConversationId());
        if (!att.getUserId().equals(clerkUserId)) {
            throw new BusinessException(ApiCode.NO_PERMISSION);
        }
        if (!VerlaAttachmentStatus.UPLOADED.name().equals(att.getStatus())) {
            throw new BusinessException(ApiCode.PARAM_ERROR, "finalize requires UPLOADED status");
        }
        if (att.getStorageUri() == null || att.getStorageUri().startsWith("pending://")) {
            throw new BusinessException(ApiCode.PARAM_ERROR, "upload content first");
        }
        if (StringUtils.hasText(clientChecksumSha256)
                && att.getChecksumSha256() != null
                && !clientChecksumSha256.trim().equalsIgnoreCase(att.getChecksumSha256())) {
            throw new BusinessException(ApiCode.PARAM_ERROR, "checksum mismatch");
        }

        if (turnId != null) {
            attachmentRepository.updateByObjectIdSelective(VerlaAttachment.builder()
                    .objectId(objectId)
                    .turnId(turnId)
                    .build());
            att = attachmentRepository.findByObjectId(objectId);
        }

        VerlaConversation conv = conversationService.getOwned(clerkUserId, att.getConversationId());

        if (skipAttachmentParse) {
            uploadTickets.invalidate(objectId);
            log.info("[Verla/attachment/V2] finalize objectId={} → skip cmd.attachment.parse", objectId);
            return attachmentRepository.findByObjectId(objectId);
        }

        attachmentRepository.updateParseProgress(VerlaAttachment.builder()
                .objectId(objectId)
                .status(VerlaAttachmentStatus.PARSING.name())
                .build());

        long pseudoSid = VerlaPseudoSessionIds.forAttachmentParse(objectId);
        long turnPart = att.getTurnId() != null ? att.getTurnId() : 0L;

        Map<String, Object> payload = new HashMap<>();
        payload.put("objectId", objectId);
        payload.put("filename", att.getFilename());
        payload.put("mime", att.getMime());
        payload.put("storageUri", att.getStorageUri());
        payload.put("ossKey", att.getOssKey());
        payload.put("sizeBytes", att.getSizeBytes());

        VerlaCommandEnvelope env = VerlaCommandEnvelope.builder()
                .schemaVersion(1)
                .messageId("cmd-" + UUID.randomUUID())
                .correlationId(VerlaCorrelationId.of(conv.getId(), turnPart, pseudoSid))
                .orderingKey(VerlaCorrelationId.orderingKey(pseudoSid))
                .action(VerlaCommandAction.CMD_ATTACHMENT_PARSE.getCode())
                .timestamp(Instant.now())
                .producer(VerlaProducerInfo.builder()
                        .service(PRODUCER_SERVICE)
                        .instanceId(INSTANCE_ID)
                        .build())
                .conversation(VerlaConversationRef.builder()
                        .conversationId(conv.getId())
                        .userId(conv.getUserId())
                        .build())
                .turn(VerlaTurnRef.builder().turnId(turnPart).build())
                .session(VerlaSessionRef.builder()
                        .sessionId(pseudoSid)
                        .kind(VerlaSessionKind.MATERIALS)
                        .feature("attachment_parse")
                        .build())
                .payload(payload)
                .build();

        mqOutboxService.createVerlaCommand(env, commandExchange,
                VerlaCommandAction.CMD_ATTACHMENT_PARSE.getCode());

        uploadTickets.invalidate(objectId);
        log.info("[Verla/attachment/V2] finalize objectId={} → PARSING + outbox cmd.attachment.parse", objectId);
        return attachmentRepository.findByObjectId(objectId);
    }

    public List<VerlaAttachment> listByConversation(String clerkUserId, long conversationId, int limit) {
        ensureUser(clerkUserId);
        conversationService.getOwned(clerkUserId, conversationId);
        return attachmentRepository.listByConversation(conversationId, limit);
    }

    public VerlaAttachment getOwned(String clerkUserId, String objectId) {
        ensureUser(clerkUserId);
        VerlaAttachment a = attachmentRepository.findByObjectId(objectId);
        if (a == null) {
            throw new BusinessException(ApiCode.TASK_NOT_FOUND, "attachment");
        }
        conversationService.getOwned(clerkUserId, a.getConversationId());
        if (!a.getUserId().equals(clerkUserId)) {
            throw new BusinessException(ApiCode.NO_PERMISSION);
        }
        return a;
    }

    /** Py 内部接口：仅校验 conversation 存在（所有权由 internal 网络层约束） */
    public VerlaAttachment getForInternal(String objectId) {
        VerlaAttachment a = attachmentRepository.findByObjectId(objectId);
        if (a == null) {
            throw new BusinessException(ApiCode.TASK_NOT_FOUND, "attachment");
        }
        return a;
    }

    /**
     * 读取附件字节：先查内存缓存（上传后 10 min 内有效），再回落 OSS/本地文件。
     * 供 artifact handler hydrate bodyOrRef 使用，避免对 OSS/文件系统读取可靠性的依赖。
     */
    public byte[] loadAttachmentBytes(String objectId) {
        if (objectId == null || objectId.isBlank()) {
            return null;
        }
        byte[] cached = uploadedContentCache.getIfPresent(objectId);
        if (cached != null && cached.length > 0) {
            log.debug("[Verla/attachment/V2] loadAttachmentBytes cache-hit objectId={} bytes={}", objectId, cached.length);
            return cached;
        }
        VerlaAttachment att = attachmentRepository.findByObjectId(objectId);
        if (att == null) {
            return null;
        }
        try {
            return readAttachmentBytesFromStorage(att);
        } catch (Exception e) {
            log.warn("[Verla/attachment/V2] loadAttachmentBytes failed objectId={}: {}", objectId, e.getMessage());
            return null;
        }
    }

    private byte[] readAttachmentBytesFromStorage(VerlaAttachment att) throws Exception {
        if (att.getOssKey() != null && !att.getOssKey().isBlank()) {
            byte[] ossBytes = ossStorageService.getObjectBytes(att.getOssKey());
            if (ossBytes != null && ossBytes.length > 0) {
                return ossBytes;
            }
        }
        String uri = att.getStorageUri();
        if (uri != null && uri.startsWith("file:")) {
            return java.nio.file.Files.readAllBytes(java.nio.file.Path.of(java.net.URI.create(uri)));
        }
        log.warn("[Verla/attachment/V2] no readable path objectId={} ossKey={} storageUri={}",
                att.getObjectId(), att.getOssKey(), uri);
        return null;
    }

    /**
     * 按 ossKey 从 OSS 读取字节（供内部消费链兜底；优先由 Py 直连 OSS）。
     */
    public byte[] loadBytesFromOss(String ossKey) {
        if (ossKey == null || ossKey.isBlank()) {
            return null;
        }
        return ossStorageService.getObjectBytes(ossKey.trim());
    }

    private void requireOssConfigured() {
        if (!ossStorageService.isEnabled() && !localFallbackEnabled) {
            throw storageNotConfigured();
        }
    }

    private BusinessException storageNotConfigured() {
        return new BusinessException(ApiCode.PARAM_VALIDATION_FAILED,
                "Verla V2 attachment storage is not configured; set OSS credentials or enable verla.attachment.local-fallback-enabled for local development");
    }

    private String writeLocalFallback(String objectKey, byte[] body) throws IOException {
        if (objectKey == null || objectKey.isBlank()) {
            throw new BusinessException(ApiCode.INTERNAL_ERROR, "attachment object key missing");
        }
        Path root = Paths.get(localRoot).toAbsolutePath().normalize();
        Path target = root.resolve(objectKey).normalize();
        if (!target.startsWith(root)) {
            throw new BusinessException(ApiCode.PARAM_VALIDATION_FAILED, "invalid attachment object key");
        }
        Files.createDirectories(target.getParent());
        Files.write(target, body);
        return target.toUri().toString();
    }

    private UploadTicket requireTicket(String objectId, String clerkUserId, String uploadToken) {
        UploadTicket t = uploadTickets.getIfPresent(objectId);
        if (t == null || Instant.now().isAfter(t.expiresAt())) {
            throw new BusinessException(ApiCode.PARAM_ERROR, "upload token expired or missing");
        }
        if (!t.userId().equals(clerkUserId) || !tokenEquals(t.secretToken(), uploadToken)) {
            throw new BusinessException(ApiCode.NO_PERMISSION, "bad upload token");
        }
        return t;
    }

    private static void ensureUser(String clerkUserId) {
        if (!StringUtils.hasText(clerkUserId)) {
            throw new BusinessException(ApiCode.USER_NOT_LOGGED_IN);
        }
    }

    private static boolean tokenEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        byte[] ba = a.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (ba.length != bb.length) {
            return false;
        }
        return MessageDigest.isEqual(ba, bb);
    }

    private static byte[] readFullyLimited(InputStream in, long declaredSize) throws IOException {
        if (declaredSize <= 0 || declaredSize > Integer.MAX_VALUE - 8) {
            throw new BusinessException(ApiCode.PARAM_ERROR, "invalid declared sizeBytes");
        }
        int cap = (int) declaredSize;
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.min(cap, 8192));
        byte[] buf = new byte[8192];
        long total = 0;
        while (true) {
            int n = in.read(buf);
            if (n < 0) {
                break;
            }
            total += n;
            if (total > declaredSize) {
                throw new BusinessException(ApiCode.PARAM_ERROR, "upload exceeds declared sizeBytes");
            }
            bos.write(buf, 0, n);
        }
        byte[] out = bos.toByteArray();
        if (out.length != declaredSize) {
            throw new BusinessException(ApiCode.PARAM_ERROR,
                    "upload size mismatch: expected " + declaredSize + " bytes, got " + out.length);
        }
        return out;
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String resolveHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown-host";
        }
    }

    private record UploadTicket(String userId, String secretToken, Instant expiresAt) {}
}
