package com.studyagent.infra.service.billing;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.stripe.model.Subscription;
import com.studyagent.infra.entity.SubscriptionPlanEntity;
import com.studyagent.infra.entity.UserSubscriptionEntity;
import com.studyagent.infra.mapper.UserSubscriptionMapper;
import com.studyagent.infra.service.PlanQuotaServiceImpl;
import com.studyagent.infra.testutil.MybatisPlusTableInfoTestHelper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SubscriptionPeriodTimeZoneWriterTest {
    private final UserSubscriptionMapper users = mock(UserSubscriptionMapper.class);
    private final BillingDomainServiceImpl billing = mock(BillingDomainServiceImpl.class, CALLS_REAL_METHODS);
    private final LocalDateTime start = LocalDateTime.parse("2027-01-27T06:28:34");
    private final LocalDateTime end = LocalDateTime.parse("2027-02-27T06:28:34");

    @BeforeAll static void mappings() { MybatisPlusTableInfoTestHelper.initTableInfo(UserSubscriptionEntity.class); }

    SubscriptionPeriodTimeZoneWriterTest() { ReflectionTestUtils.setField(billing, "userSubscriptionMapper", users); }

    @Test
    void ordinaryCallbackTagsItsUnchangedUtcPairAndClearsTagsWhenDeleting() {
        var user = member();var sub = nativeSubscription();var plan = plan("month");
        var webhook = new StripeBillingWebhookService(null, users, null, null, null, null, null, null, null, null, null, null);
        webhook.applySubscription(user, sub, plan, false, true, null, null, start);
        assertEquals(start, user.getCurrentPeriodStart());assertEquals(end, user.getQuotaPeriodEnd());
        assertEquals("UTC", user.getCurrentPeriodTimeZone());assertEquals("UTC", user.getQuotaPeriodTimeZone());
        StripeBillingWebhookService.normalizeDeletedSubscriptionState(user);
        assertNull(user.getCurrentPeriodTimeZone());assertNull(user.getQuotaPeriodTimeZone());
    }

    @Test
    void cancellationSyncTagsOnlyTheNativeCurrentPairAndPreservesQuotaSource() {
        var user = member();user.setQuotaPeriodTimeZone("Asia/Shanghai");
        var originalQuotaStart = user.getQuotaPeriodStart();
        ReflectionTestUtils.invokeMethod(billing, "syncCancellationFields", user, nativeSubscription());
        assertEquals(start, user.getCurrentPeriodStart());assertEquals(end, user.getCurrentPeriodEnd());
        assertEquals("UTC", user.getCurrentPeriodTimeZone());
        assertEquals("Asia/Shanghai", user.getQuotaPeriodTimeZone());
        assertEquals(originalQuotaStart, user.getQuotaPeriodStart());
    }

    @ParameterizedTest @ValueSource(strings={"month","year"})
    void repairDoesNotDeclareMixedNativeAndFallbackSourcesAsOneZone(String interval) {
        var user = member();user.setCurrentPeriodTimeZone("Asia/Shanghai");
        var fallbackEnd = user.getCurrentPeriodEnd();var sub = nativeSubscription();sub.setCurrentPeriodEnd(null);
        try (var stripe = mockStatic(Subscription.class)) {
            stripe.when(() -> Subscription.retrieve("sub_source")).thenReturn(sub);
            ReflectionTestUtils.invokeMethod(billing, "repairActivatedInitialSubscription", user, plan(interval));
        }
        assertEquals(start, user.getCurrentPeriodStart());assertEquals(fallbackEnd, user.getCurrentPeriodEnd());
        assertNull(user.getCurrentPeriodTimeZone());
        assertEquals("year".equals(interval) ? "UTC" : null, user.getQuotaPeriodTimeZone());
    }

    @Test
    void repairUsesUtcOnlyForTwoNativeValuesAndRetainsTwoFallbackSources() {
        for (boolean nativeDates : new boolean[]{true,false}) {
            var user = member();var beforeStart = user.getCurrentPeriodStart();var beforeEnd = user.getCurrentPeriodEnd();
            var sub = nativeSubscription();
            if (!nativeDates) { sub.setCurrentPeriodStart(null);sub.setCurrentPeriodEnd(null); }
            try (var stripe = mockStatic(Subscription.class)) {
                stripe.when(() -> Subscription.retrieve("sub_source")).thenReturn(sub);
                ReflectionTestUtils.invokeMethod(billing, "repairActivatedInitialSubscription", user, plan("month"));
            }
            assertEquals(nativeDates ? "UTC" : "Asia/Shanghai", user.getCurrentPeriodTimeZone());
            assertEquals(nativeDates ? start : beforeStart, user.getCurrentPeriodStart());
            assertEquals(nativeDates ? end : beforeEnd, user.getCurrentPeriodEnd());
        }
    }

    @ParameterizedTest @NullSource @ValueSource(strings={"UTC","Asia/Shanghai"})
    void annualRefreshPropagatesActualInputSourceAndDoesNotRewriteTheDates(String source) {
        var service = mock(PlanQuotaServiceImpl.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(service, "userSubscriptionMapper", users);
        var user = member();user.setQuotaPeriodTimeZone(source);
        var captured = new AtomicReference<UpdateWrapper<UserSubscriptionEntity>>();
        when(users.update(isNull(), any(Wrapper.class))).thenAnswer(call -> { captured.set(call.getArgument(1));return 1; });
        var nextStart = start.plusMonths(1);var nextEnd = end.plusMonths(1);
        ReflectionTestUtils.invokeMethod(service, "syncAnnualSubscriptionQuotaWindow", user, nextStart, nextEnd, start);
        assertEquals(nextStart, user.getQuotaPeriodStart());assertEquals(nextEnd, user.getQuotaPeriodEnd());
        assertEquals(source, user.getQuotaPeriodTimeZone());
        assertTrue(captured.get().getSqlSet().contains("quota_period_time_zone"));
        assertTrue(captured.get().getParamNameValuePairs().containsValue(source));
    }

    @Test
    void unchangedAnnualWindowDoesNotInventAZoneForLegacyHistory() {
        var service = mock(PlanQuotaServiceImpl.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(service, "userSubscriptionMapper", users);
        var user = member();user.setQuotaPeriodTimeZone(null);
        ReflectionTestUtils.invokeMethod(service, "syncAnnualSubscriptionQuotaWindow", user,
                user.getQuotaPeriodStart(), user.getQuotaPeriodEnd(), start);
        assertNull(user.getQuotaPeriodTimeZone());verifyNoInteractions(users);
    }

    private UserSubscriptionEntity member() {
        var user = new UserSubscriptionEntity();user.setId(1L);user.setClerkUserId("source_user");
        user.setStripeSubscriptionId("sub_source");user.setStatus("active");user.setTier("pro");
        user.setCurrentPeriodStart(start.plusHours(8));user.setCurrentPeriodEnd(end.plusHours(8));
        user.setQuotaPeriodStart(start.plusHours(8));user.setQuotaPeriodEnd(end.plusHours(8));
        user.setCurrentPeriodTimeZone("Asia/Shanghai");user.setQuotaPeriodTimeZone("Asia/Shanghai");
        return user;
    }

    private Subscription nativeSubscription() {
        var sub = new Subscription();sub.setId("sub_source");sub.setCustomer("cus_source");sub.setStatus("active");
        sub.setCurrentPeriodStart(Instant.parse("2027-01-27T06:28:34Z").getEpochSecond());
        sub.setCurrentPeriodEnd(Instant.parse("2027-02-27T06:28:34Z").getEpochSecond());
        return sub;
    }

    private SubscriptionPlanEntity plan(String interval) {
        var plan = new SubscriptionPlanEntity();plan.setTier("pro");plan.setPlanCode("pro_monthly");plan.setBillingInterval(interval);
        return plan;
    }
}
