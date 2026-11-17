package com.studyagent.infra.service.billing;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryCaseKeyTest {
    @Test void exactKeyIncludesFormalPlanSourcePeriodScheduleAndCancellation() {
        assertEquals("formal:pro_monthly:month_1:NO_SCHEDULE:RENEW",
                new RecoveryCaseKey("formal", "pro_monthly", "month", 1, "NO_SCHEDULE", "RENEW").value());
        assertEquals("pro_trial_to_monthly:pro_monthly:month_1:EQUIVALENT_SCHEDULE:PERIOD_END",
                new RecoveryCaseKey("pro_trial_to_monthly", "pro_monthly", "month", 1, "EQUIVALENT_SCHEDULE", "PERIOD_END").value());
    }
    @Test void verifiedProNeverAdmitsBasicOrOtherSourceScheduleCancellation() {
        var verified = Set.of("formal:pro_monthly:month_1:NO_SCHEDULE:RENEW");
        assertTrue(new RecoveryCaseKey("formal", "pro_monthly", "month", 1, "NO_SCHEDULE", "RENEW").verifiedBy(verified));
        assertFalse(new RecoveryCaseKey("formal", "basic_monthly", "month", 1, "NO_SCHEDULE", "RENEW").verifiedBy(verified));
        assertFalse(new RecoveryCaseKey("formal", "pro_monthly", "month", 1, "NO_SCHEDULE", "PERIOD_END").verifiedBy(verified));
        assertFalse(new RecoveryCaseKey("formal", "pro_monthly", "month", 1, "EQUIVALENT_SCHEDULE", "RENEW").verifiedBy(verified));
        assertFalse(new RecoveryCaseKey("pro_trial_to_monthly", "pro_monthly", "month", 1, "NO_SCHEDULE", "RENEW").verifiedBy(verified));
    }
    @Test void broadAliasUnknownAndContradictoryKeysAreRejected() {
        for (String key : new String[]{"formal_monthly", "formal:*:month_1:NO_SCHEDULE:RENEW",
                "formal:pro_monthly:year_1:NO_SCHEDULE:RENEW", "formal:pro_monthly:month_1:UNKNOWN:RENEW",
                "basic_trial_to_monthly:pro_monthly:month_1:NO_SCHEDULE:RENEW"})
            assertThrows(IllegalArgumentException.class, () -> RecoveryCaseKey.parse(key), key);
    }
    @Test void repairedHistoricalYearlyTrialKeepsItsSourceWhileTargetingTheActualMonthlyContract() {
        var repaired=RecoveryCaseKey.parse("pro_trial_to_yearly:pro_monthly:month_1:EQUIVALENT_SCHEDULE:RENEW");assertEquals("pro_trial_to_yearly",repaired.sourceKey());assertEquals("pro_monthly",repaired.targetPlanCode());
        assertFalse(repaired.verifiedBy(Set.of("pro_trial_to_yearly:pro_yearly:year_1:EQUIVALENT_SCHEDULE:RENEW")));assertFalse(repaired.verifiedBy(Set.of("pro_trial_to_monthly:pro_monthly:month_1:EQUIVALENT_SCHEDULE:RENEW")));
        assertTrue(repaired.verifiedBy(Set.of(repaired.value())));
    }
    @Test void allFourSubscriptionTrialSourcesKeepTheirOwnExactKeys() {
        for(String tier:new String[]{"basic","pro"})for(String interval:new String[]{"month","year"}){
            String suffix="month".equals(interval)?"monthly":"yearly";String key=tier+"_trial_to_"+suffix+":"+tier+"_"+suffix+":"+interval+"_1:EQUIVALENT_SCHEDULE:RENEW";assertEquals(key,RecoveryCaseKey.parse(key).value());
        }
    }
    @Test void defaultsCannotTakeOverAnyNewInvoice() {
        var props = new PaymentRecoveryProperties();
        assertFalse(props.isAcceptNew());assertFalse(props.isAutomaticPayEnabled());assertFalse(props.isEmailEnabled());
        assertTrue(props.getVerifiedCohorts().isEmpty()); assertTrue(props.getAllowedUserIds().isEmpty());
    }
}
