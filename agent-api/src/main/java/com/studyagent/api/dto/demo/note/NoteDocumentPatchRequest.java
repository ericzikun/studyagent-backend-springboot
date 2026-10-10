package com.studyagent.api.dto.demo.note;

import lombok.Data;

/** 右侧编辑器保存笔记的请求体（乐观锁：baseVersion 不匹配即拒绝）。 */
@Data
public class NoteDocumentPatchRequest {

    /** 用户编辑后的笔记 Markdown 全文。 */
    private String contentMd;

    /** 客户端持有的基线版本号；缺省表示不做版本校验。 */
    private Long baseVersion;
}
