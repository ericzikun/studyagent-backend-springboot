package com.studyagent.api.common;

import com.studyagent.common.log.util.TraceIdUtil;
import lombok.Data;

/**
 * 响应元数据
 */
@Data
public class Meta {
    private Integer statusCode;
    private String statusMsg;
    /**
     * 链路追踪ID，用于问题排查
     * 前端可以将此 ID 提供给后端，方便在日志中定位问题
     */
    private String traceId;
    
    public static Meta success() {
        Meta meta = new Meta();
        meta.setStatusCode(0);
        meta.setStatusMsg("success");
        meta.setTraceId(TraceIdUtil.getTraceId());
        return meta;
    }
    
    public static Meta error(String message) {
        Meta meta = new Meta();
        meta.setStatusCode(9999);
        meta.setStatusMsg(message);
        meta.setTraceId(TraceIdUtil.getTraceId());
        return meta;
    }
    
    public static Meta error(Integer code, String message) {
        Meta meta = new Meta();
        meta.setStatusCode(code);
        meta.setStatusMsg(message);
        meta.setTraceId(TraceIdUtil.getTraceId());
        return meta;
    }
}

