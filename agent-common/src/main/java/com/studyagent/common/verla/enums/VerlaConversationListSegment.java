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
 *     <li>{@link #NOTE} — AI Note（笔记 demo 域，intent = NOTE_MAKER）</li>
 *     <li>{@link #VIDEO_TUTOR} — AI 画板（交互式白板教学域，intent = video_tutor）</li>
 * </ul>
 * GET {@code /v1/verla/conversations?segment=...} 使用 {@link #queryKey}。
 */
@Getter
public enum VerlaConversationListSegment {

    ASSIGNMENT("assignment"),
    LEARNING("learning"),
    AI_WRITING("ai_writing"),
    AI_DETECTION("ai_detection"),
    AI_HUMANIZER("ai_humanizer"),
    NOTE("note"),
    /**
     * 值是**小写** {@code video_tutor}：画板建会话时前端传的就是这个小写串，而
     * {@code VerlaConversationService#create} 只 trim 不做大小写归一，库里存的就是它。
     * （其余分栏的 intent 都是大写，别照抄。）
     */
    VIDEO_TUTOR("video_tutor");

    private final String queryKey;

    VerlaConversationListSegment(String queryKey) {
        this.queryKey = queryKey;
    }

}
