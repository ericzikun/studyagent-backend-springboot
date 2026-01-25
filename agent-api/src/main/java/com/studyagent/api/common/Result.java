package com.studyagent.api.common;

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
        result.setMeta(Meta.error(message));
        return result;
    }
    
    public static <T> Result<T> error(Integer code, String message) {
        Result<T> result = new Result<>();
        result.setMeta(Meta.error(code, message));
        return result;
    }
}

