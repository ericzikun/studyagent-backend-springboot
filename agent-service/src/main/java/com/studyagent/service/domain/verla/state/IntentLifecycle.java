package com.studyagent.service.domain.verla.state;

/**
 * Conversation 上「领域意图是否已对用户确认」的生命周期。
 * <p>
 * 与 verla_conversations.intent_lifecycle 列字面量一一对应（小写 snake_case）。
 */
public enum IntentLifecycle {

    NONE("none"),
    AWAITING_USER_CONFIRMATION("awaiting_user_confirmation"),
    COMMITTED("committed");

    private final String dbValue;

    IntentLifecycle(String dbValue) {
        this.dbValue = dbValue;
    }

    public String getDbValue() {
        return dbValue;
    }

    public static IntentLifecycle fromDb(String raw) {
        if (raw == null || raw.isBlank()) {
            return NONE;
        }
        for (IntentLifecycle s : values()) {
            if (s.dbValue.equalsIgnoreCase(raw.trim())) {
                return s;
            }
        }
        return NONE;
    }

    /**
     * 对用户可见的「草稿」：Py 已完成意图路由，用户在 Dashboard 上尚未对该意图按下确认。
     */
    public static boolean conversationIsDraft(String intentLifecycleRaw) {
        return fromDb(intentLifecycleRaw) == AWAITING_USER_CONFIRMATION;
    }
}
