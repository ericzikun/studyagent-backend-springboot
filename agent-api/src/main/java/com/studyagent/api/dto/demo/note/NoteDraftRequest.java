package com.studyagent.api.dto.demo.note;

import lombok.Data;

/** 进入笔记页面的会话分配请求体。 */
@Data
public class NoteDraftRequest {

    /** 输出语言（可选，如 zh-CN / chinese）；缺省 english。 */
    private String outputLanguage;
}
