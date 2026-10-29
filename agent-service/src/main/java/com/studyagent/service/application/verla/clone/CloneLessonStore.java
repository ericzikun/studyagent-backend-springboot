package com.studyagent.service.application.verla.clone;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.service.domain.verla.VerlaArtifact;
import com.studyagent.service.domain.verla.repo.VerlaArtifactRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * clone 模式的一节“课”：
 * messages（左栏）+ events（TutorCommand 流，含 SVG URL）。
 * 单行 artifact，按 conversation 幂等。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CloneLessonStore {

    public static final String KIND = "video_tutor_clone_lesson";

    private final VerlaArtifactRepository artifactRepository;
    private final ObjectMapper objectMapper;

    public static String uid(Long conversationId) {
        return "clone_lesson_" + conversationId;
    }

    public State read(Long conversationId) {
        VerlaArtifact artifact = artifactRepository.findByUid(uid(conversationId));
        if (artifact == null || artifact.getBodyOrRef() == null || artifact.getBodyOrRef().isBlank()) {
            return State.empty();
        }
        try {
            Map<String, Object> body = objectMapper.readValue(
                    artifact.getBodyOrRef(), new TypeReference<Map<String, Object>>() {
                    });
            return new State(list(body.get("messages")), list(body.get("events")));
        } catch (Exception e) {
            log.warn("[video-tutor/clone] lesson unreadable cid={}: {}", conversationId, e.getMessage());
            return State.empty();
        }
    }

    public void save(Long conversationId, State state) {
        VerlaArtifact existing = artifactRepository.findByUid(uid(conversationId));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messages", state.messages());
        body.put("events", state.events());
        VerlaArtifact artifact = new VerlaArtifact();
        artifact.setArtifactUid(uid(conversationId));
        artifact.setConversationId(conversationId);
        // clone 模式不建 verla_turns/sessions；artifact 只按 conversation_uid 读，两列 NOT NULL 用 0 占位。
        artifact.setTurnId(0L);
        artifact.setSessionId(0L);
        artifact.setKind(KIND);
        artifact.setMime("application/json");
        artifact.setStatus("READY");
        artifact.setVersion(existing == null || existing.getVersion() == null ? 1 : existing.getVersion() + 1);
        artifact.setUpdatedAt(LocalDateTime.now());
        try {
            String json = objectMapper.writeValueAsString(body);
            artifact.setBodyOrRef(json);
            artifact.setSizeBytes((long) json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        } catch (Exception e) {
            log.warn("[video-tutor/clone] lesson serialize failed cid={}: {}", conversationId, e.getMessage());
            return;
        }
        artifactRepository.upsertByUid(artifact);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) {
        if (!(value instanceof List<?> items)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof Map<?, ?> map) {
                out.add((Map<String, Object>) map);
            }
        }
        return out;
    }

    public record State(List<Map<String, Object>> messages, List<Map<String, Object>> events) {
        public static State empty() {
            return new State(List.of(), List.of());
        }

        public State append(List<Map<String, Object>> newMessages, List<Map<String, Object>> newEvents) {
            List<Map<String, Object>> allMessages = new ArrayList<>(messages);
            allMessages.addAll(newMessages);
            List<Map<String, Object>> allEvents = new ArrayList<>(events);
            allEvents.addAll(newEvents);
            return new State(allMessages, allEvents);
        }
    }
}
