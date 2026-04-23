package com.studyagent.service.application.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.studyagent.service.application.dto.TaskDetailDTO;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 从任务 requirementJson 中解析追问 Q&amp;A 与追问附件，供任务详情展示。
 * <p>
 * 与 {@link com.studyagent.service.application.TaskApplicationService#mergeRequirementJson} 存库格式一致：
 * <pre>
 *   { "clarifyingQuestions": "[{...}]" 或 [{...}] , ... }
 * </pre>
 */
@Slf4j
public final class RequirementJsonClarifyParser {

    private RequirementJsonClarifyParser() {
    }

    public static List<TaskDetailDTO.ClarifyingQuestionInfo> parseClarifyingQuestionList(String requirementJson) {
        if (requirementJson == null || requirementJson.isBlank()) {
            return List.of();
        }
        try {
            JsonObject root = JsonParser.parseString(requirementJson).getAsJsonObject();
            if (!root.has("clarifyingQuestions")) {
                return List.of();
            }
            JsonArray arr = resolveClarifyingArray(root.get("clarifyingQuestions"));
            if (arr == null) {
                return List.of();
            }
            List<TaskDetailDTO.ClarifyingQuestionInfo> out = new ArrayList<>();
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) {
                    continue;
                }
                out.add(mapQuestion(el.getAsJsonObject()));
            }
            return out.isEmpty() ? List.of() : out;
        } catch (JsonSyntaxException e) {
            log.debug("无法解析 requirementJson: {}", e.getMessage());
            return List.of();
        } catch (Exception e) {
            log.warn("解析追问列表失败: {}", e.getMessage());
            return List.of();
        }
    }

    private static JsonArray resolveClarifyingArray(JsonElement raw) {
        if (raw == null || raw.isJsonNull()) {
            return null;
        }
        if (raw.isJsonArray()) {
            return raw.getAsJsonArray();
        }
        if (raw.isJsonPrimitive() && raw.getAsJsonPrimitive().isString()) {
            String s = raw.getAsString();
            if (s == null || s.isBlank()) {
                return null;
            }
            try {
                JsonElement inner = JsonParser.parseString(s);
                return inner.isJsonArray() ? inner.getAsJsonArray() : null;
            } catch (JsonSyntaxException e) {
                return null;
            }
        }
        return null;
    }

    private static TaskDetailDTO.ClarifyingQuestionInfo mapQuestion(JsonObject o) {
        return TaskDetailDTO.ClarifyingQuestionInfo.builder()
                .id(getString(o, "id"))
                .question(getString(o, "question"))
                .tag(getString(o, "tag"))
                .answer(getString(o, "answer"))
                .skipped(getBoolean(o, "skipped"))
                .attachments(parseAttachments(o.get("attachments")))
                .build();
    }

    private static List<TaskDetailDTO.ClarifyAttachmentInfo> parseAttachments(JsonElement el) {
        if (el == null || !el.isJsonArray() || el.getAsJsonArray().isEmpty()) {
            return Collections.emptyList();
        }
        List<TaskDetailDTO.ClarifyAttachmentInfo> list = new ArrayList<>();
        for (JsonElement a : el.getAsJsonArray()) {
            if (!a.isJsonObject()) {
                continue;
            }
            JsonObject ao = a.getAsJsonObject();
            String objectId = firstNonEmpty(
                    getString(ao, "objectId"), getString(ao, "object_id"));
            if (objectId == null) {
                continue;
            }
            list.add(TaskDetailDTO.ClarifyAttachmentInfo.builder()
                    .objectId(objectId)
                    .filename(firstNonEmpty(getString(ao, "filename"), getString(ao, "fileName"), getString(ao, "file_name")))
                    .build());
        }
        return list;
    }

    public static void enrichUploadSources(
            List<TaskDetailDTO.UploadedFileInfo> files,
            List<TaskDetailDTO.ClarifyingQuestionInfo> clarifying) {

        if (files == null || files.isEmpty()) {
            return;
        }
        java.util.Map<String, String> objectIdToQid = new java.util.HashMap<>();
        if (clarifying != null) {
            for (TaskDetailDTO.ClarifyingQuestionInfo q : clarifying) {
                if (q == null || q.getId() == null) {
                    continue;
                }
                if (q.getAttachments() == null) {
                    continue;
                }
                for (TaskDetailDTO.ClarifyAttachmentInfo a : q.getAttachments()) {
                    if (a != null && a.getObjectId() != null && !a.getObjectId().isEmpty()) {
                        objectIdToQid.putIfAbsent(a.getObjectId(), q.getId());
                    }
                }
            }
        }
        for (TaskDetailDTO.UploadedFileInfo f : files) {
            if (f == null) {
                continue;
            }
            if (f.getObjectId() == null) {
                f.setAttachmentSource("TASK");
                continue;
            }
            String qid = objectIdToQid.get(f.getObjectId());
            if (qid != null) {
                f.setAttachmentSource("CLARIFY");
                f.setClarifyQuestionId(qid);
            } else {
                f.setAttachmentSource("TASK");
            }
        }
    }

    private static String getString(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) {
            return null;
        }
        if (!o.get(key).isJsonPrimitive()) {
            return o.get(key).toString();
        }
        return o.get(key).getAsString();
    }

    private static Boolean getBoolean(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) {
            return null;
        }
        if (!o.get(key).isJsonPrimitive()) {
            return null;
        }
        try {
            return o.get(key).getAsBoolean();
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstNonEmpty(String... s) {
        for (String x : s) {
            if (x != null && !x.isBlank()) {
                return x;
            }
        }
        return null;
    }
}
