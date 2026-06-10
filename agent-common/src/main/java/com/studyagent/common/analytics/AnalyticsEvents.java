package com.studyagent.common.analytics;

/**
 * PostHog 事件常量定义
 * 所有埋点事件名称统一定义在此
 */
public final class AnalyticsEvents {

    private AnalyticsEvents() {}

    // ==================== 登录相关事件 ====================

    /**
     * 用户登录成功
     */
    public static final String USER_LOGIN_SUCCESS = "user_login_success";

    /**
     * 用户登录失败
     */
    public static final String USER_LOGIN_FAILED = "user_login_failed";

    /**
     * 用户获取信息（首次访问/刷新 token）
     */
    public static final String USER_GET_INFO = "user_get_info";

    // ==================== 购买相关事件 ====================

    /**
     * 创建支付会话（用户发起支付）
     */
    public static final String PAYMENT_SESSION_CREATED = "payment_session_created";

    /**
     * 支付会话创建失败
     */
    public static final String PAYMENT_SESSION_FAILED = "payment_session_failed";

    /**
     * 支付完成（Webhook 回调确认）
     */
    public static final String PAYMENT_COMPLETED = "payment_completed";

    /**
     * 充值成功
     */
    public static final String RECHARGE_SUCCESS = "recharge_success";

    /**
     * 查询支付状态
     */
    public static final String PAYMENT_STATUS_CHECKED = "payment_status_checked";

    // ==================== Assignment 生成相关事件 ====================

    public static final String ASSIGNMENT_GENERATION_STARTED = "assignment:generation:started";
    public static final String ASSIGNMENT_GENERATION_SUCCEEDED = "assignment:generation:succeeded";
    public static final String ASSIGNMENT_GENERATION_FAILED = "assignment:generation:failed";

    // ==================== 支付 alias 事件（与前端冒号命名对齐，保留老事件兼容） ====================

    public static final String BILLING_CHECKOUT_SESSION_CREATED = "billing:checkout:session_created";
    public static final String BILLING_CHECKOUT_SESSION_FAILED = "billing:checkout:session_failed";
    public static final String BILLING_PAYMENT_SUCCEEDED = "billing:payment:succeeded";
}