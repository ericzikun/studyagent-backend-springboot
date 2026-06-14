package com.studyagent.service.application.verla.dto;

import com.studyagent.service.domain.verla.VerlaArtifact;
import com.studyagent.service.domain.verla.VerlaMessage;
import lombok.Builder;

import java.util.List;
import java.util.Map;

@Builder
public record AiWritingRuntimeSnapshotPayloadView(
        List<VerlaMessage> messages,
        Map<String, Object> stateEventPayload,
        Map<String, Object> progress,
        List<VerlaArtifact> artifacts,
        String primaryIntent
) {
}
