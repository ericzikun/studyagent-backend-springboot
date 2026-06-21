package com.studyagent.api.controller;

import com.studyagent.api.dto.response.PaymentResumeResponse;
import com.studyagent.api.dto.response.HumanizerTaskResponse;
import com.studyagent.api.dto.verla.response.VerlaUploadSignResponseVO;
import com.studyagent.api.exception.GlobalExceptionHandler;
import com.studyagent.api.service.PaymentResumeApplicationService;
import com.studyagent.service.domain.billing.BillingDomainService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PaymentResumeControllerTest {

    private PaymentResumeApplicationService paymentResumeApplicationService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        paymentResumeApplicationService = mock(PaymentResumeApplicationService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new PaymentResumeController(paymentResumeApplicationService))
                .setControllerAdvice(new GlobalExceptionHandler(mock(BillingDomainService.class)))
                .build();
    }

    @Test
    void resume_returnsUploadSignPayload_forUploadScene() throws Exception {
        when(paymentResumeApplicationService.resume("user_1", "resume_upload_74"))
                .thenReturn(PaymentResumeResponse.builder()
                        .scene("upload")
                        .status("resumed")
                        .uploadSign(VerlaUploadSignResponseVO.builder()
                                .objectId("att_1")
                                .uploadPath("/v1/verla/v2/uploads/att_1/content")
                                .method("PUT")
                                .headers(java.util.Map.of("X-Verla-Upload-Token", "upload_token_1"))
                                .ossKey("verla/v2/attachments/74/att_1/assignment.pdf")
                                .expiresInSeconds(3600L)
                                .build())
                        .build());

        mockMvc.perform(post("/v1/payment/resume/resume_upload_74")
                        .requestAttr("clerkUserId", "user_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.statusCode").value(0))
                .andExpect(jsonPath("$.data.scene").value("upload"))
                .andExpect(jsonPath("$.data.status").value("resumed"))
                .andExpect(jsonPath("$.data.uploadSign.objectId").value("att_1"));
    }

    @Test
    void resume_returnsTaskPayload_forHumanizerScene() throws Exception {
        when(paymentResumeApplicationService.resume("user_1", "resume_humanize_100"))
                .thenReturn(PaymentResumeResponse.builder()
                        .scene("humanizer_start")
                        .status("resumed")
                        .task(HumanizerTaskResponse.builder()
                                .id(100L)
                                .status("PENDING")
                                .resumeToken(null)
                                .build())
                        .build());

        mockMvc.perform(post("/v1/payment/resume/resume_humanize_100")
                        .requestAttr("clerkUserId", "user_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.statusCode").value(0))
                .andExpect(jsonPath("$.data.scene").value("humanizer_start"))
                .andExpect(jsonPath("$.data.task.id").value(100))
                .andExpect(jsonPath("$.data.task.status").value("PENDING"));
    }
}
