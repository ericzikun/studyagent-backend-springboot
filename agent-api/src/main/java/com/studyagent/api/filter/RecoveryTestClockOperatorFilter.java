package com.studyagent.api.filter;

import com.studyagent.infra.service.billing.RecoveryTestClockProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Loopback only, independent of Clerk/dev-bypass; proxy headers never grant operator access. */
@Component @Order(Ordered.HIGHEST_PRECEDENCE+20)
@ConditionalOnProperty(name="billing.payment-recovery.test-clock.enabled",havingValue="true")
public class RecoveryTestClockOperatorFilter extends OncePerRequestFilter {
    public static final String PREFIX="/v1/internal/billing/recovery-test-clocks";
    public static final String HEADER="X-Recovery-Test-Token";
    private final RecoveryTestClockProperties properties;
    public RecoveryTestClockOperatorFilter(RecoveryTestClockProperties properties){this.properties=properties;}
    @Override protected boolean shouldNotFilter(HttpServletRequest request){
        return !request.getRequestURI().startsWith(PREFIX)&&!request.getServletPath().startsWith(PREFIX);
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws IOException,ServletException{
        try{requireOperator(request);}catch(ResponseStatusException denied){response.sendError(denied.getStatusCode().value());return;}
        chain.doFilter(request,response);
    }
    /** Also invoked by the mapped controller; authorization never depends on raw URI matching. */
    public void requireOperator(HttpServletRequest request){
        if(!properties.isEnabled()||!Set.of("127.0.0.1","::1","0:0:0:0:0:0:0:1").contains(request.getRemoteAddr()))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        String supplied=request.getHeader(HEADER),expected=properties.getOperatorToken();
        if(expected==null||expected.length()<32||supplied==null||supplied.length()>256
                ||!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8)))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
}
