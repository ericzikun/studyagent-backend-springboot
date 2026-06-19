package com.studyagent.infra.job;

import com.studyagent.infra.entity.UserSubscriptionEntity;
import com.studyagent.infra.mapper.UserSubscriptionMapper;
import com.studyagent.service.domain.quota.PlanQuotaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class PlanQuotaRefreshScheduler {

    private final UserSubscriptionMapper userSubscriptionMapper;
    private final PlanQuotaService planQuotaService;

    @Value("${billing.plan-refresh.enabled:false}")
    private boolean refreshEnabled;

    @Value("${billing.plan-refresh.batch-size:200}")
    private int batchSize;

    @Scheduled(cron = "${billing.plan-refresh.cron:0 10 * * * ?}")
    public void refreshDuePlanQuotas() {
        if (!refreshEnabled || batchSize <= 0) {
            return;
        }

        List<UserSubscriptionEntity> subscriptions =
                userSubscriptionMapper.selectAnnualSubscriptionsDueForPlanRefresh(batchSize);
        for (UserSubscriptionEntity subscription : subscriptions) {
            if (subscription == null || subscription.getClerkUserId() == null || subscription.getClerkUserId().isBlank()) {
                continue;
            }
            planQuotaService.refreshAllPlanQuotasIfNeeded(subscription.getClerkUserId());
        }
        if (!subscriptions.isEmpty()) {
            log.info("[Billing/planRefresh] scanned {} subscriptions for quota refresh", subscriptions.size());
        }
    }
}
