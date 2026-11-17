package com.studyagent.infra.service.billing;

import com.google.gson.Gson;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.time.Instant;
import java.util.*;

/** Test-only policy and private staged URL handoff; never contacts a provider. */
final class RecoverySandboxPortalHosted {
    static void requireScope(RecoverySandboxFixture fixture,Map<String,String> env,String failure){
        if(!"true".equals(env.get("STRIPE_RECOVERY_APPLICATION_IT"))||!"true".equals(env.get("STRIPE_RECOVERY_ATOMIC_TRIAL_END"))
                ||!fixture.priceUpdate()||!"month".equals(fixture.interval())||fixture.keepSchedule()||fixture.periodEnd()
                ||!"usd".equals(fixture.currency())||fixture.unitAmountMinor()!=7999||fixture.introAmountMinor()!=299||!"generic_decline".equals(failure))throw new IllegalArgumentException("PORTAL_HOSTED_SCOPE_REQUIRED");
        for(String flag:List.of("MAIL_ACCEPTANCE","FUNDS_SETUP","D30_CLOSE","ANNUAL_REFRESH","SHIFT_ACK_LOSS","ACTIVE_CANCEL","PRECLOSE_SETUP_DRAFT"))
            if("true".equals(env.get("STRIPE_RECOVERY_"+flag)))throw new IllegalArgumentException("PORTAL_HOSTED_SCENARIO_CONFLICT");
    }
    static Map<String,Object> configurationParameters(String run){
        var features=new LinkedHashMap<String,Object>();
        for(String feature:List.of("payment_method_update","customer_update","invoice_history","subscription_cancel","subscription_update","subscription_pause"))features.put(feature,Map.of("enabled",feature.equals("payment_method_update")));
        return Map.of("metadata",Map.of("recovery_test_run",run,"purpose","portal_method_only"),"features",features,"login_page",Map.of("enabled",false));
    }
    static RecoverySandboxMailApproval.ChallengeFile writeChallenge(Path path,String run,long rootId,String step,String portal,String hosted,Instant expiresAt)throws Exception {
        requireOrigin(portal,"billing.stripe.com");requireOrigin(hosted,"invoice.stripe.com");
        if(!Set.of("PORTAL_METHOD","HOSTED_PAYMENT").contains(step))throw new IllegalArgumentException("PORTAL_HOSTED_STAGE_REQUIRED");
        var values=new LinkedHashMap<String,Object>();values.put("runId",run);values.put("rootId",rootId);values.put("stripeMode","TEST");values.put("step",step);values.put("expiresAt",expiresAt.toString());values.put("portalUrl",portal);
        if("HOSTED_PAYMENT".equals(step))values.put("hostedInvoiceUrl",hosted);
        values.put("portalUiEvidence","NOT_OBSERVED");values.put("paymentUiEvidence","NOT_OBSERVED");values.put("authorization","USER_AUTHORIZED_TEST_OBJECTS_AND_PAYMENTS");
        var permissions=PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
        try(var channel=Files.newByteChannel(path,Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS),permissions)){
            var bytes=ByteBuffer.wrap(new Gson().toJson(values).getBytes(StandardCharsets.UTF_8));while(bytes.hasRemaining())channel.write(bytes);
        }
        return new RecoverySandboxMailApproval.ChallengeFile(path,Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey());
    }
    private static void requireOrigin(String value,String host){
        try{var uri=URI.create(value);if("https".equals(uri.getScheme())&&host.equals(uri.getHost())&&uri.getUserInfo()==null&&(uri.getPort()==-1||uri.getPort()==443))return;}
        catch(IllegalArgumentException ignored){}
        throw new IllegalArgumentException("PORTAL_HOSTED_ORIGIN_REQUIRED");
    }
}
