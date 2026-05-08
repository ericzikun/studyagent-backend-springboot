package com.studyagent.service.domain.verla;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Verla Agent 澄清问卷领域对象（V2）。
 * <p>
 * 对应 {@code verla_clarify_forms} 表，由 {@code AGENT_CLARIFY_FORM_ISSUED} 事件创建，
 * 用户提交后由 {@code cmd.clarify.submit} 关闭。
 * 详见 docs/V2/5.1 Java后端 + 数据库 V2 升级技术方案.md §3 / §4。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerlaClarifyForm {

    private Long id;
    /** 业务唯一 ID（form_*），Py 生成 */
    private String formId;
    private Long conversationId;
    private Long turnId;
    /** 产出该 form 的 plan/agent session */
    private Long sessionId;
    /** 关联的 verla_messages.id（assistant 提问那条） */
    private Long messageId;
    private String title;
    private String description;
    /** 动态字段定义 JSON：[{key,label,type,options,required,...}] */
    private String schemaJson;
    /** OPEN / SUBMITTED / EXPIRED / CANCELLED */
    private String status;
    private LocalDateTime expiresAt;
    private LocalDateTime submittedAt;
    /** 回填 verla_clarify_responses.id */
    private Long submittedResponseId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
