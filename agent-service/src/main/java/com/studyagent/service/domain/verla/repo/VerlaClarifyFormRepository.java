package com.studyagent.service.domain.verla.repo;

import com.studyagent.service.domain.verla.VerlaClarifyForm;

import java.util.List;

/**
 * Verla 澄清问卷仓储接口（V2）。
 * <p>
 * 详见 docs/V2/5.1 §3 / §4。
 */
public interface VerlaClarifyFormRepository {

    /**
     * 由 {@code AGENT_CLARIFY_FORM_ISSUED} 事件驱动，按 formId 幂等 upsert。
     * - 不存在 -> insert，status=OPEN。
     * - 存在 -> 更新可变字段（schemaJson / title / description / expiresAt / messageId）。
     */
    VerlaClarifyForm upsertByFormId(VerlaClarifyForm form);

    VerlaClarifyForm findByFormId(String formId);

    VerlaClarifyForm findById(Long id);

    /** 当前 conversation 下尚未关闭的问卷，前端入会时可批量恢复 */
    List<VerlaClarifyForm> findOpenByConversation(Long conversationId);

    /**
     * 关闭问卷：写 submittedAt + submittedResponseId + status=SUBMITTED。
     * 仅当现状态为 OPEN 时才允许。
     */
    int markSubmitted(String formId, Long submittedResponseId);

    /** 标记过期或被取消（OPEN -> EXPIRED / CANCELLED） */
    int markStatus(String formId, String newStatus);
}
