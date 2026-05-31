package com.studyagent.service.domain.verla;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Verla Message 领域对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerlaMessage {

    private Long id;
    private Long conversationId;
    private Long turnId;
    /** user / assistant / system / agent_workforce */
    private String role;
    private Long sourceSessionId;
    private String textContent;
    private String blocksJson;
    private String attachmentsJson;
    private String metaJson;
    private LocalDateTime createdAt;
}
