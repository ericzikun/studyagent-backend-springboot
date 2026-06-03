package com.studyagent.service.application.verla;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.studyagent.common.api.ApiCode;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.service.domain.file.OssStorageService;
import com.studyagent.service.application.verla.dto.VerlaEditorAssetSignResult;
import com.studyagent.service.domain.verla.VerlaEditorAsset;
import com.studyagent.service.domain.verla.VerlaArtifact;
import com.studyagent.service.domain.verla.repo.VerlaEditorAssetRepository;
import com.studyagent.service.domain.verla.repo.VerlaArtifactRepository;
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
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 编辑器内部素材服务：sign → PUT → finalize，不参与附件解析语义。
 * 与 VerlaAttachmentService 完全独立。
 */
@Slf4j
@Service
public class VerlaEditorAssetService {

    private static final String API_V2_EDITOR_ASSET_BASE = "/v1/verla/editor-assets/";
    public static final String HDR_UPLOAD_TOKEN = "X-Verla-Upload-Token";

    private final VerlaConversationService conversationService;
    private final VerlaEditorAssetRepository assetRepository;
    private final VerlaArtifactRepository artifactRepository;
    private final OssStorageService ossStorageService;

    @Value("${verla.attachment.max-bytes:33554432}")
    private long maxBytes;

    @Value("${verla.attachment.sign-ttl-seconds:3600}")
    private long signTtlSeconds;

    @Value("${verla.attachment.doc-editor-image-key-prefix:studyagent/document_editor_images}")
    private String editorAssetKeyPrefix;

    /**
     * 本地开发兜底：OSS 未配置时仍允许编辑器上传。
     */
    @Value("${verla.attachment.local-fallback-enabled:false}")
    private boolean localFallbackEnabled;

    @Value("${verla.attachment.local-root:../storage}")
    private String localRoot;

    @Value("${verla.attachment.allowed-mimes:application/pdf,image/png,image/jpeg,image/webp,text/plain,text/markdown}")
    private String allowedMimesRaw;

    private Set<String> allowedMimes;
    private Cache<String, UploadTicket> uploadTickets;
    private Cache<String, byte[]> uploadedContentCache;

    public VerlaEditorAssetService(VerlaConversationService conversationService,
                                    VerlaEditorAssetRepository assetRepository,
                                    VerlaArtifactRepository artifactRepository,
                                    OssStorageService ossStorageService) {
        this.conversationService = conversationService;
        this.assetRepository = assetRepository;
        this.artifactRepository = artifactRepository;
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
        log.info("[Verla/editorAsset] init maxBytes={}, signTtl={}s, ossEnabled={}, localFallback={}, ossPrefix={}",
                maxBytes, signTtlSeconds, ossStorageService.isEnabled(), localFallbackEnabled, editorAssetKeyPrefix);
    }

    @Transactional
    public VerlaEditorAssetSignResult requestSign(String clerkUserId, long conversationId, String artifactUid,
                                                   String filename,
                                                   String mime, long sizeBytes, String editorKind, String assetRole) {
        if (!ossStorageService.isEnabled() && !localFallbackEnabled) {
            throw new BusinessException(ApiCode.INTERNAL_ERROR, "OSS storage not configured");
        }
        if (!StringUtils.hasText(clerkUserId)) {
            throw new BusinessException(ApiCode.USER_NOT_LOGGED_IN);
        }
        if (StringUtils.hasText(editorKind) && !VerlaEditorAsset.KIND_DOCUMENT.equals(editorKind)
                && !VerlaEditorAsset.KIND_SLIDES.equals(editorKind)
                && !VerlaEditorAsset.KIND_CODE.equals(editorKind)) {
            throw new BusinessException(ApiCode.PARAM_ERROR, "invalid editorKind: " + editorKind);
        }
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

        conversationService.getOwned(clerkUserId, conversationId);
        String normalizedArtifactUid = normalizeOwnedArtifactUid(clerkUserId, conversationId, artifactUid);

        String assetId = "ea_" + UUID.randomUUID().toString().replace("-", "");
        String uploadToken = UUID.randomUUID().toString().replace("-", "");

        String ossKey = editorAssetKeyPrefix + "/" + conversationId + "/" + assetId + "_" + filename.trim();

        LocalDateTime now = LocalDateTime.now();
        VerlaEditorAsset row = VerlaEditorAsset.builder()
                .assetId(assetId)
                .conversationId(conversationId)
                .artifactUid(normalizedArtifactUid)
                .editorKind(StringUtils.hasText(editorKind) ? editorKind : VerlaEditorAsset.KIND_DOCUMENT)
                .assetRole(StringUtils.hasText(assetRole) ? assetRole : VerlaEditorAsset.ROLE_INLINE_IMAGE)
                .userId(clerkUserId)
                .filename(filename.trim())
                .mime(mimeLc)
                .sizeBytes(sizeBytes)
                .ossKey(ossKey)
                .storageUri("pending://" + assetId)
                .status(VerlaEditorAsset.STATUS_PENDING)
                .createdAt(now)
                .updatedAt(now)
                .build();
        assetRepository.save(row);

        Instant exp = Instant.now().plusSeconds(signTtlSeconds);
        uploadTickets.put(assetId, new UploadTicket(clerkUserId, uploadToken, exp));

        log.info("[Verla/editorAsset] sign assetId={} conv={} kind={} role={} ossKey={}",
                assetId, conversationId, editorKind, assetRole, ossKey);

        return VerlaEditorAssetSignResult.builder()
                .assetId(assetId)
                .uploadPath(API_V2_EDITOR_ASSET_BASE + assetId + "/content")
                .method("PUT")
                .uploadToken(uploadToken)
                .expiresInSeconds(signTtlSeconds)
                .build();
    }

    public void uploadContent(String clerkUserId, String assetId, String uploadToken, InputStream rawIn)
            throws IOException {
        if (!ossStorageService.isEnabled() && !localFallbackEnabled) {
            throw new BusinessException(ApiCode.INTERNAL_ERROR, "OSS storage not configured");
        }
        if (!StringUtils.hasText(clerkUserId)) {
            throw new BusinessException(ApiCode.USER_NOT_LOGGED_IN);
        }
        UploadTicket ticket = uploadTickets.getIfPresent(assetId);
        if (ticket == null || !ticket.clerkUserId().equals(clerkUserId)
                || !ticket.token().equals(uploadToken)) {
            throw new BusinessException(ApiCode.PARAM_VALIDATION_FAILED, "invalid or expired upload token");
        }

        VerlaEditorAsset asset = assetRepository.findByAssetId(assetId);
        if (asset == null) {
            throw new BusinessException(ApiCode.TASK_NOT_FOUND, "editor asset");
        }
        if (!asset.getUserId().equals(clerkUserId)) {
            throw new BusinessException(ApiCode.NO_PERMISSION);
        }
        if (!VerlaEditorAsset.STATUS_PENDING.equals(asset.getStatus())) {
            throw new BusinessException(ApiCode.PARAM_ERROR, "asset not in PENDING state");
        }

        byte[] body = readFullyLimited(rawIn, asset.getSizeBytes());
        String checksum = sha256Hex(body);
        long actualSize = body.length;

        String storageUri;
        if (ossStorageService.isEnabled()) {
            boolean ok = ossStorageService.putBytesAtKey(asset.getOssKey(), body);
            if (!ok) {
                throw new BusinessException(ApiCode.INTERNAL_ERROR, "OSS upload failed");
            }
            storageUri = ossStorageService.formatVerlaStorageUri(asset.getOssKey());
            if (storageUri == null) {
                throw new BusinessException(ApiCode.INTERNAL_ERROR, "could not build storage URI");
            }
        } else if (localFallbackEnabled) {
            storageUri = writeLocalFallback(asset.getOssKey(), body);
        } else {
            throw new BusinessException(ApiCode.INTERNAL_ERROR, "OSS storage not configured");
        }

        assetRepository.updateByAssetIdSelective(VerlaEditorAsset.builder()
                .assetId(assetId)
                .storageUri(storageUri)
                .checksumSha256(checksum)
                .sizeBytes(actualSize)
                .status(VerlaEditorAsset.STATUS_UPLOADED)
                .build());

        uploadedContentCache.put(assetId, body);
        uploadTickets.put(assetId, ticket);
        log.info("[Verla/editorAsset] content stored assetId={} bytes={} uri={}", assetId, actualSize, storageUri);
    }

    @Transactional
    public VerlaEditorAsset finalizeUpload(String clerkUserId, String assetId, String uploadToken,
                                            String clientChecksumSha256) {
        if (!StringUtils.hasText(clerkUserId)) {
            throw new BusinessException(ApiCode.USER_NOT_LOGGED_IN);
        }
        UploadTicket ticket = uploadTickets.getIfPresent(assetId);
        if (ticket == null || !ticket.clerkUserId().equals(clerkUserId)
                || !ticket.token().equals(uploadToken)) {
            throw new BusinessException(ApiCode.PARAM_VALIDATION_FAILED, "invalid or expired upload token");
        }

        VerlaEditorAsset asset = assetRepository.findByAssetId(assetId);
        if (asset == null) {
            throw new BusinessException(ApiCode.TASK_NOT_FOUND, "editor asset");
        }
        conversationService.getOwned(clerkUserId, asset.getConversationId());
        if (!asset.getUserId().equals(clerkUserId)) {
            throw new BusinessException(ApiCode.NO_PERMISSION);
        }
        if (!VerlaEditorAsset.STATUS_UPLOADED.equals(asset.getStatus())) {
            throw new BusinessException(ApiCode.PARAM_ERROR, "finalize requires UPLOADED status");
        }
        if (asset.getStorageUri() == null || asset.getStorageUri().startsWith("pending://")) {
            throw new BusinessException(ApiCode.PARAM_ERROR, "upload content first");
        }
        if (StringUtils.hasText(clientChecksumSha256)
                && asset.getChecksumSha256() != null
                && !clientChecksumSha256.trim().equalsIgnoreCase(asset.getChecksumSha256())) {
            throw new BusinessException(ApiCode.PARAM_ERROR, "checksum mismatch");
        }

        assetRepository.updateByAssetIdSelective(VerlaEditorAsset.builder()
                .assetId(assetId)
                .status(VerlaEditorAsset.STATUS_FINALIZED)
                .build());

        uploadTickets.invalidate(assetId);
        log.info("[Verla/editorAsset] finalize assetId={}", assetId);
        return assetRepository.findByAssetId(assetId);
    }

    private String normalizeOwnedArtifactUid(String clerkUserId, long conversationId, String artifactUid) {
        if (!StringUtils.hasText(artifactUid)) {
            return null;
        }
        VerlaArtifact artifact = artifactRepository.findByUid(artifactUid.trim());
        if (artifact == null) {
            throw new BusinessException(ApiCode.PARAM_ERROR, "artifact not found");
        }
        if (!conversationIdEquals(conversationId, artifact.getConversationId())) {
            throw new BusinessException(ApiCode.PARAM_ERROR, "artifact does not belong to conversation");
        }
        return artifact.getArtifactUid();
    }

    private static boolean conversationIdEquals(long expectedConversationId, Long actualConversationId) {
        return actualConversationId != null && actualConversationId == expectedConversationId;
    }

    public VerlaEditorAsset getOwned(String clerkUserId, String assetId) {
        if (!StringUtils.hasText(clerkUserId)) {
            throw new BusinessException(ApiCode.USER_NOT_LOGGED_IN);
        }
        VerlaEditorAsset asset = assetRepository.findByAssetId(assetId);
        if (asset == null) {
            throw new BusinessException(ApiCode.TASK_NOT_FOUND, "editor asset");
        }
        conversationService.getOwned(clerkUserId, asset.getConversationId());
        if (!asset.getUserId().equals(clerkUserId)) {
            throw new BusinessException(ApiCode.NO_PERMISSION);
        }
        return asset;
    }

    public byte[] loadContentBytes(String assetId) {
        if (assetId == null || assetId.isBlank()) {
            return null;
        }
        byte[] cached = uploadedContentCache.getIfPresent(assetId);
        if (cached != null && cached.length > 0) {
            return cached;
        }
        VerlaEditorAsset asset = assetRepository.findByAssetId(assetId);
        if (asset == null) {
            return null;
        }
        try {
            return readBytesFromStorage(asset);
        } catch (Exception e) {
            log.warn("[Verla/editorAsset] loadContentBytes failed assetId={}: {}", assetId, e.getMessage());
            return null;
        }
    }

    private byte[] readBytesFromStorage(VerlaEditorAsset asset) throws Exception {
        if (asset.getOssKey() != null && !asset.getOssKey().isBlank()) {
            byte[] ossBytes = ossStorageService.getObjectBytes(asset.getOssKey());
            if (ossBytes != null && ossBytes.length > 0) {
                return ossBytes;
            }
        }
        String uri = asset.getStorageUri();
        if (uri != null && uri.startsWith("file:")) {
            return Files.readAllBytes(Path.of(java.net.URI.create(uri)));
        }
        return null;
    }

    private byte[] readFullyLimited(InputStream rawIn, long expectedSize) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        long total = 0;
        int n;
        while ((n = rawIn.read(chunk)) != -1) {
            total += n;
            if (total > maxBytes) {
                throw new BusinessException(ApiCode.PARAM_ERROR, "upload exceeds max size");
            }
            buf.write(chunk, 0, n);
        }
        if (expectedSize > 0 && total != expectedSize) {
            log.warn("[Verla/editorAsset] size mismatch signed={} actual={}", expectedSize, total);
        }
        return buf.toByteArray();
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private String writeLocalFallback(String objectKey, byte[] body) throws IOException {
        Path root = Paths.get(localRoot).toAbsolutePath().normalize();
        Path target = root.resolve(objectKey).normalize();
        if (!target.startsWith(root)) {
            throw new BusinessException(ApiCode.PARAM_VALIDATION_FAILED, "invalid asset object key");
        }
        Files.createDirectories(target.getParent());
        Files.write(target, body);
        return target.toUri().toString();
    }

    private record UploadTicket(String clerkUserId, String token, Instant expiresAt) {}
}
