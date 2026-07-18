package com.studyagent.api.controller;

import com.studyagent.common.analytics.AnalyticsService;
import com.studyagent.common.api.ApiCode;
import com.studyagent.service.domain.billing.BillingDomainException;
import com.studyagent.service.domain.billing.BillingDomainService;
import com.studyagent.service.domain.payment.PaymentDomainService;
import com.studyagent.service.domain.payment.SessionStatusResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PaymentControllerTest {
    private PaymentDomainService paymentDomainService;
    private BillingDomainService billingDomainService;
    private PaymentController controller;

    @BeforeEach
    void setUp() {
        paymentDomainService = mock(PaymentDomainService.class);
        billingDomainService = mock(BillingDomainService.class);
        controller = new PaymentController(
                paymentDomainService,
                billingDomainService,
                mock(AnalyticsService.class));
    }

    @Test
    void legacyCheckoutRejectsBodyUserThatDiffersFromAuthenticatedUser() {
        PaymentController.CreateCheckoutSessionRequest request =
                new PaymentController.CreateCheckoutSessionRequest();
        request.setClerkUserId("user_b");
        request.setCustomerEmail("user-b@example.test");
        request.setPackageType("basic");

        var result = controller.createCheckoutSession(request, "user_a", null);

        assertThat(result.getMeta().getStatusCode()).isEqualTo(ApiCode.NO_PERMISSION.getCode());
        verifyNoInteractions(paymentDomainService);
    }

    @Test
    void sessionStatusRequiresLogin() {
        var result = controller.getSessionStatus("cs_test_123", null);

        assertThat(result.getMeta().getStatusCode()).isEqualTo(ApiCode.USER_NOT_LOGGED_IN.getCode());
        verifyNoInteractions(paymentDomainService);
    }

    @Test
    void sessionStatusRejectsSessionOwnedByAnotherUser() {
        when(paymentDomainService.getSessionStatus("cs_test_user_b"))
                .thenReturn(SessionStatusResult.builder()
                        .sessionId("cs_test_user_b")
                        .status("complete")
                        .paymentStatus("paid")
                        .customerEmail("user-b@example.test")
                        .clerkUserId("user_b")
                        .build());

        var result = controller.getSessionStatus("cs_test_user_b", "user_a");

        assertThat(result.getMeta().getStatusCode()).isEqualTo(ApiCode.NO_PERMISSION.getCode());
        assertThat(result.getData()).isNull();
    }

    @Test
    void subscriptionChangePendingUsesDedicatedBusinessCode() {
        PaymentController.SubscriptionCheckoutRequest request =
                new PaymentController.SubscriptionCheckoutRequest();
        request.setPlanCode("pro_monthly");
        when(billingDomainService.createSubscriptionCheckout(
                "user_a", null, "pro_monthly", null, null, null))
                .thenThrow(new BillingDomainException(
                        "SUBSCRIPTION_CHANGE_PENDING",
                        "The previous upgrade payment is still being applied"));

        var result = controller.createSubscriptionCheckout(request, "user_a", null);

        assertThat(result.getMeta().getStatusCode())
                .isEqualTo(ApiCode.SUBSCRIPTION_CHANGE_PENDING.getCode());
    }
}
