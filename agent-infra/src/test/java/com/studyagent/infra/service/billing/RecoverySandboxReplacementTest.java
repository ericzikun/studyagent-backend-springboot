package com.studyagent.infra.service.billing;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecoverySandboxReplacementTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir;
    @Test void doublePaymentRequiresItsOwnExplicitFlagAndEveryReplacementGuard() {
        var fixture = RecoverySandboxFixture.parse("pro_trial_to_monthly:pro_monthly:month_1:NO_SCHEDULE:RENEW",
                "{\"currency\":\"usd\",\"unitAmountMinor\":7999,\"introAmountMinor\":299,\"conversionMode\":\"PRICE_UPDATE\"}");
        var allowed = Map.of("STRIPE_RECOVERY_APPLICATION_IT", "true", "STRIPE_RECOVERY_ATOMIC_TRIAL_END", "true",
                "STRIPE_RECOVERY_REPLACEMENT", "true", "STRIPE_RECOVERY_REPLACEMENT_DOUBLE", "true");
        assertDoesNotThrow(() -> RecoverySandboxReplacement.requireDoubleScope(fixture, allowed, "generic_decline"));
        for (String required : allowed.keySet()) {
            var missing = new HashMap<>(allowed); missing.remove(required);
            assertThrows(IllegalArgumentException.class, () -> RecoverySandboxReplacement.requireDoubleScope(fixture, missing, "generic_decline"));
        }
        assertThrows(IllegalArgumentException.class, () -> RecoverySandboxReplacement.requireDoubleScope(fixture, allowed, "insufficient_funds"));
        var conflict = new HashMap<>(allowed); conflict.put("STRIPE_RECOVERY_PORTAL_HOSTED", "true");
        assertThrows(IllegalArgumentException.class, () -> RecoverySandboxReplacement.requireDoubleScope(fixture, conflict, "generic_decline"));
    }
    @Test void oldHostedChallengeIsAnIndependentPrivateStageWithStrictOriginAndInodeCleanup() throws Exception {
        var path = dir.resolve("double.json"); var expiry = java.time.Instant.now().plusSeconds(900);
        try (var file = RecoverySandboxReplacement.writeOldHostedChallenge(path, "owned", 42L,
                "https://invoice.stripe.com/i/owned", expiry)) {
            var json = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(path)).getAsJsonObject();
            assertEquals("OLD_HOSTED_PAYMENT", json.get("step").getAsString());
            assertEquals("https://invoice.stripe.com/i/owned", json.get("hostedInvoiceUrl").getAsString());
            assertFalse(json.has("checkoutUrl")); assertEquals("TEST", json.get("stripeMode").getAsString());
            assertEquals(expiry.toString(), json.get("expiresAt").getAsString()); assertEquals(42L, json.get("rootId").getAsLong());
            assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"), java.nio.file.Files.getPosixFilePermissions(path));
            assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> RecoverySandboxReplacement.writeOldHostedChallenge(path, "owned", 42L, "https://invoice.stripe.com/i/owned", expiry));
            var held = dir.resolve("held"); java.nio.file.Files.move(path, held); java.nio.file.Files.writeString(path, "replacement inode");
        }
        assertEquals("replacement inode", java.nio.file.Files.readString(path)); java.nio.file.Files.delete(path);
        for (String bad : List.of("http://invoice.stripe.com/x", "https://invoice.stripe.com.evil/x", "https://user@invoice.stripe.com/x", "https://invoice.stripe.com:444/x", "https://checkout.stripe.com/x"))
            assertThrows(IllegalArgumentException.class, () -> RecoverySandboxReplacement.writeOldHostedChallenge(path, "owned", 42L, bad, expiry));
        assertThrows(IllegalArgumentException.class, () -> RecoverySandboxReplacement.writeOldHostedChallenge(path, "owned", 42L, "https://invoice.stripe.com/x", java.time.Instant.now().plusSeconds(901)));
        assertFalse(java.nio.file.Files.exists(path));
    }
    @Test void checkoutChallengeIsPrivateBoundedAndAllowsOnlyTheStripeCheckoutOrigin() throws Exception {
        var path = dir.resolve("checkout.json");
        var expiry = java.time.Instant.now().plusSeconds(900);
        try (var file = RecoverySandboxReplacement.writeChallenge(path, "run-owned", 42L,
                "https://checkout.stripe.com/c/pay/cs_test_owned", expiry)) {
            assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
                    java.nio.file.Files.getPosixFilePermissions(path));
            var value = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(path)).getAsJsonObject();
            assertEquals("CHECKOUT_PAYMENT", value.get("step").getAsString());
            assertEquals("TEST", value.get("stripeMode").getAsString());
            assertEquals("run-owned", value.get("runId").getAsString());
            assertEquals(42L, value.get("rootId").getAsLong());
            assertEquals(expiry.toString(), value.get("expiresAt").getAsString());
            assertEquals("https://checkout.stripe.com/c/pay/cs_test_owned", value.get("checkoutUrl").getAsString());
            assertThrows(java.nio.file.FileAlreadyExistsException.class, () ->
                    RecoverySandboxReplacement.writeChallenge(path, "run-owned", 42L,
                            "https://checkout.stripe.com/c/pay/cs_test_other", expiry));
        }
        assertFalse(java.nio.file.Files.exists(path));
        for (String bad : List.of("http://checkout.stripe.com/x", "https://checkout.stripe.com.attacker.example/x",
                "https://user@checkout.stripe.com/x", "https://checkout.stripe.com:444/x"))
            assertThrows(IllegalArgumentException.class, () ->
                    RecoverySandboxReplacement.writeChallenge(path, "run-owned", 42L, bad, expiry));
        for (var bad : List.of(java.time.Instant.now().minusSeconds(1), java.time.Instant.now().plusSeconds(901)))
            assertThrows(IllegalArgumentException.class, () -> RecoverySandboxReplacement.writeChallenge(path,
                    "run-owned", 42L, "https://checkout.stripe.com/c/pay/cs_test_owned", bad));
        assertFalse(java.nio.file.Files.exists(path));
    }
    @Test void replacementScopeRequiresOwnFlagAndRejectsPortalHostedOrOtherScenarioFlags() {
        var fixture = RecoverySandboxFixture.parse(
                "pro_trial_to_monthly:pro_monthly:month_1:NO_SCHEDULE:RENEW",
                "{\"currency\":\"usd\",\"unitAmountMinor\":7999,\"introAmountMinor\":299,\"conversionMode\":\"PRICE_UPDATE\"}");
        var allowed = Map.of("STRIPE_RECOVERY_APPLICATION_IT", "true",
                "STRIPE_RECOVERY_ATOMIC_TRIAL_END", "true", "STRIPE_RECOVERY_REPLACEMENT", "true");
        assertDoesNotThrow(() -> RecoverySandboxReplacement.requireScope(fixture, allowed, "generic_decline"));

        var missingFlag = new HashMap<>(allowed);
        missingFlag.remove("STRIPE_RECOVERY_REPLACEMENT");
        assertThrows(IllegalArgumentException.class,
                () -> RecoverySandboxReplacement.requireScope(fixture, missingFlag, "generic_decline"));
        for (String conflict : List.of("PORTAL_HOSTED", "MAIL_ACCEPTANCE", "FUNDS_SETUP", "D30_CLOSE",
                "ANNUAL_REFRESH", "SHIFT_ACK_LOSS", "ACTIVE_CANCEL", "PRECLOSE_SETUP_DRAFT")) {
            var conflicting = new HashMap<>(allowed);
            conflicting.put("STRIPE_RECOVERY_" + conflict, "true");
            assertThrows(IllegalArgumentException.class,
                    () -> RecoverySandboxReplacement.requireScope(fixture, conflicting, "generic_decline"), conflict);
        }
    }
}
