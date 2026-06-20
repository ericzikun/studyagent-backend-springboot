package com.studyagent.infra.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.studyagent.infra.entity.RechargeOrderEntity;
import com.studyagent.infra.entity.UserSubscriptionEntity;
import com.studyagent.infra.mapper.RechargeOrderMapper;
import com.studyagent.infra.mapper.UserSubscriptionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class PendingSubscriptionUpgradeCleanupScheduler {

    private static final String CLEANUP_REASON = "upgrade_checkout_timeout";

    private final RechargeOrderMapper rechargeOrderMapper;
    private final UserSubscriptionMapper userSubscriptionMapper;

    @Value("${billing.upgrade-pending-cleanup.enabled:false}")
    private boolean cleanupEnabled;

    @Value("${billing.upgrade-pending-cleanup.retention-hours:24}")
    private int retentionHours;

    @Value("${billing.upgrade-pending-cleanup.batch-size:100}")
    private int batchSize;

    @Scheduled(cron = "${billing.upgrade-pending-cleanup.cron:0 25 * * * ?}")
    public void cleanupStalePendingUpgrades() {
        if (!cleanupEnabled || retentionHours <= 0 || batchSize <= 0) {
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusHours(retentionHours);
        int total = 0;
        while (true) {
            List<RechargeOrderEntity> staleOrders = rechargeOrderMapper.selectList(
                    new LambdaQueryWrapper<RechargeOrderEntity>()
                            .eq(RechargeOrderEntity::getOrderType, "subscription_upgrade")
                            .eq(RechargeOrderEntity::getStatus, "pending")
                            .lt(RechargeOrderEntity::getCreatedAt, cutoff)
                            .orderByAsc(RechargeOrderEntity::getCreatedAt)
                            .last("LIMIT " + batchSize));
            if (staleOrders == null || staleOrders.isEmpty()) {
                break;
            }

            int cleanedThisBatch = 0;
            for (RechargeOrderEntity order : staleOrders) {
                if (order == null || order.getId() == null) {
                    continue;
                }
                int updated = rechargeOrderMapper.update(null, new LambdaUpdateWrapper<RechargeOrderEntity>()
                        .eq(RechargeOrderEntity::getId, order.getId())
                        .eq(RechargeOrderEntity::getStatus, "pending")
                        .set(RechargeOrderEntity::getStatus, "expired")
                        .set(RechargeOrderEntity::getFailureReason, CLEANUP_REASON)
                        .set(RechargeOrderEntity::getUpdatedAt, LocalDateTime.now()));
                if (updated <= 0) {
                    continue;
                }
                cleanedThisBatch += updated;
                clearPendingUpgradeFlagIfSafe(order);
            }

            total += cleanedThisBatch;
            if (cleanedThisBatch <= 0 || staleOrders.size() < batchSize) {
                break;
            }
        }
        if (total > 0) {
            log.info("[Billing/upgradeCleanup] expired {} stale pending upgrade orders", total);
        }
    }

    private void clearPendingUpgradeFlagIfSafe(RechargeOrderEntity order) {
        if (order == null || !hasText(order.getClerkUserId()) || !hasText(order.getPlanCode())) {
            return;
        }
        Long pendingCount = rechargeOrderMapper.selectCount(new LambdaQueryWrapper<RechargeOrderEntity>()
                .eq(RechargeOrderEntity::getClerkUserId, order.getClerkUserId())
                .eq(RechargeOrderEntity::getOrderType, "subscription_upgrade")
                .eq(RechargeOrderEntity::getStatus, "pending"));
        if (pendingCount != null && pendingCount > 0) {
            return;
        }
        userSubscriptionMapper.update(null, new LambdaUpdateWrapper<UserSubscriptionEntity>()
                .eq(UserSubscriptionEntity::getClerkUserId, order.getClerkUserId())
                .eq(UserSubscriptionEntity::getPendingPlanCode, order.getPlanCode())
                .isNull(UserSubscriptionEntity::getPendingEffectiveAt)
                .set(UserSubscriptionEntity::getPendingPlanCode, null)
                .set(UserSubscriptionEntity::getPendingEffectiveAt, null)
                .set(UserSubscriptionEntity::getUpdatedAt, LocalDateTime.now()));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
