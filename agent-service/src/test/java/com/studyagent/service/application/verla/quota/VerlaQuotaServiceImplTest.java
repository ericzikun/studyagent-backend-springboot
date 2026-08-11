package com.studyagent.service.application.verla.quota;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link VerlaQuotaServiceImpl} 核心行为单测：
 * - 豁免（admin / Quota VIP / 白名单 / 开关关闭）
 * - 余额不足抛 {@link InsufficientQuotaException}
 * - 扣费成功回写 verla_sessions.quota_ledger_id
 * - 并发绑定失败 → 抛 {@link IllegalStateException} 触发外层事务回滚
 * - 退款幂等：未扣过 / refund 失败均静默
 */
class VerlaQuotaServiceImplTest {

    private QuotaDomainService quotaDomainService;
    private UserRepository userRepository;
    private VerlaSessionRepository sessionRepository;
    private VerlaQuotaWordCounter wordCounter;
    private QuotaVipAccessService quotaVipAccessService;
    private VerlaQuotaServiceImpl service;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        quotaDomainService = mock(QuotaDomainService.class);
        userRepository = mock(UserRepository.class);
        sessionRepository = mock(VerlaSessionRepository.class);
        wordCounter = new VerlaQuotaWordCounter();
        quotaVipAccessService = mock(QuotaVipAccessService.class);
        meterRegistry = new SimpleMeterRegistry();
        service = new VerlaQuotaServiceImpl(
                quotaDomainService, userRepository, sessionRepository, wordCounter, quotaVipAccessService,
                new QuotaBusinessMetrics(meterRegistry));
        ReflectionTestUtils.setField(service, "quotaEnabled", true);
        ReflectionTestUtils.setField(service, "whitelistUserIds", List.of());
        when(quotaVipAccessService.isQuotaVip(anyString())).thenReturn(false);
    }

    private VerlaQuotaContext ctx() {
        return VerlaQuotaContext.builder()
                .clerkUserId("user_abc")
                .conversationId(11L)
                .turnId(22L)
                .sessionId(33L)
                .intent("ASSIGNMENT")
                .userMessageId(44L)
                .build();
    }

    private QuotaBalance balance(long totalAvailable) {
        return balance(FeatureCode.TASK_CREATE, "Assignment", "count", totalAvailable);
    }

    private QuotaBalance balance(FeatureCode featureCode, String featureName, String quotaUnit, long totalAvailable) {
        return new QuotaBalance(
                featureCode.getCode(),
                featureName,
                quotaUnit,
                Math.min(totalAvailable, 5),
                5L,
                null,
                Math.max(0, totalAvailable - 5),
                null,
                0L,
                List.of(),
                0L,
                totalAvailable);
    }

    // ---------------------- 豁免 ----------------------

    @Test
    void isQuotaExempt_returnsTrue_whenQuotaDisabled() {
        ReflectionTestUtils.setField(service, "quotaEnabled", false);
        assertTrue(service.isQuotaExempt("user_abc"));
        verifyNoInteractions(userRepository);
    }

    @Test
    void isQuotaExempt_returnsTrue_forAdmin() {
        User admin = User.builder()
                .clerkUserId("user_admin")
                .isAdmin(Boolean.TRUE)
                .isActive(Boolean.TRUE)
                .build();
        when(userRepository.findByClerkUserId("user_admin")).thenReturn(Optional.of(admin));
        assertTrue(service.isQuotaExempt("user_admin"));
    }

    @Test
    void isQuotaExempt_returnsTrue_forWhitelist() {
        ReflectionTestUtils.setField(service, "whitelistUserIds", List.of("user_w"));
        when(userRepository.findByClerkUserId("user_w")).thenReturn(Optional.empty());
        assertTrue(service.isQuotaExempt("user_w"));
    }

    @Test
    void isQuotaExempt_returnsTrue_forQuotaVip() {
        when(userRepository.findByClerkUserId("user_vip")).thenReturn(Optional.empty());
        when(quotaVipAccessService.isQuotaVip("user_vip")).thenReturn(true);
        assertTrue(service.isQuotaExempt("user_vip"));
    }

    @Test
    void consumeForAssignmentRun_returnsExempt_andSkipsQuotaService_whenExempt() {
        ReflectionTestUtils.setField(service, "quotaEnabled", false);
        when(sessionRepository.findByTurn(22L)).thenReturn(List.of());

        VerlaQuotaConsumeResult r = service.consumeForAssignmentRun(ctx());

        assertTrue(r.exempt());
        assertNull(r.ledgerId());
        verifyNoInteractions(quotaDomainService);
        verify(sessionRepository, never()).bindQuotaLedger(anyLong(), anyLong(), anyLong());
    }

    // ---------------------- 余额不足 ----------------------

    @Test
    void consumeForAssignmentRun_throwsInsufficient_whenCanConsumeFalse() {
        when(sessionRepository.findByTurn(22L)).thenReturn(List.of());
        when(userRepository.findByClerkUserId(anyString())).thenReturn(Optional.empty());
        when(quotaDomainService.canConsume("user_abc", FeatureCode.TASK_CREATE.getCode(), 1L))
                .thenReturn(false);
        when(quotaDomainService.getUserQuota("user_abc", FeatureCode.TASK_CREATE.getCode()))
                .thenReturn(balance(0));

        InsufficientQuotaException ex = assertThrows(
                InsufficientQuotaException.class,
                () -> service.consumeForAssignmentRun(ctx()));

        assertEquals("user_abc", ex.getData().getClerkUserId());
        assertEquals(FeatureCode.TASK_CREATE.getCode(), ex.getData().getFeatureCode());
        assertEquals("assignment", ex.getData().getPurchaseProductId());
        assertEquals("assignment_generate", ex.getData().getBlockedAction());
        assertEquals(0L, ex.getData().getTotalAvailable());
        verify(quotaDomainService, never()).consume(
                anyString(), anyString(), anyLong(), anyString(), anyString(), any(), any());
        verify(sessionRepository, never()).bindQuotaLedger(anyLong(), anyLong(), anyLong());
    }

    @Test
    void consumeForDetection_throwsInsufficient_withCommercialMetadata() {
        when(userRepository.findByClerkUserId(anyString())).thenReturn(Optional.empty());
        when(quotaDomainService.canConsume("user_abc", FeatureCode.AI_DETECTION.getCode(), 4L))
                .thenReturn(false);
        when(quotaDomainService.getUserQuota("user_abc", FeatureCode.AI_DETECTION.getCode()))
                .thenReturn(balance(FeatureCode.AI_DETECTION, "AI Detection", "words", 0));

        InsufficientQuotaException ex = assertThrows(
                InsufficientQuotaException.class,
                () -> service.consumeForDetection(ctx(), "hello world ai detection"));

        assertEquals("user_abc", ex.getData().getClerkUserId());
        assertEquals("ai_detection", ex.getData().getPurchaseProductId());
        assertEquals("ai_detection_start", ex.getData().getBlockedAction());
        assertEquals(Integer.valueOf(4), ex.getData().getTotalWords());
    }

    @Test
    void consumeForHumanizer_throwsInsufficient_withCommercialMetadata() {
        when(userRepository.findByClerkUserId(anyString())).thenReturn(Optional.empty());
        when(quotaDomainService.canConsume("user_abc", FeatureCode.HUMANIZER.getCode(), 3L))
                .thenReturn(false);
        when(quotaDomainService.getUserQuota("user_abc", FeatureCode.HUMANIZER.getCode()))
                .thenReturn(balance(FeatureCode.HUMANIZER, "Humanizer", "words", 0));

        InsufficientQuotaException ex = assertThrows(
                InsufficientQuotaException.class,
                () -> service.consumeForHumanizer(ctx(), "many many words"));

        assertEquals("user_abc", ex.getData().getClerkUserId());
        assertEquals("humanizer", ex.getData().getPurchaseProductId());
        assertEquals("humanizer_start", ex.getData().getBlockedAction());
        assertEquals(Integer.valueOf(3), ex.getData().getTotalWords());
    }

    // ---------------------- 扣费成功 ----------------------

    @Test
    void consumeForDetection_chargesByWordCount() {
        when(userRepository.findByClerkUserId(anyString())).thenReturn(Optional.empty());
        when(quotaDomainService.canConsume(anyString(), eq(FeatureCode.AI_DETECTION.getCode()), eq(4L)))
                .thenReturn(true);
        when(quotaDomainService.consume(
                anyString(), eq(FeatureCode.AI_DETECTION.getCode()), eq(4L),
                eq("verla_session"), eq("33"), any(), eq((String) null)))
                .thenReturn(new ConsumeResult(9999L));
        when(sessionRepository.bindQuotaLedger(33L, 9999L, 4L)).thenReturn(true);

        VerlaQuotaConsumeResult r = service.consumeForDetection(ctx(), "hello world ai detection");

        assertFalse(r.exempt());
        assertEquals(9999L, r.ledgerId());
        assertEquals(4L, r.amount());

        ArgumentCaptor<Map<String, Object>> biz = ArgumentCaptor.forClass(Map.class);
        verify(quotaDomainService).consume(
                eq("user_abc"), eq(FeatureCode.AI_DETECTION.getCode()), eq(4L),
                eq("verla_session"), eq("33"), biz.capture(), eq((String) null));
        Map<String, Object> bz = biz.getValue();
        assertEquals("per_words", bz.get("charged_mode"));
        assertEquals(4L, bz.get("word_count"));
    }

    @Test
    void consumeForHumanizer_chargesByWordCount() {
        when(userRepository.findByClerkUserId(anyString())).thenReturn(Optional.empty());
        when(quotaDomainService.canConsume(anyString(), eq(FeatureCode.HUMANIZER.getCode()), eq(3L)))
                .thenReturn(true);
        when(quotaDomainService.consume(
                anyString(), eq(FeatureCode.HUMANIZER.getCode()), eq(3L),
                anyString(), anyString(), any(), eq((String) null)))
                .thenReturn(new ConsumeResult(123L));
        when(sessionRepository.bindQuotaLedger(33L, 123L, 3L)).thenReturn(true);

        VerlaQuotaConsumeResult r = service.consumeForHumanizer(ctx(), "many many words");

        assertEquals(3L, r.amount());
        ArgumentCaptor<Map<String, Object>> biz = ArgumentCaptor.forClass(Map.class);
        verify(quotaDomainService).consume(
                eq("user_abc"), eq(FeatureCode.HUMANIZER.getCode()), eq(3L),
                eq("verla_session"), eq("33"), biz.capture(), eq((String) null));
        assertEquals("per_words", biz.getValue().get("charged_mode"));
    }

    // ---------------------- 并发绑定冲突 ----------------------

    @Test
    void consumeForAssignmentRun_reusesTurnLedger_withoutSecondConsume_whenTurnAlreadyCharged() {
        VerlaSession charged = VerlaSession.builder()
                .id(10L)
                .turnId(22L)
                .quotaLedgerId(777L)
                .quotaAmount(1L)
                .build();
        when(sessionRepository.findByTurn(22L)).thenReturn(List.of(charged));
        when(sessionRepository.bindQuotaLedger(33L, 777L, 1L)).thenReturn(true);

        VerlaQuotaConsumeResult r = service.consumeForAssignmentRun(ctx());

        assertEquals(777L, r.ledgerId());
        assertEquals(1L, r.amount());
        verify(quotaDomainService, never()).consume(
                anyString(), anyString(), anyLong(), anyString(), anyString(), any(), any());
    }

    @Test
    void inheritAssignmentQuotaLedger_bindsLedgerFromChargedSiblingSession() {
        VerlaSession charged = VerlaSession.builder()
                .id(10L)
                .turnId(22L)
                .quotaLedgerId(888L)
                .quotaAmount(1L)
                .build();
        when(sessionRepository.findById(55L)).thenReturn(VerlaSession.builder().id(55L).turnId(22L).build());
        when(sessionRepository.findByTurn(22L)).thenReturn(List.of(charged));
        when(sessionRepository.bindQuotaLedger(55L, 888L, 1L)).thenReturn(true);

        service.inheritAssignmentQuotaLedger(55L, 22L);

        verify(sessionRepository).bindQuotaLedger(55L, 888L, 1L);
        verifyNoInteractions(quotaDomainService);
    }

    @Test
    void consumeForAssignmentRun_throwsIllegalState_whenBindReturnsFalse() {
        when(sessionRepository.findByTurn(22L)).thenReturn(List.of());
        when(userRepository.findByClerkUserId(anyString())).thenReturn(Optional.empty());
        when(quotaDomainService.canConsume(anyString(), anyString(), anyLong())).thenReturn(true);
        when(quotaDomainService.consume(
                anyString(), anyString(), anyLong(), anyString(), anyString(), any(), anyString()))
                .thenReturn(new ConsumeResult(111L));
        when(sessionRepository.bindQuotaLedger(33L, 111L, 1L)).thenReturn(false);
        // 反查 session 用于日志
        VerlaSession existing = VerlaSession.builder().id(33L).quotaLedgerId(222L).build();
        when(sessionRepository.findById(33L)).thenReturn(existing);

        assertThrows(IllegalStateException.class,
                () -> service.consumeForAssignmentRun(ctx()));
        verify(quotaDomainService).consume(
                eq("user_abc"), eq(FeatureCode.TASK_CREATE.getCode()), eq(1L),
                eq("verla_session"), eq("33"), any(), eq("assignment:11:generate"));
    }

    // ---------------------- 退款 ----------------------

    @Test
    void refundBySessionId_skips_whenNoLedgerBound() {
        VerlaSession s = VerlaSession.builder().id(33L).turnId(22L).build();
        when(sessionRepository.findById(33L)).thenReturn(s);
        when(sessionRepository.findByTurn(22L)).thenReturn(List.of());

        assertDoesNotThrow(() -> service.refundBySessionId(33L, "agent_failed"));
        verify(quotaDomainService, never()).refund(anyLong(), anyString());
    }

    @Test
    void refundBySessionId_fallsBackToTurnLedger_whenCurrentSessionUnbound() {
        VerlaSession runSession = VerlaSession.builder().id(33L).turnId(22L).build();
        VerlaSession clarifySession = VerlaSession.builder()
                .id(10L)
                .turnId(22L)
                .quotaLedgerId(666L)
                .build();
        when(sessionRepository.findById(33L)).thenReturn(runSession);
        when(sessionRepository.findByTurn(22L)).thenReturn(List.of(clarifySession));
        when(quotaDomainService.refund(666L, "agent_failed")).thenReturn(true);

        service.refundBySessionId(33L, "agent_failed");

        verify(quotaDomainService).refund(666L, "agent_failed");
    }

    @Test
    void refundBySessionId_callsDomainRefund_withReason() {
        VerlaSession s = VerlaSession.builder().id(33L).featureCode("task_create").quotaLedgerId(555L).build();
        when(sessionRepository.findById(33L)).thenReturn(s);
        when(quotaDomainService.refund(555L, "agent_failed")).thenReturn(true);

        service.refundBySessionId(33L, "agent_failed");

        verify(quotaDomainService, times(1)).refund(555L, "agent_failed");
    }

    @Test
    void refundBySessionId_swallowsException_forIdempotency() {
        VerlaSession s = VerlaSession.builder().id(33L).quotaLedgerId(555L).build();
        when(sessionRepository.findById(33L)).thenReturn(s);
        when(quotaDomainService.refund(eq(555L), anyString()))
                .thenThrow(new IllegalArgumentException("Ledger not found"));

        assertDoesNotThrow(() -> service.refundBySessionId(33L, "agent_cancelled"));
    }

    @Test
    void refundBySessionId_skips_whenSessionMissing() {
        when(sessionRepository.findById(33L)).thenReturn(null);

        assertDoesNotThrow(() -> service.refundBySessionId(33L, "x"));
        verifyNoInteractions(quotaDomainService);
    }

    @Test
    void refundBySessionId_recordsSkipped_whenRefundWasAlreadyApplied() {
        VerlaSession s = VerlaSession.builder().id(33L).quotaLedgerId(555L).build();
        when(sessionRepository.findById(33L)).thenReturn(s);
        when(quotaDomainService.refund(555L, "agent_failed")).thenReturn(false);

        service.refundBySessionId(33L, "agent_failed");

        assertEquals(1.0, meterRegistry.get("quota.refund")
                .tags("feature", "assignment", "trigger", "agent_failed", "result", "skipped")
                .counter().count());
    }

    // ---------------------- assertSufficientForAssignmentRun ----------------------

    @Test
    void assertSufficientForAssignmentRun_passes_whenExempt() {
        ReflectionTestUtils.setField(service, "quotaEnabled", false);

        assertDoesNotThrow(() -> service.assertSufficientForAssignmentRun("user_abc"));
        verifyNoInteractions(quotaDomainService);
    }

    @Test
    void assertSufficientForAssignmentRun_passes_whenBalanceOk() {
        when(quotaDomainService.canConsume("user_abc", FeatureCode.TASK_CREATE.getCode(), 1))
                .thenReturn(true);

        assertDoesNotThrow(() -> service.assertSufficientForAssignmentRun("user_abc"));
        verify(quotaDomainService, never()).consume(anyString(), anyString(), anyLong(), anyString(), anyString(), any());
        verify(quotaDomainService, never()).consume(anyString(), anyString(), anyLong(), anyString(), anyString(), any(), any());
    }

    @Test
    void assertSufficientForAssignmentRun_throws_whenInsufficient() {
        when(quotaDomainService.canConsume("user_abc", FeatureCode.TASK_CREATE.getCode(), 1))
                .thenReturn(false);
        when(quotaDomainService.getUserQuota("user_abc", FeatureCode.TASK_CREATE.getCode()))
                .thenReturn(balance(0));

        InsufficientQuotaException ex = assertThrows(InsufficientQuotaException.class,
                () -> service.assertSufficientForAssignmentRun("user_abc"));
        assertEquals("user_abc", ex.getData().getClerkUserId());
        assertEquals("assignment", ex.getData().getPurchaseProductId());
        assertEquals("assignment_generate", ex.getData().getBlockedAction());
        verify(quotaDomainService, never()).consume(anyString(), anyString(), anyLong(), anyString(), anyString(), any());
        verify(quotaDomainService, never()).consume(anyString(), anyString(), anyLong(), anyString(), anyString(), any(), any());
    }

    // ---------------------- WordCounter ----------------------

    @Test
    void wordCounter_countsCjkAndAsciiMixed() {
        // "你好世界 你好 hello world ai" → 6 个 CJK + 3 个英文词
        long n = wordCounter.countWords("你好世界 你好 hello world ai");
        assertEquals(6 + 3, n);
    }

    @Test
    void wordCounter_returnsZero_forBlank() {
        assertEquals(0L, wordCounter.countWords(""));
        assertEquals(0L, wordCounter.countWords("   "));
        assertEquals(0L, wordCounter.countWords(null));
    }
}
