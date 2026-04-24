package com.studyagent.api.mock;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.studyagent.api.common.Result;
import com.studyagent.api.dto.request.ClarifyTaskRequest;
import com.studyagent.api.dto.request.DeleteTasksRequest;
import com.studyagent.api.dto.request.RateTaskRequest;
import com.studyagent.api.dto.request.SaveDraftRequest;
import com.studyagent.api.dto.request.StopTaskRequest;
import com.studyagent.api.dto.request.SubmitTaskRequest;
import com.studyagent.api.dto.request.TaskDetailRequest;
import com.studyagent.api.dto.request.TaskListRequest;
import com.studyagent.api.dto.response.ClarifyTaskResponse;
import com.studyagent.api.dto.response.DeleteTasksResponse;
import com.studyagent.api.dto.response.InsufficientQuotaResponse;
import com.studyagent.api.dto.response.SaveDraftResponse;
import com.studyagent.api.dto.response.StopTaskResponse;
import com.studyagent.api.dto.response.SubmitQuotaInfo;
import com.studyagent.api.dto.response.SubmitTaskResponse;
import com.studyagent.api.dto.response.TaskDetailResponse;
import com.studyagent.api.dto.response.TaskListItemResponse;
import com.studyagent.api.dto.response.TaskListResponse;
import com.studyagent.api.dto.response.TaskSummaryResponse;
import com.studyagent.api.util.TaskIdEncoder;
import com.studyagent.common.api.ApiCode;
import com.studyagent.service.application.dto.TaskDetailDTO;
import com.studyagent.service.application.util.RequirementJsonClarifyParser;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@RestController
@RequestMapping("/v1/task")
@RequiredArgsConstructor
public class MockTaskController {

    private static final Gson GSON = new Gson();

    private final MockStateStore store;
    private final MockAuthSupport mockAuthSupport;

    @GetMapping("/submit-quota")
    public Result<SubmitQuotaInfo> getSubmitQuota(
        @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        MockAuthSupport.MockUser user = mockAuthSupport.requireUser(authorization);
        if (user == null) {
            return Result.error(ApiCode.USER_NOT_LOGGED_IN);
        }
        SubmitQuotaInfo response = SubmitQuotaInfo.builder()
            .dailyLimit(3)
            .usedToday(1)
            .remainingQuota(2)
            .quotaResetAt(LocalDateTime.now().plusDays(1).withHour(0).withMinute(0).withSecond(0).withNano(0).toString())
            .build();
        return Result.success(response);
    }

    @PostMapping("/submit")
    public Result<Object> submitTask(
        @Valid @RequestBody SubmitTaskRequest request,
        @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        MockAuthSupport.MockUser user = mockAuthSupport.requireUser(authorization);
        if (user == null) {
            return Result.error(ApiCode.USER_NOT_LOGGED_IN);
        }

        // Mock 规则：输入中包含 "cost" 时，模拟额度不足（1011）
        if (containsCostTrigger(request)) {
            InsufficientQuotaResponse quota = InsufficientQuotaResponse.builder()
                .featureCode("task_create")
                .featureName("作业任务")
                .quotaUnit("count")
                .freeBalance(0L)
                .freePeriodTotal(3L)
                .paidBalance(0L)
                .totalAvailable(0L)
                .build();
            Result<Object> result = Result.error(ApiCode.INSUFFICIENT_QUOTA);
            result.setData(quota);
            return result;
        }

        MockStateStore.MockTaskRecord existingDraft = Optional.ofNullable(TaskIdEncoder.decode(request.getDraftId()))
            .flatMap(store::findTask)
            .filter(t -> user.uid().equals(t.clerkUserId))
            .orElse(null);

        MockStateStore.MockTaskRecord record = new MockStateStore.MockTaskRecord();
        record.clerkUserId = user.uid();
        record.taskTitle = existingDraft != null && !isBlank(existingDraft.taskTitle)
            ? existingDraft.taskTitle
            : titleFromDescription(request.getTaskDesc(), "Mock Assignment");
        record.taskDesc = request.getTaskDesc();
        record.subject = request.getSubject();
        record.academicLevel = request.getAcademicLevel();
        record.priorityLevel = request.getPriorityLevel();
        record.dueDate = request.getDueDate() != null ? request.getDueDate().toString() : LocalDateTime.now().plusDays(7).toString();
        record.requirementsJson = mergeRequirementJson(
            existingDraft == null ? null : existingDraft.requirementsJson,
            request.getRequirementsJson(),
            request.getClarifyingQuestions()
        );
        record.objectIds = mergeObjectIdsForTaskFiles(
            request.getObjectIds() == null && existingDraft != null ? existingDraft.objectIds : request.getObjectIds(),
            request.getClarifyingQuestions(),
            record.requirementsJson
        );
        record.format = request.getFormat() == null ? List.of(1) : new ArrayList<>(request.getFormat());
        record.citationStyle = request.getCitationStyle();
        record.pageLength = request.getPageLength() == null ? 5 : request.getPageLength();
        record.specialInstructions = request.getSpecialInstructions();
        record.clarifyingQuestions = request.getClarifyingQuestions();
        record.status = MockStateStore.STATUS_PENDING;
        record.completePercent = BigDecimal.ZERO;
        record.queueAheadCount = 0;
        record.costTime = 0;
        record.shouldFail = containsFailTrigger(request);
        store.createTask(record);

        SubmitTaskResponse response = SubmitTaskResponse.builder()
            .taskId(TaskIdEncoder.encode(record.taskId))
            .quota(SubmitQuotaInfo.builder()
                .dailyLimit(3)
                .usedToday(2)
                .remainingQuota(1)
                .quotaResetAt(LocalDateTime.now().plusDays(1).withHour(0).withMinute(0).withSecond(0).withNano(0).toString())
                .build())
            .build();

        return Result.success(response);
    }

    @PostMapping("/save-draft")
    public Result<SaveDraftResponse> saveDraft(
        @RequestBody SaveDraftRequest request,
        @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        MockAuthSupport.MockUser user = mockAuthSupport.requireUser(authorization);
        if (user == null) {
            return Result.error(ApiCode.USER_NOT_LOGGED_IN);
        }

        MockStateStore.MockTaskRecord existingDraft = null;
        Long decodedDraftId = request.getDraftId() == null ? null : TaskIdEncoder.decode(request.getDraftId());
        if (decodedDraftId != null) {
            existingDraft = store.findTask(decodedDraftId)
                .filter(t -> user.uid().equals(t.clerkUserId))
                .orElse(null);
        }

        MockStateStore.MockTaskRecord record = new MockStateStore.MockTaskRecord();
        record.taskId = decodedDraftId == null ? 0L : decodedDraftId;
        record.clerkUserId = user.uid();
        record.taskTitle = existingDraft != null && !isBlank(existingDraft.taskTitle)
            ? existingDraft.taskTitle
            : titleFromDescription(request.getTaskDesc(), "未命名草稿");
        record.taskDesc = defaultString(request.getTaskDesc(), "");
        record.subject = request.getSubject() == null ? 0 : request.getSubject();
        record.academicLevel = request.getAcademicLevel() == null ? 0 : request.getAcademicLevel();
        record.priorityLevel = request.getPriorityLevel() == null ? 0 : request.getPriorityLevel();
        record.dueDate = request.getDueDate() != null ? request.getDueDate().toString() : LocalDateTime.now().plusDays(7).toString();
        record.requirementsJson = mergeRequirementJson(
            existingDraft == null ? null : existingDraft.requirementsJson,
            request.getRequirementsJson(),
            request.getClarifyingQuestions()
        );
        record.objectIds = mergeObjectIdsForTaskFiles(
            request.getObjectIds() == null && existingDraft != null ? existingDraft.objectIds : request.getObjectIds(),
            request.getClarifyingQuestions(),
            record.requirementsJson
        );
        record.format = request.getFormat() == null ? List.of(1) : new ArrayList<>(request.getFormat());
        record.citationStyle = request.getCitationStyle() == null ? 0 : request.getCitationStyle();
        record.pageLength = request.getPageLength() == null ? 5 : request.getPageLength();
        record.specialInstructions = request.getSpecialInstructions();
        record.clarifyingQuestions = request.getClarifyingQuestions();
        record.completePercent = BigDecimal.ZERO;
        record.queueAheadCount = 0;
        record.costTime = 0;

        store.saveDraft(record);

        SaveDraftResponse response = SaveDraftResponse.builder()
            .draftId(TaskIdEncoder.encode(record.taskId))
            .savedAt(LocalDateTime.now().toString())
            .build();
        return Result.success(response);
    }

    @PostMapping("/stop")
    public Result<StopTaskResponse> stopTask(
        @Valid @RequestBody StopTaskRequest request,
        @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        MockAuthSupport.MockUser user = mockAuthSupport.requireUser(authorization);
        if (user == null) {
            return Result.error(ApiCode.USER_NOT_LOGGED_IN);
        }

        Long internalTaskId = TaskIdEncoder.decode(request.getTaskId());
        if (internalTaskId == null) {
            return Result.error(ApiCode.TASK_NOT_FOUND);
        }
        boolean ok = store.stopTask(internalTaskId, user.uid());
        if (!ok) {
            return Result.error(ApiCode.TASK_NOT_FOUND);
        }

        StopTaskResponse response = StopTaskResponse.builder()
            .taskId(request.getTaskId())
            .message("任务已停止")
            .build();
        return Result.success(response);
    }

    @PostMapping("/list")
    public Result<TaskListResponse> getTaskList(
        @RequestBody(required = false) TaskListRequest request,
        @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        MockAuthSupport.MockUser user = mockAuthSupport.requireUser(authorization);
        if (user == null) {
            return Result.error(ApiCode.USER_NOT_LOGGED_IN);
        }

        TaskListRequest req = request == null ? new TaskListRequest() : request;
        int pageNo = (req.getPageNo() == null || req.getPageNo() < 1) ? 1 : req.getPageNo();
        int pageSize = (req.getPageSize() == null || req.getPageSize() < 1) ? 10 : req.getPageSize();

        List<MockStateStore.MockTaskRecord> filtered = store.allTasks(user.uid(), req.getTaskKeyword(), req.getTaskStatus(), req.getOrder());
        int total = filtered.size();

        int fromIndex = Math.min((pageNo - 1) * pageSize, total);
        int toIndex = Math.min(fromIndex + pageSize, total);
        List<MockStateStore.MockTaskRecord> page = filtered.subList(fromIndex, toIndex);

        List<TaskListItemResponse> taskList = page.stream()
            .map(this::toTaskListItem)
            .toList();

        MockStateStore.Summary summary = store.summary(user.uid());

        TaskListResponse response = TaskListResponse.builder()
            .taskList(taskList)
            .taskSummary(TaskSummaryResponse.builder()
                .taskCompletedSize(summary.completedCount())
                .taskInProgressSize(summary.inProgressCount())
                .avgQuality(round(summary.avgQuality(), 2))
                .build())
            .total(total)
            .pageNo(pageNo)
            .pageSize(pageSize)
            .build();

        return Result.success(response);
    }

    @GetMapping("/list")
    public Result<TaskListResponse> getTaskListByGet(
        @RequestParam(value = "taskKeyword", required = false) String taskKeyword,
        @RequestParam(value = "taskStatus", required = false) Integer taskStatus,
        @RequestParam(value = "order", required = false) Integer order,
        @RequestParam(value = "pageNo", defaultValue = "1") Integer pageNo,
        @RequestParam(value = "pageSize", defaultValue = "10") Integer pageSize,
        @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        TaskListRequest request = new TaskListRequest();
        request.setTaskKeyword(taskKeyword);
        request.setTaskStatus(taskStatus);
        request.setOrder(order);
        request.setPageNo(pageNo);
        request.setPageSize(pageSize);
        return getTaskList(request, authorization);
    }

    @GetMapping("/summary")
    public Result<TaskSummaryResponse> getTaskSummary(
        @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        MockAuthSupport.MockUser user = mockAuthSupport.requireUser(authorization);
        if (user == null) {
            return Result.error(ApiCode.USER_NOT_LOGGED_IN);
        }

        MockStateStore.Summary summary = store.summary(user.uid());
        TaskSummaryResponse response = TaskSummaryResponse.builder()
            .taskCompletedSize(summary.completedCount())
            .taskInProgressSize(summary.inProgressCount())
            .avgQuality(round(summary.avgQuality(), 2))
            .build();
        return Result.success(response);
    }

    @PostMapping("/detail")
    public Result<TaskDetailResponse> getTaskDetail(
        @Valid @RequestBody TaskDetailRequest request,
        @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        MockAuthSupport.MockUser user = mockAuthSupport.requireUser(authorization);
        if (user == null) {
            return Result.error(ApiCode.USER_NOT_LOGGED_IN);
        }

        Long detailTaskId = TaskIdEncoder.decode(request.getTaskId());
        if (detailTaskId == null) {
            return Result.error(ApiCode.TASK_NOT_FOUND);
        }
        MockStateStore.MockTaskRecord task = store.findTask(detailTaskId)
            .filter(t -> user.uid().equals(t.clerkUserId))
            .orElse(null);
        if (task == null) {
            return Result.error(ApiCode.TASK_NOT_FOUND);
        }
        List<TaskDetailDTO.ClarifyingQuestionInfo> clarifyingQuestions =
            RequirementJsonClarifyParser.parseClarifyingQuestionList(task.requirementsJson);
        List<String> detailObjectIds = mergeObjectIdsForTaskFiles(
            task.objectIds,
            task.clarifyingQuestions,
            task.requirementsJson
        );

        TaskDetailResponse response = TaskDetailResponse.builder()
            .taskBaseInfo(TaskDetailResponse.TaskBaseInfoResponse.builder()
                .taskTitle(task.taskTitle)
                .taskDesc(task.taskDesc)
                .taskStatus(toTaskStatusCode(task.status))
                .startTime(toUnixSeconds(task.startTime))
                .dueTime(toUnixSeconds(task.dueDate))
                .finishTime(toUnixSeconds(task.finishTime))
                .costTime(task.costTime == null ? 0 : task.costTime)
                .subject(task.subject == null ? 0 : task.subject)
                .academicLevel(task.academicLevel == null ? 0 : task.academicLevel)
                .priorityLevel(task.priorityLevel == null ? 0 : task.priorityLevel)
                .citationStyle(task.citationStyle == null ? 0 : task.citationStyle)
                .pageLength(task.pageLength == null ? 0 : task.pageLength)
                .formatList(task.format == null ? List.of() : task.format)
                .specialInstructions(task.specialInstructions)
                .completePercent(task.completePercent == null ? 0.0 : task.completePercent.doubleValue())
                .taskCompletedSize(MockStateStore.STATUS_COMPLETED.equals(task.status) ? 1 : 0)
                .activeAgentSize(MockStateStore.STATUS_IN_PROGRESS.equals(task.status) ? 1 : 0)
                .estRemainingTime(MockStateStore.STATUS_IN_PROGRESS.equals(task.status) ? 1800 : 0)
                .queueAheadCount(task.queueAheadCount == null ? 0 : task.queueAheadCount)
                .requirementJson(task.requirementsJson)
                .build())
            .agentInfoList(List.of(
                TaskDetailResponse.AgentInfoResponse.builder()
                    .agentName("Task Planner")
                    .subtaskId("0.0")
                    .subtaskTitle("Analyze requirements")
                    .agentStatus(MockStateStore.STATUS_COMPLETED.equals(task.status) ? 3 : 2)
                    .completePercent(MockStateStore.STATUS_COMPLETED.equals(task.status) ? 100.0 : 75.0)
                    .agentDesc("Analyzing task requirements and references")
                    .agentStartTime(toUnixSeconds(task.startTime))
                    .agentFinishTime(MockStateStore.STATUS_COMPLETED.equals(task.status) ? toUnixSeconds(task.finishTime) : null)
                    .agentPriority(1)
                    .agentOutput("Requirement analysis generated")
                    .build()
            ))
            .subTaskInfoList(List.of(
                TaskDetailResponse.SubTaskInfoResponse.builder()
                    .title("Requirement analysis")
                    .desc("Break down task constraints and expected outputs")
                    .processDesc(MockStateStore.STATUS_COMPLETED.equals(task.status) ? "100%" : "75%")
                    .agentName("Task Planner")
                    .subtaskCode("0.0")
                    .agentStatus(MockStateStore.STATUS_COMPLETED.equals(task.status) ? 3 : 2)
                    .agentCompletePercent(MockStateStore.STATUS_COMPLETED.equals(task.status) ? 100.0 : 75.0)
                    .agentDesc("Task analysis in progress")
                    .agentStartTime(toUnixSeconds(task.startTime))
                    .agentFinishTime(MockStateStore.STATUS_COMPLETED.equals(task.status) ? toUnixSeconds(task.finishTime) : null)
                    .agentPriority(1)
                    .agentOutput("Outline and constraints")
                    .build()
            ))
            .activityInfoList(List.of(
                TaskDetailResponse.ActivityInfoResponse.builder()
                    .activityTime(toUnixSeconds(task.startTime))
                    .agentName("System")
                    .activityDesc("Task created")
                    .build(),
                TaskDetailResponse.ActivityInfoResponse.builder()
                    .activityTime(Math.max(toUnixSeconds(task.startTime), (System.currentTimeMillis() / 1000) - 120))
                    .agentName("Task Planner")
                    .activityDesc("Generated initial outline")
                    .build()
            ))
            .outputSummaryInfo(TaskDetailResponse.OutputInfoResponse.builder()
                .title(defaultString(task.taskTitle, "Task") + " - Summary")
                .desc("Mock output summary")
                .url("/v1/task/output/download/" + task.taskId)
                .sizeDesc("12KB")
                .pageSize(task.pageLength == null ? 0 : task.pageLength)
                .format(task.format == null || task.format.isEmpty() ? 1 : task.format.get(0))
                .outputType(1)
                .build())
            .outputDetailInfoList(List.of(
                TaskDetailResponse.OutputInfoResponse.builder()
                    .title(defaultString(task.taskTitle, "Task") + " - Draft")
                    .desc("Draft output")
                    .url("/v1/task/output/download/" + task.taskId)
                    .sizeDesc("8KB")
                    .pageSize(task.pageLength == null ? 0 : task.pageLength)
                    .format(task.format == null || task.format.isEmpty() ? 1 : task.format.get(0))
                    .outputType(0)
                    .build(),
                TaskDetailResponse.OutputInfoResponse.builder()
                    .title(defaultString(task.taskTitle, "Task") + " - Final")
                    .desc("Final output")
                    .url("/v1/task/output/download/" + task.taskId)
                    .sizeDesc("12KB")
                    .pageSize(task.pageLength == null ? 0 : task.pageLength)
                    .format(task.format == null || task.format.isEmpty() ? 1 : task.format.get(0))
                    .outputType(1)
                    .build()
            ))
            .uploadedFileInfoList(toUploadedFileInfo(detailObjectIds, clarifyingQuestions))
            .clarifyingQuestionList(toClarifyingQuestionInfoResponse(clarifyingQuestions))
            .build();

        return Result.success(response);
    }

    @PostMapping("/rate")
    public Result<Void> rateTask(
        @Valid @RequestBody RateTaskRequest request,
        @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        MockAuthSupport.MockUser user = mockAuthSupport.requireUser(authorization);
        if (user == null) {
            return Result.error(ApiCode.USER_NOT_LOGGED_IN);
        }

        Long rateTaskId = TaskIdEncoder.decode(request.getTaskId());
        if (rateTaskId == null) {
            return Result.error(ApiCode.TASK_NOT_FOUND);
        }
        boolean ok = store.rateTask(rateTaskId, user.uid(), request.getScore(), request.getContent());
        if (!ok) {
            return Result.error(ApiCode.TASK_NOT_FOUND);
        }
        return Result.success(null);
    }

    @PostMapping("/clarify")
    public Result<ClarifyTaskResponse> clarifyTask(
        @RequestBody ClarifyTaskRequest request,
        @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        MockAuthSupport.MockUser user = mockAuthSupport.requireUser(authorization);
        if (user == null) {
            return Result.error(ApiCode.USER_NOT_LOGGED_IN);
        }

        List<String> questions = new ArrayList<>();
        if (isBlank(request.getTaskTitle())) {
            questions.add("请补充作业标题或核心主题。");
        }
        if (isBlank(request.getTaskDesc())) {
            questions.add("请描述作业的具体要求、评分标准或老师说明。");
        }
        if (isBlank(request.getDueDate())) {
            questions.add("请补充截止时间（建议包含时区）。");
        }
        if (request.getPageLength() == null) {
            questions.add("请确认目标篇幅（页数或字数）。");
        }
        if (request.getCitationStyle() == null) {
            questions.add("请确认引用格式（如 APA / MLA / Chicago）。");
        }
        if (request.getFormat() == null || request.getFormat().isEmpty()) {
            questions.add("请确认输出格式（Word / PDF / PPT）。");
        }

        if (questions.isEmpty()) {
            questions = List.of(
                "是否有必须使用的参考文献或课程资料？",
                "老师是否要求特定结构（如引言-方法-结论）？",
                "是否需要附加图表、代码或附录？"
            );
        }

        ClarifyTaskResponse response = ClarifyTaskResponse.builder()
            .questions(questions.stream().limit(5).toList())
            .suggestions("请优先补充缺失信息，能显著提升任务产出质量。")
            .build();

        return Result.success(response);
    }

    @PostMapping("/delete")
    public Result<DeleteTasksResponse> deleteTasks(
        @Valid @RequestBody DeleteTasksRequest request,
        @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        MockAuthSupport.MockUser user = mockAuthSupport.requireUser(authorization);
        if (user == null) {
            return Result.error(ApiCode.USER_NOT_LOGGED_IN);
        }

        List<Long> internalIds = request.getTaskIds().stream()
            .map(TaskIdEncoder::decode)
            .filter(Objects::nonNull)
            .toList();
        MockStateStore.DeleteResult result = store.deleteTasks(internalIds, user.uid());
        DeleteTasksResponse response = DeleteTasksResponse.builder()
            .deletedCount(result.deletedCount())
            .failedTaskIds(result.failedTaskIds().stream().map(TaskIdEncoder::encode).toList())
            .build();
        return Result.success(response);
    }

    @GetMapping("/output/download/{outputId}")
    public ResponseEntity<Resource> downloadOutput(@PathVariable Long outputId) {
        String filename = "mock_output_" + outputId + ".md";
        String content = "# Mock Output\n\nThis is a mock output for task/output id " + outputId + ".\n";
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8);

        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("text/markdown; charset=utf-8"))
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + encodedFilename + "\"; filename*=UTF-8''" + encodedFilename)
            .body(new ByteArrayResource(bytes));
    }

    @GetMapping("/{taskId}/activities")
    public Result<TaskActivitiesPageResponse> getTaskActivities(
        @PathVariable Long taskId,
        @RequestParam(value = "pageNo", defaultValue = "1") Integer pageNo,
        @RequestParam(value = "pageSize", defaultValue = "10") Integer pageSize,
        @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        MockAuthSupport.MockUser user = mockAuthSupport.requireUser(authorization);
        if (user == null) {
            return Result.error(ApiCode.USER_NOT_LOGGED_IN);
        }

        MockStateStore.MockTaskRecord task = store.findTask(taskId)
            .filter(t -> user.uid().equals(t.clerkUserId))
            .orElse(null);
        if (task == null) {
            return Result.error(ApiCode.TASK_NOT_FOUND);
        }

        List<TaskDetailResponse.ActivityInfoResponse> all = List.of(
            TaskDetailResponse.ActivityInfoResponse.builder()
                .activityTime(toUnixSeconds(task.startTime))
                .agentName("System")
                .activityDesc("Task created")
                .build(),
            TaskDetailResponse.ActivityInfoResponse.builder()
                .activityTime(Math.max(toUnixSeconds(task.startTime), (System.currentTimeMillis() / 1000) - 150))
                .agentName("Task Planner")
                .activityDesc("Analyzing requirements")
                .build(),
            TaskDetailResponse.ActivityInfoResponse.builder()
                .activityTime(Math.max(toUnixSeconds(task.startTime), (System.currentTimeMillis() / 1000) - 60))
                .agentName("Writer")
                .activityDesc("Generating output draft")
                .build()
        ).stream().sorted(Comparator.comparingLong(TaskDetailResponse.ActivityInfoResponse::getActivityTime).reversed()).toList();

        int pn = pageNo == null || pageNo < 1 ? 1 : pageNo;
        int ps = pageSize == null || pageSize < 1 ? 10 : Math.min(pageSize, 100);

        int total = all.size();
        int fromIndex = Math.min((pn - 1) * ps, total);
        int toIndex = Math.min(fromIndex + ps, total);

        TaskActivitiesPageResponse response = TaskActivitiesPageResponse.builder()
            .activityList(all.subList(fromIndex, toIndex))
            .total(total)
            .pageNo(pn)
            .pageSize(ps)
            .build();
        return Result.success(response);
    }

    private TaskListItemResponse toTaskListItem(MockStateStore.MockTaskRecord task) {
        return TaskListItemResponse.builder()
            .id(TaskListItemResponse.IdValue.builder().value(TaskIdEncoder.encode(task.taskId)).build())
            .clerkUserId(task.clerkUserId)
            .taskTitle(task.taskTitle)
            .taskDesc(task.taskDesc)
            .subject(task.subject)
            .academicLevel(task.academicLevel)
            .priorityLevel(task.priorityLevel)
            .dueDate(task.dueDate)
            .format(task.format == null ? List.of() : task.format)
            .citationStyle(task.citationStyle)
            .pageLength(task.pageLength)
            .specialInstructions(task.specialInstructions)
            .status(task.status)
            .startTime(task.startTime)
            .finishTime(task.finishTime)
            .costTime(task.costTime)
            .completePercent(task.completePercent)
            .taskCompletedSize(MockStateStore.STATUS_COMPLETED.equals(task.status) ? 1 : 0)
            .activeAgentSize(MockStateStore.STATUS_IN_PROGRESS.equals(task.status) ? 1 : 0)
            .estRemainingTime(MockStateStore.STATUS_IN_PROGRESS.equals(task.status) ? 1800 : 0)
            .requirementJson(task.requirementsJson)
            .finalResult(MockStateStore.STATUS_COMPLETED.equals(task.status) ? "Mock final output" : null)
            .errorMessage(MockStateStore.STATUS_FAILED.equals(task.status) ? "Mock failure" : null)
            .queueAheadCount(task.queueAheadCount == null ? 0 : task.queueAheadCount)
            .build();
    }

    private List<TaskDetailResponse.UploadedFileInfoResponse> toUploadedFileInfo(
        List<String> objectIds,
        List<TaskDetailDTO.ClarifyingQuestionInfo> clarifyingQuestions
    ) {
        if (objectIds == null || objectIds.isEmpty()) {
            return List.of();
        }

        Map<String, String> clarifyQuestionIdByObjectId = clarifyQuestionIdByObjectId(clarifyingQuestions);

        return objectIds.stream()
            .map(store::findFile)
            .filter(Optional::isPresent)
            .map(Optional::get)
            .map(file -> TaskDetailResponse.UploadedFileInfoResponse.builder()
                .objectId(file.objectId)
                .fileName(file.filename)
                .fileType(file.filename.contains(".") ? file.filename.substring(file.filename.lastIndexOf('.') + 1) : "")
                .fileSize(file.fileSize)
                .uploadTime(file.uploadEpochSec)
                .downloadUrl("/v1/file/download/" + file.objectId)
                .attachmentSource(clarifyQuestionIdByObjectId.containsKey(file.objectId) ? "CLARIFY" : "TASK")
                .clarifyQuestionId(clarifyQuestionIdByObjectId.get(file.objectId))
                .build())
            .toList();
    }

    private List<TaskDetailResponse.ClarifyingQuestionInfoResponse> toClarifyingQuestionInfoResponse(
        List<TaskDetailDTO.ClarifyingQuestionInfo> clarifyingQuestions
    ) {
        if (clarifyingQuestions == null || clarifyingQuestions.isEmpty()) {
            return List.of();
        }
        return clarifyingQuestions.stream()
            .map(q -> TaskDetailResponse.ClarifyingQuestionInfoResponse.builder()
                .id(q.getId())
                .question(q.getQuestion())
                .tag(q.getTag())
                .answer(q.getAnswer())
                .skipped(q.getSkipped())
                .attachments(q.getAttachments() == null
                    ? List.of()
                    : q.getAttachments().stream()
                        .map(a -> TaskDetailResponse.ClarifyAttachmentInfoResponse.builder()
                            .objectId(a.getObjectId())
                            .filename(a.getFilename())
                            .build())
                        .toList())
                .build())
            .toList();
    }

    private Map<String, String> clarifyQuestionIdByObjectId(List<TaskDetailDTO.ClarifyingQuestionInfo> clarifyingQuestions) {
        if (clarifyingQuestions == null || clarifyingQuestions.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new HashMap<>();
        for (TaskDetailDTO.ClarifyingQuestionInfo q : clarifyingQuestions) {
            if (q == null || q.getAttachments() == null || isBlank(q.getId())) {
                continue;
            }
            for (TaskDetailDTO.ClarifyAttachmentInfo attachment : q.getAttachments()) {
                if (attachment != null && !isBlank(attachment.getObjectId())) {
                    result.putIfAbsent(attachment.getObjectId(), q.getId());
                }
            }
        }
        return result;
    }

    private int toTaskStatusCode(String status) {
        if (MockStateStore.STATUS_DRAFT.equals(status)) {
            return 0;
        }
        if (MockStateStore.STATUS_PENDING.equals(status)) {
            return 1;
        }
        if (MockStateStore.STATUS_IN_PROGRESS.equals(status)) {
            return 2;
        }
        if (MockStateStore.STATUS_COMPLETED.equals(status)) {
            return 3;
        }
        if (MockStateStore.STATUS_FAILED.equals(status)) {
            return 4;
        }
        if (MockStateStore.STATUS_CANCELLED.equals(status)) {
            return 5;
        }
        return 0;
    }

    private long toUnixSeconds(String localDateTimeText) {
        if (localDateTimeText == null || localDateTimeText.isBlank()) {
            return 0L;
        }
        try {
            return LocalDateTime.parse(localDateTimeText).atZone(ZoneId.systemDefault()).toEpochSecond();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private String defaultString(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private String titleFromDescription(String taskDesc, String fallback) {
        if (taskDesc == null || taskDesc.isBlank()) {
            return fallback;
        }
        String normalized = taskDesc.trim().replaceAll("\\s+", " ");
        return normalized.length() <= 60 ? normalized : normalized.substring(0, 60);
    }

    private String mergeRequirementJson(String existingJson, String requirementsJson, String clarifyingQuestions) {
        boolean hasRequirements = requirementsJson != null && !requirementsJson.trim().isEmpty();
        boolean hasClarifying = clarifyingQuestions != null && !clarifyingQuestions.trim().isEmpty();
        if (!hasRequirements && !hasClarifying) {
            return existingJson;
        }

        Map<String, Object> merged = new LinkedHashMap<>();
        if (existingJson != null && !existingJson.trim().isEmpty()) {
            Object parsedExisting = parseJsonOrString(existingJson);
            if (parsedExisting instanceof Map<?, ?> existingMap) {
                for (Map.Entry<?, ?> entry : existingMap.entrySet()) {
                    if (entry.getKey() != null) {
                        merged.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                }
            } else {
                merged.put("existingRequirementJson", parsedExisting);
            }
        }
        if (hasRequirements) {
            merged.put("requirementsJson", parseJsonOrString(requirementsJson));
        }
        if (hasClarifying) {
            merged.put("clarifyingQuestions", clarifyingQuestions);
        }
        return GSON.toJson(merged);
    }

    private Object parseJsonOrString(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return raw;
        }
        try {
            return GSON.fromJson(raw, Object.class);
        } catch (JsonSyntaxException e) {
            return raw;
        }
    }

    private List<String> mergeObjectIdsForTaskFiles(
        List<String> mainObjectIds,
        String clarifyingQuestionsJson,
        String requirementJson
    ) {
        List<String> result = new ArrayList<>();
        if (mainObjectIds != null) {
            for (String objectId : mainObjectIds) {
                addDistinctObjectId(result, objectId);
            }
        }
        appendClarifyingAttachmentObjectIdsFromArrayJson(clarifyingQuestionsJson, result);
        appendClarifyingAttachmentObjectIdsFromRequirementJson(requirementJson, result);
        return result;
    }

    private void appendClarifyingAttachmentObjectIdsFromRequirementJson(String requirementJson, List<String> result) {
        if (requirementJson == null || requirementJson.isBlank()) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(requirementJson).getAsJsonObject();
            if (!root.has("clarifyingQuestions")) {
                return;
            }
            JsonArray clarifyingQuestions = resolveClarifyingArray(root.get("clarifyingQuestions"));
            appendClarifyingAttachmentObjectIds(clarifyingQuestions, result);
        } catch (Exception ignored) {
            // Mock 环境容忍不完整的 requirementJson，避免联调被历史草稿阻断。
        }
    }

    private void appendClarifyingAttachmentObjectIdsFromArrayJson(String clarifyingQuestionsJson, List<String> result) {
        if (clarifyingQuestionsJson == null || clarifyingQuestionsJson.isBlank()) {
            return;
        }
        try {
            JsonArray clarifyingQuestions = JsonParser.parseString(clarifyingQuestionsJson).getAsJsonArray();
            appendClarifyingAttachmentObjectIds(clarifyingQuestions, result);
        } catch (Exception ignored) {
            // Mock 环境容忍前端开发时的临时 payload。
        }
    }

    private JsonArray resolveClarifyingArray(JsonElement raw) {
        if (raw == null || raw.isJsonNull()) {
            return null;
        }
        if (raw.isJsonArray()) {
            return raw.getAsJsonArray();
        }
        if (raw.isJsonPrimitive() && raw.getAsJsonPrimitive().isString()) {
            String json = raw.getAsString();
            if (json == null || json.isBlank()) {
                return null;
            }
            JsonElement parsed = JsonParser.parseString(json);
            return parsed.isJsonArray() ? parsed.getAsJsonArray() : null;
        }
        return null;
    }

    private void appendClarifyingAttachmentObjectIds(JsonArray clarifyingQuestions, List<String> result) {
        if (clarifyingQuestions == null || clarifyingQuestions.isEmpty()) {
            return;
        }
        for (JsonElement item : clarifyingQuestions) {
            if (!item.isJsonObject()) {
                continue;
            }
            JsonObject question = item.getAsJsonObject();
            if (question.has("attachments") && question.get("attachments").isJsonArray()) {
                for (JsonElement attachmentEl : question.get("attachments").getAsJsonArray()) {
                    if (!attachmentEl.isJsonObject()) {
                        continue;
                    }
                    JsonObject attachment = attachmentEl.getAsJsonObject();
                    addDistinctObjectId(result, firstJsonString(attachment, "objectId", "object_id"));
                }
            }
            addDistinctObjectId(result, firstJsonString(question, "attachmentObjectId", "attachment_object_id"));
        }
    }

    private String firstJsonString(JsonObject object, String... keys) {
        if (object == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (object.has(key) && !object.get(key).isJsonNull()) {
                return object.get(key).getAsString();
            }
        }
        return null;
    }

    private void addDistinctObjectId(List<String> result, String objectId) {
        if (objectId == null || objectId.isBlank()) {
            return;
        }
        String normalized = objectId.trim();
        if (!result.contains(normalized)) {
            result.add(normalized);
        }
    }

    private boolean containsFailTrigger(SubmitTaskRequest request) {
        return containsIgnoreCase(request.getTaskDesc(), "fail")
            || containsIgnoreCase(request.getSpecialInstructions(), "fail")
            || containsIgnoreCase(request.getRequirementsJson(), "fail")
            || containsIgnoreCase(request.getClarifyingQuestions(), "fail");
    }

    private boolean containsCostTrigger(SubmitTaskRequest request) {
        return containsIgnoreCase(request.getTaskDesc(), "cost")
            || containsIgnoreCase(request.getSpecialInstructions(), "cost")
            || containsIgnoreCase(request.getRequirementsJson(), "cost")
            || containsIgnoreCase(request.getClarifyingQuestions(), "cost");
    }

    private boolean containsIgnoreCase(String source, String keyword) {
        return source != null && source.toLowerCase().contains(keyword);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private double round(double value, int scale) {
        double factor = Math.pow(10, scale);
        return Math.round(value * factor) / factor;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TaskActivitiesPageResponse {
        private List<TaskDetailResponse.ActivityInfoResponse> activityList;
        private Integer total;
        private Integer pageNo;
        private Integer pageSize;
    }
}
