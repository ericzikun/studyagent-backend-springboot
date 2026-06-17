package com.studyagent.service.domain.verla.repo;

import com.studyagent.service.domain.verla.FollowupEditUsage;

public interface FollowupEditUsageRepository {

    FollowupEditUsage findByUserMessageId(Long userMessageId);

    FollowupEditUsage findByAssignmentChatSessionId(Long assignmentChatSessionId);

    long countActiveByAssignmentSessionId(Long assignmentSessionId);

    FollowupEditUsage save(FollowupEditUsage usage);

    FollowupEditUsage updateState(Long userMessageId,
                                  String state,
                                  Long assignmentChatSessionId,
                                  String releaseReason);
}
