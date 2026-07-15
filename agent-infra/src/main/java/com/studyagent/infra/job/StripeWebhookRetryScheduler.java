package com.studyagent.infra.job;

import com.studyagent.infra.service.billing.StripeBillingWebhookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class StripeWebhookRetryScheduler {
    private final StripeBillingWebhookService webhookService;

    @Value("${billing.webhook-retry.enabled:true}")
    private boolean enabled;

    @Value("${billing.webhook-retry.batch-size:100}")
    private int batchSize;

    @Scheduled(cron = "${billing.webhook-retry.cron:0 */2 * * * ?}")
    public void retryFailedEvents() {
        if (!enabled || batchSize <= 0) {
            return;
        }
        int retried = webhookService.retryDueEvents(batchSize);
        if (retried > 0) {
            log.info("Retried {} failed Stripe webhook events", retried);
        }
    }
}
