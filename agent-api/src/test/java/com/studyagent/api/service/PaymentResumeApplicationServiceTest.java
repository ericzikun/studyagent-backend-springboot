package com.studyagent.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.api.dto.response.HumanizerTaskResponse;
import com.studyagent.common.api.ApiCode;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.service.application.verla.VerlaAttachmentService;
import com.studyagent.service.application.verla.dto.VerlaUploadSignResult;
import com.studyagent.service.domain.payment.PaymentResumeContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentResumeApplicationServiceTest {

    private PaymentResumeContextService paymentResumeContextService;
    private HumanizerApplicationService humanizerApplicationService;
    private VerlaAttachmentService verlaAttachmentService;
    private PaymentResumeApplicationService service;

    @BeforeEach
    void setUp() {
        paymentResumeContextService = mock(PaymentResumeContextService.class);
        humanizerApplicationService = mock(HumanizerApplicationService.class);
        verlaAttachmentService = mock(VerlaAttachmentService.class);
        service = new PaymentResumeApplicationService(
                paymentResumeContextService,
                humanizerApplicationService,
                verlaAttachmentService);
    }

    @Test
    void resume_reissuesUploadSign_andMarksContextResumed() throws Exception {
        PaymentResumeContextService.UploadResumePayload payload =
                PaymentResumeContextService.UploadResumePayload.builder()
                        .conversationId(74L)
                        .filename("assignment.pdf")
                        .mime("application/pdf")
                        .sizeBytes(8L)
                        .build();
        PaymentResumeContext context = PaymentResumeContext.builder()
                .id(1L)
                .resumeToken("resume_upload_74")
                .clerkUserId("user_1")
                .scene("upload")
                .status("pending")
                .expiresAt(LocalDateTime.now().plusMinutes(10))
                .payloadJson(new ObjectMapper().writeValueAsString(payload))
                .build();
        when(paymentResumeContextService.findByTokenForUpdate("resume_upload_74")).thenReturn(context);
        when(paymentResumeContextService.readUploadPayload(context.getPayloadJson())).thenReturn(payload);
        when(verlaAttachmentService.requestSign(
                "user_1",
                74L,
                "assignment.pdf",
                "application/pdf",
                8L,
                null,
                null,
                null,
                null))
                .thenReturn(VerlaUploadSignResult.builder()
                        .objectId("att_1")
                        .uploadPath("/v1/verla/v2/uploads/att_1/content")
                        .method("PUT")
                        .uploadToken("upload_token_1")
                        .ossKey("verla/v2/attachments/74/att_1/assignment.pdf")
                        .expiresInSeconds(3600L)
                        .build());

        var response = service.resume("user_1", "resume_upload_74");

        assertThat(response.getScene()).isEqualTo("upload");
        assertThat(response.getStatus()).isEqualTo("resumed");
        assertThat(response.getUploadSign()).isNotNull();
        assertThat(response.getUploadSign().getObjectId()).isEqualTo("att_1");
        verify(paymentResumeContextService).markResumed(1L);
    }

    @Test
    void resume_restartsHumanizerTask_andMarksContextResumed() {
        PaymentResumeContext context = PaymentResumeContext.builder()
                .id(2L)
                .resumeToken("resume_humanize_100")
                .clerkUserId("user_1")
                .scene("humanizer_start")
                .resourceId("100")
                .status("pending")
                .expiresAt(LocalDateTime.now().plusMinutes(10))
                .build();
        when(paymentResumeContextService.findByTokenForUpdate("resume_humanize_100")).thenReturn(context);
        when(humanizerApplicationService.resumeTask(100L, "user_1"))
                .thenReturn(HumanizerTaskResponse.builder()
                        .id(100L)
                        .status("PENDING")
                        .build());

        var response = service.resume("user_1", "resume_humanize_100");

        assertThat(response.getScene()).isEqualTo("humanizer_start");
        assertThat(response.getTask()).isNotNull();
        assertThat(response.getTask().getId()).isEqualTo(100L);
        verify(paymentResumeContextService).markResumed(2L);
    }

    @Test
    void resume_rejectsExpiredToken() {
        PaymentResumeContext context = PaymentResumeContext.builder()
                .id(3L)
                .resumeToken("resume_expired")
                .clerkUserId("user_1")
                .scene("upload")
                .status("pending")
                .expiresAt(LocalDateTime.now().minusMinutes(1))
                .build();
        when(paymentResumeContextService.findByTokenForUpdate("resume_expired")).thenReturn(context);

        assertThatThrownBy(() -> service.resume("user_1", "resume_expired"))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ApiCode.RESUME_TOKEN_INVALID.getMessage());
        verify(paymentResumeContextService, never()).markResumed(anyLong());
    }
}
