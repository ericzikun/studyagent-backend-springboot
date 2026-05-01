package com.studyagent.common.verla.enums;

/**
 * Artifact（卡片/材料终稿）状态。
 * <p>
 * - PENDING：占位（如附件解析中预占的 markdown 全文 artifact）。
 * - READY：可渲染、可被 hydrate 注入上下文。
 * - FAILED：解析或生成失败，前端只显示错误占位。
 * <p>
 * 对应文档 docs/V2/5.1 Java后端 + 数据库 V2 升级技术方案.md §3。
 */
public enum VerlaArtifactStatus {

    PENDING,
    READY,
    FAILED;

    public static VerlaArtifactStatus fromCode(String code) {
        if (code == null) {
            return READY;
        }
        for (VerlaArtifactStatus s : values()) {
            if (s.name().equalsIgnoreCase(code)) {
                return s;
            }
        }
        return READY;
    }
}
