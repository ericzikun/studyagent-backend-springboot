package com.studyagent.service.application.verla.dto;

import lombok.Builder;

/**
 * Single snapshot used by the frontend to recover a Verla Assignment page after
 * refresh. {@code stateEventType} is a real Verla event type, while
 * {@code resumeAfterEventId} is only an SSE continuation cursor.
 */
@Builder
public record AssignmentRuntimeSnapshotView(
        Long conversationId,
        Long resumeAfterEventId,
        String stateEventType,
        AssignmentRuntimeSnapshotPayloadView payload
) {
}
