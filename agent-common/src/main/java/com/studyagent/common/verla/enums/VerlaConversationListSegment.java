package com.studyagent.common.verla.enums;

import lombok.Getter;

/**
 * Dashboard / 「全部会话」右侧分栏用的会话维度筛选。
 * <p>
 * 与前端常量 {@code DashboardSectionKey} 对齐语义：
 * <ul>
 *     <li>{@link #ASSIGNMENT} — Assignment（含尚未写入 intent、仍为 ASSIGNMENT 的对话）</li>
 *     <li>{@link #LEARNING} — Learn with Verla / 学习材料类会话（intent 落在 MATERIALS、FLASHCARDS 等）</li>
 *     <li>{@link #AI_WRITING} — AI Detection & Humanizer（合并，兼容旧客户端）</li>
 *     <li>{@link #AI_DETECTION} — AI Detection</li>
 *     <li>{@link #AI_HUMANIZER} — Humanizer</li>
 * </ul>
 * GET {@code /v1/verla/conversations?segment=...} 使用 {@link #queryKey}。
 */
@Getter
public enum VerlaConversationListSegment {

    ASSIGNMENT("assignment"),
    LEARNING("learning"),
    AI_WRITING("ai_writing"),
    AI_DETECTION("ai_detection"),
    AI_HUMANIZER("ai_humanizer");

    private final String queryKey;

    VerlaConversationListSegment(String queryKey) {
        this.queryKey = queryKey;
    }

}
