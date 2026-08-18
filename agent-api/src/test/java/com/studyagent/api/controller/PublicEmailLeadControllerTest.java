package com.studyagent.api.controller;

import com.studyagent.api.web.ClientIpResolver;
import com.studyagent.api.exception.GlobalExceptionHandler;
import com.studyagent.common.exception.PublicWriteProtectionUnavailableException;
import com.studyagent.common.log.annotation.ApiLog;
import com.studyagent.service.application.emaillead.PublicEmailLeadApplicationService;
import com.studyagent.service.domain.billing.BillingDomainService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PublicEmailLeadControllerTest {

    private PublicEmailLeadApplicationService applicationService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        applicationService = mock(PublicEmailLeadApplicationService.class);
        ClientIpResolver clientIpResolver = mock(ClientIpResolver.class);
        when(clientIpResolver.resolve(any())).thenReturn("203.0.113.8");
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new PublicEmailLeadController(applicationService, clientIpResolver))
                .setControllerAdvice(new GlobalExceptionHandler(mock(BillingDomainService.class)))
                .build();
    }

    @Test
    void returnsSameAcceptedShapeAfterApplicationServiceCompletes() throws Exception {
        mockMvc.perform(post("/v1/public/email-leads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "person@example.com",
                                  "source": "/tools",
                                  "companyWebsite": ""
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.accepted").value(true));

        verify(applicationService).capture(
                "person@example.com", "/tools", "", "203.0.113.8");
    }

    @Test
    void disablesGenericRequestLoggingForEmailPii() throws Exception {
        ApiLog apiLog = PublicEmailLeadController.class
                .getDeclaredMethod(
                        "capture",
                        com.studyagent.api.dto.request.PublicEmailLeadRequest.class,
                        jakarta.servlet.http.HttpServletRequest.class)
                .getAnnotation(ApiLog.class);

        assertThat(apiLog).isNotNull();
        assertThat(apiLog.logRequest()).isFalse();
    }

    @Test
    void returnsServiceUnavailableWhenRedisProtectionCannotRun() throws Exception {
        doThrow(new PublicWriteProtectionUnavailableException())
                .when(applicationService)
                .capture("person@example.com", "/tools", "", "203.0.113.8");

        mockMvc.perform(post("/v1/public/email-leads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "person@example.com",
                                  "source": "/tools",
                                  "companyWebsite": ""
                                }
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.meta.statusCode").value(503));
    }
}
