package com.studyagent.infra.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.studyagent.infra.entity.RechargeOrderEntity;
import com.studyagent.infra.mapper.RechargeOrderMapper;
import com.studyagent.infra.service.billing.StripeBillingWebhookService;
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
public class ManualSubscriptionUpgradeRetryScheduler {

    private final RechargeOrderMapper rechargeOrderMapper;
    private final StripeBillingWebhookService stripeBillingWebhookService;

    @Value("${billing.manual-upgrade-switch-retry.enabled:true}")
    private boolean retryEnabled;

    @Value("${billing.manual-upgrade-switch-retry.after-minutes:10}")
    private int retryAfterMinutes;

    @Value("${billing.manual-upgrade-switch-retry.batch-size:100}")
    private int batchSize;

    @Scheduled(cron = "${billing.manual-upgrade-switch-retry.cron:0 */10 * * * ?}")
    public void retryFailedManualUpgradeSwitches() {
        if (!retryEnabled || retryAfterMinutes <= 0 || batchSize <= 0) {
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(retryAfterMinutes);
        int total = 0;
        while (true) {
            List<RechargeOrderEntity> retryableOrders = rechargeOrderMapper.selectList(
                    new LambdaQueryWrapper<RechargeOrderEntity>()
                            .eq(RechargeOrderEntity::getOrderType, "subscription_upgrade_manual")
                            .eq(RechargeOrderEntity::getStatus, "switch_failed")
                            .lt(RechargeOrderEntity::getUpdatedAt, cutoff)
                            .orderByAsc(RechargeOrderEntity::getUpdatedAt)
                            .last("LIMIT " + batchSize));
            if (retryableOrders == null || retryableOrders.isEmpty()) {
                break;
            }

            int processedThisBatch = 0;
            for (RechargeOrderEntity order : retryableOrders) {
                if (order == null || order.getOrderNo() == null || order.getOrderNo().isBlank()) {
                    continue;
                }
                try {
                    stripeBillingWebhookService.retryManualUpgradeSwitch(order.getOrderNo());
                    processedThisBatch++;
                } catch (RuntimeException e) {
                    log.warn("[Billing/manualUpgradeRetry] retry failed for orderNo={}: {}",
                            order.getOrderNo(), e.getMessage());
                }
            }

            total += processedThisBatch;
            if (retryableOrders.size() < batchSize) {
                break;
            }
        }
        if (total > 0) {
            log.info("[Billing/manualUpgradeRetry] retried {} manual upgrade switch orders", total);
        }
    }
}
