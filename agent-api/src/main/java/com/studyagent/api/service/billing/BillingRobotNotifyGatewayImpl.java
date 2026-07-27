package com.studyagent.api.service.billing;

import com.studyagent.api.service.robot.RobotNotifyBillingService;
import com.studyagent.service.domain.billing.BillingCheckoutNotifyRequest;
import com.studyagent.service.domain.billing.BillingPaymentFailedNotifyRequest;
import com.studyagent.service.domain.billing.BillingReviewNotifyRequest;
import com.studyagent.service.domain.billing.BillingRobotNotifyGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class BillingRobotNotifyGatewayImpl implements BillingRobotNotifyGateway {

    private final RobotNotifyBillingService robotNotifyBillingService;

    @Override
    public void notifyCheckoutSucceeded(BillingCheckoutNotifyRequest request) {
        robotNotifyBillingService.notifyCheckoutSucceeded(request);
    }

    @Override
    public void notifyCheckoutExpired(BillingCheckoutNotifyRequest request) {
        robotNotifyBillingService.notifyCheckoutExpired(request);
    }

    @Override
    public void notifyPaymentFailed(BillingPaymentFailedNotifyRequest request) {
        robotNotifyBillingService.notifyPaymentFailed(request);
    }

    @Override
    public void notifyBillingReviewRequired(BillingReviewNotifyRequest request) {
        robotNotifyBillingService.notifyBillingReviewRequired(request);
    }
}
