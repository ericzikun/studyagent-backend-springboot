package com.studyagent.service.domain.verla.state;

/**
 * Verla Conversation 状态枚举
 * <p>
 * 与 verla_conversations.status 列字面量一一对应（小写）。
 * 状态机详见 docs/verla-Java侧MVP技术方案.md §11.1。
 */
public enum ConversationStatus {

    ACTIVE("active"),
    ARCHIVED("archived"),
    DELETED("deleted");

    private final String dbValue;

    ConversationStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String getDbValue() {
        return dbValue;
    }

    public static ConversationStatus fromDb(String dbValue) {
        if (dbValue == null) {
            return null;
        }
        for (ConversationStatus s : values()) {
            if (s.dbValue.equalsIgnoreCase(dbValue)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown conversation status: " + dbValue);
    }
}
