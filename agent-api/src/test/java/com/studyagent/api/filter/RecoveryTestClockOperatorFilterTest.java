package com.studyagent.api.filter;

import com.studyagent.infra.service.billing.RecoveryTestClockProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryTestClockOperatorFilterTest {
    static final String TOKEN="test-operator-token-at-least-thirty-two-characters";
    private int request(String remote,String token,boolean enabled,String spoof)throws Exception{
        var props=new RecoveryTestClockProperties();props.setEnabled(enabled);props.setOperatorToken(TOKEN);
        var req=new MockHttpServletRequest("POST",RecoveryTestClockOperatorFilter.PREFIX+"/runs");req.setRemoteAddr(remote);
        if(token!=null)req.addHeader(RecoveryTestClockOperatorFilter.HEADER,token);
        if(spoof!=null)req.addHeader("X-Forwarded-For",spoof);
        var response=new MockHttpServletResponse();new RecoveryTestClockOperatorFilter(props).doFilter(req,response,(a,b)->((jakarta.servlet.http.HttpServletResponse)b).setStatus(204));return response.getStatus();
    }
    @Test void localOperatorWithStrongTokenCanReachTheHandler()throws Exception{assertEquals(204,request("127.0.0.1",TOKEN,true,null));}
    @Test void ipv6LoopbackIsSupported()throws Exception{assertEquals(204,request("::1",TOKEN,true,null));}
    @Test void missingTokenCannotUseDevBypass()throws Exception{assertEquals(401,request("127.0.0.1",null,true,null));}
    @Test void wrongTokenIsRejected()throws Exception{assertEquals(401,request("127.0.0.1","invalid",true,null));}
    @Test void proxyHeadersCannotTurnRemoteTrafficIntoAnOperator()throws Exception{assertEquals(403,request("172.18.0.2",TOKEN,true,"127.0.0.1"));}
    @Test void publicTrafficCannotUseTheOperatorToken()throws Exception{assertEquals(403,request("198.51.100.2",TOKEN,true,null));}
    @Test void disabledFeatureRejectsEvenValidCredentials()throws Exception{assertEquals(403,request("127.0.0.1",TOKEN,false,null));}
    @Test void exactPrefixWithoutSlashStillRequiresAuthorization()throws Exception{
        var props=new RecoveryTestClockProperties();props.setEnabled(true);props.setOperatorToken(TOKEN);
        var request=new MockHttpServletRequest("GET",RecoveryTestClockOperatorFilter.PREFIX);request.setRemoteAddr("127.0.0.1");
        var response=new MockHttpServletResponse();new RecoveryTestClockOperatorFilter(props).doFilter(request,response,(a,b)->((jakarta.servlet.http.HttpServletResponse)b).setStatus(204));assertEquals(401,response.getStatus());
    }
    @Test void unrelatedApiIsUnaffected()throws Exception{
        var response=new MockHttpServletResponse();new RecoveryTestClockOperatorFilter(new RecoveryTestClockProperties()).doFilter(new MockHttpServletRequest("GET","/v1/billing/config"),response,(a,b)->((jakarta.servlet.http.HttpServletResponse)b).setStatus(204));assertEquals(204,response.getStatus());
    }
}
