package com.studyagent.service.domain.quota;

public enum QuotaLedgerPlanTier {
    FREE("free"),
    BASIC("basic"),
    PLUS("plus"),
    PRO("pro");

    private final String code;

    QuotaLedgerPlanTier(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static QuotaLedgerPlanTier fromPlanCode(String planCode) {
        if (planCode == null || planCode.isBlank()) {
            return null;
        }

        String normalized = planCode.trim().toLowerCase();
        int separator = normalized.indexOf('_');
        String tierCode = separator >= 0 ? normalized.substring(0, separator) : normalized;

        return switch (tierCode) {
            case "free" -> FREE;
            case "basic" -> BASIC;
            case "plus" -> PLUS;
            case "pro" -> PRO;
            default -> null;
        };
    }
}
