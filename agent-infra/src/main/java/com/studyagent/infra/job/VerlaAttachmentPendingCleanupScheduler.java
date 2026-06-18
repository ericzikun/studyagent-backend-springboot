package com.studyagent.infra.job;

import com.studyagent.service.domain.verla.repo.VerlaAttachmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 清理 Python 直传链路中 sign 后未 finalize 的 agent 输出预登记行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VerlaAttachmentPendingCleanupScheduler {

    private static final String CLEANUP_REASON = "stale agent output upload was not finalized";

    private final VerlaAttachmentRepository attachmentRepository;

    @Value("${verla.attachment.pending-cleanup-enabled:false}")
    private boolean cleanupEnabled;

    @Value("${verla.attachment.pending-cleanup-retention-hours:24}")
    private int retentionHours;

    @Value("${verla.attachment.pending-cleanup-batch-size:200}")
    private int batchSize;

    @Scheduled(cron = "${verla.attachment.pending-cleanup-cron:0 20 * * * ?}")
    public void cleanupStalePendingAgentOutputs() {
        if (!cleanupEnabled || retentionHours <= 0 || batchSize <= 0) {
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusHours(retentionHours);
        int total = 0;
        while (true) {
            int updated = attachmentRepository.markStaleUploadedAgentOutputsFailed(
                    cutoff, batchSize, CLEANUP_REASON);
            total += updated;
            if (updated <= 0 || updated < batchSize) {
                break;
            }
        }
        if (total > 0) {
            log.info("[Verla/attachment/V2] marked {} stale pending agent output uploads failed", total);
        }
    }
}
