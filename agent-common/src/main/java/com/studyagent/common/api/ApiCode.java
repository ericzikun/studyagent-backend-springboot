package com.studyagent.common.api;

import lombok.Getter;

/**
 * API 响应码枚举
 * <p>
 * 统一管理 statusCode、英文/中文消息，目前 API 返回采用英文 messageEn
 */
@Getter
public enum ApiCode {

    SUCCESS(0, "Success", "成功"),

    // 1xxx - 业务参数/状态
    USER_NOT_LOGGED_IN(401, "User not logged in", "用户未登录"),
    PARAM_ERROR(1001, "Parameter error", "参数错误"),
    PARAM_VALIDATION_FAILED(1001, "Parameter validation failed: %s", "参数验证失败: %s"),
    ILLEGAL_STATE(1002, "Illegal state", "状态异常"),
    TASK_NOT_FOUND(1003, "Task not found", "任务不存在"),
    NO_PERMISSION(1004, "No permission", "无权限"),

    // 1020 - 反馈
    FEEDBACK_SESSION_NOT_FOUND(1020, "Feedback session not found", "反馈会话不存在"),
    FEEDBACK_ALREADY_SUBMITTED(1021, "Feedback already submitted", "反馈已提交"),
    FEEDBACK_INVALID_REQUEST(1022, "Invalid feedback: %s", "反馈请求无效: %s"),
    RESUME_TOKEN_INVALID(1023, "Resume token is invalid, expired, or already used", "恢复令牌无效、已过期或已使用"),

    // 1010 - 任务提交额度
    /** 额度超限，支持格式参数: limit */
    QUOTA_EXCEEDED(1010, "Daily task submission limit reached (%d times). Please try again tomorrow.", "今日任务提交次数已达上限（%d 次），请明天再试"),
    /** AI 额度不足，提示去充值 */
    INSUFFICIENT_QUOTA(1011, "Insufficient quota. Please recharge to continue.", "额度不足，请充值后继续"),
    OUTPUT_TYPE_NOT_ALLOWED(1013, "Current plan does not support the requested output type", "当前套餐不支持目标输出类型"),
    FILE_LIMIT_REACHED(1014, "Current plan has reached the file limit for this assignment", "当前套餐已达到该 assignment 的文件上限"),
    ADDON_REQUIRES_PAID_MEMBER(1015, "A paid subscription is required to purchase add-ons", "付费会员才能购买加量包"),
    SUBSCRIPTION_CHANGE_PENDING(1016, "A subscription change is already pending", "已有待处理的订阅变更"),
    SUBSCRIPTION_STATE_INVALID(1017, "The subscription cannot be changed in its current state", "当前订阅状态不可变更"),
    PAYMENT_PROCESSING(1018, "Payment is still processing", "支付处理中"),
    FOLLOWUP_EDIT_LIMIT_REACHED(1019, "Current plan has reached the follow-up edit limit for this assignment", "当前套餐已达到该 assignment 的 follow-up edit 上限"),

    // 4xxx - 文件上传
    FILE_UPLOAD_FAILED(4000, "File upload failed: %s", "文件上传失败: %s"),
    FILE_UPLOAD_STREAM_INTERRUPTED(4001, "File upload failed: transfer interrupted. Please check your network.", "文件上传失败：上传过程中断，请检查网络连接后重试"),
    FILE_UPLOAD_SIZE_EXCEEDED(4002, "File upload failed: file size exceeds limit (max 100MB)", "文件上传失败：文件大小超过限制（最大 100MB）"),

    // 5xx - 服务异常
    BAD_REQUEST(400, "Bad request: %s", "请求错误: %s"),
    INTERNAL_ERROR(500, "Internal server error: %s", "服务器错误: %s"),
    STRIPE_NOT_CONFIGURED(500, "Stripe not configured", "Stripe 未配置"),
    INVALID_PACKAGE_TYPE(400, "Invalid package type: %s", "无效的套餐类型: %s"),
    PRICE_CONFIG_ERROR(500, "Price ID config error for %s package. Please create product in Stripe and set STRIPE_PRICE_%s=price_xxxxx", "套餐配置错误"),
    PRICE_NOT_FOUND(400, "No valid price found for product: %s", "产品下没有找到有效的价格 ID"),
    STRIPE_API_ERROR(500, "Stripe error: %s", "Stripe 错误: %s"),
    PAYMENT_SESSION_CREATE_FAILED(500, "Failed to create checkout session", "创建支付会话失败"),
    SESSION_ID_REQUIRED(400, "sessionId is required", "sessionId 参数不能为空"),
    SESSION_QUERY_FAILED(9999, "Failed to query session status: %s", "查询会话状态失败"),
    INVALID_CHECKOUT_RETURN_URL(400, "Invalid checkout return URL: %s", "无效支付回跳地址: %s"),
    INVALID_PLAN(400, "Invalid subscription plan: %s", "无效订阅套餐: %s"),
    INVALID_ADDON(400, "Invalid add-on package: %s", "无效加量包: %s"),
    SUBSCRIPTION_NOT_FOUND(404, "Subscription not found", "订阅不存在"),
    SUBSCRIPTION_ALREADY_EXISTS(409, "User already has a subscription", "用户已有订阅"),

    // 9999 - 未知/系统异常
    UNKNOWN_ERROR(9999, "Internal server error", "系统异常"),
    UNKNOWN_ERROR_WITH_MSG(9999, "%s", "%s");

    private final int code;
    private final String messageEn;
    private final String messageZh;

    ApiCode(int code, String messageEn, String messageZh) {
        this.code = code;
        this.messageEn = messageEn;
        this.messageZh = messageZh;
    }

    /**
     * 获取英文消息（当前 API 默认使用）
     */
    public String getMessage() {
        return messageEn;
    }

    /**
     * 格式化英文消息
     */
    public String formatEn(Object... args) {
        return args == null || args.length == 0 ? messageEn : String.format(messageEn, args);
    }

    /**
     * 格式化中文消息
     */
    public String formatZh(Object... args) {
        return args == null || args.length == 0 ? messageZh : String.format(messageZh, args);
    }

    /**
     * 根据 code 查找枚举，未找到返回 UNKNOWN_ERROR
     */
    public static ApiCode fromCode(int code) {
        for (ApiCode c : values()) {
            if (c.code == code) {
                return c;
            }
        }
        return UNKNOWN_ERROR;
    }
}
