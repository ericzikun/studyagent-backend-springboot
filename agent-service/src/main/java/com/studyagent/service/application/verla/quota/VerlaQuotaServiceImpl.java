package com.studyagent.service.application.verla.quota;

import com.studyagent.common.exception.InsufficientQuotaData;
import com.studyagent.common.exception.InsufficientQuotaException;
import com.studyagent.common.quota.FeatureCode;
import com.studyagent.service.domain.quota.ConsumeResult;
import com.studyagent.service.domain.quota.QuotaBalance;
import com.studyagent.service.domain.quota.QuotaDomainService;
import com.studyagent.service.domain.quota.QuotaVipAccessService;
import com.studyagent.service.domain.user.User;
import com.studyagent.service.domain.user.UserRepository;
import com.studyagent.service.domain.verla.VerlaSession;
import com.studyagent.service.domain.verla.repo.VerlaSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * V2 verla 链路 商业化额度门面实现。
 * <p>
 * 设计要点：
 * <ul>
 *   <li><b>复用 1.0</b>：直接调 {@code QuotaDomainService}，无新增表。</li>
 *   <li><b>同事务</b>：扣费方法标 {@code @Transactional(MANDATORY)}，强制调用方持有事务，
 *       保证「扣费 + verla_sessions 绑定 + outbox 写入」三件事原子。</li>
 *   <li><b>幂等绑定</b>：{@code verla_sessions.bindQuotaLedger} 用 {@code WHERE quota_ledger_id IS NULL}
 *       做乐观保护，并发派发只有一方成功，另一方抛 {@link IllegalStateException} 触发整事务回滚。</li>
 *   <li><b>退款幂等</b>：退款入口先按 sessionId 取 ledgerId；底层 refund 已补全幂等校验
 *       （见 {@code QuotaDomainServiceImpl.refund} 的 {@code original_ledger_id} 去重）。</li>
 *   <li><b>豁免</b>：admin / DB Quota VIP / env 白名单 / 总开关关闭。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VerlaQuotaServiceImpl implements VerlaQuotaService {

    private static final String SOURCE_TYPE_VERLA_SESSION = "verla_session";

    private final QuotaDomainService quotaDomainService;
    private final UserRepository userRepository;
    private final VerlaSessionRepository sessionRepository;
    private final VerlaQuotaWordCounter wordCounter;
    private final QuotaVipAccessService quotaVipAccessService;
    private final QuotaBusinessMetrics quotaBusinessMetrics;

    /** 总开关：false 时全部豁免，方便灰度上线 / 紧急关停。 */
    @Value("${verla.quota.enabled:true}")
    private boolean quotaEnabled;

    /**
     * 紧急兜底白名单（env）。日常开通请写 {@code quota_vip_users} 表，无需重启。
     * 与 1.0 {@code humanizer.whitelist-user-ids} 独立。
     */
    @Value("${verla.quota.whitelist-user-ids:}")
    private List<String> whitelistUserIds;

    // ===================================================================
    //  豁免判定
    // ===================================================================

    @Override
    public boolean isQuotaExempt(String clerkUserId) {
        if (!quotaEnabled) {
            return true;
        }
        if (clerkUserId == null || clerkUserId.isBlank()) {
            return false;
        }
        boolean isAdmin = userRepository.findByClerkUserId(clerkUserId)
                .map(User::getIsAdmin)
                .orElse(false);
        if (Boolean.TRUE.equals(isAdmin)) {
            return true;
        }
        if (quotaVipAccessService.isQuotaVip(clerkUserId)) {
            return true;
        }
        return whitelistUserIds != null && whitelistUserIds.contains(clerkUserId);
    }

    @Override
    public void assertSufficientForAssignmentRun(String clerkUserId) {
        if (isQuotaExempt(clerkUserId)) {
            return;
        }
        if (!quotaDomainService.canConsume(clerkUserId, FeatureCode.TASK_CREATE.getCode(), 1)) {
            QuotaBalance balance = quotaDomainService.getUserQuota(clerkUserId, FeatureCode.TASK_CREATE.getCode());
            throw new InsufficientQuotaException(
                    "Insufficient quota for assignment clarify, required=1, available="
                            + balance.totalAvailable(),
                    InsufficientQuotaData.builder()
                            .clerkUserId(clerkUserId)
                            .featureCode(balance.featureCode())
                            .featureName(balance.featureName())
                            .purchaseProductId("assignment")
                            .blockedAction("assignment_generate")
                            .quotaUnit(balance.quotaUnit())
                            .freeBalance(balance.freeBalance())
                            .freePeriodTotal(balance.freePeriodTotal())
                            .paidBalance(balance.paidBalance())
                            .totalAvailable(balance.totalAvailable())
                            .totalWords(1)
                            .build());
        }
    }

    // ===================================================================
    //  扣费
    // ===================================================================

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public VerlaQuotaConsumeResult consumeForAssignmentRun(VerlaQuotaContext ctx) {
        Map<String, Object> biz = baseBizContext(ctx);
        return consumeInternal(ctx, FeatureCode.TASK_CREATE, 1L, biz);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public VerlaQuotaConsumeResult consumeForDetection(VerlaQuotaContext ctx, String text) {
        Map<String, Object> biz = baseBizContext(ctx);
        long words = resolveChargeableWords(text);
        biz.put("charged_mode", "per_words");
        biz.put("word_count", words);
        return consumeInternal(ctx, FeatureCode.AI_DETECTION, words, biz);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public VerlaQuotaConsumeResult consumeForHumanizer(VerlaQuotaContext ctx, String text) {
        Map<String, Object> biz = baseBizContext(ctx);
        long words = resolveChargeableWords(text);
        biz.put("charged_mode", "per_words");
        biz.put("word_count", words);
        return consumeInternal(ctx, FeatureCode.HUMANIZER, words, biz);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void inheritAssignmentQuotaLedger(Long targetSessionId, Long turnId) {
        if (targetSessionId == null || turnId == null) {
            return;
        }
        VerlaSession target = sessionRepository.findById(targetSessionId);
        if (target == null || target.getQuotaLedgerId() != null) {
            return;
        }
        findChargedSessionForTurn(turnId).ifPresent(charged -> {
            boolean bound = sessionRepository.bindQuotaLedger(
                    targetSessionId, charged.getQuotaLedgerId(), charged.getQuotaAmount());
            if (bound) {
                log.info("[VerlaQuota] inherited ledger: targetSessionId={}, turnId={}, ledgerId={}",
                        targetSessionId, turnId, charged.getQuotaLedgerId());
            }
        });
    }

    private VerlaQuotaConsumeResult consumeInternal(
            VerlaQuotaContext ctx,
            FeatureCode feature,
            long amount,
            Map<String, Object> bizContext) {

        if (ctx == null || ctx.sessionId() == null) {
            throw new IllegalArgumentException("VerlaQuotaContext.sessionId is required");
        }

        // 0) 同 turn 已扣费 → 仅绑定既有 ledger，避免 finalize 重试双扣
        if (ctx.turnId() != null && feature == FeatureCode.TASK_CREATE) {
            Optional<VerlaSession> charged = findChargedSessionForTurn(ctx.turnId());
            if (charged.isPresent()) {
                VerlaSession existing = charged.get();
                boolean bound = sessionRepository.bindQuotaLedger(
                        ctx.sessionId(), existing.getQuotaLedgerId(), existing.getQuotaAmount());
                if (!bound) {
                    VerlaSession current = sessionRepository.findById(ctx.sessionId());
                    Long alreadyBound = current == null ? null : current.getQuotaLedgerId();
                    if (alreadyBound == null) {
                        throw new IllegalStateException(
                                "Failed to bind existing assignment quota ledger: sessionId="
                                        + ctx.sessionId() + ", turnId=" + ctx.turnId());
                    }
                }
                log.info("[VerlaQuota] reused turn ledger: sessionId={}, turnId={}, ledgerId={}",
                        ctx.sessionId(), ctx.turnId(), existing.getQuotaLedgerId());
                return VerlaQuotaConsumeResult.of(existing.getQuotaLedgerId(), existing.getQuotaAmount());
            }
        }

        // 1) admin / Quota VIP / env 白名单 / 配额关闭 → 直接放行
        if (isQuotaExempt(ctx.clerkUserId())) {
            log.info("[VerlaQuota] exempt: feature={}, userId={}, sessionId={}",
                    feature.getCode(), ctx.clerkUserId(), ctx.sessionId());
            return VerlaQuotaConsumeResult.exempted();
        }

        try {
            // 2) 余额校验失败 → 抛 InsufficientQuotaException，由 GlobalExceptionHandler 包装
            if (!quotaDomainService.canConsume(ctx.clerkUserId(), feature.getCode(), amount)) {
                QuotaBalance balance = quotaDomainService.getUserQuota(ctx.clerkUserId(), feature.getCode());
                quotaBusinessMetrics.recordConsume(feature.getCode(), QuotaBusinessMetrics.Result.INSUFFICIENT);
                throw new InsufficientQuotaException(
                        "Insufficient quota for " + feature.getCode()
                                + ", required=" + amount
                                + ", available=" + balance.totalAvailable(),
                        InsufficientQuotaData.builder()
                                .clerkUserId(ctx.clerkUserId())
                                .featureCode(balance.featureCode())
                                .featureName(balance.featureName())
                                .purchaseProductId(purchaseProductId(feature))
                                .blockedAction(blockedAction(feature))
                                .quotaUnit(balance.quotaUnit())
                                .freeBalance(balance.freeBalance())
                                .freePeriodTotal(balance.freePeriodTotal())
                                .paidBalance(balance.paidBalance())
                                .totalAvailable(balance.totalAvailable())
                                .totalWords((int) Math.min(Integer.MAX_VALUE, amount))
                                .build());
            }

            ConsumeResult cr = quotaDomainService.consume(
                    ctx.clerkUserId(), feature.getCode(), amount, SOURCE_TYPE_VERLA_SESSION,
                    String.valueOf(ctx.sessionId()), bizContext, buildConsumeIdempotencyKey(ctx, feature));
            boolean bound = sessionRepository.bindQuotaLedger(ctx.sessionId(), cr.ledgerId(), amount);
            if (!bound) {
                VerlaSession s = sessionRepository.findById(ctx.sessionId());
                Long alreadyBound = s == null ? null : s.getQuotaLedgerId();
                log.error("[VerlaQuota] duplicate consume detected, rolling back: sessionId={}, newLedgerId={}, existingLedgerId={}",
                        ctx.sessionId(), cr.ledgerId(), alreadyBound);
                throw new IllegalStateException(
                        "Concurrent verla session billing detected: sessionId=" + ctx.sessionId()
                                + ", existingLedgerId=" + alreadyBound);
            }

            quotaBusinessMetrics.recordConsume(feature.getCode(), QuotaBusinessMetrics.Result.SUCCESS);
            log.info("[VerlaQuota] consumed: feature={}, userId={}, sessionId={}, amount={}, ledgerId={}",
                    feature.getCode(), ctx.clerkUserId(), ctx.sessionId(), amount, cr.ledgerId());
            return VerlaQuotaConsumeResult.of(cr.ledgerId(), amount);
        } catch (InsufficientQuotaException insufficient) {
            throw insufficient;
        } catch (RuntimeException error) {
            quotaBusinessMetrics.recordConsume(feature.getCode(), QuotaBusinessMetrics.Result.ERROR);
            throw error;
        }
    }

    private long resolveChargeableWords(String text) {
        long words = wordCounter.countWords(text);
        return Math.max(words, 1L);
    }

    private String buildConsumeIdempotencyKey(VerlaQuotaContext ctx, FeatureCode feature) {
        if (ctx == null || feature == null) {
            return null;
        }
        if (feature == FeatureCode.TASK_CREATE && ctx.conversationId() != null) {
            return "assignment:" + ctx.conversationId() + ":generate";
        }
        return null;
    }

    private String purchaseProductId(FeatureCode feature) {
        if (feature == FeatureCode.AI_DETECTION) {
            return "ai_detection";
        }
        if (feature == FeatureCode.HUMANIZER) {
            return "humanizer";
        }
        return "assignment";
    }

    private String blockedAction(FeatureCode feature) {
        if (feature == FeatureCode.AI_DETECTION) {
            return "ai_detection_start";
        }
        if (feature == FeatureCode.HUMANIZER) {
            return "humanizer_start";
        }
        return "assignment_generate";
    }

    // ===================================================================
    //  退款
    // ===================================================================

    @Override
    @Transactional
    public void refundBySessionId(Long sessionId, String reason) {
        if (sessionId == null) {
            return;
        }
        VerlaSession s = sessionRepository.findById(sessionId);
        if (s == null) {
            log.debug("[VerlaQuota] refund skip: session not found, sessionId={}", sessionId);
            return;
        }
        Long ledgerId = s.getQuotaLedgerId();
        if (ledgerId == null && s.getTurnId() != null) {
            ledgerId = findChargedSessionForTurn(s.getTurnId())
                    .map(VerlaSession::getQuotaLedgerId)
                    .orElse(null);
        }
        if (ledgerId == null) {
            // 未扣过费（admin / Quota VIP / 白名单 / 配额关闭），无需退款
            quotaBusinessMetrics.recordRefund(s.getFeatureCode(), reason, QuotaBusinessMetrics.Result.SKIPPED);
            return;
        }
        try {
            boolean refunded = quotaDomainService.refund(
                    ledgerId, reason == null ? "verla_session_terminated" : reason);
            quotaBusinessMetrics.recordRefund(s.getFeatureCode(), reason, refunded
                    ? QuotaBusinessMetrics.Result.SUCCESS
                    : QuotaBusinessMetrics.Result.SKIPPED);
            log.info("[VerlaQuota] refund result: sessionId={}, ledgerId={}, refunded={}, reason={}",
                    sessionId, ledgerId, refunded, reason);
        } catch (Exception ex) {
            quotaBusinessMetrics.recordRefund(s.getFeatureCode(), reason, QuotaBusinessMetrics.Result.ERROR);
            // 这里只对未预期异常（如 SQL 故障）告警，不抛出，避免影响 turn 状态推进。
            log.warn("[VerlaQuota] refund failed: sessionId={}, ledgerId={}, reason={}, err={}",
                    sessionId, ledgerId, reason, ex.getMessage());
        }
    }

    // ===================================================================
    //  helpers
    // ===================================================================

    private Optional<VerlaSession> findChargedSessionForTurn(Long turnId) {
        if (turnId == null) {
            return Optional.empty();
        }
        return sessionRepository.findByTurn(turnId).stream()
                .filter(session -> session.getQuotaLedgerId() != null)
                .findFirst();
    }

    private Map<String, Object> baseBizContext(VerlaQuotaContext ctx) {
        Map<String, Object> biz = new HashMap<>();
        biz.put("verla_session_id", ctx.sessionId());
        if (ctx.conversationId() != null) biz.put("conversation_id", ctx.conversationId());
        if (ctx.turnId() != null) biz.put("turn_id", ctx.turnId());
        if (ctx.userMessageId() != null) biz.put("user_message_id", ctx.userMessageId());
        if (ctx.intent() != null && !ctx.intent().isBlank()) biz.put("intent", ctx.intent());
        return biz;
    }
}
