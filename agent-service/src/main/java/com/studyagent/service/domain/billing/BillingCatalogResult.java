package com.studyagent.service.domain.billing;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class BillingCatalogResult {
    private List<BillingPlan> plans;
    private List<BillingAddon> addons;
}
