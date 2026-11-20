package com.studyagent.infra.service.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.studyagent.common.datetime.DateTimeFormats;
import com.studyagent.common.datetime.OffsetLocalDateTimeSerializer;
import com.studyagent.infra.entity.UserSubscriptionEntity;
import com.studyagent.service.domain.billing.SubscriptionResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.ReflectionUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BillingSubscriptionPeriodTimeZoneTest {
    private final BillingDomainServiceImpl service = mock(BillingDomainServiceImpl.class, CALLS_REAL_METHODS);
    private final Instant start = Instant.parse("2027-01-27T06:28:34Z");
    private final Instant end = Instant.parse("2027-02-27T06:28:34Z");

    @Test
    void normalRenewalUtcDatesSerializeTheActualStripeEpochWithoutChangingStoredValues() throws Exception {
        var user = member("2027-01-27T06:28:34", "2027-02-27T06:28:34", "UTC", "UTC");
        for (int read = 0; read < 2; read++) assertEpochs(result(user), start, end, start, end);
        assertEquals(LocalDateTime.parse("2027-01-27T06:28:34"), user.getCurrentPeriodStart());
        assertEquals(LocalDateTime.parse("2027-02-27T06:28:34"), user.getQuotaPeriodEnd());
    }

    @Test
    void recoveryAndReplacementAppDatesAreNotShiftedAgainWhenRecoveryPointerIsNull() throws Exception {
        var user = member("2027-01-27T14:28:34", "2027-02-27T14:28:34", "Asia/Shanghai", "Asia/Shanghai");
        assertNull(user.getPaymentRecoveryId());
        assertEpochs(result(user), start, end, start, end);
    }

    @Test
    void memberAndAllowanceWindowsMayHaveDifferentExplicitSources() throws Exception {
        var user = member("2027-01-27T06:28:34", "2027-02-27T06:28:34", "UTC", "Asia/Shanghai");
        user.setQuotaPeriodStart(DateTimeFormats.fromInstant(start));
        user.setQuotaPeriodEnd(DateTimeFormats.fromInstant(end));
        assertEpochs(result(user), start, end, start, end);
    }

    @Test
    void annualMemberWindowAndLaterMonthlyAllowanceSerializeIndependently() throws Exception {
        var user = member("2027-01-30T20:30:00", "2028-01-30T20:30:00", "UTC", "Asia/Shanghai");
        user.setPlanCode("pro_yearly");
        var quotaStart = Instant.parse("2027-02-28T20:30:00Z");
        var quotaEnd = Instant.parse("2027-03-30T20:30:00Z");
        user.setQuotaPeriodStart(DateTimeFormats.fromInstant(quotaStart));
        user.setQuotaPeriodEnd(DateTimeFormats.fromInstant(quotaEnd));
        assertEpochs(result(user), Instant.parse("2027-01-30T20:30:00Z"),
                Instant.parse("2028-01-30T20:30:00Z"), quotaStart, quotaEnd);
    }

    @Test
    void explicitOffsetSupportsLocalMockOrManualWritersWithoutGuessingJvmZone() throws Exception {
        var user = member("2027-01-27T01:28:34", "2027-02-27T01:28:34", "-05:00", "-05:00");
        assertEpochs(result(user), start, end, start, end);
    }

    @Test
    void unmarkedHistoryKeepsItsPriorPresentationAndMissingDatesRemainNull() {
        var user = member("2027-01-27T06:28:34", "2027-02-27T06:28:34", null, null);
        var result = result(user);
        assertEquals(user.getCurrentPeriodStart(), result.getCurrentPeriodStart());
        assertEquals(user.getCurrentPeriodEnd(), result.getCurrentPeriodEnd());
        user.setCurrentPeriodStart(null);user.setCurrentPeriodEnd(null);
        user.setQuotaPeriodStart(null);user.setQuotaPeriodEnd(null);
        result = result(user);
        assertNull(result.getCurrentPeriodStart());assertNull(result.getQuotaPeriodEnd());
    }

    @Test
    void invalidExplicitSourceDoesNotGuessADateOrChangeMembershipPermissions() {
        var user = member("2027-01-27T06:28:34", "2027-02-27T06:28:34", "invalid/source", "invalid/source");
        var result = result(user);
        assertNull(result.getCurrentPeriodStart());assertNull(result.getQuotaPeriodEnd());
        assertEquals("pro", result.getTier());assertTrue(result.getCanConsumePaidEntitlements());
    }

    @Test
    void activeIntroTrialNestedDeadlineMatchesTopLevelPeriodEnd() {
        var user = member("2027-01-27T06:28:34", "2027-02-27T06:28:34", "UTC", "UTC");
        user.setTier("basic");user.setPlanCode("basic_trial_to_monthly");user.setStatus("trialing");
        user.setPendingPlanCode("basic_monthly");
        var result = result(user);
        assertEquals(DateTimeFormats.fromInstant(end), result.getCurrentPeriodEnd());
        assertEquals(result.getCurrentPeriodEnd(), result.getBasicTrial().getEndsAt());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={true,false})
    void recoveryPaidAndFinancialHoldKeepCorrectTimeWithoutChangingAccess(boolean usable) throws Exception {
        var user = member("2027-01-27T14:28:34", "2027-02-27T14:28:34", "Asia/Shanghai", "Asia/Shanghai");
        user.setPaymentRecoveryId(2L);
        var summary = new com.studyagent.service.domain.billing.PaymentRecoverySummary("2", "PAID", !usable,
                "pro", "pro_monthly", 3499L, 3499L, 0L, "sgd", null, null, null, java.util.List.of(), "CONFIRMED", usable ? "NONE" : "OPEN", null);
        var access = new com.studyagent.service.domain.billing.PaymentRecoveryAccess(!usable, usable,
                usable ? start : null, usable ? end : null, summary);
        var resolver = mock(com.studyagent.service.domain.billing.PaymentRecoveryAccessService.class);
        when(resolver.resolve("period_user", "sub_period")).thenReturn(access);
        ReflectionTestUtils.setField(service, "paymentRecoveryAccessService", resolver);
        var result = result(user);
        assertEpochs(result, start, end, start, end);
        assertEquals(usable, result.getCanConsumePaidEntitlements());
        assertEquals(usable, result.getCanRefreshPlan());
    }

    private UserSubscriptionEntity member(String start, String end, String currentZone, String quotaZone) {
        var user = new UserSubscriptionEntity();user.setClerkUserId("period_user");
        user.setTier("pro");user.setStatus("active");user.setPlanCode("pro_monthly");
        user.setStripeSubscriptionId("sub_period");user.setPricingZone("Z090");user.setPriceVersion("regional-v1");
        user.setCurrentPeriodStart(LocalDateTime.parse(start));user.setCurrentPeriodEnd(LocalDateTime.parse(end));
        user.setQuotaPeriodStart(LocalDateTime.parse(start));user.setQuotaPeriodEnd(LocalDateTime.parse(end));
        marker(user, "currentPeriodTimeZone", currentZone);marker(user, "quotaPeriodTimeZone", quotaZone);
        return user;
    }

    private void marker(UserSubscriptionEntity user, String name, String value) {
        // Allows the assertion to demonstrate the original API bug before adding provenance fields.
        if (ReflectionUtils.findField(UserSubscriptionEntity.class, name) != null)
            ReflectionTestUtils.setField(user, name, value);
    }

    private SubscriptionResult result(UserSubscriptionEntity user) {
        return ReflectionTestUtils.invokeMethod(service, "toResult", user);
    }

    private void assertEpochs(SubscriptionResult result, Instant start, Instant end, Instant quotaStart, Instant quotaEnd) throws Exception {
        var mapper = new ObjectMapper().registerModule(new SimpleModule()
                .addSerializer(LocalDateTime.class, new OffsetLocalDateTimeSerializer()));
        var json = mapper.readTree(mapper.writeValueAsString(Map.of(
                "start", result.getCurrentPeriodStart(), "end", result.getCurrentPeriodEnd(),
                "quotaStart", result.getQuotaPeriodStart(), "quotaEnd", result.getQuotaPeriodEnd())));
        assertEquals(start, OffsetDateTime.parse(json.get("start").asText()).toInstant());
        assertEquals(end, OffsetDateTime.parse(json.get("end").asText()).toInstant());
        assertEquals(quotaStart, OffsetDateTime.parse(json.get("quotaStart").asText()).toInstant());
        assertEquals(quotaEnd, OffsetDateTime.parse(json.get("quotaEnd").asText()).toInstant());
    }
}
