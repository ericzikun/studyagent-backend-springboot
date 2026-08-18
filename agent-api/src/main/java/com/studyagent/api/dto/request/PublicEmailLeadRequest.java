package com.studyagent.api.dto.request;

import lombok.Data;

/**
 * 公开邮箱留资请求。
 *
 * <p>companyWebsite 是不可见蜜罐字段，正常用户必须留空；业务校验在应用服务中执行，确保蜜罐命中时优先静默返回。</p>
 */
@Data
public class PublicEmailLeadRequest {

    private String email;
    private String source;
    private String companyWebsite;
}
