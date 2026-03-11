package com.studyagent.api.mock;

import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Mock 内存态数据仓库（进程重启后重置）。
 */
@Component
public class MockStateStore {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String FEEDBACK_PROMPT_STATUS_SHOWN = "SHOWN";
    public static final String FEEDBACK_PROMPT_STATUS_SUBMITTED = "SUBMITTED";

    public static class MockFileRecord {
        public String objectId;
        public String filename;
        public String contentType;
        public String rawBody;
        public long fileSize;
        public long uploadEpochSec;
    }

    public static class MockTaskRecord {
        public long taskId;
        public String clerkUserId;
        public String taskTitle;
        public String taskDesc;
        public Integer subject;
        public Integer academicLevel;
        public Integer priorityLevel;
        public String dueDate;
        public List<String> objectIds = new ArrayList<>();
        public List<Integer> format = new ArrayList<>();
        public Integer citationStyle;
        public Integer pageLength;
        public String specialInstructions;
        public String clarifyingQuestions;
        public String requirementsJson;

        public String status;
        public String startTime;
        public String finishTime;
        public Integer costTime;
        public BigDecimal completePercent;
        public Integer queueAheadCount;

        public Double quality;
        public String ratingContent;
    }

    public static class MockFeedbackPromptRecord {
        public String promptSessionId;
        public String clerkUserId;
        public String triggerCode;
        public String subjectType;
        public String subjectId;
        public String sourcePage;
        public String status;
        public String shownAt;
        public String submittedAt;
    }

    public static class MockFeedbackSubmissionRecord {
        public String promptSessionId;
        public String clerkUserId;
        public Integer score;
        public String vote;
        public List<String> selectedTagCodes = new ArrayList<>();
        public String comment;
        public String contact;
        public String submittedAt;
    }

    private final AtomicLong fileCounter = new AtomicLong(1000);
    private final AtomicLong taskCounter = new AtomicLong(1000);
    private final AtomicLong feedbackPromptCounter = new AtomicLong(1000);

    private final Map<String, MockFileRecord> files = new ConcurrentHashMap<>();
    private final Map<Long, MockTaskRecord> tasks = new ConcurrentHashMap<>();
    private final Map<String, String> feedbackPromptIdsByDedupKey = new ConcurrentHashMap<>();
    private final Map<String, MockFeedbackPromptRecord> feedbackPrompts = new ConcurrentHashMap<>();
    private final Map<String, MockFeedbackSubmissionRecord> feedbackSubmissions = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        seedFiles();
        seedTasks();
    }

    public synchronized MockFileRecord saveFile(String filename, String rawBody) {
        String objectId = "mock_file_" + fileCounter.incrementAndGet();
        MockFileRecord file = new MockFileRecord();
        file.objectId = objectId;
        file.filename = (filename == null || filename.isBlank()) ? objectId + ".txt" : filename;
        file.contentType = detectContentType(file.filename);
        file.rawBody = rawBody;
        file.fileSize = estimateDecodedSize(rawBody);
        file.uploadEpochSec = System.currentTimeMillis() / 1000;
        files.put(objectId, file);
        return file;
    }

    public Optional<MockFileRecord> findFile(String objectId) {
        return Optional.ofNullable(files.get(objectId));
    }

    public synchronized MockTaskRecord createTask(MockTaskRecord draft) {
        long id = taskCounter.incrementAndGet();
        draft.taskId = id;
        draft.status = defaultIfBlank(draft.status, STATUS_PENDING);
        draft.startTime = LocalDateTime.now().toString();
        draft.completePercent = draft.completePercent == null ? BigDecimal.ZERO : draft.completePercent;
        draft.queueAheadCount = draft.queueAheadCount == null ? 0 : draft.queueAheadCount;
        draft.quality = draft.quality == null ? 0.0 : draft.quality;
        tasks.put(id, draft);
        return draft;
    }

    public synchronized MockTaskRecord saveDraft(MockTaskRecord draft) {
        if (draft.taskId <= 0) {
            long id = taskCounter.incrementAndGet();
            draft.taskId = id;
        }
        draft.status = STATUS_DRAFT;
        draft.startTime = defaultIfBlank(draft.startTime, LocalDateTime.now().toString());
        draft.completePercent = draft.completePercent == null ? BigDecimal.ZERO : draft.completePercent;
        draft.queueAheadCount = draft.queueAheadCount == null ? 0 : draft.queueAheadCount;
        draft.quality = draft.quality == null ? 0.0 : draft.quality;
        tasks.put(draft.taskId, draft);
        return draft;
    }

    public Optional<MockTaskRecord> findTask(long taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    public synchronized boolean stopTask(long taskId, String clerkUserId) {
        MockTaskRecord task = tasks.get(taskId);
        if (task == null || !Objects.equals(task.clerkUserId, clerkUserId)) {
            return false;
        }
        task.status = STATUS_CANCELLED;
        task.finishTime = LocalDateTime.now().toString();
        return true;
    }

    public synchronized boolean rateTask(long taskId, String clerkUserId, double score, String content) {
        MockTaskRecord task = tasks.get(taskId);
        if (task == null || !Objects.equals(task.clerkUserId, clerkUserId)) {
            return false;
        }
        task.quality = score;
        task.ratingContent = content;
        if (!STATUS_DRAFT.equals(task.status) && !STATUS_PENDING.equals(task.status) && !STATUS_IN_PROGRESS.equals(task.status)) {
            task.status = STATUS_COMPLETED;
        }
        task.completePercent = BigDecimal.valueOf(100);
        task.finishTime = defaultIfBlank(task.finishTime, LocalDateTime.now().toString());
        return true;
    }

    public synchronized DeleteResult deleteTasks(List<Long> taskIds, String clerkUserId) {
        int deleted = 0;
        List<Long> failed = new ArrayList<>();
        for (Long taskId : taskIds) {
            MockTaskRecord task = tasks.get(taskId);
            if (task == null || !Objects.equals(task.clerkUserId, clerkUserId)) {
                failed.add(taskId);
                continue;
            }
            tasks.remove(taskId);
            deleted++;
        }
        return new DeleteResult(deleted, failed);
    }

    public List<MockTaskRecord> listTasks(String clerkUserId) {
        return tasks.values().stream()
            .filter(t -> Objects.equals(t.clerkUserId, clerkUserId))
            .sorted(Comparator.comparingLong((MockTaskRecord t) -> t.taskId).reversed())
            .toList();
    }

    public List<MockTaskRecord> allTasks(String clerkUserId, String taskKeyword, Integer taskStatus, Integer order) {
        List<MockTaskRecord> list = new ArrayList<>(listTasks(clerkUserId));

        if (taskKeyword != null && !taskKeyword.isBlank()) {
            String keyword = taskKeyword.toLowerCase();
            list.removeIf(t -> t.taskTitle == null || !t.taskTitle.toLowerCase().contains(keyword));
        }

        if (taskStatus != null && taskStatus > 0) {
            list.removeIf(t -> !statusMatches(t.status, taskStatus));
        }

        if (order != null) {
            switch (order) {
                case 2 -> list.sort(Comparator.comparingLong(t -> t.taskId));
                case 3 -> list.sort(Comparator.comparing(t -> defaultIfBlank(t.taskTitle, "")));
                case 4 -> list.sort(Comparator.comparing((MockTaskRecord t) -> defaultIfBlank(t.taskTitle, "")).reversed());
                case 5 -> list.sort(Comparator.comparing((MockTaskRecord t) -> t.quality == null ? 0.0 : t.quality).reversed());
                default -> list.sort(Comparator.comparingLong((MockTaskRecord t) -> t.taskId).reversed());
            }
        }

        return list;
    }

    public Summary summary(String clerkUserId) {
        List<MockTaskRecord> list = listTasks(clerkUserId);
        int completed = 0;
        int inProgress = 0;
        double sumQuality = 0;
        int qualityCount = 0;

        for (MockTaskRecord item : list) {
            if (STATUS_COMPLETED.equals(item.status)) {
                completed++;
            }
            if (STATUS_IN_PROGRESS.equals(item.status) || STATUS_PENDING.equals(item.status)) {
                inProgress++;
            }
            if (item.quality != null && item.quality > 0) {
                sumQuality += item.quality;
                qualityCount++;
            }
        }

        double avg = qualityCount == 0 ? 0.0 : sumQuality / qualityCount;
        return new Summary(completed, inProgress, avg);
    }

    public synchronized FeedbackConsumeResult consumeFeedbackTrigger(
        String clerkUserId,
        String triggerCode,
        String subjectType,
        String subjectId,
        String sourcePage
    ) {
        String normalizedUserId = requireNonBlank(clerkUserId, "clerkUserId is required");
        String normalizedTriggerCode = requireNonBlank(triggerCode, "triggerCode is required");
        String normalizedSubjectType = requireNonBlank(subjectType, "subjectType is required");
        String normalizedSubjectId = requireNonBlank(subjectId, "subjectId is required");

        validateFeedbackTrigger(normalizedTriggerCode, normalizedSubjectType);
        String dedupKey = buildFeedbackDedupKey(
            normalizedUserId,
            normalizedSubjectType,
            normalizedSubjectId,
            normalizedTriggerCode
        );

        String existingPromptId = feedbackPromptIdsByDedupKey.get(dedupKey);
        if (existingPromptId != null) {
            return new FeedbackConsumeResult(false, feedbackPrompts.get(existingPromptId));
        }

        MockFeedbackPromptRecord prompt = new MockFeedbackPromptRecord();
        prompt.promptSessionId = nextFeedbackPromptSessionId();
        prompt.clerkUserId = normalizedUserId;
        prompt.triggerCode = normalizedTriggerCode;
        prompt.subjectType = normalizedSubjectType;
        prompt.subjectId = normalizedSubjectId;
        prompt.sourcePage = normalizeOptional(sourcePage);
        prompt.status = FEEDBACK_PROMPT_STATUS_SHOWN;
        prompt.shownAt = LocalDateTime.now().toString();

        feedbackPromptIdsByDedupKey.put(dedupKey, prompt.promptSessionId);
        feedbackPrompts.put(prompt.promptSessionId, prompt);
        return new FeedbackConsumeResult(true, prompt);
    }

    public synchronized FeedbackSubmitResult submitFeedback(
        String clerkUserId,
        String promptSessionId,
        Integer score,
        String vote,
        List<String> selectedTagCodes,
        String comment,
        String contact
    ) {
        String normalizedUserId = requireNonBlank(clerkUserId, "clerkUserId is required");
        String normalizedPromptSessionId = requireNonBlank(promptSessionId, "promptSessionId is required");

        MockFeedbackPromptRecord prompt = feedbackPrompts.get(normalizedPromptSessionId);
        if (prompt == null) {
            return new FeedbackSubmitResult(FeedbackSubmitStatus.PROMPT_NOT_FOUND, "Feedback prompt session not found");
        }
        if (!Objects.equals(prompt.clerkUserId, normalizedUserId)) {
            return new FeedbackSubmitResult(FeedbackSubmitStatus.NO_PERMISSION, "No permission");
        }
        if (feedbackSubmissions.containsKey(normalizedPromptSessionId)) {
            return new FeedbackSubmitResult(FeedbackSubmitStatus.ALREADY_SUBMITTED, "Feedback already submitted");
        }

        validateFeedbackSubmission(score, vote);

        MockFeedbackSubmissionRecord submission = new MockFeedbackSubmissionRecord();
        submission.promptSessionId = normalizedPromptSessionId;
        submission.clerkUserId = normalizedUserId;
        submission.score = score;
        submission.vote = normalizeOptional(vote);
        submission.selectedTagCodes = sanitizeTagCodes(selectedTagCodes);
        submission.comment = comment == null ? "" : comment;
        submission.contact = contact == null ? "" : contact;
        submission.submittedAt = LocalDateTime.now().toString();

        feedbackSubmissions.put(normalizedPromptSessionId, submission);
        prompt.status = FEEDBACK_PROMPT_STATUS_SUBMITTED;
        prompt.submittedAt = submission.submittedAt;
        return new FeedbackSubmitResult(FeedbackSubmitStatus.SUCCESS, null);
    }

    public record DeleteResult(int deletedCount, List<Long> failedTaskIds) {}

    public record Summary(int completedCount, int inProgressCount, double avgQuality) {}

    public record FeedbackConsumeResult(boolean shouldPrompt, MockFeedbackPromptRecord prompt) {}

    public record FeedbackSubmitResult(FeedbackSubmitStatus status, String message) {}

    public enum FeedbackSubmitStatus {
        SUCCESS,
        PROMPT_NOT_FOUND,
        NO_PERMISSION,
        ALREADY_SUBMITTED
    }

    private void seedFiles() {
        saveFile("requirements.pdf", "U3R1ZHlBZ2VudCBtb2NrIGZpbGU=");
        saveFile("reference.docx", "TW9jayByZWZlcmVuY2UgY29udGVudA==");
    }

    private void seedTasks() {
        MockTaskRecord t1 = new MockTaskRecord();
        t1.clerkUserId = "user_mock_demo";
        t1.taskTitle = "Marketing Case Analysis";
        t1.taskDesc = "Analyze market entry strategy";
        t1.subject = 5;
        t1.academicLevel = 4;
        t1.priorityLevel = 2;
        t1.dueDate = LocalDateTime.now().plusDays(3).toString();
        t1.format = List.of(1);
        t1.citationStyle = 1;
        t1.pageLength = 8;
        t1.specialInstructions = "Use Harvard references.";
        t1.status = STATUS_IN_PROGRESS;
        t1.completePercent = BigDecimal.valueOf(62.5);
        t1.queueAheadCount = 0;
        t1.quality = 4.2;
        createTask(t1);

        MockTaskRecord t2 = new MockTaskRecord();
        t2.clerkUserId = "user_mock_demo";
        t2.taskTitle = "AI Ethics Short Essay";
        t2.taskDesc = "Discuss fairness risks in LLM applications";
        t2.subject = 13;
        t2.academicLevel = 3;
        t2.priorityLevel = 1;
        t2.dueDate = LocalDateTime.now().plusDays(1).toString();
        t2.format = List.of(1, 2);
        t2.citationStyle = 1;
        t2.pageLength = 4;
        t2.status = STATUS_DRAFT;
        t2.completePercent = BigDecimal.ZERO;
        t2.queueAheadCount = 2;
        t2.quality = 0.0;
        saveDraft(t2);
    }

    private boolean statusMatches(String status, int taskStatus) {
        return switch (taskStatus) {
            case 1 -> STATUS_DRAFT.equals(status);
            case 2 -> STATUS_PENDING.equals(status);
            case 3 -> STATUS_IN_PROGRESS.equals(status);
            case 4 -> STATUS_COMPLETED.equals(status);
            case 5 -> STATUS_FAILED.equals(status);
            case 6 -> STATUS_CANCELLED.equals(status);
            default -> true;
        };
    }

    private long estimateDecodedSize(String base64) {
        if (base64 == null) {
            return 0;
        }
        int len = base64.length();
        if (len == 0) {
            return 0;
        }
        int padding = 0;
        if (base64.endsWith("==")) {
            padding = 2;
        } else if (base64.endsWith("=")) {
            padding = 1;
        }
        return (len * 3L) / 4L - padding;
    }

    private String detectContentType(String filename) {
        String lower = filename == null ? "" : filename.toLowerCase();
        if (lower.endsWith(".pdf")) {
            return "application/pdf";
        }
        if (lower.endsWith(".doc") || lower.endsWith(".docx")) {
            return "application/msword";
        }
        if (lower.endsWith(".txt") || lower.endsWith(".md")) {
            return "text/plain";
        }
        if (lower.endsWith(".ppt") || lower.endsWith(".pptx")) {
            return "application/vnd.ms-powerpoint";
        }
        return "application/octet-stream";
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return (value == null || value.isBlank()) ? defaultValue : value;
    }

    private String buildFeedbackDedupKey(String clerkUserId, String subjectType, String subjectId, String triggerCode) {
        return clerkUserId + ":" + subjectType + ":" + subjectId + ":" + triggerCode;
    }

    private String nextFeedbackPromptSessionId() {
        return "fps_mock_" + feedbackPromptCounter.incrementAndGet();
    }

    private void validateFeedbackTrigger(String triggerCode, String subjectType) {
        if (!"task".equals(subjectType) && !"humanizer_task".equals(subjectType)) {
            throw new IllegalArgumentException("Unsupported subjectType: " + subjectType);
        }

        switch (triggerCode) {
            case "task_download_first", "editor_back_first", "editor_copy_first" -> {
                if (!"task".equals(subjectType)) {
                    throw new IllegalArgumentException("Trigger " + triggerCode + " requires subjectType=task");
                }
            }
            case "detection_complete_first" -> {
                if (!"humanizer_task".equals(subjectType)) {
                    throw new IllegalArgumentException("Trigger detection_complete_first requires subjectType=humanizer_task");
                }
            }
            case "humanizer_complete_first" -> {
                if (!"humanizer_task".equals(subjectType)) {
                    throw new IllegalArgumentException("Trigger humanizer_complete_first requires subjectType=humanizer_task");
                }
            }
            default -> throw new IllegalArgumentException("Unsupported triggerCode: " + triggerCode);
        }
    }

    private void validateFeedbackSubmission(Integer score, String vote) {
        String normalizedVote = normalizeOptional(vote);
        boolean hasValidScore = score != null && score >= 1 && score <= 5;
        boolean hasValidVote = "up".equals(normalizedVote) || "down".equals(normalizedVote);

        if (!hasValidScore && !hasValidVote) {
            throw new IllegalArgumentException("either a valid score or vote is required");
        }
    }

    private List<String> sanitizeTagCodes(List<String> selectedTagCodes) {
        if (selectedTagCodes == null || selectedTagCodes.isEmpty()) {
            return List.of();
        }

        List<String> sanitized = new ArrayList<>();
        for (String tagCode : selectedTagCodes) {
            String normalized = normalizeOptional(tagCode);
            if (normalized != null) {
                sanitized.add(normalized);
            }
        }
        return sanitized;
    }

    private String requireNonBlank(String value, String message) {
        String normalized = normalizeOptional(value);
        if (normalized == null) {
            throw new IllegalArgumentException(message);
        }
        return normalized;
    }

    private String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
