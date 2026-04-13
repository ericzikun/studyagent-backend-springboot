package com.studyagent.api.service.robot;

/**
 * 内部机器人推送与钉钉 {@code targets.<key>} 的映射类别。
 * <ul>
 *   <li>{@link #ASSIGNMENT} — Stripe 付费成功/失败/退出付款（作业侧额度与商业播报）</li>
 *   <li>{@link #FEEDBACK} — 用户反馈提交</li>
 *   <li>{@link #REPORT} — 定时/手动的数据日报、周报</li>
 *   <li>{@link #DEFAULT} — 未分类，使用 {@code notify.default-target}</li>
 * </ul>
 */
public enum RobotNotifyRouteKind {
    ASSIGNMENT,
    FEEDBACK,
    REPORT,
    DEFAULT
}
