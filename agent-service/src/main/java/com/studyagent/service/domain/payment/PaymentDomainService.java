package com.studyagent.service.domain.payment;

/**
 * 支付领域服务
 * 负责 Stripe Checkout Session 创建、查询及支付配置
 */
public interface PaymentDomainService {

    /**
     * 创建 Stripe Checkout 支付会话
     *
     * @param command 创建命令
     * @return 会话结果
     * @throws PaymentDomainException 配置错误、套餐无效等
     */
    CheckoutSessionResult createCheckoutSession(CreateCheckoutSessionCommand command);

    /**
     * 查询支付会话状态
     *
     * @param sessionId Stripe Checkout Session ID
     * @return 会话状态
     * @throws PaymentDomainException Stripe API 异常时包装抛出
     */
    SessionStatusResult getSessionStatus(String sessionId);

    /**
     * 获取支付配置（Stripe 公钥、套餐列表）
     */
    PaymentConfigResult getPaymentConfig();
}
