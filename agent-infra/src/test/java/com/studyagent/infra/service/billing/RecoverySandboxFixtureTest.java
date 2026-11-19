package com.studyagent.infra.service.billing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class RecoverySandboxFixtureTest {
    @ParameterizedTest
    @ValueSource(strings={"formal:basic_monthly:month_1:NO_SCHEDULE:RENEW","formal:plus_yearly:year_1:EQUIVALENT_SCHEDULE:PERIOD_END","basic_trial_to_monthly:basic_monthly:month_1:EQUIVALENT_SCHEDULE:RENEW","basic_trial_to_yearly:basic_yearly:year_1:NO_SCHEDULE:RENEW","pro_trial_to_monthly:pro_monthly:month_1:NO_SCHEDULE:RENEW","pro_trial_to_yearly:pro_yearly:year_1:NO_SCHEDULE:PERIOD_END","pro_trial_to_yearly:pro_monthly:month_1:EQUIVALENT_SCHEDULE:RENEW"})
    void actualSupportedContractShapesCanUseOwnedTestObjects(String key){
        var fixture=RecoverySandboxFixture.parse(key,"{\"currency\":\"hkd\",\"unitAmountMinor\":62300,\"introAmountMinor\":2400,\"sourcePriceId\":\"price_live_reference_only\",\"priceVersion\":\"regional-20261018-v1\"}");
        assertEquals(key,fixture.caseKey());assertEquals("hkd",fixture.currency());assertEquals(62300L,fixture.unitAmountMinor());
        assertEquals(key.startsWith("formal:"),!fixture.intro());assertEquals(key.contains(":EQUIVALENT_SCHEDULE:"),fixture.keepSchedule());
    }
    @Test void legacyPriceUpdateIsAnExplicitSeparateFixturePath(){
        var f=RecoverySandboxFixture.parse("pro_trial_to_monthly:pro_monthly:month_1:NO_SCHEDULE:RENEW","{\"currency\":\"usd\",\"unitAmountMinor\":7999,\"introAmountMinor\":299,\"conversionMode\":\"PRICE_UPDATE\"}");
        assertTrue(f.priceUpdate());assertFalse(f.keepSchedule());
    }
    @ParameterizedTest
    @ValueSource(strings={"formal:pro_monthly:month_1:NO_SCHEDULE:RENEW","formal:pro_yearly:year_1:NO_SCHEDULE:PERIOD_END"})
    void formalRenewalUsesItsOwnExplicitFirstPayMode(String key){
        var f=RecoverySandboxFixture.parse(key,"{\"currency\":\"usd\",\"unitAmountMinor\":7999,\"introAmountMinor\":299,\"conversionMode\":\"EXPLICIT_FIRST_PAY\"}");
        assertEquals("EXPLICIT_FIRST_PAY",f.conversionMode());assertFalse(f.intro());assertFalse(f.priceUpdate());
    }
    @ParameterizedTest
    @ValueSource(strings={"pro_trial_to_monthly:pro_monthly:month_1:NO_SCHEDULE:RENEW","formal:pro_yearly:year_1:EQUIVALENT_SCHEDULE:RENEW"})
    void formalFirstPayCannotRelabelTrialOrScheduledContracts(String key){
        assertThrows(IllegalArgumentException.class,()->RecoverySandboxFixture.parse(key,"{\"currency\":\"usd\",\"unitAmountMinor\":7999,\"introAmountMinor\":299,\"conversionMode\":\"EXPLICIT_FIRST_PAY\"}"));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"formal:pro_monthly:month_1:NO_SCHEDULE:RENEW","pro_trial_to_monthly:pro_monthly:month_1:EQUIVALENT_SCHEDULE:RENEW"})
    void priceUpdateCannotBeRelabeledAsFormalOrScheduled(String key){
        assertThrows(IllegalArgumentException.class,()->RecoverySandboxFixture.parse(key,"{\"currency\":\"usd\",\"unitAmountMinor\":7999,\"introAmountMinor\":299,\"conversionMode\":\"PRICE_UPDATE\"}"));
    }
    @ParameterizedTest
    @ValueSource(strings={"formal:basic_monthly:month_1:EQUIVALENT_SCHEDULE:RENEW","pro_trial_to_monthly:pro_monthly:month_1:EQUIVALENT_SCHEDULE:RENEW"})
    void renewingEquivalentSchedulesUseDistinctControlledFirstPay(String key){
        var f=assertDoesNotThrow(()->RecoverySandboxFixture.parse(key,"{\"currency\":\"usd\",\"unitAmountMinor\":2999,\"introAmountMinor\":299,\"conversionMode\":\"SCHEDULE_FIRST_PAY\"}"));
        assertTrue(f.keepSchedule());assertFalse(f.periodEnd());assertEquals("SCHEDULE_FIRST_PAY",f.conversionMode());assertEquals(!key.startsWith("formal:"),f.intro());
    }
    @ParameterizedTest
    @ValueSource(strings={"formal:basic_monthly:month_1:EQUIVALENT_SCHEDULE:PERIOD_END","pro_trial_to_monthly:pro_monthly:month_1:EQUIVALENT_SCHEDULE:PERIOD_END","formal:pro_monthly:month_1:NO_SCHEDULE:RENEW"})
    void controlledScheduleFixtureDoesNotEnableRejectedOrRelabeledShapes(String key){
        assertThrows(IllegalArgumentException.class,()->RecoverySandboxFixture.parse(key,"{\"currency\":\"usd\",\"unitAmountMinor\":2999,\"introAmountMinor\":299,\"conversionMode\":\"SCHEDULE_FIRST_PAY\"}"));
    }
    @Test void unknownConversionModeFailsBeforeRemoteCalls(){
        assertThrows(IllegalArgumentException.class,()->RecoverySandboxFixture.parse("pro_trial_to_monthly:pro_monthly:month_1:NO_SCHEDULE:RENEW","{\"currency\":\"usd\",\"unitAmountMinor\":7999,\"introAmountMinor\":299,\"conversionMode\":\"unknown\"}"));
    }
    @Test void zeroDecimalCurrencyAmountsRemainIntegerMinorUnits(){
        var fixture=RecoverySandboxFixture.parse("formal:pro_monthly:month_1:NO_SCHEDULE:RENEW","{\"currency\":\"jpy\",\"unitAmountMinor\":11999,\"introAmountMinor\":450}");
        assertEquals(11999,fixture.unitAmountMinor());assertEquals("jpy",fixture.currency());
    }
    @ParameterizedTest
    @ValueSource(strings={"{\"currency\":\"hkd\",\"unitAmountMinor\":12.5,\"introAmountMinor\":1}","{\"currency\":\"hkd\",\"unitAmountMinor\":0,\"introAmountMinor\":1}","{\"currency\":\"HKD\",\"unitAmountMinor\":10,\"introAmountMinor\":1}","{\"currency\":\"hkd\",\"unitAmountMinor\":10}"})
    void malformedFinancialFixturesFailBeforeAnyRemoteCall(String json){
        assertThrows(IllegalArgumentException.class,()->RecoverySandboxFixture.parse("formal:pro_monthly:month_1:NO_SCHEDULE:RENEW",json));
    }
    @Test void aRecipeCannotRelabelADifferentExactCase(){
        assertThrows(IllegalArgumentException.class,()->RecoverySandboxFixture.parse("formal:pro_monthly:month_1:NO_SCHEDULE:RENEW","{\"caseKey\":\"formal:basic_monthly:month_1:NO_SCHEDULE:RENEW\",\"currency\":\"hkd\",\"unitAmountMinor\":18900,\"introAmountMinor\":1}"));
    }
    @Test void inventoryClassesOutsideTheImplementedSandboxCannotBeRun(){
        assertThrows(IllegalArgumentException.class,()->RecoverySandboxFixture.parse("formal:pro_monthly:month_1:FUTURE_CONTRACT_CHANGE:RENEW","{}"));
    }
}
