package com.studyagent.infra.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.studyagent.infra.entity.UserSubscriptionEntity;
import com.studyagent.infra.mapper.UserSubscriptionMapper;
import com.studyagent.service.domain.billing.BillingQuotaGateway;
import com.studyagent.service.domain.billing.IntroTrialPlans;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Historical: ends one-time {@code pro_trial_once} windows after {@code current_period_end}.
 * Subscription Pro Trial ({@code pro_trial_to_*}) is managed by Stripe Schedule — not this job.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProTrialExpiryScheduler {
    private final UserSubscriptionMapper subscriptionMapper;
    private final BillingQuotaGateway quotaGateway;

    @Value("${billing.pro-trial-expiry.batch-size:100}")
    private int batchSize;

    @Scheduled(cron = "${billing.pro-trial-expiry.cron:0 */10 * * * ?}")
    @Transactional(rollbackFor = Exception.class)
    public void expireOneTimeProTrials() {
        if (batchSize <= 0) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        List<UserSubscriptionEntity> expired = subscriptionMapper.selectList(
                new LambdaQueryWrapper<UserSubscriptionEntity>()
                        .eq(UserSubscriptionEntity::getPlanCode, IntroTrialPlans.PRO_TRIAL_ONCE_PLAN_CODE)
                        .in(UserSubscriptionEntity::getStatus, List.of("active", "trialing"))
                        .isNotNull(UserSubscriptionEntity::getCurrentPeriodEnd)
                        .le(UserSubscriptionEntity::getCurrentPeriodEnd, now)
                        .orderByAsc(UserSubscriptionEntity::getCurrentPeriodEnd)
                        .last("LIMIT " + batchSize));
        for (UserSubscriptionEntity candidate : expired) {
            UserSubscriptionEntity subscription = subscriptionMapper.selectByUserForUpdate(
                    candidate.getClerkUserId());
            if (subscription == null
                    || !IntroTrialPlans.isOneTimeProTrialPlanCode(subscription.getPlanCode())
                    || (!"active".equals(subscription.getStatus())
                            && !"trialing".equals(subscription.getStatus()))
                    || subscription.getCurrentPeriodEnd() == null
                    || subscription.getCurrentPeriodEnd().isAfter(now)) {
                continue;
            }
            String key = "pro-trial-expired:" + subscription.getClerkUserId()
                    + ":" + subscription.getCurrentPeriodEnd();
            quotaGateway.clearPlanQuota(
                    subscription.getClerkUserId(),
                    subscription.getStripeSubscriptionId(),
                    subscription.getPlanCode(),
                    key + ":plan");
            int updated = subscriptionMapper.update(null, new UpdateWrapper<UserSubscriptionEntity>()
                    .eq("id", subscription.getId())
                    .eq("plan_code", IntroTrialPlans.PRO_TRIAL_ONCE_PLAN_CODE)
                    .in("status", List.of("active", "trialing"))
                    .le("current_period_end", now)
                    .set("status", "canceled")
                    .set("subscription_phase", null)
                    .set("updated_at", now));
            if (updated > 0) {
                log.info("Expired one-time Pro Trial for user={}", subscription.getClerkUserId());
            }
        }
    }
}
