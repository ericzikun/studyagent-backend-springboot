package com.studyagent.api.dto.verla.response;

import com.studyagent.api.dto.verla.support.VerlaPublicIdVoSupport;
import com.studyagent.service.application.verla.dto.AiWritingRuntimeSnapshotPayloadView;
import com.studyagent.service.application.verla.dto.AiWritingRuntimeSnapshotView;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiWritingRuntimeSnapshotVO {

    private String conversationId;
    private Long resumeAfterEventId;
    private String stateEventType;
    private Payload payload;

    public static AiWritingRuntimeSnapshotVO from(AiWritingRuntimeSnapshotView view) {
        AiWritingRuntimeSnapshotPayloadView payloadView = view.payload();
        return AiWritingRuntimeSnapshotVO.builder()
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
        private List<VerlaMessageVO> messages;
        private Map<String, Object> stateEventPayload;
        private Map<String, Object> progress;
        private List<VerlaArtifactVO> artifacts;
        private String primaryIntent;
        private String title;

        public static Payload from(AiWritingRuntimeSnapshotPayloadView view) {
            if (view == null) {
                return Payload.builder()
                        .messages(List.of())
                        .artifacts(List.of())
                        .build();
            }
            return Payload.builder()
                    .messages(view.messages() == null
                            ? List.of()
                            : view.messages().stream().map(VerlaMessageVO::from).toList())
                    .stateEventPayload(view.stateEventPayload())
                    .progress(view.progress())
                    .artifacts(view.artifacts() == null
                            ? List.of()
                            : view.artifacts().stream().map(VerlaArtifactVO::from).toList())
                    .primaryIntent(view.primaryIntent())
                    .title(view.title())
                    .build();
        }
    }
}
