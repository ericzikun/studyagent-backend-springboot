package com.studyagent.infra.service.billing;

import com.stripe.Stripe;
import com.stripe.exception.ApiConnectionException;
import com.stripe.model.Invoice;
import com.stripe.model.InvoiceLineItem;
import com.stripe.model.InvoiceLineItemCollection;
import com.stripe.model.Subscription;
import com.stripe.net.ApiMode;
import com.stripe.net.ApiResource;
import com.stripe.net.BaseAddress;
import com.stripe.net.StripeResponseGetter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SubscriptionRecoveryStripeGatewayPaginationTest {
    @Test void originalInvoiceReadsBothSdkPagesAndRetainsOldPriceAndMixedLines() throws Exception {
        assertEquals("24.0.0", Stripe.VERSION);
        assertEquals("2023-10-16", Stripe.API_VERSION);
        var fixture = new InvoicePages(false);
        try (var invoices = mockStatic(Invoice.class); var subscriptions = mockStatic(Subscription.class)) {
            invoices.when(() -> Invoice.retrieve("in_original")).thenReturn(fixture.invoice);
            var currentSubscription = ApiResource.GSON.fromJson("""
                    {"id":"sub_original","items":{"object":"list","has_more":false,"data":[
                      {"id":"si_base","price":{"id":"price_current","currency":"usd","unit_amount":99000}}]}}
                    """, Subscription.class);
            subscriptions.when(() -> Subscription.retrieve("sub_original")).thenReturn(currentSubscription);

            var snapshot = new SubscriptionRecoveryStripeGateway().readInvoice("in_original");

            assertEquals(List.of("il_old_base", "il_addon", "il_mixed_base"),
                    snapshot.lines().stream().map(InvoiceLineItem::getId).toList());
            assertEquals(List.of("price_old", "price_addon", "price_other"),
                    snapshot.lines().stream().map(line -> line.getPrice().getId()).toList());
            assertEquals(List.of("subscription", "invoiceitem", "subscription"),
                    snapshot.lines().stream().map(InvoiceLineItem::getType).toList());
            assertEquals("si_base", snapshot.lines().get(0).getSubscriptionItem());
            assertEquals("si_other", snapshot.lines().get(2).getSubscriptionItem());
            assertEquals(2L, snapshot.lines().get(1).getQuantity());
            assertEquals("price_current", snapshot.subscription().getItems().getData().get(0).getPrice().getId());
            assertEquals(99000L,snapshot.subscription().getItems().getData().get(0).getPrice().getUnitAmount());
            assertEquals("usd",snapshot.invoice().getCurrency());
            assertEquals(13000L,snapshot.invoice().getSubtotal());
            assertEquals(11800L,snapshot.invoice().getTotal());
            assertEquals(-200L,snapshot.invoice().getStartingBalance());
            assertEquals(11600L,snapshot.invoice().getAmountDue());
            assertEquals(100L,snapshot.invoice().getAmountPaid());
            assertEquals(11500L,snapshot.invoice().getAmountRemaining());
            var original=snapshot.lines().get(0);
            assertEquals(10000L,original.getPrice().getUnitAmount());
            assertEquals(10000L,original.getAmount());
            assertEquals("usd",original.getCurrency());
            assertEquals(1794362400L,original.getPeriod().getStart());
            assertEquals(1796954400L,original.getPeriod().getEnd());
            assertEquals(2000L,original.getDiscountAmounts().get(0).getAmount());
            assertEquals("di_original",original.getDiscountAmounts().get(0).getDiscount());
            assertEquals(800L,original.getTaxAmounts().get(0).getAmount());
            assertFalse(original.getTaxAmounts().get(0).getInclusive());
            assertEquals("txr_original",original.getTaxAmounts().get(0).getTaxRate());
            assertEquals(1000L,snapshot.lines().get(1).getAmount());
            assertEquals(2000L,snapshot.lines().get(2).getAmount());
            assertEquals(2000L,snapshot.invoice().getTotalDiscountAmounts().get(0).getAmount());
            assertEquals(800L,snapshot.invoice().getTotalTaxAmounts().get(0).getAmount());
            assertSame(fixture.invoice, snapshot.invoice());
            fixture.assertBothPagesRequested();
        }
    }

    @Test void secondSdkPageFailureThrowsInsteadOfReturningAPartialSnapshot() throws Exception {
        var fixture = new InvoicePages(true);
        try (var invoices = mockStatic(Invoice.class); var subscriptions = mockStatic(Subscription.class)) {
            invoices.when(() -> Invoice.retrieve("in_original")).thenReturn(fixture.invoice);

            // The real SDK iterator wraps a lazy page-fetch failure in RuntimeException.
            var failure = assertThrows(RuntimeException.class,
                    () -> new SubscriptionRecoveryStripeGateway().readInvoice("in_original"));

            assertSame(fixture.secondPageFailure, failure.getCause());
            fixture.assertBothPagesRequested();
            subscriptions.verifyNoInteractions();
        }
    }

    /** Only the request boundary is mocked; collections and autoPagingIterable are real SDK code. */
    private static final class InvoicePages {
        private static final String PATH = "/v1/invoices/in_original/lines";
        final Invoice invoice;
        final ApiConnectionException secondPageFailure = new ApiConnectionException("second page unavailable");
        final List<Map<String, Object>> requests = new ArrayList<>();

        InvoicePages(boolean failSecondPage) throws Exception {
            invoice = ApiResource.GSON.fromJson("""
                    {"id":"in_original","object":"invoice","subscription":"sub_original",
                     "status":"open","billing_reason":"subscription_cycle","currency":"usd",
                     "subtotal":13000,"total":11800,"starting_balance":-200,
                     "amount_due":11600,"amount_paid":100,"amount_remaining":11500,
                     "total_discount_amounts":[{"amount":2000,"discount":"di_original"}],
                     "total_tax_amounts":[{"amount":800,"inclusive":false,"tax_rate":"txr_original"}],"lines":
                    """ + pageJson(true, oldBaseLine()) + "}", Invoice.class);
            var firstPage = ApiResource.GSON.fromJson(pageJson(true, oldBaseLine()), InvoiceLineItemCollection.class);
            var secondPage = ApiResource.GSON.fromJson(pageJson(false, """
                    {"id":"il_addon","object":"line_item","type":"invoiceitem","quantity":2,"amount":1000,"currency":"usd",
                     "price":{"id":"price_addon","unit_amount":500,"currency":"usd"}},
                    {"id":"il_mixed_base","object":"line_item","type":"subscription","quantity":1,"amount":2000,"currency":"usd",
                     "subscription_item":"si_other","price":{"id":"price_other","unit_amount":2000,"currency":"usd"}}
                    """), InvoiceLineItemCollection.class);
            var getter = mock(StripeResponseGetter.class);
            when(getter.request(eq(BaseAddress.API), eq(ApiResource.RequestMethod.GET), eq(PATH),
                    anyMap(), any(), isNull(), eq(ApiMode.V1))).thenAnswer(invocation -> {
                Map<String, Object> params = invocation.getArgument(3);
                requests.add(Map.copyOf(params));
                if (requests.size() == 1) {
                    assertEquals(Map.of("limit", 100L), params);
                    return firstPage;
                }
                assertEquals(2, requests.size(), "No unexpected third page request");
                assertEquals(Map.of("limit", 100L, "starting_after", "il_old_base"), params);
                if (failSecondPage) throw secondPageFailure;
                return secondPage;
            });
            // Set the same per-object request boundary a deserialized SDK response receives,
            // without changing Stripe's global getter or using any real HTTP transport.
            invoice.getLines().setResponseGetter(getter);
            firstPage.setResponseGetter(getter);
            firstPage.setRequestParams(Map.of("limit", 100L));
            secondPage.setResponseGetter(getter);
        }

        void assertBothPagesRequested() {
            assertEquals(List.of(Map.of("limit", 100L),
                    Map.of("limit", 100L, "starting_after", "il_old_base")), requests);
        }

        private static String pageJson(boolean more, String data) {
            return "{\"object\":\"list\",\"url\":\"" + PATH + "\",\"has_more\":" + more + ",\"data\":[" + data + "]}";
        }

        private static String oldBaseLine() {
            return """
                    {"id":"il_old_base","object":"line_item","type":"subscription","quantity":1,"amount":10000,"currency":"usd",
                     "subscription_item":"si_base","price":{"id":"price_old","unit_amount":10000,"currency":"usd",
                       "recurring":{"interval":"month","interval_count":1}},
                     "period":{"start":1794362400,"end":1796954400},
                     "discount_amounts":[{"amount":2000,"discount":"di_original"}],
                     "tax_amounts":[{"amount":800,"inclusive":false,"tax_rate":"txr_original"}]}
                    """;
        }
    }
}
