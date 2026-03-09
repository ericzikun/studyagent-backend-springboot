package com.studyagent.api.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.studyagent.common.log.util.TraceIdUtil;
import lombok.Data;

/**
 * 响应元数据
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Meta {
    private Integer statusCode;
    private String statusMsg;
    /**
     * 链路追踪ID，用于问题排查
     * 前端可以将此 ID 提供给后端，方便在日志中定位问题
     */
    private String traceId;
    /**
     * 本请求是否发生了额度扣减。
     * true：发生了扣减，前端应刷新用户额度、账单等相关展示；
     * false：未扣减（如 admin/白名单豁免）。
     * 仅涉及额度的接口会返回该字段。
     */
    private Boolean quotaConsumed;
    
    public static Meta success() {
        Meta meta = new Meta();
        meta.setStatusCode(0);
        meta.setStatusMsg("success");
        meta.setTraceId(TraceIdUtil.getTraceId());
        return meta;
    }
    
    /**
     * 成功且需标识额度扣减状态时使用（涉及额度的接口：task submit、humanizer 等）
     *
     * @param quotaConsumed true=发生扣减，false=未扣减
     */
    public static Meta successWithQuotaFlag(boolean quotaConsumed) {
        Meta meta = success();
        meta.setQuotaConsumed(quotaConsumed);
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

