package com.studyagent.service.domain.billing;

import lombok.Builder;
import lombok.Data;

/**
 * Stripe Customer Portal 跳转会话。
 *
 * 后端只向前端暴露短期 URL；完整账单历史、付款方式和订阅管理由 Stripe 官方页面承载。
 */
@Data
@Builder
public class BillingPortalSessionResult {
    private String url;
}
