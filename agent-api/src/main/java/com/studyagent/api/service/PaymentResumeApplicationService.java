package com.studyagent.api.service;

import com.studyagent.api.dto.response.PaymentResumeResponse;
import com.studyagent.api.dto.verla.response.VerlaUploadSignResponseVO;
import com.studyagent.common.api.ApiCode;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.service.application.verla.VerlaAttachmentService;
import com.studyagent.service.application.verla.dto.VerlaUploadSignResult;
import com.studyagent.service.domain.payment.PaymentResumeContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PaymentResumeApplicationService {

    private final PaymentResumeContextService paymentResumeContextService;
    private final HumanizerApplicationService humanizerApplicationService;
    private final VerlaAttachmentService verlaAttachmentService;

    @Transactional(rollbackFor = Exception.class)
    public PaymentResumeResponse resume(String clerkUserId, String resumeToken) {
        PaymentResumeContext context = paymentResumeContextService.findByTokenForUpdate(resumeToken);
        if (context == null
                || context.getExpiresAt() == null
                || context.getExpiresAt().isBefore(LocalDateTime.now())
                || context.getClerkUserId() == null
                || !context.getClerkUserId().equals(clerkUserId)
                || context.getStatus() == null
                || !"pending".equalsIgnoreCase(context.getStatus())) {
            throw new BusinessException(ApiCode.RESUME_TOKEN_INVALID);
        }

        PaymentResumeResponse response = switch (context.getScene()) {
            case "humanizer_start", "detection_start" -> PaymentResumeResponse.builder()
                    .scene(context.getScene())
                    .status("resumed")
                    .task(humanizerApplicationService.resumeTask(Long.valueOf(context.getResourceId()), clerkUserId))
                    .build();
            case PaymentResumeContextService.SCENE_UPLOAD -> {
                PaymentResumeContextService.UploadResumePayload payload =
                        paymentResumeContextService.readUploadPayload(context.getPayloadJson());
                VerlaUploadSignResult signResult = verlaAttachmentService.requestSign(
                        clerkUserId,
                        payload.getConversationId(),
                        payload.getFilename(),
                        payload.getMime(),
                        payload.getSizeBytes() == null ? 0L : payload.getSizeBytes(),
                        payload.getTurnId(),
                        payload.getSessionId(),
                        payload.getAttachmentOrigin(),
                        payload.getMetaJson());
                yield PaymentResumeResponse.builder()
                        .scene(context.getScene())
                        .status("resumed")
                        .uploadSign(VerlaUploadSignResponseVO.builder()
                                .objectId(signResult.getObjectId())
                                .uploadPath(signResult.getUploadPath())
                                .method(signResult.getMethod())
                                .headers(Map.of(VerlaAttachmentService.HDR_UPLOAD_TOKEN, signResult.getUploadToken()))
                                .ossKey(signResult.getOssKey())
                                .expiresInSeconds(signResult.getExpiresInSeconds())
                                .build())
                        .build();
            }
            default -> throw new BusinessException(ApiCode.RESUME_TOKEN_INVALID);
        };

        paymentResumeContextService.markResumed(context.getId());
        return response;
    }
}
