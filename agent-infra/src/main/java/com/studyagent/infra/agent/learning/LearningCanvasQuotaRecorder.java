package com.studyagent.infra.agent.learning;

import com.studyagent.common.quota.FeatureCode;
import com.studyagent.infra.entity.QuotaLedgerEntity;
import com.studyagent.infra.mapper.QuotaLedgerMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Learning Canvas 纯免费记账器。
 * <p>
 * 规范要求：纯免费不拦截、不扣付费余额，但每次用户调用必须落现有流水（quota_ledger）。
 * QuotaDomainService.consume 要求 amount&gt;0 且会实际扣减，纯免费场景改为直接向
 * quota_ledger 插入一条 amount=0 的 consume 流水（只 INSERT，不改旧表/旧逻辑，不建自建计数表）。
 */
@Component
public class LearningCanvasQuotaRecorder {

    private static final Logger log = LoggerFactory.getLogger(LearningCanvasQuotaRecorder.class);

    private static final String LEDGER_TYPE_CONSUME = "consume";
    private static final String SOURCE_TYPE = "demo_learning_canvas";

    private final QuotaLedgerMapper quotaLedgerMapper;

    public LearningCanvasQuotaRecorder(QuotaLedgerMapper quotaLedgerMapper) {
        this.quotaLedgerMapper = quotaLedgerMapper;
    }

    /**
     * 记一笔纯免费使用流水（amount=0，不扣费）。
     *
     * @param clerkUserId 用户
     * @param themeId     关联业务 ID（demo_learning_theme.id）
     */
    public void recordFreeUsage(String clerkUserId, Long themeId) {
        try {
            QuotaLedgerEntity ledger = new QuotaLedgerEntity();
            ledger.setLedgerNo(generateLedgerNo());
            ledger.setClerkUserId(clerkUserId);
            ledger.setFeatureCode(FeatureCode.DEMO_LEARNING_CANVAS.getCode());
            ledger.setLedgerType(LEDGER_TYPE_CONSUME);
            ledger.setAmount(0L);
            ledger.setSourceType(SOURCE_TYPE);
            ledger.setSourceId(String.valueOf(themeId));
            ledger.setIdempotencyKey("demo_lc_" + themeId + "_" + UUID.randomUUID().toString().substring(0, 8));
            ledger.setFreeBalanceAfter(null);
            ledger.setPlanBalanceAfter(null);
            ledger.setAddonBalanceAfter(null);
            ledger.setPaidBalanceAfter(null);
            ledger.setBizContext("{\"mode\":\"free_trial\"}");
            ledger.setCreatedAt(LocalDateTime.now());
            quotaLedgerMapper.insert(ledger);
            log.info("[LearningCanvas] free usage recorded: userId={}, themeId={}, ledgerId={}",
                    clerkUserId, themeId, ledger.getId());
        } catch (Exception ex) {
            // 免费模式不阻断主流程，仅记录
            log.warn("[LearningCanvas] record free usage failed: userId={}, themeId={}, err={}",
                    clerkUserId, themeId, ex.getMessage());
        }
    }

    private String generateLedgerNo() {
        return "QL" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
    }
}
