package com.studyagent.service.domain.verla;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FollowupEditUsage {

    public static final String STATE_RESERVED = "RESERVED";
    public static final String STATE_COMPLETED = "COMPLETED";
    public static final String STATE_RELEASED = "RELEASED";

    private Long id;
    private Long conversationId;
    private Long assignmentSessionId;
    private String clerkUserId;
    private Long userMessageId;
    private Long assignmentChatSessionId;
    private String state;
    private String releaseReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
