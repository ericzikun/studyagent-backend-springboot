package com.studyagent.service.domain.quota;

public enum QuotaLedgerDisplayType {
    FREE_REFRESH("free_refresh"),
    PLAN_REFRESH("plan_refresh"),
    PLAN_GRANT("plan_grant"),
    ADDON_EXPIRE("addon_expire"),
    CLEAR("clear"),
    EXPIRE("expire"),
    ADDON_GRANT("addon_grant"),
    USAGE("usage"),
    REFUND("refund");

    private final String code;

    QuotaLedgerDisplayType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static QuotaLedgerDisplayType fromLedgerType(String ledgerType, Long amount) {
        if (ledgerType != null) {
            String normalized = ledgerType.trim().toLowerCase();
            if (!normalized.isEmpty()) {
                return switch (normalized) {
                    case "consume", "usage" -> USAGE;
                    case "refund" -> REFUND;
                    case "free_refresh" -> FREE_REFRESH;
                    case "plan_refresh" -> PLAN_REFRESH;
                    case "plan_reset", "upgrade_grant" -> PLAN_GRANT;
                    case "addon_expired" -> ADDON_EXPIRE;
                    case "plan_clear" -> CLEAR;
                    case "plan_expired" -> EXPIRE;
                    case "addon_grant", "recharge" -> ADDON_GRANT;
                    default -> fallbackFromAmount(amount);
                };
            }
        }
        return fallbackFromAmount(amount);
    }

    private static QuotaLedgerDisplayType fallbackFromAmount(Long amount) {
        return amount != null && amount < 0 ? USAGE : ADDON_GRANT;
    }
}
