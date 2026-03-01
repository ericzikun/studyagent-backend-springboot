package com.studyagent.service.domain.payment;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 支付会话状态查询结果
 */
@Data
@Builder
public class SessionStatusResult {
    private String sessionId;
    private String status;
    private String paymentStatus;
    private Long amountTotal;
    private String currency;
    private String customerEmail;
    private Long createdAt;
    private String clerkUserId;
}
