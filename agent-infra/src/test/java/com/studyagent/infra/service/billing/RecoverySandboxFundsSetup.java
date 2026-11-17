package com.studyagent.infra.service.billing;

import com.google.gson.Gson;
import com.stripe.Stripe;
import com.stripe.exception.CardException;
import com.stripe.exception.StripeException;
import com.stripe.model.*;
import com.stripe.net.RequestOptions;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in sandbox preparation only. Authentication is completed by a real browser, never simulated here. */
final class RecoverySandboxFundsSetup {
    static final String METHOD="pm_card_authenticationRequiredChargeDeclinedInsufficientFunds";
    private static final int AUTH_TIMEOUT_SECONDS=900;
    private static final Path CHALLENGE=Path.of("/private/tmp/recovery-funds-challenge.json");
    private RecoverySandboxFundsSetup(){}

    static PaymentMethod prepare(String run,Customer customer,RequestOptions original,
            RecoverySandboxCreationTracker creates,RecoverySandboxCreationTracker.Journal journal,
            Consumer<SetupIntent> registerCleanup) throws Exception {
        assertEquals("24.0.0",Stripe.VERSION);assertEquals("2023-10-16",Stripe.API_VERSION);
        assertEquals(Boolean.FALSE,customer.getLivemode());assertEquals(run,customer.getMetadata().get("recovery_test_run"));
        var options=original.toBuilder().setMaxNetworkRetries(0).build();
        try {
            var params=new LinkedHashMap<String,Object>();params.put("customer",customer.getId());params.put("usage","off_session");
            params.put("payment_method_types",List.of("card"));params.put("payment_method",METHOD);params.put("confirm",true);
            params.put("return_url","http://localhost:18787/stripe-return");params.put("metadata",Map.of("recovery_test_run",run));
            var setup=creates.create("create-funds-setup-intent",params,
                    (body,key)->{
                        try{return SetupIntent.create(body,options.toBuilder().setIdempotencyKey(key).build());}
                        catch(CardException e){
                            var nativeSetup=e.getStripeError()==null?null:e.getStripeError().getSetupIntent();
                            journal.record("funds-setup-native-decline",Map.of("requestId",Objects.toString(e.getRequestId(),""),"declineCode",Objects.toString(e.getDeclineCode(),""),"setupIntentId",nativeSetup==null?"":Objects.toString(nativeSetup.getId(),""),"result","FAIL"));
                            if(nativeSetup==null||nativeSetup.getId()==null)throw e;
                            return nativeSetup;
                        }
                    },
                    created->{checkSetup(created,run,customer.getId());registerCleanup.accept(created);});
            String id=setup.getId();recordState(journal,setup,"funds-setup-created");
            if("requires_action".equals(setup.getStatus())) {
                var next=setup.getNextAction();
                if(next==null||!"redirect_to_url".equals(next.getType())||next.getRedirectToUrl()==null||next.getRedirectToUrl().getUrl()==null){
                    journal.record("funds-setup-unsupported-action",Map.of("setupIntentId",id,"state",setup.getStatus(),"nextActionType",next==null?"missing":Objects.toString(next.getType(),"missing"),"result","NOT_RUN"));
                    throw new IllegalStateException("FUNDS_SETUP_REQUIRES_BROWSER_REDIRECT_NOT_AVAILABLE");
                }
                try(var challenge=writeChallenge(CHALLENGE,run,id,next.getRedirectToUrl().getUrl())) {
                    journal.record("funds-setup-awaiting-browser",Map.of("setupIntentId",id,"state",setup.getStatus(),"privateChallengePath",CHALLENGE.toString(),"timeoutSeconds",AUTH_TIMEOUT_SECONDS,"result","NOT_RUN"));
                    long deadline=System.nanoTime()+java.time.Duration.ofSeconds(AUTH_TIMEOUT_SECONDS).toNanos();
                    while("requires_action".equals(setup.getStatus())||"processing".equals(setup.getStatus())) {
                        if(System.nanoTime()>=deadline)throw new IllegalStateException("FUNDS_SETUP_BROWSER_AUTHENTICATION_TIMEOUT");
                        Thread.sleep(2000);setup=SetupIntent.retrieve(id,options);checkSetup(setup,run,customer.getId());
                    }
                }
            }
            recordState(journal,setup,"funds-setup-observed");
            assertEquals("succeeded",setup.getStatus(),"Real SetupIntent must succeed before retaining a reusable method");assertNotNull(setup.getPaymentMethod());
            var method=PaymentMethod.retrieve(setup.getPaymentMethod(),options);assertEquals(Boolean.FALSE,method.getLivemode());assertEquals(customer.getId(),method.getCustomer());assertEquals("card",method.getType());
            var body=Map.<String,Object>of("invoice_settings",Map.of("default_payment_method",method.getId()));String key=run+":funds-setup-default-method";
            journal.record("funds-setup-default-intent",Map.of("customerId",customer.getId(),"setupIntentId",id,"parameters",body,"idempotencyKey",key));
            var saved=customer.update(body,options.toBuilder().setIdempotencyKey(key).build());assertEquals(Boolean.FALSE,saved.getLivemode());assertEquals(method.getId(),saved.getInvoiceSettings().getDefaultPaymentMethod());
            journal.record("funds-setup-ready",Map.of("setupIntentId",id,"paymentMethodId",method.getId(),"state",setup.getStatus(),"fourSlotResult","NOT_RUN"));return method;
        } catch(StripeException e) {
            // Never propagate SDK response bodies/causes: authentication URLs and client secrets are private.
            journal.record("funds-setup-error",Map.of("type",e.getClass().getSimpleName(),"requestId",Objects.toString(e.getRequestId(),""),"code",Objects.toString(e.getCode(),""),"result","FAIL"));
            throw new IllegalStateException("FUNDS_SETUP_STRIPE_ERROR_SEE_REDACTED_JOURNAL");
        }
    }
    private static void checkSetup(SetupIntent setup,String run,String customer){
        assertEquals(Boolean.FALSE,setup.getLivemode());assertEquals(customer,setup.getCustomer());assertEquals(run,setup.getMetadata().get("recovery_test_run"));
    }
    private static void recordState(RecoverySandboxCreationTracker.Journal journal,SetupIntent setup,String phase)throws Exception {
        journal.record(phase,Map.of("setupIntentId",setup.getId(),"state",setup.getStatus(),"requestId",setup.getLastResponse()==null?"":Objects.toString(setup.getLastResponse().requestId(),"")));
    }
    static ChallengeFile writeChallenge(Path path,String run,String setupId,String url)throws Exception {
        var permissions=PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
        try(var channel=Files.newByteChannel(path,Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS),permissions)) {
            var json=new Gson().toJson(Map.of("runId",run,"setupIntentId",setupId,"challengeUrl",url,"expiresAt",Instant.now().plusSeconds(AUTH_TIMEOUT_SECONDS).toString()));
            var bytes=ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8));while(bytes.hasRemaining())channel.write(bytes);
        }
        return new ChallengeFile(path,Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey());
    }
    static final class ChallengeFile implements AutoCloseable {
        private final Path path;private final Object fileKey;
        ChallengeFile(Path path,Object fileKey){this.path=path;this.fileKey=Objects.requireNonNull(fileKey);}
        @Override public void close()throws Exception {
            if(Files.exists(path,LinkOption.NOFOLLOW_LINKS)&&fileKey.equals(Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey()))Files.delete(path);
        }
    }
}
