package com.studyagent.common.quota;

import java.util.Arrays;

/**
 * 功能点编码枚举
 * 与 ai_feature_defs 表中的 feature_code 对应
 */
public enum FeatureCode {

    TASK_CREATE("task_create"),
    AI_DETECTION("ai_detection"),
    HUMANIZER("humanizer"),

    /** Learning Canvas 新产品 Demo（纯免费 + 每次调用记 quota_ledger，见 sql/081_demo_learning_canvas.sql） */
    DEMO_LEARNING_CANVAS("demo_learning_canvas");

    private final String code;

    FeatureCode(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    /**
     * 根据字符串解析枚举
     *
     * @param code feature_code 字符串
     * @return 对应枚举，未找到则抛出 IllegalArgumentException
     */
    public static FeatureCode fromCode(String code) {
        if (code == null || code.isEmpty()) {
            throw new IllegalArgumentException("feature_code cannot be null or empty");
        }
        return Arrays.stream(values())
                .filter(e -> e.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown feature_code: " + code));
    }

    /**
     * 解析枚举，未找到时返回 null（用于可选参数场景）
     */
    public static FeatureCode fromCodeOrNull(String code) {
        if (code == null || code.isEmpty()) {
            return null;
        }
        return Arrays.stream(values())
                .filter(e -> e.code.equals(code))
                .findFirst()
                .orElse(null);
    }
}
