package com.studyagent.api.controller;

import com.studyagent.api.controller.internal.RecoveryTestClockController;
import com.studyagent.infra.service.billing.*;
import com.studyagent.api.filter.RecoveryTestClockOperatorFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Deliberately no servlet filter: the mapped controller must authorize every operation itself. */
class RecoveryTestClockControllerTest {
    private final RecoveryTestClockSupport support=mock(RecoveryTestClockSupport.class);
    private final String prefix="/v1/internal/billing/recovery-test-clocks";
    private final String token="independent-controller-token-at-least-thirty-two";
    private org.springframework.test.web.servlet.MockMvc mvc() {
        var properties=new RecoveryTestClockProperties();properties.setEnabled(true);properties.setOperatorToken(token);
        return MockMvcBuilders.standaloneSetup(new RecoveryTestClockController(support,new RecoveryTestClockOperatorFilter(properties)))
                .setControllerAdvice(new com.studyagent.api.exception.GlobalExceptionHandler(mock(com.studyagent.service.domain.billing.BillingDomainService.class))).build();
    }
    @Test void mappedControllerCannotBeUsedWithoutFilterAndToken()throws Exception{
        mvc().perform(get(prefix+"/runs/recovery-unit").with(r->{r.setRemoteAddr("127.0.0.1");return r;})).andExpect(status().isUnauthorized());verifyNoInteractions(support);
    }
    @Test void encodedMappedPathRequiresAuthorizationEvenWithoutServletFilter()throws Exception{
        mvc().perform(get(java.net.URI.create("/v1/internal/billing/%72ecovery-test-clocks/runs/recovery-unit")).with(r->{r.setRemoteAddr("127.0.0.1");return r;})).andExpect(status().isUnauthorized());verifyNoInteractions(support);
    }
    @Test void contextPathCannotBypassControllerAuthorization()throws Exception{
        mvc().perform(get("/app"+prefix+"/runs/recovery-unit").contextPath("/app").with(r->{r.setRemoteAddr("127.0.0.1");return r;})).andExpect(status().isUnauthorized());verifyNoInteractions(support);
    }
    @Test void authorizedLocalOperatorStillWorksWithoutServletFilter()throws Exception{
        when(support.status("recovery-unit")).thenReturn(Map.of("testOnly",true));
        mvc().perform(get(prefix+"/runs/recovery-unit").header(RecoveryTestClockOperatorFilter.HEADER,token).with(r->{r.setRemoteAddr("127.0.0.1");return r;})).andExpect(status().isOk()).andExpect(jsonPath("$.testOnly").value(true));verify(support).status("recovery-unit");
    }
    @Test void wrongTokenIsRejectedByTheController()throws Exception{
        mvc().perform(get(prefix+"/runs/recovery-unit").header(RecoveryTestClockOperatorFilter.HEADER,"invalid").with(r->{r.setRemoteAddr("127.0.0.1");return r;})).andExpect(status().isUnauthorized());verifyNoInteractions(support);
    }
    @Test void remoteClientCannotReachTheControllerWithValidTokenOrSpoofedHeaders()throws Exception{
        mvc().perform(get(prefix+"/runs/recovery-unit").header(RecoveryTestClockOperatorFilter.HEADER,token).header("X-Forwarded-For","127.0.0.1").with(r->{r.setRemoteAddr("198.51.100.2");return r;})).andExpect(status().isForbidden());verifyNoInteractions(support);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"/runs","/runs/recovery-unit/pause","/runs/recovery-unit/first-failure","/runs/recovery-unit/advance"})
    void everyWriteRechecksAuthorizationBeforeItsService(String path)throws Exception{
        mvc().perform(post(prefix+path).contentType("application/json").content("{}").with(r->{r.setRemoteAddr("127.0.0.1");return r;})).andExpect(status().isUnauthorized());verifyNoInteractions(support);
    }
}
