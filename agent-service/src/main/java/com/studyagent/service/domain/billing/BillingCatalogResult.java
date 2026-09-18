package com.studyagent.service.domain.billing;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class BillingCatalogResult {
    private List<BillingPlan> plans;
    private List<BillingAddon> addons;
    /** 题库通行证一次性购买项；未配置或未启用时为 null。 */
    private BillingStudyPass studyPass;
}
