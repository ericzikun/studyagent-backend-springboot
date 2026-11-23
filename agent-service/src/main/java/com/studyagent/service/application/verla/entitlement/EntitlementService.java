package com.studyagent.service.application.verla.entitlement;

import com.studyagent.service.domain.verla.FollowupEditUsage;

import java.util.List;
import java.util.Map;

public interface EntitlementService {

    EffectiveEntitlements getEffectiveEntitlements(String clerkUserId);

    void assertAssignmentOutputAllowed(String clerkUserId, Map<String, Object> requirementForm);

    void assertAssignmentOutputAllowed(EffectiveEntitlements entitlements, Map<String, Object> requirementForm);

    /** 签名期只保留防滥用硬上限，不抛商业化错误，避免弹窗早于追问表单出现。 */
    void assertCanReserveUserUpload(String clerkUserId, Long conversationId);

    /** 套餐文件上限，在 assignment finalize（confirm/skip）时判定。 */
    void assertAssignmentFileLimit(EffectiveEntitlements entitlements, Long conversationId);

    FollowupEditUsage reserveFollowupEdit(String clerkUserId, Long conversationId,
                                          Long userMessageId, List<String> artifactUids);

    void bindFollowupEditSession(Long userMessageId, Long assignmentChatSessionId);

    void markFollowupEditCompleted(Long assignmentChatSessionId);

    void releaseFollowupEdit(Long assignmentChatSessionId, String reason);
}
