package com.studyagent.service.application.verla;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.studyagent.service.application.verla.dto.FileChatAnalysisState;
import com.studyagent.service.application.verla.dto.FileChatAnalysisStatus;
import com.studyagent.service.application.verla.dto.FileChatMessageMeta;
import com.studyagent.service.application.verla.dto.FileChatPanelState;
import com.studyagent.service.domain.verla.VerlaAttachment;

import java.util.ArrayList;
import java.util.List;

public final class VerlaFileChatMetadataHelper {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private VerlaFileChatMetadataHelper() {
    }

    public static FileChatPanelState readAttachmentState(VerlaAttachment attachment) {
        if (attachment == null || attachment.getMetaJson() == null || attachment.getMetaJson().isBlank()) {
            return defaultPanelState();
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(attachment.getMetaJson());
            JsonNode fileChat = root == null ? null : root.get("fileChat");
            if (fileChat == null || !fileChat.isObject()) {
                return defaultPanelState();
            }
            FileChatAnalysisStatus status = parseStatus(textValue(fileChat.get("analysisStatus")));
            String text = textValue(fileChat.get("analysisText"));
            List<String> suggestedQuestions = stringList(fileChat.get("suggestedQuestions"));
            return FileChatPanelState.builder()
                    .analysis(FileChatAnalysisState.builder()
                            .status(status)
                            .text(text == null ? "" : text)
                            .build())
                    .suggestedQuestions(suggestedQuestions)
                    .updatedAt(textValue(fileChat.get("updatedAt")))
                    .build();
        } catch (Exception ignored) {
            return defaultPanelState();
        }
    }

    public static String writeAttachmentState(String currentMetaJson, FileChatPanelState state) {
        try {
            ObjectNode root = readObject(currentMetaJson);
            ObjectNode fileChat = root.putObject("fileChat");
            FileChatAnalysisState analysis = state == null ? null : state.getAnalysis();
            FileChatAnalysisStatus status = analysis == null || analysis.getStatus() == null
                    ? FileChatAnalysisStatus.PENDING
                    : analysis.getStatus();
            fileChat.put("analysisStatus", status.name());
            fileChat.put("analysisText", analysis == null || analysis.getText() == null ? "" : analysis.getText());
            ArrayNode questions = fileChat.putArray("suggestedQuestions");
            if (state != null && state.getSuggestedQuestions() != null) {
                for (String question : state.getSuggestedQuestions()) {
                    questions.add(question);
                }
            }
            if (state != null && state.getUpdatedAt() != null) {
                fileChat.put("updatedAt", state.getUpdatedAt());
            }
            return OBJECT_MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalArgumentException("failed to write file chat attachment meta", e);
        }
    }

    public static FileChatMessageMeta readMessageMeta(String metaJson) {
        if (metaJson == null || metaJson.isBlank()) {
            return null;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(metaJson);
            return FileChatMessageMeta.builder()
                    .scene(textValue(root.get("scene")))
                    .objectId(textValue(root.get("objectId")))
                    .build();
        } catch (Exception ignored) {
            return null;
        }
    }

    public static String writeMessageMeta(FileChatMessageMeta meta) {
        try {
            ObjectNode root = OBJECT_MAPPER.createObjectNode();
            root.put("scene", meta == null || meta.getScene() == null ? FileChatMessageMeta.SCENE_FILE_CHAT : meta.getScene());
            root.put("objectId", meta == null ? null : meta.getObjectId());
            return OBJECT_MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalArgumentException("failed to write file chat message meta", e);
        }
    }

    private static FileChatPanelState defaultPanelState() {
        return FileChatPanelState.builder()
                .analysis(FileChatAnalysisState.pending())
                .suggestedQuestions(List.of())
                .build();
    }

    private static FileChatAnalysisStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return FileChatAnalysisStatus.PENDING;
        }
        try {
            return FileChatAnalysisStatus.valueOf(raw);
        } catch (IllegalArgumentException ignored) {
            return FileChatAnalysisStatus.PENDING;
        }
    }

    private static String textValue(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    private static List<String> stringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item != null && !item.isNull()) {
                values.add(item.asText());
            }
        }
        return values;
    }

    private static ObjectNode readObject(String currentMetaJson) throws Exception {
        if (currentMetaJson == null || currentMetaJson.isBlank()) {
            return OBJECT_MAPPER.createObjectNode();
        }
        JsonNode root = OBJECT_MAPPER.readTree(currentMetaJson);
        if (root instanceof ObjectNode objectNode) {
            return objectNode.deepCopy();
        }
        return OBJECT_MAPPER.createObjectNode();
    }
}
