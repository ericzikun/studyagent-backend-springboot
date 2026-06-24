package com.studyagent.api.controller;

import com.studyagent.api.common.Result;
import com.studyagent.common.api.ApiCode;
import com.studyagent.service.domain.billing.BillingDomainException;
import com.studyagent.service.domain.billing.BillingDomainService;
import com.studyagent.service.domain.billing.SubscriptionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubscriptionControllerTest {

    @Mock
    private BillingDomainService billingDomainService;

    private SubscriptionController controller;

    @BeforeEach
    void setUp() {
        controller = new SubscriptionController(billingDomainService);
    }

    @Test
    void upgrade_shouldReturnDeprecatedErrorWithoutCallingBillingService() {
        SubscriptionController.ChangePlanRequest request = new SubscriptionController.ChangePlanRequest();
        request.setPlanCode("plus_yearly");

        Result<SubscriptionResult> result = controller.upgrade(request, "user_1");

        assertThat(result.getMeta().getStatusCode()).isEqualTo(400);
        assertThat(result.getMeta().getStatusMsg()).contains("deprecated");
        verifyNoInteractions(billingDomainService);
    }

    @Test
    void change_shouldStillDelegateToBillingDomainService() {
        SubscriptionController.ChangePlanRequest request = new SubscriptionController.ChangePlanRequest();
        request.setPlanCode("plus_yearly");
        SubscriptionResult subscriptionResult = SubscriptionResult.builder().planCode("plus_yearly").build();
        when(billingDomainService.changeSubscription("user_1", "plus_yearly")).thenReturn(subscriptionResult);

        Result<SubscriptionResult> result = controller.change(request, "user_1");

        assertThat(result.getMeta().getStatusCode()).isEqualTo(0);
        assertThat(result.getData()).isSameAs(subscriptionResult);
        verify(billingDomainService).changeSubscription("user_1", "plus_yearly");
    }

    @Test
    void change_shouldSurfaceCheckoutMessageForImmediateUpgradeRequests() {
        SubscriptionController.ChangePlanRequest request = new SubscriptionController.ChangePlanRequest();
        request.setPlanCode("plus_yearly");
        when(billingDomainService.changeSubscription("user_1", "plus_yearly"))
                .thenThrow(new BillingDomainException(
                        "UPGRADE_REQUIRES_CHECKOUT",
                        "Immediate upgrades must use /v1/payment/subscription-checkout"));

        Result<SubscriptionResult> result = controller.change(request, "user_1");

        assertThat(result.getMeta().getStatusCode())
                .isEqualTo(ApiCode.SUBSCRIPTION_UPGRADE_REQUIRES_CHECKOUT.getCode());
        assertThat(result.getMeta().getStatusMsg()).contains("/v1/payment/subscription-checkout");
    }
}
