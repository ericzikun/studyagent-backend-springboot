package com.studyagent.service.domain.payment;

import lombok.Builder;
import lombok.Data;

/**
 * 支付会话创建结果
 */
@Data
@Builder
public class CheckoutSessionResult {
    private String sessionId;
    private String checkoutUrl;
    private Long expiresAt;
    private String resumeToken;
}
