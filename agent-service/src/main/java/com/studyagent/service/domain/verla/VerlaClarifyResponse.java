package com.studyagent.service.domain.verla;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Verla 澄清问卷用户响应领域对象（V2）。
 * <p>
 * 对应 {@code verla_clarify_responses} 表。提交时 Java 写入并下发 {@code cmd.clarify.submit}
 * 给 Py，Py 据此重新进入 plan/agent session。
 * 详见 docs/V2/5.1 Java后端 + 数据库 V2 升级技术方案.md §3 / §4。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerlaClarifyResponse {

    private Long id;
    /** 业务唯一 ID（resp_*），Java 生成 */
    private String responseUid;
    /** 关联 verla_clarify_forms.form_id */
    private String formId;
    private Long conversationId;
    private Long turnId;
    /** 提交者 clerkUserId */
    private String userId;
    /** {key: value} JSON，对齐 schema */
    private String answersJson;
    private LocalDateTime submittedAt;
}
