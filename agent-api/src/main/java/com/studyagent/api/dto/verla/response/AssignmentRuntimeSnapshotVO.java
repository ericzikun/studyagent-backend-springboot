package com.studyagent.api.dto.verla.response;

import com.studyagent.api.dto.verla.support.VerlaPublicIdVoSupport;
import com.studyagent.service.application.verla.dto.AssignmentRuntimeSnapshotPayloadView;
import com.studyagent.service.application.verla.dto.AssignmentRuntimeSnapshotView;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Frontend recovery contract for a Verla Assignment runtime.
 *
 * The response intentionally exposes a backend event cursor and a real Verla
 * state event type, not frontend-derived UI fields such as phase or activeTurn.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssignmentRuntimeSnapshotVO {

    private String conversationId;
    private Long resumeAfterEventId;
    private String stateEventType;
    private Payload payload;

    public static AssignmentRuntimeSnapshotVO from(AssignmentRuntimeSnapshotView view) {
        AssignmentRuntimeSnapshotPayloadView payloadView = view.payload();
        return AssignmentRuntimeSnapshotVO.builder()
                .conversationId(VerlaPublicIdVoSupport.conversation(view.conversationId(), true))
                .resumeAfterEventId(view.resumeAfterEventId())
                .stateEventType(view.stateEventType())
                .payload(Payload.from(payloadView))
                .build();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Payload {
        /** Chronological message list, ready to hydrate the frontend runtime conversation. */
        private List<VerlaMessageVO> messages;
        /** Sanitized payload of the processed Verla event represented by stateEventType. */
        private Map<String, Object> stateEventPayload;
        /** Latest backend-owned runtime progress, including Assignment generation ETA when available. */
        private Map<String, Object> progress;
        /** Latest folded Assignment workflow canvas nodes; each node may include persisted `detailed` output. */
        private List<Map<String, Object>> agentNodes;
        /** Latest persisted output artifacts for this conversation. */
        private List<VerlaArtifactVO> artifacts;

        public static Payload from(AssignmentRuntimeSnapshotPayloadView view) {
            if (view == null) {
                return Payload.builder()
                        .messages(List.of())
                        .agentNodes(List.of())
                        .artifacts(List.of())
                        .build();
            }
            return Payload.builder()
                    .messages(view.messages() == null
                            ? List.of()
                            : view.messages().stream().map(VerlaMessageVO::from).toList())
                    .stateEventPayload(view.stateEventPayload())
                    .progress(view.progress())
                    .agentNodes(view.agentNodes() == null ? List.of() : view.agentNodes())
                    .artifacts(view.artifacts() == null
                            ? List.of()
                            : view.artifacts().stream().map(VerlaArtifactVO::from).toList())
                    .build();
        }
    }
}
