package com.studyagent.infra.service.billing;

import java.util.Set;

/** Canonical release unit. No aliases, wildcard cohorts or cross-plan equivalence. */
record RecoveryCaseKey(String sourceKey, String targetPlanCode, String interval, int intervalCount,
        String scheduleClass, String cancelMode) {
    RecoveryCaseKey {
        if (!Set.of("month", "year").contains(interval) || intervalCount != 1
                || !Set.of("NO_SCHEDULE", "EQUIVALENT_SCHEDULE").contains(scheduleClass)
                || !Set.of("RENEW", "PERIOD_END").contains(cancelMode)) throw invalid();
        String suffix = "month".equals(interval) ? "monthly" : "yearly";
        if (!Set.of("basic_" + suffix, "plus_" + suffix, "pro_" + suffix).contains(targetPlanCode)) throw invalid();
        if (!"formal".equals(sourceKey)) {
            String tier = targetPlanCode.substring(0, targetPlanCode.indexOf('_'));
            boolean originalTarget=(tier + "_trial_to_" + suffix).equals(sourceKey);
            // SQL 085 and the repair job retain this original source while moving its actual target to monthly.
            boolean repairedTarget="pro_trial_to_yearly".equals(sourceKey) && "pro_monthly".equals(targetPlanCode);
            if (!Set.of("basic", "pro").contains(tier) || !(originalTarget || repairedTarget)) throw invalid();
        }
    }
    String value() { return sourceKey + ":" + targetPlanCode + ":" + interval + "_" + intervalCount + ":" + scheduleClass + ":" + cancelMode; }
    boolean verifiedBy(Set<String> verified) { return verified != null && verified.contains(value()); }
    static RecoveryCaseKey parse(String key) {
        if (key == null) throw invalid();
        String[] pieces = key.split(":", -1);
        if (pieces.length != 5) throw invalid();
        String[] period = pieces[2].split("_", -1);
        if (period.length != 2) throw invalid();
        try {
            var parsed = new RecoveryCaseKey(pieces[0], pieces[1], period[0], Integer.parseInt(period[1]), pieces[3], pieces[4]);
            if (!parsed.value().equals(key)) throw invalid();
            return parsed;
        } catch (NumberFormatException e) { throw invalid(); }
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid exact payment recovery caseKey"); }
}
