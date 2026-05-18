package com.studyagent.api.dto.verla.response;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class VerlaBlocksJsonSanitizer {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private VerlaBlocksJsonSanitizer() {
    }

    static String withoutTopLevelStage(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(raw);
            if (node instanceof ObjectNode objectNode && objectNode.has("stage")) {
                ObjectNode copy = objectNode.deepCopy();
                copy.remove("stage");
                return OBJECT_MAPPER.writeValueAsString(copy);
            }
            return raw;
        } catch (Exception e) {
            log.warn("[Verla] blocksJson sanitize failed: {}", e.getMessage());
            return raw;
        }
    }
}
