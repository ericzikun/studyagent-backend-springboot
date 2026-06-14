package com.studyagent.service.application.verla.dto;

import lombok.Builder;

@Builder
public record AiWritingRuntimeSnapshotView(
        Long conversationId,
        Long resumeAfterEventId,
        String stateEventType,
        AiWritingRuntimeSnapshotPayloadView payload
) {
}
