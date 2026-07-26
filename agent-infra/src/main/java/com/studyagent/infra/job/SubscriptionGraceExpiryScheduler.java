package com.studyagent.infra.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.studyagent.infra.entity.UserSubscriptionEntity;
import com.studyagent.infra.mapper.UserSubscriptionMapper;
import com.studyagent.service.domain.billing.BillingQuotaGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
public class SubscriptionGraceExpiryScheduler {
    private final UserSubscriptionMapper subscriptionMapper;
    private final BillingQuotaGateway quotaGateway;

    @Value("${billing.grace-expiry.batch-size:100}")
    private int batchSize;

    @Scheduled(cron = "${billing.grace-expiry.cron:0 */10 * * * ?}")
    @Transactional(rollbackFor = Exception.class)
    public void suspendExpiredGraceSubscriptions() {
        if (batchSize <= 0) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        List<UserSubscriptionEntity> expired = subscriptionMapper.selectList(
                new LambdaQueryWrapper<UserSubscriptionEntity>()
                        .eq(UserSubscriptionEntity::getStatus, "past_due")
                        .isNotNull(UserSubscriptionEntity::getGraceEndAt)
                        .le(UserSubscriptionEntity::getGraceEndAt, now)
                        .orderByAsc(UserSubscriptionEntity::getGraceEndAt)
                        .last("LIMIT " + batchSize));
        for (UserSubscriptionEntity candidate : expired) {
            UserSubscriptionEntity subscription = subscriptionMapper.selectByUserForUpdate(
                    candidate.getClerkUserId());
            if (subscription == null
                    || !"past_due".equals(subscription.getStatus())
                    || subscription.getGraceEndAt() == null
                    || subscription.getGraceEndAt().isAfter(now)) {
                continue;
            }
            String key = "grace-expired:" + subscription.getStripeSubscriptionId()
                    + ":" + subscription.getGraceEndAt();
            quotaGateway.clearPlanQuota(
                    subscription.getClerkUserId(),
                    subscription.getStripeSubscriptionId(),
                    subscription.getPlanCode(),
                    key + ":plan");
            quotaGateway.pauseAddons(
                    subscription.getClerkUserId(),
                    subscription.getStripeSubscriptionId(),
                    key + ":addons");
            subscriptionMapper.update(null, new UpdateWrapper<UserSubscriptionEntity>()
                    .eq("id", subscription.getId())
                    .eq("status", "past_due")
                    .le("grace_end_at", now)
                    .set("status", "unpaid")
                    .set("updated_at", now));
        }
    }
}
