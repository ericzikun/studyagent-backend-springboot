package com.studyagent.service.application.verla.dto;

import com.studyagent.service.domain.verla.VerlaArtifact;
import com.studyagent.service.domain.verla.VerlaMessage;
import lombok.Builder;

import java.util.List;
import java.util.Map;

/**
 * Backend-owned current-state data needed to restore the Assignment runtime UI.
 *
 * The payload deliberately keeps frontend UI state out of persistence. Messages
 * and artifacts come from their existing tables; state event payload is the
 * current phase checkpoint payload; agent nodes are folded from processed
 * Verla events; progress is folded from the latest backend ETA event.
 */
@Builder
public record AssignmentRuntimeSnapshotPayloadView(
        List<VerlaMessage> messages,
        Map<String, Object> stateEventPayload,
        Map<String, Object> progress,
        List<Map<String, Object>> agentNodes,
        List<VerlaArtifact> artifacts
) {
}
