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

