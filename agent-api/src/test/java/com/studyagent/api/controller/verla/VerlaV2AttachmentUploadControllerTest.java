package com.studyagent.api.controller.verla;

import com.studyagent.api.exception.GlobalExceptionHandler;
import com.studyagent.api.service.PaymentResumeContextService;
import com.studyagent.common.api.ApiCode;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.service.application.verla.VerlaAttachmentService;
import com.studyagent.service.domain.file.OssStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class VerlaV2AttachmentUploadControllerTest {

    private VerlaAttachmentService attachmentService;
    private PaymentResumeContextService paymentResumeContextService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        attachmentService = mock(VerlaAttachmentService.class);
        paymentResumeContextService = mock(PaymentResumeContextService.class);
        OssStorageService ossStorageService = mock(OssStorageService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new VerlaV2AttachmentUploadController(
                                attachmentService,
                                ossStorageService,
                                paymentResumeContextService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void sign_returnsResumeToken_whenFileLimitReached() throws Exception {
        doThrow(new BusinessException(ApiCode.FILE_LIMIT_REACHED))
                .when(attachmentService).requestSign(
                        anyString(),
                        anyLong(),
                        anyString(),
                        anyString(),
                        anyLong(),
                        org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.isNull());
        when(paymentResumeContextService.createUploadResumeContext(
                org.mockito.ArgumentMatchers.eq("user_1"),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn("resume_upload_74");

        mockMvc.perform(post("/v1/verla/v2/uploads/sign")
                        .requestAttr("clerkUserId", "user_1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "conversationId": 74,
                                  "filename": "assignment.pdf",
                                  "mime": "application/pdf",
                                  "sizeBytes": 8
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.statusCode").value(ApiCode.FILE_LIMIT_REACHED.getCode()))
                .andExpect(jsonPath("$.data.resumeToken").value("resume_upload_74"))
                .andExpect(jsonPath("$.data.scene").value("upload"));
    }
}
