package com.studyagent.api.service.robot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RobotNotifyBillingServiceTest {

    @Test
    void formatsCountryWithEnglishAndChineseNames() {
        assertEquals("HK · Hong Kong · 中国香港", RobotNotifyBillingService.formatCountry("hk"));
        assertEquals("CN · China · 中国大陆", RobotNotifyBillingService.formatCountry("CN"));
        assertEquals("US · United States · 美国", RobotNotifyBillingService.formatCountry("US"));
        assertEquals("未知 / Unknown", RobotNotifyBillingService.formatCountry(null));
    }
}
