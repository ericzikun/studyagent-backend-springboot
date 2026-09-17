package com.studyagent.infra.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.studyagent.infra.entity.UserSubscriptionEntity;
import com.studyagent.infra.mapper.UserSubscriptionMapper;
import com.studyagent.service.domain.billing.BillingDomainService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Historical: after the Pro Trial yearly SKU ({@code pro_trial_to_yearly}) was retired from sale,
 * existing subscriptions on it still carry a Stripe Schedule that converts to {@code pro_yearly}
 * at trial end. This job re-points them at {@code pro_monthly}, matching the monthly SKU.
 *
 * <p>Only the local pending plan is rewritten here; the Schedule itself is rebuilt by
 * {@link BillingDomainService#fulfillIntroTrialSubscription}, which re-applies both phases
 * (current weekly trial price until period end, then the conversion plan's price).
 *
 * <p>Rows already pointing at the monthly conversion are not candidates, so the job is safe to
 * run repeatedly and can be disabled once it stops finding work. Deliberately not transactional:
 * each row is committed on its own so one failing subscription does not roll back the batch.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProTrialYearlyConversionRepairScheduler {
    private static final String YEARLY_TRIAL_PLAN_CODE = "pro_trial_to_yearly";
    private static final String MONTHLY_CONVERSION_PLAN_CODE = "pro_monthly";
    private static final String LEGACY_CONVERSION_PLAN_CODE = "pro_yearly";

    private final UserSubscriptionMapper subscriptionMapper;
    private final BillingDomainService billingDomainService;

    @Value("${billing.pro-trial-yearly-repair.enabled:false}")
    private boolean repairEnabled;

    @Value("${billing.pro-trial-yearly-repair.batch-size:100}")
    private int batchSize;

    @Scheduled(cron = "${billing.pro-trial-yearly-repair.cron:0 45 * * * ?}")
    public void repointYearlyTrialConversions() {
        if (!repairEnabled || batchSize <= 0) {
            return;
        }
        List<UserSubscriptionEntity> candidates = subscriptionMapper.selectList(
                new LambdaQueryWrapper<UserSubscriptionEntity>()
                        .eq(UserSubscriptionEntity::getPlanCode, YEARLY_TRIAL_PLAN_CODE)
                        .in(UserSubscriptionEntity::getStatus, List.of("active", "trialing"))
                        .isNotNull(UserSubscriptionEntity::getStripeSubscriptionId)
                        .and(pending -> pending
                                .isNull(UserSubscriptionEntity::getPendingPlanCode)
                                .or()
                                .eq(UserSubscriptionEntity::getPendingPlanCode,
                                        LEGACY_CONVERSION_PLAN_CODE))
                        .orderByAsc(UserSubscriptionEntity::getUpdatedAt)
                        .last("LIMIT " + batchSize));

        int repaired = 0;
        for (UserSubscriptionEntity candidate : candidates) {
            if (candidate == null || candidate.getId() == null) {
                continue;
            }
            try {
                if (repointOne(candidate)) {
                    repaired++;
                }
            } catch (RuntimeException e) {
                log.warn("[Billing/proTrialYearlyRepair] repoint failed for user={}: {}",
                        candidate.getClerkUserId(), e.getMessage());
            }
        }
        if (repaired > 0) {
            log.info("[Billing/proTrialYearlyRepair] repointed {} yearly Pro Trial subscriptions to {}",
                    repaired, MONTHLY_CONVERSION_PLAN_CODE);
        }
    }

    private boolean repointOne(UserSubscriptionEntity candidate) {
        int updated = subscriptionMapper.update(null, new LambdaUpdateWrapper<UserSubscriptionEntity>()
                .eq(UserSubscriptionEntity::getId, candidate.getId())
                .eq(UserSubscriptionEntity::getPlanCode, YEARLY_TRIAL_PLAN_CODE)
                .and(pending -> pending
                        .isNull(UserSubscriptionEntity::getPendingPlanCode)
                        .or()
                        .eq(UserSubscriptionEntity::getPendingPlanCode, LEGACY_CONVERSION_PLAN_CODE))
                .set(UserSubscriptionEntity::getPendingPlanCode, MONTHLY_CONVERSION_PLAN_CODE)
                .set(UserSubscriptionEntity::getUpdatedAt, LocalDateTime.now()));
        if (updated <= 0) {
            // Already re-pointed by someone else, or the subscription drifted out of scope.
            return false;
        }
        billingDomainService.fulfillIntroTrialSubscription(
                candidate.getClerkUserId(),
                candidate.getStripeCustomerId(),
                candidate.getStripeSubscriptionId());
        log.info("[Billing/proTrialYearlyRepair] repointed user={} to {}",
                candidate.getClerkUserId(), MONTHLY_CONVERSION_PLAN_CODE);
        return true;
    }
}
