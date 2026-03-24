package com.studyagent.api.mock;

import com.studyagent.api.common.Result;
import com.studyagent.api.dto.request.HumanizerRequest;
import com.studyagent.common.api.ApiCode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/v1/humanizer")
@RequiredArgsConstructor
public class MockHumanizerController {

    private static final long PENDING_SECONDS = 1L;
    private static final long PROCESSING_SECONDS = 2L;
    private static final long TOTAL_SECONDS = PENDING_SECONDS + PROCESSING_SECONDS;

    private final MockAuthSupport mockAuthSupport;
    private final AtomicLong taskIdGenerator = new AtomicLong(10_000);
    private final ConcurrentMap<Long, MockHumanizerTask> tasks = new ConcurrentHashMap<>();

    @PostMapping("/process")
    public Result<Map<String, Object>> process(
        @Valid @RequestBody HumanizerRequest request,
        @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        MockAuthSupport.MockUser user = mockAuthSupport.requireUser(authorization);
        if (user == null) {
            return Result.error(ApiCode.USER_NOT_LOGGED_IN);
        }

        MockHumanizerTask task = createTask(user.uid(), "HUMANIZE", request.getText());
        return Result.success(toSubmitResponse(materialize(task)));
    }

    @PostMapping("/detect")
    public Result<Map<String, Object>> detect(
        @Valid @RequestBody HumanizerRequest request,
        @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        MockAuthSupport.MockUser user = mockAuthSupport.requireUser(authorization);
        if (user == null) {
            return Result.error(ApiCode.USER_NOT_LOGGED_IN);
        }

        MockHumanizerTask task = createTask(user.uid(), "DETECT", request.getText());
        return Result.success(toSubmitResponse(materialize(task)));
    }

    @GetMapping("/tasks")
    public Result<Map<String, Object>> listTasks(
        @RequestParam(value = "page", defaultValue = "1") Integer page,
        @RequestParam(value = "size", defaultValue = "6") Integer size,
        @RequestParam(value = "taskType", required = false) String taskType,
        @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        MockAuthSupport.MockUser user = mockAuthSupport.requireUser(authorization);
        if (user == null) {
            return Result.error(ApiCode.USER_NOT_LOGGED_IN);
        }

        int safePage = page == null || page < 1 ? 1 : page;
        int safeSize = size == null || size < 1 ? 6 : size;

        List<TaskView> all = tasks.values().stream()
            .filter(task -> user.uid().equals(task.userId))
            .filter(task -> taskType == null || taskType.isBlank() || task.taskType.equalsIgnoreCase(taskType))
            .map(this::materialize)
            .sorted(Comparator.comparing((TaskView view) -> view.createdAt).reversed())
            .toList();

        int total = all.size();
        int totalPages = total == 0 ? 1 : (int) Math.ceil((double) total / safeSize);
        int fromIndex = Math.min((safePage - 1) * safeSize, total);
        int toIndex = Math.min(fromIndex + safeSize, total);

        List<Map<String, Object>> items = all.subList(fromIndex, toIndex).stream()
            .map(this::toHistoryItem)
            .toList();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items);
        data.put("page", safePage);
        data.put("size", safeSize);
        data.put("total", total);
        data.put("totalPages", totalPages);
        return Result.success(data);
    }

    @GetMapping("/tasks/{taskId}")
    public Result<Map<String, Object>> getTask(
        @PathVariable("taskId") Long taskId,
        @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        MockAuthSupport.MockUser user = mockAuthSupport.requireUser(authorization);
        if (user == null) {
            return Result.error(ApiCode.USER_NOT_LOGGED_IN);
        }

        MockHumanizerTask task = tasks.get(taskId);
        if (task == null || !user.uid().equals(task.userId)) {
            return Result.error(ApiCode.TASK_NOT_FOUND);
        }

        return Result.success(toTaskDetail(materialize(task)));
    }

    @PostMapping("/tasks/{taskId}/cancel")
    public Result<Map<String, Object>> cancelTask(
        @PathVariable("taskId") Long taskId,
        @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        MockAuthSupport.MockUser user = mockAuthSupport.requireUser(authorization);
        if (user == null) {
            return Result.error(ApiCode.USER_NOT_LOGGED_IN);
        }

        MockHumanizerTask task = tasks.get(taskId);
        if (task == null || !user.uid().equals(task.userId)) {
            return Result.error(ApiCode.TASK_NOT_FOUND);
        }

        task.cancelled = true;
        task.cancelledAt = LocalDateTime.now();
        return Result.success(toTaskDetail(materialize(task)));
    }

    @PostMapping("/tasks/{taskId}/resume")
    public Result<Map<String, Object>> resumeTask(
        @PathVariable("taskId") Long taskId,
        @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        MockAuthSupport.MockUser user = mockAuthSupport.requireUser(authorization);
        if (user == null) {
            return Result.error(ApiCode.USER_NOT_LOGGED_IN);
        }

        MockHumanizerTask task = tasks.get(taskId);
        if (task == null || !user.uid().equals(task.userId)) {
            return Result.error(ApiCode.TASK_NOT_FOUND);
        }

        task.cancelled = false;
        task.cancelledAt = null;
        task.startedAt = LocalDateTime.now();
        return Result.success(toTaskDetail(materialize(task)));
    }

    private MockHumanizerTask createTask(String userId, String taskType, String inputText) {
        MockHumanizerTask task = new MockHumanizerTask();
        task.id = taskIdGenerator.incrementAndGet();
        task.userId = userId;
        task.taskType = taskType;
        task.inputText = inputText;
        task.createdAt = LocalDateTime.now();
        task.startedAt = task.createdAt;
        tasks.put(task.id, task);
        return task;
    }

    private TaskView materialize(MockHumanizerTask task) {
        if (task.cancelled) {
            return buildView(task, "CANCELLED", 0, TOTAL_SECONDS, totalSentences(task.inputText), task.inputText, null, null);
        }

        long elapsedSeconds = Math.max(0L, Duration.between(task.startedAt, LocalDateTime.now()).getSeconds());
        int sentenceCount = totalSentences(task.inputText);

        if (elapsedSeconds < PENDING_SECONDS) {
            return buildView(task, "PENDING", 0, TOTAL_SECONDS, sentenceCount, null, TOTAL_SECONDS, PENDING_SECONDS - elapsedSeconds);
        }
        if (elapsedSeconds < TOTAL_SECONDS) {
            long processingElapsed = elapsedSeconds - PENDING_SECONDS;
            int completedSentences = Math.max(1, Math.min(sentenceCount, (int) Math.ceil((double) processingElapsed / PROCESSING_SECONDS * sentenceCount)));
            return buildView(
                task,
                "PROCESSING",
                completedSentences,
                elapsedSeconds,
                sentenceCount,
                task.taskType.equals("HUMANIZE") ? task.inputText : null,
                Math.max(1L, TOTAL_SECONDS - elapsedSeconds),
                0L
            );
        }
        return buildView(
            task,
            "COMPLETED",
            sentenceCount,
            TOTAL_SECONDS,
            sentenceCount,
            task.inputText,
            0L,
            0L
        );
    }

    private TaskView buildView(
        MockHumanizerTask task,
        String status,
        int completedSentences,
        long elapsedSeconds,
        int totalSentences,
        String resultText,
        Long estimatedSeconds,
        Long estimatedQueueSeconds
    ) {
        List<Map<String, Object>> sentenceDetails = buildSentenceDetails(task.inputText, completedSentences);
        long consumedWords = countWords(joinCompletedSentenceText(sentenceDetails));
        long totalWords = countWords(task.inputText);

        TaskView view = new TaskView();
        view.id = task.id;
        view.taskType = task.taskType;
        view.status = status;
        view.inputText = task.inputText;
        view.resultText = resultText;
        view.createdAt = task.createdAt;
        view.elapsedSeconds = (double) elapsedSeconds;
        view.totalSentences = totalSentences;
        view.completedSentences = completedSentences;
        view.sentencesJson = sentenceDetails.isEmpty() ? null : Jsons.toJson(sentenceDetails);
        view.estimatedSeconds = estimatedSeconds;
        view.estimatedQueueSeconds = estimatedQueueSeconds;
        view.queuePosition = "PENDING".equals(status) ? 1 : 0;
        view.totalWords = totalWords;
        view.consumedWords = task.taskType.equals("DETECT") ? consumedWords : totalWords;
        view.probability = task.taskType.equals("DETECT") ? 0.18d : null;
        view.label = task.taskType.equals("DETECT") ? "Likely Human" : null;
        return view;
    }

    private Map<String, Object> toSubmitResponse(TaskView view) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", view.id);
        data.put("taskId", view.id);
        data.put("status", view.status);
        data.put("estimatedSeconds", view.estimatedSeconds);
        data.put("estimatedQueueSeconds", view.estimatedQueueSeconds);
        data.put("queuePosition", view.queuePosition);
        data.put("totalWords", view.totalWords);
        data.put("consumedWords", view.consumedWords);
        return data;
    }

    private Map<String, Object> toTaskDetail(TaskView view) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", view.id);
        data.put("taskId", view.id);
        data.put("taskType", view.taskType);
        data.put("status", view.status);
        data.put("inputText", view.inputText);
        data.put("inputTextPreview", preview(view.inputText));
        data.put("resultText", view.resultText);
        data.put("resultTextPreview", preview(view.resultText));
        data.put("probability", view.probability);
        data.put("label", view.label);
        data.put("sentencesJson", view.sentencesJson);
        data.put("totalSentences", view.totalSentences);
        data.put("completedSentences", view.completedSentences);
        data.put("elapsedSeconds", view.elapsedSeconds);
        data.put("estimatedSeconds", view.estimatedSeconds);
        data.put("estimatedQueueSeconds", view.estimatedQueueSeconds);
        data.put("queuePosition", view.queuePosition);
        data.put("totalWords", view.totalWords);
        data.put("consumedWords", view.consumedWords);
        data.put("createdAt", view.createdAt.toString());
        return data;
    }

    private Map<String, Object> toHistoryItem(TaskView view) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", view.id);
        item.put("taskType", view.taskType);
        item.put("status", view.status);
        item.put("inputTextPreview", preview(view.inputText));
        item.put("resultTextPreview", preview(view.resultText));
        item.put("probability", view.probability);
        item.put("label", view.label);
        item.put("totalSentences", view.totalSentences);
        item.put("completedSentences", view.completedSentences);
        item.put("elapsedSeconds", view.elapsedSeconds);
        item.put("createdAt", view.createdAt.toString());
        return item;
    }

    private List<Map<String, Object>> buildSentenceDetails(String text, int completedSentences) {
        List<String> parts = splitSentences(text);
        List<Map<String, Object>> details = new ArrayList<>();
        for (int i = 0; i < completedSentences && i < parts.size(); i++) {
            String sentence = parts.get(i);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("index", i);
            detail.put("sentence", sentence);
            detail.put("fullSentence", sentence);
            detail.put("probability", 0.18d);
            detail.put("label", "Likely Human");
            detail.put("weight", 0.82d);
            detail.put("success", true);
            details.add(detail);
        }
        return details;
    }

    private List<String> splitSentences(String text) {
        String normalized = text == null ? "" : text.trim();
        if (normalized.isEmpty()) {
            return List.of();
        }
        String[] rawParts = normalized.split("(?<=[.!?])\\s+|\\n+");
        List<String> parts = new ArrayList<>();
        for (String rawPart : rawParts) {
            String sentence = rawPart.trim();
            if (!sentence.isEmpty()) {
                parts.add(sentence);
            }
        }
        return parts.isEmpty() ? List.of(normalized) : parts;
    }

    private int totalSentences(String text) {
        return Math.max(1, splitSentences(text).size());
    }

    private long countWords(String text) {
        String normalized = text == null ? "" : text.trim();
        if (normalized.isEmpty()) {
            return 0L;
        }
        return normalized.split("\\s+").length;
    }

    private String joinCompletedSentenceText(List<Map<String, Object>> sentenceDetails) {
        return sentenceDetails.stream()
            .map(detail -> String.valueOf(detail.get("fullSentence")))
            .reduce((left, right) -> left + " " + right)
            .orElse("");
    }

    private String preview(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return text.length() <= 120 ? text : text.substring(0, 120);
    }

    private static final class MockHumanizerTask {
        private Long id;
        private String userId;
        private String taskType;
        private String inputText;
        private LocalDateTime createdAt;
        private LocalDateTime startedAt;
        private boolean cancelled;
        private LocalDateTime cancelledAt;
    }

    private static final class TaskView {
        private Long id;
        private String taskType;
        private String status;
        private String inputText;
        private String resultText;
        private Double probability;
        private String label;
        private String sentencesJson;
        private Integer totalSentences;
        private Integer completedSentences;
        private Double elapsedSeconds;
        private Long estimatedSeconds;
        private Long estimatedQueueSeconds;
        private Integer queuePosition;
        private Long totalWords;
        private Long consumedWords;
        private LocalDateTime createdAt;
    }

    private static final class Jsons {
        private static final com.fasterxml.jackson.databind.ObjectMapper OBJECT_MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

        private static String toJson(Object value) {
            try {
                return OBJECT_MAPPER.writeValueAsString(value);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to serialize mock humanizer payload", e);
            }
        }
    }
}
