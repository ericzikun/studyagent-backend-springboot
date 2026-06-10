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
        List<VerlaArtifact> artifacts,
        /**
         * Chat With Assignment / write 模式当前活跃的 Edit Proposal（刷新恢复用，设计 §4.8）。
         * {@code {state:"generating"|"reviewing", proposalId, targets:[...]}}；无活跃提案时为 null。
         */
        Map<String, Object> artifactEditProposal
) {
}
