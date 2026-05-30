package com.studyagent.service.application.verla.quota;

import com.studyagent.common.exception.InsufficientQuotaData;
import com.studyagent.common.exception.InsufficientQuotaException;
import com.studyagent.common.quota.FeatureCode;
import com.studyagent.service.domain.quota.ConsumeResult;
import com.studyagent.service.domain.quota.QuotaBalance;
import com.studyagent.service.domain.quota.QuotaDomainService;
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

    /** 总开关：false 时全部豁免，方便灰度上线 / 紧急关停。 */
    @Value("${verla.quota.enabled:true}")
    private boolean quotaEnabled;

    /** verla 链路专属白名单（与 1.0 humanizer.whitelist-user-ids 独立配置）。 */
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
        boolean isWhitelisted = whitelistUserIds != null
                && whitelistUserIds.contains(clerkUserId);
        return isAdmin || isWhitelisted;
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
        long words = Math.max(1, wordCounter.countWords(text));
        Map<String, Object> biz = baseBizContext(ctx);
        biz.put("word_count", words);
        return consumeInternal(ctx, FeatureCode.AI_DETECTION, words, biz);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public VerlaQuotaConsumeResult consumeForHumanizer(VerlaQuotaContext ctx, String text) {
        long words = Math.max(1, wordCounter.countWords(text));
        Map<String, Object> biz = baseBizContext(ctx);
        biz.put("word_count", words);
        return consumeInternal(ctx, FeatureCode.HUMANIZER, words, biz);
    }

    private VerlaQuotaConsumeResult consumeInternal(
            VerlaQuotaContext ctx,
            FeatureCode feature,
            long amount,
            Map<String, Object> bizContext) {

        if (ctx == null || ctx.sessionId() == null) {
            throw new IllegalArgumentException("VerlaQuotaContext.sessionId is required");
        }

        // 1) admin / 白名单 / 配额关闭 → 直接放行
        if (isQuotaExempt(ctx.clerkUserId())) {
            log.info("[VerlaQuota] exempt: feature={}, userId={}, sessionId={}",
                    feature.getCode(), ctx.clerkUserId(), ctx.sessionId());
            return VerlaQuotaConsumeResult.exempted();
        }

        // 2) 余额校验失败 → 抛 InsufficientQuotaException，由 GlobalExceptionHandler 包装
        if (!quotaDomainService.canConsume(ctx.clerkUserId(), feature.getCode(), amount)) {
            QuotaBalance balance = quotaDomainService.getUserQuota(ctx.clerkUserId(), feature.getCode());
            throw new InsufficientQuotaException(
                    "Insufficient quota for " + feature.getCode()
                            + ", required=" + amount
                            + ", available=" + balance.totalAvailable(),
                    InsufficientQuotaData.builder()
                            .featureCode(balance.featureCode())
                            .featureName(balance.featureName())
                            .quotaUnit(balance.quotaUnit())
                            .freeBalance(balance.freeBalance())
                            .freePeriodTotal(balance.freePeriodTotal())
                            .paidBalance(balance.paidBalance())
                            .totalAvailable(balance.totalAvailable())
                            .totalWords((int) Math.min(Integer.MAX_VALUE, amount))
                            .build());
        }

        // 3) 真扣费（事务由调用方持有）
        ConsumeResult cr = quotaDomainService.consume(
                ctx.clerkUserId(),
                feature.getCode(),
                amount,
                SOURCE_TYPE_VERLA_SESSION,
                String.valueOf(ctx.sessionId()),
                bizContext);

        // 4) 回填 verla_sessions.quota_ledger_id（乐观锁，避免并发派发双扣）
        boolean bound = sessionRepository.bindQuotaLedger(ctx.sessionId(), cr.ledgerId(), amount);
        if (!bound) {
            // 并发：另一方已经扣过且绑定。当前事务必须回滚，否则会产生重复扣费。
            VerlaSession s = sessionRepository.findById(ctx.sessionId());
            Long alreadyBound = s == null ? null : s.getQuotaLedgerId();
            log.error("[VerlaQuota] duplicate consume detected, rolling back: sessionId={}, newLedgerId={}, existingLedgerId={}",
                    ctx.sessionId(), cr.ledgerId(), alreadyBound);
            throw new IllegalStateException(
                    "Concurrent verla session billing detected: sessionId=" + ctx.sessionId()
                            + ", existingLedgerId=" + alreadyBound);
        }

        log.info("[VerlaQuota] consumed: feature={}, userId={}, sessionId={}, amount={}, ledgerId={}",
                feature.getCode(), ctx.clerkUserId(), ctx.sessionId(), amount, cr.ledgerId());

        return VerlaQuotaConsumeResult.of(cr.ledgerId(), amount);
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
        if (ledgerId == null) {
            // 未扣过费（admin / 白名单 / 配额关闭），无需退款
            return;
        }
        try {
            quotaDomainService.refund(ledgerId, reason == null ? "verla_session_terminated" : reason);
            log.info("[VerlaQuota] refunded: sessionId={}, ledgerId={}, reason={}",
                    sessionId, ledgerId, reason);
        } catch (Exception ex) {
            // 已经 refund / ledger 已被处理：QuotaDomainServiceImpl.refund 内部幂等校验已兜底，
            // 这里只对未预期异常（如 SQL 故障）告警，不抛出，避免影响 turn 状态推进。
            log.warn("[VerlaQuota] refund skipped (already refunded or unknown error): sessionId={}, ledgerId={}, reason={}, err={}",
                    sessionId, ledgerId, reason, ex.getMessage());
        }
    }

    // ===================================================================
    //  helpers
    // ===================================================================

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
