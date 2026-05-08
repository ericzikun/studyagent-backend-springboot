package com.studyagent.api.dto.verla.request;

import lombok.Data;

@Data
public class PatchConversationRequest {

    /** 改标题；为 null 时不修改 */
    private String title;

    /** true=归档；false=恢复；null 不变 */
    private Boolean archive;
}
