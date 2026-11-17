package com.studyagent.infra.service.billing;

import java.util.Map;

/** Test-only replacement acceptance guard; no provider or database calls. */
final class RecoverySandboxReplacement {
    static void requireDoubleScope(RecoverySandboxFixture fixture, Map<String, String> env, String failure) {
        requireScope(fixture, env, failure);
        if (!"true".equals(env.get("STRIPE_RECOVERY_REPLACEMENT_DOUBLE")))
            throw new IllegalArgumentException("REPLACEMENT_DOUBLE_SCOPE_REQUIRED");
    }
    static RecoverySandboxMailApproval.ChallengeFile writeOldHostedChallenge(java.nio.file.Path path,
            String run, long rootId, String hostedInvoiceUrl, java.time.Instant expiresAt) throws Exception {
        return writePaymentChallenge(path, run, rootId, "OLD_HOSTED_PAYMENT", "hostedInvoiceUrl",
                "invoice.stripe.com", hostedInvoiceUrl, expiresAt);
    }
    static void requireScope(RecoverySandboxFixture fixture, Map<String, String> env, String failure) {
        if (!"true".equals(env.get("STRIPE_RECOVERY_REPLACEMENT"))
                || !"true".equals(env.get("STRIPE_RECOVERY_APPLICATION_IT"))
                || !"true".equals(env.get("STRIPE_RECOVERY_ATOMIC_TRIAL_END"))
                || !fixture.caseKey().equals("pro_trial_to_monthly:pro_monthly:month_1:NO_SCHEDULE:RENEW")
                || !fixture.priceUpdate() || !"usd".equals(fixture.currency())
                || fixture.unitAmountMinor() != 7999 || fixture.introAmountMinor() != 299
                || !"generic_decline".equals(failure)) throw new IllegalArgumentException("REPLACEMENT_SCOPE_REQUIRED");
        for (String flag : java.util.List.of("PORTAL_HOSTED", "MAIL_ACCEPTANCE", "FUNDS_SETUP", "D30_CLOSE",
                "ANNUAL_REFRESH", "SHIFT_ACK_LOSS", "ACTIVE_CANCEL", "PRECLOSE_SETUP_DRAFT"))
            if ("true".equals(env.get("STRIPE_RECOVERY_" + flag)))
                throw new IllegalArgumentException("REPLACEMENT_SCENARIO_CONFLICT");
    }
    static RecoverySandboxMailApproval.ChallengeFile writeChallenge(java.nio.file.Path path, String run,
            long rootId, String checkoutUrl, java.time.Instant expiresAt) throws Exception {
        return writePaymentChallenge(path, run, rootId, "CHECKOUT_PAYMENT", "checkoutUrl",
                "checkout.stripe.com", checkoutUrl, expiresAt);
    }
    private static RecoverySandboxMailApproval.ChallengeFile writePaymentChallenge(java.nio.file.Path path,
            String run, long rootId, String step, String urlField, String host, String checkoutUrl,
            java.time.Instant expiresAt) throws Exception {
        var now=java.time.Instant.now();
        if(run==null||run.isBlank()||rootId<=0||expiresAt==null||!expiresAt.isAfter(now)||expiresAt.isAfter(now.plusSeconds(900)))
            throw new IllegalArgumentException("REPLACEMENT_CHALLENGE_SCOPE_REQUIRED");
        var uri=java.net.URI.create(checkoutUrl);
        if(!"https".equals(uri.getScheme())||!host.equals(uri.getHost())||uri.getUserInfo()!=null||(uri.getPort()!=-1&&uri.getPort()!=443))
            throw new IllegalArgumentException("REPLACEMENT_CHECKOUT_ORIGIN_REQUIRED");
        var values=new java.util.LinkedHashMap<String,Object>();values.put("runId",run);values.put("rootId",rootId);values.put("step",step);values.put("stripeMode","TEST");
        values.put(urlField,checkoutUrl);values.put("expiresAt",expiresAt.toString());values.put("paymentUiEvidence","NOT_OBSERVED");
        var permissions=java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        try(var channel=java.nio.file.Files.newByteChannel(path,java.util.Set.of(java.nio.file.StandardOpenOption.CREATE_NEW,java.nio.file.StandardOpenOption.WRITE,java.nio.file.LinkOption.NOFOLLOW_LINKS),permissions)){
            var bytes=java.nio.ByteBuffer.wrap(new com.google.gson.Gson().toJson(values).getBytes(java.nio.charset.StandardCharsets.UTF_8));while(bytes.hasRemaining())channel.write(bytes);
        }
        return new RecoverySandboxMailApproval.ChallengeFile(path,java.nio.file.Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes.class,java.nio.file.LinkOption.NOFOLLOW_LINKS).fileKey());
    }
}
