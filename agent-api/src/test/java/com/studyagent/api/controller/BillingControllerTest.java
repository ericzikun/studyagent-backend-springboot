package com.studyagent.api.controller;

import com.studyagent.common.api.ApiCode;
import com.studyagent.service.domain.billing.BillingDomainException;
import com.studyagent.service.domain.billing.BillingDomainService;
import com.studyagent.service.domain.billing.BillingPortalSessionResult;
import com.studyagent.service.domain.billing.BillingRecordResult;
import com.studyagent.service.domain.payment.PaymentDomainService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BillingControllerTest {
    private BillingDomainService billingDomainService;
    private PaymentDomainService paymentDomainService;
    private BillingController controller;

    @BeforeEach
    void setUp() {
        billingDomainService = mock(BillingDomainService.class);
        paymentDomainService = mock(PaymentDomainService.class);
        controller = new BillingController(billingDomainService, paymentDomainService);
    }

    @Test
    void portalSessionRequiresLogin() {
        BillingController.BillingPortalSessionRequest request =
                new BillingController.BillingPortalSessionRequest();
        request.setReturnUrl("http://localhost:3001/dashboard");

        var result = controller.portalSession(request, null);

        assertThat(result.getMeta().getStatusCode()).isEqualTo(ApiCode.USER_NOT_LOGGED_IN.getCode());
        verifyNoInteractions(billingDomainService);
    }

    @Test
    void recordsRequiresLogin() {
        var result = controller.records(null);

        assertThat(result.getMeta().getStatusCode()).isEqualTo(ApiCode.USER_NOT_LOGGED_IN.getCode());
        verifyNoInteractions(billingDomainService);
    }

    @Test
    void recordsReturnsLocalBillingRows() {
        when(billingDomainService.getBillingRecords("user_1"))
                .thenReturn(List.of(BillingRecordResult.builder()
                        .id("RO202606150001")
                        .paidAt(LocalDateTime.parse("2026-06-15T08:00:00"))
                        .amountCents(98214)
                        .currency("php")
                        .status("completed")
                        .orderType("subscription_initial")
                        .build()));

        var result = controller.records("user_1");

        assertThat(result.getMeta().getStatusCode()).isEqualTo(0);
        assertThat(result.getData()).hasSize(1);
        assertThat(result.getData().get(0).getId()).isEqualTo("RO202606150001");
        verify(billingDomainService).getBillingRecords("user_1");
        verifyNoInteractions(paymentDomainService);
    }

    @Test
    void portalSessionReturnsStripeUrl() {
        BillingController.BillingPortalSessionRequest request =
                new BillingController.BillingPortalSessionRequest();
        request.setReturnUrl("http://localhost:3001/dashboard");
        when(billingDomainService.createBillingPortalSession(
                "user_1",
                "http://localhost:3001/dashboard"))
                .thenReturn(BillingPortalSessionResult.builder()
                        .url("https://billing.stripe.com/p/session/test_123")
                        .build());

        var result = controller.portalSession(request, "user_1");

        assertThat(result.getMeta().getStatusCode()).isEqualTo(0);
        assertThat(result.getData()).containsEntry("url", "https://billing.stripe.com/p/session/test_123");
        verify(billingDomainService).createBillingPortalSession(
                "user_1",
                "http://localhost:3001/dashboard");
        verifyNoInteractions(paymentDomainService);
    }

    @Test
    void portalSessionMapsMissingCustomer() {
        BillingController.BillingPortalSessionRequest request =
                new BillingController.BillingPortalSessionRequest();
        request.setReturnUrl("http://localhost:3001/dashboard");
        when(billingDomainService.createBillingPortalSession(
                "user_2",
                "http://localhost:3001/dashboard"))
                .thenThrow(new BillingDomainException(
                        "STRIPE_CUSTOMER_NOT_FOUND",
                        "No Stripe billing customer found for user"));

        var result = controller.portalSession(request, "user_2");

        assertThat(result.getMeta().getStatusCode())
                .isEqualTo(ApiCode.BILLING_CUSTOMER_NOT_FOUND.getCode());
    }
}
