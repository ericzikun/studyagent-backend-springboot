package com.studyagent.service.domain.payment;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 支付配置查询结果
 */
@Data
@Builder
public class PaymentConfigResult {
    private String stripePublishableKey;
    private List<Map<String, Object>> packages;
}
