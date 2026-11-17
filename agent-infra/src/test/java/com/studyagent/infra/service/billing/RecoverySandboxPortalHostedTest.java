package com.studyagent.infra.service.billing;

import com.google.gson.JsonParser;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RecoverySandboxPortalHostedTest {
    @TempDir Path dir;
    private RecoverySandboxFixture fixture(){return RecoverySandboxFixture.parse("pro_trial_to_monthly:pro_monthly:month_1:NO_SCHEDULE:RENEW","{\"currency\":\"usd\",\"unitAmountMinor\":7999,\"introAmountMinor\":299,\"conversionMode\":\"PRICE_UPDATE\"}");}
    private Map<String,String> flags(){return new HashMap<>(Map.of("STRIPE_RECOVERY_APPLICATION_IT","true","STRIPE_RECOVERY_ATOMIC_TRIAL_END","true"));}
    @Test void permitsOnlyApplicationMonthlyPriceUpdateRenewWithAtomicShift(){
        assertDoesNotThrow(()->RecoverySandboxPortalHosted.requireScope(fixture(),flags(),"generic_decline"));
        for(String required:flags().keySet()){var env=flags();env.remove(required);assertThrows(IllegalArgumentException.class,()->RecoverySandboxPortalHosted.requireScope(fixture(),env,"generic_decline"));}
        for(String excluded:List.of("MAIL_ACCEPTANCE","FUNDS_SETUP","D30_CLOSE","ANNUAL_REFRESH","SHIFT_ACK_LOSS","ACTIVE_CANCEL","PRECLOSE_SETUP_DRAFT")){
            var env=flags();env.put("STRIPE_RECOVERY_"+excluded,"true");assertThrows(IllegalArgumentException.class,()->RecoverySandboxPortalHosted.requireScope(fixture(),env,"generic_decline"));
        }
        assertThrows(IllegalArgumentException.class,()->RecoverySandboxPortalHosted.requireScope(fixture(),flags(),"insufficient_funds"));
        for(String caseKey:List.of("pro_trial_to_yearly:pro_yearly:year_1:NO_SCHEDULE:RENEW","pro_trial_to_monthly:pro_monthly:month_1:EQUIVALENT_SCHEDULE:RENEW","pro_trial_to_monthly:pro_monthly:month_1:NO_SCHEDULE:PERIOD_END")){
            var original=fixture();var changed=RecoverySandboxFixture.parse(caseKey,"{\"currency\":\"usd\",\"unitAmountMinor\":7999,\"introAmountMinor\":299}");
            var f=new RecoverySandboxFixture(changed.caseKey(),changed.sourceKey(),changed.targetPlanCode(),changed.interval(),changed.intro(),changed.keepSchedule(),changed.periodEnd(),original.currency(),original.unitAmountMinor(),original.introAmountMinor(),null,null,"PRICE_UPDATE");
            assertThrows(IllegalArgumentException.class,()->RecoverySandboxPortalHosted.requireScope(f,flags(),"generic_decline"));
        }
    }
    @Test void configurationHasOnlyMethodUpdateAndNeverMutatesDefault(){
        var params=RecoverySandboxPortalHosted.configurationParameters("run-owned");
        assertEquals(Map.of("recovery_test_run","run-owned","purpose","portal_method_only"),params.get("metadata"));
        assertFalse(params.containsKey("active"));assertFalse(params.containsKey("is_default"));
        assertEquals(Map.of("enabled",false),params.get("login_page"));
        var features=(Map<?,?>)params.get("features");assertEquals(Set.of("payment_method_update","customer_update","invoice_history","subscription_cancel","subscription_update","subscription_pause"),features.keySet());
        for(var item:features.entrySet())assertEquals(Map.of("enabled",item.getKey().equals("payment_method_update")),item.getValue());
    }
    @Test void challengeIsPrivateAndRejectsWrongOriginsOrReplacement()throws Exception {
        var path=dir.resolve("challenge.json");var expiry=Instant.now().plusSeconds(900);
        try(var file=RecoverySandboxPortalHosted.writeChallenge(path,"run-owned",1L,"PORTAL_METHOD","https://billing.stripe.com/p/session/test_private","https://invoice.stripe.com/i/acct_owned/test_private",expiry)){
            assertEquals(PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(path));
            var json=JsonParser.parseString(Files.readString(path)).getAsJsonObject();assertEquals("PORTAL_METHOD",json.get("step").getAsString());assertEquals("TEST",json.get("stripeMode").getAsString());
            assertEquals("NOT_OBSERVED",json.get("portalUiEvidence").getAsString());assertEquals("https://billing.stripe.com/p/session/test_private",json.get("portalUrl").getAsString());
            assertFalse(json.has("hostedInvoiceUrl"),"Hosted URL is exposed only after real method sync");
            assertThrows(FileAlreadyExistsException.class,()->RecoverySandboxPortalHosted.writeChallenge(path,"run-owned",1L,"HOSTED_PAYMENT","https://billing.stripe.com/p/session/test_private","https://invoice.stripe.com/i/acct_owned/test_private",expiry));
        }
        assertFalse(Files.exists(path));
        for(String bad:List.of("http://billing.stripe.com/p/session/test","https://billing.stripe.com.attacker.example/x","https://user@billing.stripe.com/x","https://billing.stripe.com:444/x"))assertThrows(IllegalArgumentException.class,()->RecoverySandboxPortalHosted.writeChallenge(path,"run-owned",1L,"PORTAL_METHOD",bad,"https://invoice.stripe.com/i/test",expiry));
        assertFalse(Files.exists(path));
        try(var file=RecoverySandboxPortalHosted.writeChallenge(path,"run-owned",1L,"HOSTED_PAYMENT","https://billing.stripe.com/p/session/test","https://invoice.stripe.com/i/test",expiry)){
            assertTrue(JsonParser.parseString(Files.readString(path)).getAsJsonObject().has("hostedInvoiceUrl"));
        }
    }
}
