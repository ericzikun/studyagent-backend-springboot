package com.studyagent.service.domain.payment;

import lombok.Builder;
import lombok.Data;

/**
 * 支付会话创建结果
 */
@Data
@Builder
public class CheckoutSessionResult {
    private String checkoutKind;
    private String sessionId;
    private String referenceId;
    private String checkoutUrl;
    private Long expiresAt;
    private String resumeToken;
    private Integer quotedAmountCents;
    private String upgradeChargeType;
    private String targetPlanCode;
}
