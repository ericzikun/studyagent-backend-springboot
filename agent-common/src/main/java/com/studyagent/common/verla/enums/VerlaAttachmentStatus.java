package com.studyagent.common.verla.enums;

import lombok.extern.slf4j.Slf4j;

/**
 * 附件解析状态机。
 * <p>
 * 状态推进：UPLOADED →（finalize 触发 cmd.attachment.parse）→ PARSING
 *           → PARSED（attached 主 artifact） / FAILED。
 * 对应文档 docs/V2/5.1 Java后端 + 数据库 V2 升级技术方案.md §3 / §6。
 */
@Slf4j
public enum VerlaAttachmentStatus {

    UPLOADED,
    PARSING,
    PARSED,
    FAILED;

    public boolean isTerminal() {
        return this == PARSED || this == FAILED;
    }

    /**
     * Parse a status code emitted by the Python side.
     * <p>
     * Unknown non-null codes are mapped to {@link #FAILED} (with a warning) instead of
     * throwing, so that a future status added on the Python side is treated as terminal
     * and cannot stall the turn gate waiting forever.
     */
    public static VerlaAttachmentStatus fromCode(String code) {
        if (code == null) {
            return UPLOADED;
        }
        for (VerlaAttachmentStatus s : values()) {
            if (s.name().equalsIgnoreCase(code)) {
                return s;
            }
        }
        log.warn("unknown attachment status: {}, falling back to FAILED", code);
        return FAILED;
    }
}
