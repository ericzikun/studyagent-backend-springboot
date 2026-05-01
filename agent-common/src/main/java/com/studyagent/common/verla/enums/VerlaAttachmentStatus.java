package com.studyagent.common.verla.enums;

/**
 * 附件解析状态机。
 * <p>
 * 状态推进：UPLOADED →（finalize 触发 cmd.attachment.parse）→ PARSING
 *           → PARSED（attached 主 artifact） / FAILED。
 * 对应文档 docs/V2/5.1 Java后端 + 数据库 V2 升级技术方案.md §3 / §6。
 */
public enum VerlaAttachmentStatus {

    UPLOADED,
    PARSING,
    PARSED,
    FAILED;

    public boolean isTerminal() {
        return this == PARSED || this == FAILED;
    }

    public static VerlaAttachmentStatus fromCode(String code) {
        if (code == null) {
            return UPLOADED;
        }
        for (VerlaAttachmentStatus s : values()) {
            if (s.name().equalsIgnoreCase(code)) {
                return s;
            }
        }
        throw new IllegalArgumentException("unknown attachment status: " + code);
    }
}
