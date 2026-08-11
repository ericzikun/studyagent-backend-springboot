package com.studyagent.api.controller;

import com.studyagent.common.analytics.AnalyticsService;
import com.studyagent.common.api.ApiCode;
import com.studyagent.infra.service.billing.BillingBusinessMetrics;
import com.studyagent.service.domain.billing.BillingDomainException;
import com.studyagent.service.domain.billing.BillingDomainService;
import com.studyagent.service.domain.payment.CheckoutSessionResult;
import com.studyagent.service.domain.payment.PaymentDomainService;
import com.studyagent.service.domain.payment.SessionStatusResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PaymentControllerTest {
    private PaymentDomainService paymentDomainService;
    private BillingDomainService billingDomainService;
    private SimpleMeterRegistry meterRegistry;
    private PaymentController controller;

    @BeforeEach
    void setUp() {
        paymentDomainService = mock(PaymentDomainService.class);
        billingDomainService = mock(BillingDomainService.class);
        meterRegistry = new SimpleMeterRegistry();
        controller = new PaymentController(
                paymentDomainService,
                billingDomainService,
                mock(AnalyticsService.class),
                new BillingBusinessMetrics(meterRegistry));
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

    @Test
    void paymentResolutionRequiredUsesDedicatedBusinessCode() {
        PaymentController.SubscriptionCheckoutRequest request =
                new PaymentController.SubscriptionCheckoutRequest();
        request.setPlanCode("plus_monthly");
        when(billingDomainService.createSubscriptionCheckout(
                "user_a", null, "plus_monthly", null, null, null))
                .thenThrow(new BillingDomainException(
                        "PAYMENT_RESOLUTION_REQUIRED",
                        "Resolve the existing subscription payment before changing plans"));

        var result = controller.createSubscriptionCheckout(request, "user_a", null);

        assertThat(result.getMeta().getStatusCode())
                .isEqualTo(ApiCode.PAYMENT_RESOLUTION_REQUIRED.getCode());
    }

    @Test
    void subscriptionUpgradeCheckoutRecordsSuccessfulUpgradeMetric() {
        PaymentController.SubscriptionCheckoutRequest request =
                new PaymentController.SubscriptionCheckoutRequest();
        request.setPlanCode("plus_monthly");
        when(billingDomainService.createSubscriptionCheckout(
                "user_a", null, "plus_monthly", null, null, null))
                .thenReturn(CheckoutSessionResult.builder()
                        .checkoutKind("session")
                        .sessionId("cs_upgrade")
                        .targetPlanCode("plus_monthly")
                        .build());

        controller.createSubscriptionCheckout(request, "user_a", null);

        assertThat(meterRegistry.get("billing.checkout")
                .tags(
                        "purchase_type", "subscription_upgrade",
                        "result", "success",
                        "error_type", "none")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void invalidAddonRecordsCatalogCheckoutError() {
        PaymentController.AddonCheckoutRequest request =
                new PaymentController.AddonCheckoutRequest();
        request.setAddonCode("missing_addon");
        when(billingDomainService.createAddonCheckout(
                "user_a", null, "missing_addon", null, null, null))
                .thenThrow(new BillingDomainException("INVALID_ADDON", "unknown addon"));

        controller.createAddonCheckout(request, "user_a", null);

        assertThat(meterRegistry.get("billing.checkout")
                .tags(
                        "purchase_type", "addon",
                        "result", "error",
                        "error_type", "catalog")
                .counter().count()).isEqualTo(1.0);
    }
}
