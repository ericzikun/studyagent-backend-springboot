package com.studyagent.service.domain.quota;

/** Immutable add-on terms captured when the Checkout order is created. */
public record AddonGrantSnapshot(
        Long sourceOrderId,
        String addonCode,
        String featureCode,
        long quotaAmount,
        int validityMonths
) {
}
