package com.studyagent.service.domain.payment;

import lombok.Builder;
import lombok.Data;

/**
 * 创建支付会话命令
 */
@Data
@Builder
public class CreateCheckoutSessionCommand {
    private String clerkUserId;
    private String customerEmail;
    private String packageType;
    private String successUrl;
    private String cancelUrl;
}
