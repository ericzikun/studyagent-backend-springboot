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
    void addonRecoveryBlockPreservesTyped1031Data() {
        var request = new PaymentController.AddonCheckoutRequest(); request.setAddonCode("addon_1");
        var data = com.studyagent.common.exception.CommercialBlockData.builder().reasonCode("payment_recovery_required")
                .paymentRecoveryId("91").blockedAction("addon_purchase").build();
        when(billingDomainService.createAddonCheckout("user_a", null, "addon_1", null, null, null))
                .thenThrow(com.studyagent.common.exception.BusinessException.withData(ApiCode.PAYMENT_RECOVERY_REQUIRED, data));
        var result = controller.createAddonCheckout(request, "user_a", null);
        assertThat(result.getMeta().getStatusCode()).isEqualTo(1031);
        assertThat((Object) result.getData()).isSameAs(data);
    }

    @Test
    void replacementCheckoutRequiresPairedIntentAndRoutesDedicatedCommand() {
        var request = new PaymentController.SubscriptionCheckoutRequest(); request.setPlanCode("pro_monthly");
        request.setRecoveryId("91");
        assertThat(controller.createSubscriptionCheckout(request, "user_a", null).getMeta().getStatusCode()).isEqualTo(ApiCode.PARAM_ERROR.getCode());
        verifyNoInteractions(billingDomainService);
        request.setPurchaseIntent("recovery_replacement");
        when(billingDomainService.createRecoveryReplacementCheckout(org.mockito.ArgumentMatchers.eq("user_a"), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(com.studyagent.service.domain.payment.CheckoutSessionResult.builder().orderNo("order_91").sessionId("cs_91").build());
        assertThat(controller.createSubscriptionCheckout(request, "user_a", null).getData()).containsEntry("orderNo", "order_91");
        org.mockito.Mockito.verify(billingDomainService, org.mockito.Mockito.never()).createSubscriptionCheckout(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void replacementStatusReturnsTrustedRecoveryContextAfterBrowserStorageExpires() {
        when(paymentDomainService.getSessionStatus("cs_91")).thenReturn(SessionStatusResult.builder()
                .sessionId("cs_91").clerkUserId("user_a").purchaseType("subscription_replacement")
                .recoveryId("91").orderNo("order_91").targetPlanCode("pro_monthly").build());
        assertThat(controller.getSessionStatus("cs_91", "user_a").getData()).containsEntry("recoveryId", "91")
                .containsEntry("orderNo", "order_91").containsEntry("targetPlanCode", "pro_monthly");
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
}
