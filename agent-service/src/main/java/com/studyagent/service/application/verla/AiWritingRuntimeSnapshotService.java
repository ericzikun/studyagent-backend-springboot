package com.studyagent.service.application.verla;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.verla.enums.VerlaAgentEventType;
import com.studyagent.common.verla.envelope.VerlaEventEnvelope;
import com.studyagent.service.application.verla.dto.AiWritingRuntimeSnapshotPayloadView;
import com.studyagent.service.application.verla.dto.AiWritingRuntimeSnapshotView;
import com.studyagent.service.domain.verla.VerlaArtifact;
import com.studyagent.service.domain.verla.VerlaConversation;
import com.studyagent.service.domain.verla.VerlaEventInbox;
import com.studyagent.service.domain.verla.VerlaMessage;
import com.studyagent.service.domain.verla.repo.VerlaArtifactRepository;
import com.studyagent.service.domain.verla.repo.VerlaConversationRepository;
import com.studyagent.service.domain.verla.repo.VerlaEventInboxRepository;
import com.studyagent.service.domain.verla.repo.VerlaMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds AI Detection / Humanizer runtime recovery snapshot from existing Verla tables.
 */
@Service
@RequiredArgsConstructor
public class AiWritingRuntimeSnapshotService {

    private static final int MESSAGE_LIMIT = 50;
    private static final int EVENT_SCAN_LIMIT = 300;

    private final VerlaConversationRepository conversationRepository;
    private final VerlaMessageRepository messageRepository;
    private final VerlaArtifactRepository artifactRepository;
    private final VerlaEventInboxRepository eventInboxRepository;
    private final ObjectMapper objectMapper;

    public AiWritingRuntimeSnapshotView getSnapshot(Long conversationId) {
        VerlaConversation conversation = conversationRepository.findById(conversationId);
        List<VerlaEventInbox> recentEvents =
                eventInboxRepository.findRecentProcessedByConversation(conversationId, EVENT_SCAN_LIMIT);
        Long resumeAfterEventId = recentEvents == null || recentEvents.isEmpty()
                ? 0L
                : recentEvents.get(0).getId();

        List<VerlaMessage> messages = chronologicalMessages(
                messageRepository.findByCursor(conversationId, null, MESSAGE_LIMIT));
        List<VerlaArtifact> artifacts = artifactRepository.findByConversation(conversationId);
        ResolvedStateEvent stateEvent = resolveStateEvent(recentEvents);

        return AiWritingRuntimeSnapshotView.builder()
                .conversationId(conversationId)
                .resumeAfterEventId(resumeAfterEventId)
                .stateEventType(stateEvent == null ? null : stateEvent.stateEventType())
                .payload(AiWritingRuntimeSnapshotPayloadView.builder()
                        .messages(messages)
                        .stateEventPayload(stateEvent == null ? null : stateEvent.payload())
                        .progress(resolveProgress(recentEvents, stateEvent))
                        .artifacts(artifacts == null ? List.of() : artifacts)
                        .primaryIntent(conversation == null ? null : conversation.getPrimaryIntent())
                        .title(conversation == null ? null : conversation.getTitle())
                        .build())
                .build();
    }

    private Map<String, Object> resolveProgress(
            List<VerlaEventInbox> recentEvents,
            ResolvedStateEvent stateEvent) {
        if (stateEvent != null && isDispatchQueued(stateEvent.stateEventType())) {
            Map<String, Object> progress = new LinkedHashMap<>();
            Object queuePosition = stateEvent.payload().get("queuePosition");
            if (queuePosition instanceof Number number) {
                progress.put("queuePosition", number.intValue());
            }
            progress.put("label", formatDispatchQueuedLabel(queuePosition));
            return progress;
        }
        if (recentEvents == null || recentEvents.isEmpty()) {
            return null;
        }
        for (VerlaEventInbox event : recentEvents) {
            VerlaAgentEventType type = parseEventType(event.getEventType());
            if (type != VerlaAgentEventType.AGENT_PROGRESS) {
                continue;
            }
            Map<String, Object> payload = sanitizedPayload(event);
            if (payload.isEmpty()) {
                continue;
            }
            Map<String, Object> progress = new LinkedHashMap<>();
            Object label = payload.get("label");
            if (label != null) {
                progress.put("label", String.valueOf(label));
            }
            Object percent = payload.get("percent");
            if (percent instanceof Number number) {
                progress.put("percent", number.intValue());
            }
            Object chunkIndex = payload.get("chunkIndex");
            if (chunkIndex instanceof Number number) {
                progress.put("chunkIndex", number.intValue());
            }
            Object chunkTotal = payload.get("chunkTotal");
            if (chunkTotal instanceof Number number) {
                progress.put("chunkTotal", number.intValue());
            }
            return progress;
        }
        return null;
    }

    private static String formatDispatchQueuedLabel(Object queuePosition) {
        if (queuePosition instanceof Number number && number.intValue() > 0) {
            return "Waiting in queue (" + number.intValue() + " ahead)…";
        }
        return "Waiting for an available slot…";
    }

    private static boolean isDispatchQueued(String stateEventType) {
        return VerlaAgentEventType.AI_DETECTION_RUN_DISPATCH_QUEUED.name().equals(stateEventType)
                || VerlaAgentEventType.AI_HUMANIZER_RUN_DISPATCH_QUEUED.name().equals(stateEventType);
    }

    private List<VerlaMessage> chronologicalMessages(List<VerlaMessage> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<VerlaMessage> messages = new ArrayList<>(rows);
        messages.sort((a, b) -> Long.compare(nullToZero(a.getId()), nullToZero(b.getId())));
        return messages;
    }

    private ResolvedStateEvent resolveStateEvent(List<VerlaEventInbox> recentEvents) {
        if (recentEvents == null || recentEvents.isEmpty()) {
            return null;
        }
        for (VerlaEventInbox event : recentEvents) {
            VerlaAgentEventType type = parseEventType(event.getEventType());
            if (type == null) {
                continue;
            }
            String resolved = mapStateEventType(type);
            if (resolved != null) {
                return new ResolvedStateEvent(resolved, sanitizedPayload(event));
            }
        }
        return null;
    }

    private String mapStateEventType(VerlaAgentEventType type) {
        return switch (type) {
            case AI_DETECTION_COMPLETED, AI_DETECTION_FAILED, AI_DETECTION_CANCELLED,
                    AI_HUMANIZER_COMPLETED, AI_HUMANIZER_FAILED, AI_HUMANIZER_CANCELLED -> type.name();
            case AI_DETECTION_RUN_DISPATCH_QUEUED, AI_HUMANIZER_RUN_DISPATCH_QUEUED -> type.name();
            case AGENT_STARTED -> VerlaAgentEventType.AGENT_STARTED.name();
            case AGENT_PROGRESS -> VerlaAgentEventType.AGENT_PROGRESS.name();
            case AGENT_ARTIFACT_UPDATED -> VerlaAgentEventType.AGENT_ARTIFACT_UPDATED.name();
            default -> null;
        };
    }

    private Map<String, Object> sanitizedPayload(VerlaEventInbox event) {
        Map<String, Object> payload = parsePayload(event.getPayloadJson());
        return VerlaFrontendPayloadSanitizer.sanitize(event.getEventType(), payload);
    }

    private Map<String, Object> parsePayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return Map.of();
        }
        try {
            VerlaEventEnvelope envelope = objectMapper.readValue(payloadJson, VerlaEventEnvelope.class);
            if (envelope.getPayload() != null) {
                return envelope.getPayload();
            }
        } catch (Exception ignored) {
            // Some older tests and tools store the payload object directly.
        }
        try {
            Map<String, Object> raw = objectMapper.readValue(
                    payloadJson, new TypeReference<Map<String, Object>>() {});
            Map<String, Object> payload = asMap(raw.get("payload"));
            return payload == null ? raw : payload;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return null;
        }
        return (Map<String, Object>) raw;
    }

    private VerlaAgentEventType parseEventType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return VerlaAgentEventType.valueOf(raw.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private long nullToZero(Long value) {
        return value == null ? 0L : value;
    }

    private record ResolvedStateEvent(String stateEventType, Map<String, Object> payload) {
    }
}
