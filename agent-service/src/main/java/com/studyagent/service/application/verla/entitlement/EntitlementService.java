package com.studyagent.service.application.verla.entitlement;

import com.studyagent.service.domain.verla.FollowupEditUsage;

import java.util.List;
import java.util.Map;

public interface EntitlementService {

    EffectiveEntitlements getEffectiveEntitlements(String clerkUserId);

    void assertAssignmentOutputAllowed(String clerkUserId, Map<String, Object> requirementForm);

    void assertCanReserveUserUpload(String clerkUserId, Long conversationId);

    FollowupEditUsage reserveFollowupEdit(String clerkUserId, Long conversationId,
                                          Long userMessageId, List<String> artifactUids);

    void bindFollowupEditSession(Long userMessageId, Long assignmentChatSessionId);

    void markFollowupEditCompleted(Long assignmentChatSessionId);

    void releaseFollowupEdit(Long assignmentChatSessionId, String reason);
}
