package com.studyagent.api.dto.demo.note;

import lombok.Data;

/** 生成笔记的请求体：文件与文本二选一。 */
@Data
public class NoteGenerateRequest {

    /** {@code file} 或 {@code text}；缺省时按 objectId / text 是否存在推断。 */
    private String sourceType;

    /** sourceType=file 时的附件 objectId（由 /v1/verla/v2/uploads 链路产出）。 */
    private String objectId;

    /** sourceType=text 时粘贴的原始文本。 */
    private String text;

    /** 文件展示名（可选，仅用于来源标签与初始标题）。 */
    private String filename;

    /** 输出语言（可选，如 zh-CN / chinese）；缺省沿用会话偏好。 */
    private String outputLanguage;
}
