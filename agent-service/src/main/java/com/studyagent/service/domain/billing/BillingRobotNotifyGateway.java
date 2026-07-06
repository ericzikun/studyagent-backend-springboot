package com.studyagent.service.domain.billing;

/**
 * V2 Stripe 商业化 webhook 与飞书机器人通知之间的集成契约。
 * <p>
 * 实现位于 agent-api；infra 层 webhook 处理通过 {@code ObjectProvider} 可选注入，
 * 未部署 Notify 时不影响 Stripe 入账主链路。
 */
public interface BillingRobotNotifyGateway {

    void notifyCheckoutSucceeded(BillingCheckoutNotifyRequest request);

    void notifyCheckoutExpired(BillingCheckoutNotifyRequest request);

    void notifyPaymentFailed(BillingPaymentFailedNotifyRequest request);
}
