package com.studyagent.infra.service.billing;

import com.studyagent.service.domain.quota.AddonGrantService;
import com.studyagent.service.domain.quota.PlanQuotaService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BillingQuotaGatewayImplTest {
    @Mock
    private PlanQuotaService planQuotaService;
    @Mock
    private AddonGrantService addonGrantService;

    @Test
    void delegatesPlanResetToPlanQuotaService() {
        Instant start = Instant.parse("2026-06-15T00:00:00Z");
        Instant end = Instant.parse("2026-07-15T00:00:00Z");

        BillingQuotaGatewayImpl gateway = new BillingQuotaGatewayImpl(planQuotaService, addonGrantService);
        gateway.resetFromPaidInvoice("user_1", "sub_1", "basic_monthly", start, end, "in_1");

        verify(planQuotaService).resetFromPaidInvoice("user_1", "sub_1", "basic_monthly", start, end, "in_1");
    }

    @Test
    void delegatesAddonGrantToAddonGrantService() {
        Instant paidAt = Instant.parse("2026-06-15T08:30:00Z");

        BillingQuotaGatewayImpl gateway = new BillingQuotaGatewayImpl(planQuotaService, addonGrantService);
        gateway.grantAddonFromCheckout("user_1", "addon_assignment_3", "cs_1", "pi_1", paidAt);

        verify(addonGrantService).grantFromPaidCheckout("user_1", "addon_assignment_3", "cs_1", "pi_1", paidAt);
    }
}
