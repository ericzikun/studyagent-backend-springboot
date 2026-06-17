package com.studyagent.service.application.verla.entitlement;

import java.util.Set;

public record EffectiveEntitlements(
        String planCode,
        String tier,
        Integer maxFiles,
        Integer maxFollowupEdits,
        Set<String> allowedOutputTypes) {
}
