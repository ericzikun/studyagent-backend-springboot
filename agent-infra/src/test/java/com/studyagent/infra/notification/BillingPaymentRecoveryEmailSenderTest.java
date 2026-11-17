package com.studyagent.infra.notification;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.infra.entity.SubscriptionPaymentRecoveryEntity;
import com.studyagent.infra.service.billing.PaymentRecoveryProperties;
import com.studyagent.service.domain.user.ClerkClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.reactive.function.client.*;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BillingPaymentRecoveryEmailSenderTest {
    private static final LocalDateTime MAIL_DEADLINE = LocalDateTime.of(2099, 1, 31, 23, 59, 45);
    private static final String RECOVERY_TARGET = "https://app.example.test/?payment_recovery_id=1";

    @ParameterizedTest(name = "{0} minor={1} is rendered as {2}")
    @CsvSource({
            "usd, 2900, 29.00 USD",
            "usd, 1, 0.01 USD",
            "eur, 12345, 123.45 EUR",
            "jpy, 2900, 2900 JPY",
            "jpy, 1, 1 JPY",
            "krw, 2490, 2490 KRW",
            "kwd, 1234, 1.234 KWD",
            "kwd, 1, 0.001 KWD"
    })
    void prepareFreezesNeutralCopyWithInvoiceMinorAmountCurrencyAndDeadline(
            String currency, long remainingMinor, String displayedAmount) throws Exception {
        var row = manual();
        row.setCurrency(currency);
        row.setAmountRemainingMinor(remainingMinor);
        row.setAmountDueMinor(999999L);
        row.setDeadlineAt(MAIL_DEADLINE);
        var sender = prepareOnlySender("billing@example.test", "own@example.test");

        JsonNode frozen = new ObjectMapper().readTree(sender.prepare(row, RECOVERY_TARGET));

        assertEquals(4, frozen.size());
        assertEquals("billing@example.test", frozen.path("from").asText());
        assertTrue(frozen.path("to").isArray());
        assertEquals(1, frozen.path("to").size());
        assertEquals("own@example.test", frozen.path("to").get(0).asText());
        assertEquals("Subscription payment pending", frozen.path("subject").asText());
        assertEquals("Your subscription payment is pending (" + displayedAmount
                + "). Check your bank's notice, complete verification, or update your payment method in your account.\n"
                + "Payment window ends: 2099-01-31T23:59:45+08:00.\n"
                + RECOVERY_TARGET, frozen.path("text").asText());
    }

    @Test
    void prepareKeepsOnlyTheApplicationRecoveryTargetWithoutLeakingStripePaymentLinks() throws Exception {
        var row = manual();
        String hostedInvoice = "https://invoice.stripe.com/i/acct_test/opaque_payable_invoice";
        String portalSession = "https://billing.stripe.com/p/session/opaque_portal_session";
        String checkoutSession = "https://checkout.stripe.com/c/pay/opaque_checkout_session";
        row.setFailureEvidence("{\"hosted_invoice_url\":\"" + hostedInvoice
                + "\",\"portal_url\":\"" + portalSession + "\"}");
        row.setContractSnapshot("{\"checkout_url\":\"" + checkoutSession + "\"}");

        String prepared = prepareOnlySender("billing@example.test", "own@example.test")
                .prepare(row, RECOVERY_TARGET);
        String text = new ObjectMapper().readTree(prepared).path("text").asText();
        var links = text.lines().filter(line -> line.startsWith("https://")).toList();

        assertEquals(java.util.List.of(RECOVERY_TARGET), links);
        URI target = URI.create(links.get(0));
        assertEquals("app.example.test", target.getHost());
        assertEquals("/", target.getPath());
        assertEquals("payment_recovery_id=" + row.getId(), target.getQuery());
        assertFalse(prepared.contains(hostedInvoice));
        assertFalse(prepared.contains(portalSession));
        assertFalse(prepared.contains(checkoutSession));
        assertFalse(prepared.contains("stripe.com"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void prepareRejectsMissingSenderBeforeFreezingMail(String from) {
        var sender = prepareOnlySender(from, "own@example.test");

        var error = assertThrows(IllegalStateException.class, () -> sender.prepare(manual(), RECOVERY_TARGET));

        assertEquals("Recovery mail sender or recipient unavailable", error.getMessage());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void prepareRejectsMissingRecipientBeforeFreezingMail(String recipient) {
        var sender = prepareOnlySender("billing@example.test", recipient);

        var error = assertThrows(IllegalStateException.class, () -> sender.prepare(manual(), RECOVERY_TARGET));

        assertEquals("Recovery mail sender or recipient unavailable", error.getMessage());
    }

    private BillingPaymentRecoveryEmailSender prepareOnlySender(String from, String recipient) {
        var props = new PaymentRecoveryProperties();
        props.setEmailFrom(from);
        var clerk = mock(ClerkClient.class);
        when(clerk.getUserEmail("user")).thenReturn(recipient);
        var web = WebClient.builder().exchangeFunction(request -> {
            throw new AssertionError("prepare must never dispatch provider HTTP");
        }).build();
        return new BillingPaymentRecoveryEmailSender(clerk, web, props);
    }

    @Test void insufficientFundsNeverQualifiesIncludingManualAdviceAndD30(){
        var r=manual();r.setLatestDeclineCode("insufficient_funds");assertFalse(BillingPaymentRecoveryEmailSender.eligible(r));
        for(String state:new String[]{"OPEN_AUTO","CLOSING","EXPIRED","PAID"}){r.setStatus(state);assertFalse(BillingPaymentRecoveryEmailSender.eligible(r));}
    }
    @Test void onlyCurrentUnpaidManualWindowQualifies(){var r=manual();assertTrue(BillingPaymentRecoveryEmailSender.eligible(r));r.setAmountPaidMinor(1L);assertFalse(BillingPaymentRecoveryEmailSender.eligible(r));}
    @Test void providerMessageIdAndFrozenIdempotencyHeaderAreReturned(){
        var request=new AtomicReference<ClientRequest>();
        WebClient web=WebClient.builder().exchangeFunction(req->{request.set(req);return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type","application/json").body("{\"id\":\"email_1\"}").build());}).build();
        var props=new PaymentRecoveryProperties();props.setEmailFrom("billing@example.test");var clerk=mock(ClerkClient.class);when(clerk.getUserEmail("user")).thenReturn("own@example.test");
        var sender=new BillingPaymentRecoveryEmailSender(clerk,web,props);org.springframework.test.util.ReflectionTestUtils.setField(sender,"apiKey","test-only");
        String frozen=sender.prepare(manual(),"https://example.test/?payment_recovery_id=1");
        var result=sender.send(frozen,"recovery:in_1:manual-email");assertEquals("SUCCEEDED",result.state());assertEquals("email_1",result.messageId());
        assertEquals("recovery:in_1:manual-email",request.get().headers().getFirst("Idempotency-Key"));
    }
    @Test void connectionTimeoutRemainsUnknown(){
        var web=WebClient.builder().exchangeFunction(req->Mono.error(new RuntimeException("timeout"))).build();
        var sender=new BillingPaymentRecoveryEmailSender(mock(ClerkClient.class),web,new PaymentRecoveryProperties());org.springframework.test.util.ReflectionTestUtils.setField(sender,"apiKey","test-only");
        assertEquals("UNKNOWN",sender.send("{\"to\":\"own@example.test\"}","saved-key").state());
    }
    private SubscriptionPaymentRecoveryEntity manual(){var r=new SubscriptionPaymentRecoveryEntity();r.setId(1L);r.setClerkUserId("user");r.setStatus("OPEN_MANUAL");r.setLatestDeclineCode("do_not_honor");r.setAmountPaidMinor(0L);r.setAmountRemainingMinor(2900L);r.setCurrency("usd");r.setDeadlineAt(com.studyagent.common.datetime.DateTimeFormats.now().plusDays(1));return r;}
}
