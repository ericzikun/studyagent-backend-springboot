package com.studyagent.api.controller.verla;

import com.studyagent.api.common.Result;
import com.studyagent.api.dto.verla.request.VerlaEditorAssetFinalizeRequest;
import com.studyagent.api.dto.verla.request.VerlaEditorAssetSignRequest;
import com.studyagent.api.dto.verla.response.VerlaEditorAssetSignResponseVO;
import com.studyagent.api.dto.verla.response.VerlaEditorAssetVO;
import com.studyagent.common.api.ApiCode;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.service.application.verla.VerlaEditorAssetService;
import com.studyagent.service.application.verla.dto.VerlaEditorAssetSignResult;
import com.studyagent.service.domain.file.OssStorageService;
import com.studyagent.service.domain.verla.VerlaEditorAsset;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

/**
 * 编辑器内部素材上传与读取（独立域，与 VerlaAttachment 无关）。
 * <p>
 * API：
 * <ul>
 *   <li>POST /v1/verla/conversations/{cid}/editor-assets/sign</li>
 *   <li>PUT  /v1/verla/editor-assets/{assetId}/content</li>
 *   <li>POST /v1/verla/editor-assets/{assetId}/finalize</li>
 *   <li>GET  /v1/verla/editor-assets/{assetId}</li>
 *   <li>GET  /v1/verla/editor-assets/{assetId}/content</li>
 * </ul>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class VerlaEditorAssetController {

    private final VerlaEditorAssetService assetService;
    private final OssStorageService ossStorageService;

    @PostMapping("/v1/verla/conversations/{cid}/editor-assets/sign")
    public Result<VerlaEditorAssetSignResponseVO> sign(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @PathVariable Long cid,
            @RequestBody VerlaEditorAssetSignRequest req) {
        ensureLogin(clerkUserId);
        if (req == null || req.getConversationId() == null) {
            throw new BusinessException(ApiCode.PARAM_ERROR, "conversationId required");
        }
        if (!cid.equals(req.getConversationId())) {
            throw new BusinessException(ApiCode.PARAM_ERROR, "conversationId mismatch");
        }
        VerlaEditorAssetSignResult r = assetService.requestSign(
                clerkUserId,
                cid,
                req.getArtifactUid(),
                req.getFilename(),
                req.getMime(),
                req.getSizeBytes() == null ? 0L : req.getSizeBytes(),
                req.getEditorKind(),
                req.getAssetRole());
        return Result.success(VerlaEditorAssetSignResponseVO.builder()
                .assetId(r.getAssetId())
                .uploadPath(r.getUploadPath())
                .method(r.getMethod())
                .headers(Map.of(VerlaEditorAssetService.HDR_UPLOAD_TOKEN, r.getUploadToken()))
                .expiresInSeconds(r.getExpiresInSeconds())
                .build());
    }

    @PutMapping(value = "/v1/verla/editor-assets/{assetId}/content",
            consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public Result<Void> uploadContent(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @PathVariable String assetId,
            @RequestHeader(VerlaEditorAssetService.HDR_UPLOAD_TOKEN) String uploadToken,
            HttpServletRequest request) {
        ensureLogin(clerkUserId);
        try {
            assetService.uploadContent(clerkUserId, assetId, uploadToken, request.getInputStream());
        } catch (IOException e) {
            log.warn("[verla/editorAsset] IO failed assetId={}: {}", assetId, e.getMessage());
            throw new BusinessException(ApiCode.INTERNAL_ERROR, "upload failed");
        }
        return Result.success(null);
    }

    @PostMapping("/v1/verla/editor-assets/{assetId}/finalize")
    public Result<VerlaEditorAssetVO> finalizeUpload(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @PathVariable String assetId,
            @RequestHeader(VerlaEditorAssetService.HDR_UPLOAD_TOKEN) String uploadToken,
            @RequestBody(required = false) VerlaEditorAssetFinalizeRequest body) {
        ensureLogin(clerkUserId);
        String chk = body == null ? null : body.getChecksumSha256();
        VerlaEditorAsset saved = assetService.finalizeUpload(clerkUserId, assetId, uploadToken, chk);

        VerlaEditorAssetVO vo = VerlaEditorAssetVO.from(saved);
        if (saved.getOssKey() != null && !saved.getOssKey().isBlank()) {
            String publicUrl = ossStorageService.getOssUrl(saved.getOssKey());
            if (publicUrl != null) {
                vo.setPublicUrl(publicUrl);
            }
        }
        return Result.success(vo);
    }

    @GetMapping("/v1/verla/editor-assets/{assetId}")
    public Result<VerlaEditorAssetVO> getOne(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @PathVariable String assetId) {
        ensureLogin(clerkUserId);
        VerlaEditorAsset a = assetService.getOwned(clerkUserId, assetId);
        return Result.success(VerlaEditorAssetVO.from(a));
    }

    @GetMapping("/v1/verla/editor-assets/{assetId}/content")
    public ResponseEntity<Resource> getContent(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @PathVariable String assetId) {
        ensureLogin(clerkUserId);
        VerlaEditorAsset asset = assetService.getOwned(clerkUserId, assetId);
        byte[] content = assetService.loadContentBytes(assetId);
        if (content == null || content.length == 0) {
            return ResponseEntity.notFound().build();
        }
        String mimeType = asset.getMime();
        MediaType mediaType = MediaType.APPLICATION_OCTET_STREAM;
        if (mimeType != null && !mimeType.isBlank()) {
            mediaType = MediaType.parseMediaType(mimeType.trim());
        }
        return ResponseEntity.ok()
                .contentType(mediaType)
                .cacheControl(CacheControl.noCache().cachePrivate())
                .body(new ByteArrayResource(content));
    }

    private static void ensureLogin(String clerkUserId) {
        if (clerkUserId == null || clerkUserId.isBlank()) {
            throw new BusinessException(ApiCode.USER_NOT_LOGGED_IN);
        }
    }
}
