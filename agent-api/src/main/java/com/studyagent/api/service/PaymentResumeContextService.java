package com.studyagent.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.api.dto.verla.request.VerlaUploadSignRequest;
import com.studyagent.service.domain.payment.PaymentResumeContext;
import com.studyagent.service.domain.payment.PaymentResumeContextRepository;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentResumeContextService {

    public static final String STATUS_PENDING = "pending";
    public static final String SCENE_UPLOAD = "upload";

    private final PaymentResumeContextRepository paymentResumeContextRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public String createHumanizerResumeContext(
            String clerkUserId,
            String scene,
            Long taskId,
            String idempotencyKey) {
        PaymentResumeContext saved = paymentResumeContextRepository.save(PaymentResumeContext.builder()
                .resumeToken(newResumeToken())
                .clerkUserId(clerkUserId)
                .scene(scene)
                .resourceId(taskId == null ? null : String.valueOf(taskId))
                .idempotencyKey(idempotencyKey)
                .status(STATUS_PENDING)
                .expiresAt(LocalDateTime.now().plusMinutes(30))
                .build());
        return saved.getResumeToken();
    }

    @Transactional
    public String createUploadResumeContext(String clerkUserId, VerlaUploadSignRequest request) {
        UploadResumePayload payload = UploadResumePayload.builder()
                .conversationId(request.getConversationId())
                .filename(request.getFilename())
                .mime(request.getMime())
                .sizeBytes(request.getSizeBytes())
                .turnId(request.getTurnId())
                .sessionId(request.getSessionId())
                .attachmentOrigin(request.getAttachmentOrigin())
                .metaJson(request.getMetaJson())
                .build();
        PaymentResumeContext saved = paymentResumeContextRepository.save(PaymentResumeContext.builder()
                .resumeToken(newResumeToken())
                .clerkUserId(clerkUserId)
                .scene(SCENE_UPLOAD)
                .resourceId(request.getConversationId() == null ? null : String.valueOf(request.getConversationId()))
                .payloadJson(writePayload(payload))
                .status(STATUS_PENDING)
                .expiresAt(LocalDateTime.now().plusMinutes(30))
                .build());
        return saved.getResumeToken();
    }

    @Transactional(readOnly = true)
    public PaymentResumeContext findByTokenForUpdate(String resumeToken) {
        return paymentResumeContextRepository.findByTokenForUpdate(resumeToken);
    }

    @Transactional
    public void markResumed(Long id) {
        paymentResumeContextRepository.markResumed(id, LocalDateTime.now());
    }

    public UploadResumePayload readUploadPayload(String payloadJson) {
        try {
            return objectMapper.readValue(payloadJson, UploadResumePayload.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid upload resume payload", e);
        }
    }

    private String writePayload(UploadResumePayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize upload resume payload", e);
        }
    }

    private String newResumeToken() {
        return "resume_" + UUID.randomUUID().toString().replace("-", "");
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UploadResumePayload {
        private Long conversationId;
        private String filename;
        private String mime;
        private Long sizeBytes;
        private Long turnId;
        private Long sessionId;
        private String attachmentOrigin;
        private String metaJson;
    }
}
