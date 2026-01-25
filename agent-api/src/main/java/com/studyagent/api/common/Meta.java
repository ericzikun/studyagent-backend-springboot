package com.studyagent.api.common;

import lombok.Data;

/**
 * 响应元数据
 */
@Data
public class Meta {
    private Integer statusCode;
    private String statusMsg;
    
    public static Meta success() {
        Meta meta = new Meta();
        meta.setStatusCode(0);
        meta.setStatusMsg("success");
        return meta;
    }
    
    public static Meta error(String message) {
        Meta meta = new Meta();
        meta.setStatusCode(9999);
        meta.setStatusMsg(message);
        return meta;
    }
    
    public static Meta error(Integer code, String message) {
        Meta meta = new Meta();
        meta.setStatusCode(code);
        meta.setStatusMsg(message);
        return meta;
    }
}

