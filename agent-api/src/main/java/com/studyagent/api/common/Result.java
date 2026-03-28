package com.studyagent.api.common;

import com.studyagent.common.api.ApiCode;
import lombok.Data;

/**
 * 统一响应格式
 */
@Data
public class Result<T> {
    private Meta meta;
    private T data;

    public static <T> Result<T> success(T data) {
        Result<T> result = new Result<>();
        result.setMeta(Meta.success());
        result.setData(data);
        return result;
    }

    /**
     * 成功响应，并标识是否发生了额度扣减。
     * 当 quotaConsumed 为 true 时，前端应刷新用户额度、账单等信息展示。
     * 未扣减时返回 quotaConsumed: false。
     *
     * @param quotaConsumed 本请求是否发生了额度扣减
     */
    public static <T> Result<T> success(T data, boolean quotaConsumed) {
        Result<T> result = new Result<>();
        result.setMeta(Meta.successWithQuotaFlag(quotaConsumed));
        result.setData(data);
        return result;
    }

    public static <T> Result<T> error(String message) {
        Result<T> result = new Result<>();
        result.setMeta(Meta.error(ApiCode.UNKNOWN_ERROR.getCode(), message));
        return result;
    }

    public static <T> Result<T> error(Integer code, String message) {
        Result<T> result = new Result<>();
        result.setMeta(Meta.error(code, message));
        return result;
    }

    /**
     * 使用 ApiCode 枚举返回错误（统一消息管理，当前采用英文）
     */
    public static <T> Result<T> error(ApiCode apiCode) {
        Result<T> result = new Result<>();
        result.setMeta(Meta.error(apiCode.getCode(), apiCode.getMessage()));
        return result;
    }

    /**
     * 使用 ApiCode 枚举返回错误，支持消息格式化参数（当前采用英文）
     */
    public static <T> Result<T> error(ApiCode apiCode, Object... args) {
        Result<T> result = new Result<>();
        result.setMeta(Meta.error(apiCode.getCode(), apiCode.formatEn(args)));
        return result;
    }
}

