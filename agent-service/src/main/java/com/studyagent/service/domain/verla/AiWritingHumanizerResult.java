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
public class AiWritingHumanizerResult {

    private Long id;
    private String clerkUserId;
    private Long conversationId;
    private Long sessionId;
    private String artifactUid;
    private String resultHash;
    private String resultText;
    private LocalDateTime createdAt;
}
