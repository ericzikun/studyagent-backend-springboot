package com.studyagent.common.verla.enums;

/**
 * Verla session 类型
 * <p>
 * 对应文档 docs/verla-Java侧MVP技术方案.md §0 / §6.3
 */
public enum VerlaSessionKind {

    /** 意图识别 */
    PLAN,

    /** 作业/Assignment 执行 */
    ASSIGNMENT,

    /** 文件对话 */
    FILE_CHAT,

    /** 学习材料生成 */
    MATERIALS
}
