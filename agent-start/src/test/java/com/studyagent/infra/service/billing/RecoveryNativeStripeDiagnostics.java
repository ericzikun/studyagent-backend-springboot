package com.studyagent.infra.service.billing;

import com.stripe.exception.*;
import com.stripe.model.*;
import com.stripe.net.*;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.*;

/** Test-only transparent SDK observer; never changes parameters, retries, API version or outcomes. */
final class RecoveryNativeStripeDiagnostics implements StripeResponseGetter, AutoCloseable {
    private abstract static class Access extends ApiResource { static StripeResponseGetter current(){return getGlobalResponseGetter();} }
    private final StripeResponseGetter previous=Access.current();
    private final RecoverySandboxApplicationHarness fixture;
    RecoveryNativeStripeDiagnostics(RecoverySandboxApplicationHarness fixture){this.fixture=fixture;ApiResource.setStripeResponseGetter(this);}
    @Override public <T extends StripeObjectInterface>T request(BaseAddress base,ApiResource.RequestMethod method,String path,Map<String,Object> params,Type type,RequestOptions options,ApiMode mode)throws StripeException{
        try{
            T value=previous.request(base,method,path,params,type,options,mode);
            if(value instanceof StripeActiveObject active)active.setResponseGetter(this);
            return value;
        }catch(InvalidRequestException rejected){
            if(method==ApiResource.RequestMethod.POST&&path.endsWith("/"+fixture.ownedSubscriptionId())&&params!=null&&params.containsKey("default_payment_method")){
                String message=Objects.toString(rejected.getMessage(),"").replaceAll("(?:sk|rk)_(?:test|live)_[A-Za-z0-9]+","[REDACTED]");
                try{fixture.evidence("native-method-write-rejection",Map.of("target",fixture.ownedSubscriptionId(),"providerRequestId",Objects.toString(rejected.getRequestId(),""),"parameter",Objects.toString(rejected.getParam(),""),"code",Objects.toString(rejected.getCode(),"invalid_request"),"message",message.substring(0,Math.min(message.length(),1000))));}
                catch(Exception auditFailure){throw new IllegalStateException("Native SDK rejection evidence could not persist",auditFailure);}
            }
            throw rejected;
        }
    }
    @Override public InputStream requestStream(BaseAddress base,ApiResource.RequestMethod method,String path,Map<String,Object> params,RequestOptions options,ApiMode mode)throws StripeException{return previous.requestStream(base,method,path,params,options,mode);}
    @Override public void validateRequestOptions(RequestOptions options){previous.validateRequestOptions(options);}
    @Override public void close(){ApiResource.setStripeResponseGetter(previous);}
}
