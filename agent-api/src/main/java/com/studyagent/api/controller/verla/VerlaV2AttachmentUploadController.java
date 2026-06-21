package com.studyagent.api.controller.verla;

import com.studyagent.api.common.Result;
import com.studyagent.api.dto.verla.request.VerlaUploadFinalizeRequest;
import com.studyagent.api.dto.verla.request.VerlaUploadSignRequest;
import com.studyagent.api.dto.verla.response.VerlaAttachmentVO;
import com.studyagent.api.dto.verla.response.VerlaUploadSignResponseVO;
import com.studyagent.common.api.ApiCode;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.common.exception.CommercialBlockData;
import com.studyagent.api.service.PaymentResumeContextService;
import com.studyagent.service.application.verla.VerlaAttachmentService;
import com.studyagent.service.application.verla.dto.VerlaUploadSignResult;
import com.studyagent.service.domain.billing.BillingDomainService;
import com.studyagent.service.domain.billing.BillingPlan;
import com.studyagent.service.domain.file.OssStorageService;
import com.studyagent.service.domain.verla.VerlaAttachment;
import com.studyagent.common.exception.CurrentPlanData;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

/**
 * Verla V2 专用上传（OSS）；与 {@code /v1/file} 及旧版本地落盘无关。
 */
@Slf4j
@RestController
@RequestMapping("/v1/verla/v2/uploads")
@RequiredArgsConstructor
public class VerlaV2AttachmentUploadController {

    private final VerlaAttachmentService attachmentService;
    private final OssStorageService ossStorageService;
    private final PaymentResumeContextService paymentResumeContextService;
    private final BillingDomainService billingDomainService;

    @PostMapping("/sign")
    public ResponseEntity<Result<Object>> sign(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @RequestBody VerlaUploadSignRequest req) {
        ensureLogin(clerkUserId);
        if (req == null || req.getConversationId() == null) {
            throw new BusinessException(ApiCode.PARAM_ERROR, "conversationId required");
        }
        try {
            VerlaUploadSignResult r = attachmentService.requestSign(
                    clerkUserId,
                    req.getConversationId(),
                    req.getFilename(),
                    req.getMime(),
                    req.getSizeBytes() == null ? 0L : req.getSizeBytes(),
                    req.getTurnId(),
                    req.getSessionId(),
                    req.getAttachmentOrigin(),
                    req.getMetaJson());
            return ResponseEntity.ok(Result.success(VerlaUploadSignResponseVO.builder()
                    .objectId(r.getObjectId())
                    .uploadPath(r.getUploadPath())
                    .method(r.getMethod())
                    .headers(Map.of(VerlaAttachmentService.HDR_UPLOAD_TOKEN, r.getUploadToken()))
                    .ossKey(r.getOssKey())
                    .expiresInSeconds(r.getExpiresInSeconds())
                    .build()));
        } catch (BusinessException e) {
            if (e.getCode() != ApiCode.FILE_LIMIT_REACHED.getCode()) {
                throw e;
            }
            String resumeToken = paymentResumeContextService.createUploadResumeContext(clerkUserId, req);
            CommercialBlockData payload = enrichFileLimitPayload(clerkUserId, e.getData(), resumeToken);
            Result<Object> result = new Result<>();
            result.setMeta(com.studyagent.api.common.Meta.error(e.getCode(), e.getMessage()));
            result.setData(payload);
            return ResponseEntity.badRequest().body(result);
        }
    }

    @PutMapping(value = "/{objectId}/content", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public Result<Void> uploadContent(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @PathVariable String objectId,
            @RequestHeader(VerlaAttachmentService.HDR_UPLOAD_TOKEN) String uploadToken,
            HttpServletRequest request) {
        ensureLogin(clerkUserId);
        try {
            attachmentService.uploadContent(clerkUserId, objectId, uploadToken, request.getInputStream());
        } catch (IOException e) {
            log.warn("[verla/v2/uploads] IO failed objectId={}: {}", objectId, e.getMessage());
            throw new BusinessException(ApiCode.INTERNAL_ERROR, "upload failed");
        }
        return Result.success(null);
    }

    @PostMapping("/{objectId}/finalize")
    public Result<VerlaAttachmentVO> finalizeUpload(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @PathVariable String objectId,
            @RequestHeader(VerlaAttachmentService.HDR_UPLOAD_TOKEN) String uploadToken,
            @RequestBody(required = false) VerlaUploadFinalizeRequest body) {
        ensureLogin(clerkUserId);
        Long turnId = body == null ? null : body.getTurnId();
        String chk = body == null ? null : body.getChecksumSha256();
        boolean skipParse = body != null && Boolean.TRUE.equals(body.getSkipAttachmentParse());
        VerlaAttachment saved = attachmentService.finalizeUpload(
                clerkUserId, objectId, uploadToken, turnId, chk, skipParse);
        VerlaAttachmentVO vo = VerlaAttachmentVO.fromUser(saved);
        if (saved.getOssKey() != null && !saved.getOssKey().isBlank()
                && ("DOCUMENT_EDITOR_IMAGE".equalsIgnoreCase(saved.getAttachmentOrigin())
                    || "EDITOR_PREVIEW_IMAGE".equalsIgnoreCase(saved.getAttachmentOrigin()))) {
            String publicUrl = ossStorageService.getOssUrl(saved.getOssKey());
            if (publicUrl == null) {
                publicUrl = "/api/document-editor/images/" + saved.getObjectId();
            }
            vo.setPublicUrl(publicUrl);
        }
        return Result.success(vo);
    }

    @DeleteMapping("/{objectId}")
    public Result<Void> deleteAttachment(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @PathVariable String objectId) {
        ensureLogin(clerkUserId);
        attachmentService.deleteAttachment(clerkUserId, objectId);
        return Result.success(null);
    }

    private static void ensureLogin(String clerkUserId) {
        if (clerkUserId == null || clerkUserId.isBlank()) {
            throw new BusinessException(ApiCode.USER_NOT_LOGGED_IN);
        }
    }

    private CommercialBlockData enrichFileLimitPayload(String clerkUserId, Object data, String resumeToken) {
        if (data instanceof CommercialBlockData payload) {
            return payload.toBuilder()
                    .scene(PaymentResumeContextService.SCENE_UPLOAD)
                    .resumeToken(resumeToken)
                    .build();
        }
        return CommercialBlockData.builder()
                .currentPlan(resolveCurrentPlan(clerkUserId))
                .reasonCode("file_limit_reached")
                .purchaseProductId("assignment")
                .blockedAction("upload_sign")
                .scene(PaymentResumeContextService.SCENE_UPLOAD)
                .resumeToken(resumeToken)
                .build();
    }

    private CurrentPlanData resolveCurrentPlan(String clerkUserId) {
        if (clerkUserId == null || clerkUserId.isBlank() || billingDomainService == null) {
            return null;
        }
        BillingPlan plan = billingDomainService.getEffectivePlanOrFree(clerkUserId);
        if (plan == null) {
            return null;
        }
        return CurrentPlanData.builder()
                .planCode(plan.getPlanCode())
                .tier(plan.getTier())
                .isPaid(isPaidPlan(plan))
                .build();
    }

    private boolean isPaidPlan(BillingPlan plan) {
        if (plan == null) {
            return false;
        }
        String tier = plan.getTier();
        if (tier != null) {
            return !"free".equalsIgnoreCase(tier);
        }
        String planCode = plan.getPlanCode();
        return planCode != null && !"free".equalsIgnoreCase(planCode);
    }
}
