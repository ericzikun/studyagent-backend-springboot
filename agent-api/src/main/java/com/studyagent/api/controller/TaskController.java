package com.studyagent.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.studyagent.api.common.Result;
import com.studyagent.api.dto.request.ClarifyTaskRequest;
import com.studyagent.api.dto.request.RateTaskRequest;
import com.studyagent.api.dto.request.SaveDraftRequest;
import com.studyagent.api.dto.request.StopTaskRequest;
import com.studyagent.api.dto.request.SubmitTaskRequest;
import com.studyagent.api.dto.request.TaskDetailRequest;
import com.studyagent.api.dto.request.TaskListRequest;
import com.studyagent.api.dto.response.ClarifyTaskResponse;
import com.studyagent.api.dto.response.SaveDraftResponse;
import com.studyagent.api.dto.response.StopTaskResponse;
import com.studyagent.api.dto.response.SubmitTaskResponse;
import com.studyagent.api.dto.response.TaskDetailResponse;
import com.studyagent.api.dto.response.TaskListResponse;
import com.studyagent.api.dto.response.TaskListItemResponse;
import com.studyagent.api.dto.response.TaskSummaryResponse;
import com.studyagent.infra.entity.TaskEntity;
import com.studyagent.infra.entity.TaskAgentEntity;
import com.studyagent.infra.entity.SubTaskEntity;
import com.studyagent.infra.entity.TaskActivityEntity;
import com.studyagent.infra.entity.TaskOutputEntity;
import com.studyagent.infra.mapper.TaskMapper;
import com.studyagent.infra.mapper.TaskAgentMapper;
import com.studyagent.infra.mapper.SubTaskMapper;
import com.studyagent.infra.mapper.TaskActivityMapper;
import com.studyagent.infra.mapper.TaskOutputMapper;
import com.studyagent.infra.mapper.TaskFileMapper;
import com.studyagent.infra.mapper.FileMapper;
import com.studyagent.infra.entity.TaskFileEntity;
import com.studyagent.infra.entity.FileEntity;
import com.studyagent.service.application.TaskApplicationService;
import com.studyagent.service.domain.task.PythonBackendClient;
import com.studyagent.service.domain.task.Task;
import com.studyagent.service.domain.task.TaskId;
import com.studyagent.service.domain.task.TaskRepository;
import com.studyagent.service.domain.task.TaskStatus;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.lang.reflect.Type;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 任务管理控制器
 */
@Slf4j
@RestController
@RequestMapping("/v1/task")
@RequiredArgsConstructor
public class TaskController {
    
    private final TaskApplicationService taskApplicationService;
    private final PythonBackendClient pythonBackendClient;
    private final TaskMapper taskMapper;
    private final TaskAgentMapper taskAgentMapper;
    private final SubTaskMapper subTaskMapper;
    private final TaskActivityMapper taskActivityMapper;
    private final TaskOutputMapper taskOutputMapper;
    private final TaskFileMapper taskFileMapper;
    private final FileMapper fileMapper;
    private final Gson gson = new Gson();
    private final Type formatListType = new TypeToken<List<Integer>>(){}.getType();
    
    @PostMapping("/submit")
    public Result<SubmitTaskResponse> submitTask(
            @Valid @RequestBody SubmitTaskRequest request,
            @RequestHeader("Authorization") String token) {
        // 将 API 层的 Request DTO 转换为应用层的 Request Model
        com.studyagent.service.application.request.SubmitTaskRequest appRequest = 
            com.studyagent.service.application.request.SubmitTaskRequest.builder()
                .draftId(request.getDraftId())
                .taskTitle(request.getTaskTitle())
                .taskDesc(request.getTaskDesc())
                .subject(request.getSubject())
                .academicLevel(request.getAcademicLevel())
                .priorityLevel(request.getPriorityLevel())
                .dueDate(request.getDueDate())
                .format(request.getFormat())
                .citationStyle(request.getCitationStyle())
                .pageLength(request.getPageLength())
                .specialInstructions(request.getSpecialInstructions())
                .objectIds(request.getObjectIds())
                .clarifyingQuestions(request.getClarifyingQuestions())
                .requirementsJson(request.getRequirementsJson())
                .token(token)
                .build();
        
        Long taskId = taskApplicationService.submitTask(appRequest);
        
        SubmitTaskResponse response = SubmitTaskResponse.builder()
            .taskId(taskId)
            .build();
        
        return Result.success(response);
    }

    @PostMapping("/save-draft")
    public Result<SaveDraftResponse> saveDraft(
            @RequestBody SaveDraftRequest request,
            @RequestHeader("Authorization") String token) {
        // 将 API 层的 Request DTO 转换为应用层的 Request Model
        com.studyagent.service.application.request.SaveDraftRequest appRequest =
            com.studyagent.service.application.request.SaveDraftRequest.builder()
                .draftId(request.getDraftId())
                .taskTitle(request.getTaskTitle())
                .taskDesc(request.getTaskDesc())
                .subject(request.getSubject())
                .academicLevel(request.getAcademicLevel())
                .priorityLevel(request.getPriorityLevel())
                .dueDate(request.getDueDate())
                .objectIds(request.getObjectIds())
                .format(request.getFormat())
                .citationStyle(request.getCitationStyle())
                .pageLength(request.getPageLength())
                .specialInstructions(request.getSpecialInstructions())
                .clarifyingQuestions(request.getClarifyingQuestions())
                .requirementsJson(request.getRequirementsJson())
                .token(token)
                .build();

        Long draftId = taskApplicationService.saveDraft(appRequest);

        SaveDraftResponse response = SaveDraftResponse.builder()
            .draftId(draftId)
            .savedAt(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
            .build();

        return Result.success(response);
    }

    @PostMapping("/stop")
    public Result<StopTaskResponse> stopTask(
            @Valid @RequestBody StopTaskRequest request,
            @RequestHeader("Authorization") String token) {
        com.studyagent.service.application.request.StopTaskRequest appRequest =
            com.studyagent.service.application.request.StopTaskRequest.builder()
                .taskId(request.getTaskId())
                .token(token)
                .build();

        Long taskId = taskApplicationService.stopTask(appRequest);

        StopTaskResponse response = StopTaskResponse.builder()
            .taskId(taskId)
            .message("任务已停止")
            .build();

        return Result.success(response);
    }
    
    @PostMapping("/list")
    public Result<TaskListResponse> getTaskList(
            @RequestBody(required = false) TaskListRequest request,
            @RequestHeader(value = "Authorization", required = false) String token,
            @RequestAttribute(value = "clerkUserId", required = false) String clerkUserId) {
        // 从拦截器获取用户ID（拦截器已验证token并将用户ID设置到request attribute）
        if (clerkUserId == null || clerkUserId.isEmpty()) {
            return Result.error("用户未登录");
        }
        
        // 如果 request 为 null，使用默认值
        if (request == null) {
            request = new TaskListRequest();
            request.setPageNo(1);
            request.setPageSize(10);
        }
        
        // 设置默认值
        if (request.getPageNo() == null || request.getPageNo() < 1) {
            request.setPageNo(1);
        }
        if (request.getPageSize() == null || request.getPageSize() < 1) {
            request.setPageSize(10);
        }
        
        // 将 API 层的 Request DTO 转换为应用层的 Request Model
        com.studyagent.service.application.request.GetTaskListRequest appRequest = 
            com.studyagent.service.application.request.GetTaskListRequest.builder()
                .clerkUserId(clerkUserId)
                .status(request.getTaskStatus())
                .keyword(request.getTaskKeyword())
                .order(request.getOrder())
                .pageNo(request.getPageNo())
                .pageSize(request.getPageSize())
                .build();
        
        // 调用应用服务，获取分页结果
        TaskRepository.PageResult<Task> pageResult = taskApplicationService.getTaskList(appRequest);
        
        // 批量查询队列信息，避免逐条请求
        Map<Long, PythonBackendClient.TaskQueueInfo> queueInfoMap = fetchQueueInfoBatch(pageResult.getItems());
        
        // 转换为响应 DTO（驼峰命名）
        List<TaskListItemResponse> taskListItems = pageResult.getItems().stream()
            .map(task -> convertToTaskListItemResponse(task, queueInfoMap.get(task.getId().getValue())))
            .collect(Collectors.toList());
        
        TaskListResponse response = TaskListResponse.builder()
            .taskList(taskListItems)
            .total(pageResult.getTotal().intValue())
            .pageNo(request.getPageNo())
            .pageSize(request.getPageSize())
            .build();
        
        return Result.success(response);
    }
    
    /**
     * 获取任务统计数据（当前用户）
     * @param clerkUserId 用户ID（从拦截器获取）
     * @return 任务统计数据
     */
    @GetMapping("/summary")
    public Result<TaskSummaryResponse> getTaskSummary(
            @RequestAttribute(value = "clerkUserId", required = false) String clerkUserId) {
        if (clerkUserId == null || clerkUserId.isEmpty()) {
            return Result.error("用户未登录");
        }
        
        // 调用应用服务获取统计数据
        TaskApplicationService.TaskSummaryData summaryData = taskApplicationService.getTaskSummary(clerkUserId);
        
        // 转换为响应 DTO
        TaskSummaryResponse response = TaskSummaryResponse.builder()
            .taskCompletedSize(summaryData.getTaskCompletedSize())
            .taskInProgressSize(summaryData.getTaskInProgressSize())
            .avgQuality(summaryData.getAvgQuality())
            .build();
        
        return Result.success(response);
    }
    
    /**
     * GET 方法支持（用于浏览器直接访问或测试）
     * 使用查询参数，兼容 POST 方法的功能
     */
    @GetMapping("/list")
    public Result<TaskListResponse> getTaskListByGet(
            @RequestParam(value = "taskKeyword", required = false) String taskKeyword,
            @RequestParam(value = "taskStatus", required = false) Integer taskStatus,
            @RequestParam(value = "order", required = false) Integer order,
            @RequestParam(value = "pageNo", defaultValue = "1") Integer pageNo,
            @RequestParam(value = "pageSize", defaultValue = "10") Integer pageSize,
            @RequestHeader(value = "Authorization", required = false) String token,
            @RequestAttribute(value = "clerkUserId", required = false) String clerkUserId) {
        // 从拦截器获取用户ID（拦截器已验证token并将用户ID设置到request attribute）
        if (clerkUserId == null || clerkUserId.isEmpty()) {
            return Result.error("用户未登录");
        }
        
        // 设置默认值
        if (pageNo == null || pageNo < 1) {
            pageNo = 1;
        }
        if (pageSize == null || pageSize < 1) {
            pageSize = 10;
        }
        
        // 将查询参数转换为应用层的 Request Model
        com.studyagent.service.application.request.GetTaskListRequest appRequest = 
            com.studyagent.service.application.request.GetTaskListRequest.builder()
                .clerkUserId(clerkUserId)
                .status(taskStatus)
                .keyword(taskKeyword)
                .order(order)
                .pageNo(pageNo)
                .pageSize(pageSize)
                .build();
        
        // 调用应用服务，获取分页结果
        TaskRepository.PageResult<Task> pageResult = taskApplicationService.getTaskList(appRequest);
        
        // 批量查询队列信息，避免逐条请求
        Map<Long, PythonBackendClient.TaskQueueInfo> queueInfoMap = fetchQueueInfoBatch(pageResult.getItems());
        
        // 转换为响应 DTO（驼峰命名）
        List<TaskListItemResponse> taskListItems = pageResult.getItems().stream()
            .map(task -> convertToTaskListItemResponse(task, queueInfoMap.get(task.getId().getValue())))
            .collect(Collectors.toList());
        
        TaskListResponse response = TaskListResponse.builder()
            .taskList(taskListItems)
            .total(pageResult.getTotal().intValue())
            .pageNo(pageNo)
            .pageSize(pageSize)
            .build();
        
        return Result.success(response);
    }
    
    /**
     * 将 Task 域对象转换为 TaskListItemResponse DTO
     */
    private TaskListItemResponse convertToTaskListItemResponse(
            Task task,
            PythonBackendClient.TaskQueueInfo queueInfo
    ) {
        DateTimeFormatter formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
        int queueAheadCount = 0;
        if (task.getStatus() == TaskStatus.IN_PROGRESS && queueInfo != null) {
            queueAheadCount = queueInfo.getAheadCount();
        }
        
        return TaskListItemResponse.builder()
            .id(TaskListItemResponse.IdValue.builder()
                .value(task.getId().getValue())
                .build())
            .clerkUserId(task.getClerkUserId())
            .taskTitle(task.getTaskTitle())
            .taskDesc(task.getTaskDesc())
            .subject(task.getSubject())
            .academicLevel(task.getAcademicLevel())
            .priorityLevel(task.getPriorityLevel())
            .dueDate(task.getDueDate() != null ? task.getDueDate().format(formatter) : null)
            .format(task.getFormat())
            .citationStyle(task.getCitationStyle())
            .pageLength(task.getPageLength())
            .specialInstructions(task.getSpecialInstructions())
            .status(task.getStatus().name()) // 枚举转字符串
            .startTime(task.getStartTime() != null ? task.getStartTime().format(formatter) : null)
            .finishTime(task.getFinishTime() != null ? task.getFinishTime().format(formatter) : null)
            .costTime(task.getCostTime())
            .completePercent(task.getCompletePercent())
            .taskCompletedSize(task.getTaskCompletedSize())
            .activeAgentSize(task.getActiveAgentSize())
            .estRemainingTime(task.getEstRemainingTime())
            .requirementJson(task.getRequirementJson())
            .finalResult(task.getFinalResult())
            .errorMessage(task.getErrorMessage())
            .queueAheadCount(queueAheadCount)
            .build();
    }

    private Map<Long, PythonBackendClient.TaskQueueInfo> fetchQueueInfoBatch(List<Task> tasks) {
        try {
            List<TaskId> taskIds = tasks.stream()
                .filter(task -> task.getStatus() == TaskStatus.IN_PROGRESS)
                .map(Task::getId)
                .collect(Collectors.toList());
            if (taskIds.isEmpty()) {
                return new HashMap<>();
            }
            return pythonBackendClient.getTaskQueueBatchInfo(taskIds);
        } catch (Exception e) {
            log.warn("批量获取任务队列信息失败: error={}", e.getMessage());
            return new HashMap<>();
        }
    }
    
    @PostMapping("/detail")
    public Result<TaskDetailResponse> getTaskDetail(
            @Valid @RequestBody TaskDetailRequest request,
            @RequestHeader(value = "Authorization", required = false) String token,
            @RequestAttribute(value = "clerkUserId", required = false) String clerkUserId) {
        // 从拦截器获取用户ID（拦截器已验证token并将用户ID设置到request attribute）
        if (clerkUserId == null || clerkUserId.isEmpty()) {
            return Result.error("用户未登录");
        }
        
        Long taskId = request.getTaskId();
        
        // 1. 查询任务基本信息
        TaskEntity taskEntity = taskMapper.selectById(taskId);
        if (taskEntity == null) {
            return Result.error(1003, "任务不存在");
        }
        
        // 2. 验证任务是否属于当前用户
        if (!clerkUserId.equals(taskEntity.getClerkUserId())) {
            return Result.error(1004, "无权访问该任务");
        }

        int queueAheadCount = 0;
        try {
            PythonBackendClient.TaskQueueInfo queueInfo = pythonBackendClient.getTaskQueueInfo(TaskId.of(taskId));
            if (queueInfo != null) {
                queueAheadCount = queueInfo.getAheadCount();
            }
        } catch (Exception e) {
            log.warn("获取任务队列信息失败: task_id={}, error={}", taskId, e.getMessage());
        }
        
        // 2. 构建任务基础信息
        List<Integer> formatList = new ArrayList<>();
        if (taskEntity.getFormat() != null && !taskEntity.getFormat().isEmpty()) {
            try {
                formatList = gson.fromJson(taskEntity.getFormat(), formatListType);
            } catch (Exception e) {
                // 解析失败，使用空列表
            }
        }
        
        TaskDetailResponse.TaskBaseInfoResponse taskBaseInfo = TaskDetailResponse.TaskBaseInfoResponse.builder()
            .taskTitle(taskEntity.getTaskTitle())
            .taskDesc(taskEntity.getTaskDesc() != null ? taskEntity.getTaskDesc() : "")
            .taskStatus(taskEntity.getStatus())
            .startTime(taskEntity.getStartTime() != null ? 
                taskEntity.getStartTime().atZone(ZoneId.systemDefault()).toEpochSecond() : 0L)
            .dueTime(taskEntity.getDueDate() != null ? 
                taskEntity.getDueDate().atZone(ZoneId.systemDefault()).toEpochSecond() : 0L)
            .finishTime(taskEntity.getFinishTime() != null ? 
                taskEntity.getFinishTime().atZone(ZoneId.systemDefault()).toEpochSecond() : 0L)
            .costTime(taskEntity.getCostTime() != null ? taskEntity.getCostTime() : 0)
            .subject(taskEntity.getSubject())
            .academicLevel(taskEntity.getAcademicLevel())
            .priorityLevel(taskEntity.getPriorityLevel() != null ? taskEntity.getPriorityLevel() : 0)
            .citationStyle(taskEntity.getCitationStyle() != null ? taskEntity.getCitationStyle() : 0)
            .pageLength(taskEntity.getPageLength() != null ? taskEntity.getPageLength() : 0)
            .formatList(formatList)
            .specialInstructions(taskEntity.getSpecialInstructions() != null ? taskEntity.getSpecialInstructions() : "")
            .completePercent(taskEntity.getCompletePercent() != null ? 
                taskEntity.getCompletePercent().doubleValue() : 0.0)
            .taskCompletedSize(taskEntity.getTaskCompletedSize() != null ? taskEntity.getTaskCompletedSize() : 0)
            .activeAgentSize(taskEntity.getActiveAgentSize() != null ? taskEntity.getActiveAgentSize() : 0)
            .estRemainingTime(taskEntity.getEstRemainingTime() != null ? taskEntity.getEstRemainingTime() : 0)
            .queueAheadCount(queueAheadCount)
            .requirementJson(taskEntity.getRequirementJson())
            .build();
        
        // 3. 查询子任务信息列表
        List<SubTaskEntity> subTaskEntities = subTaskMapper.selectList(
            new LambdaQueryWrapper<SubTaskEntity>()
                .eq(SubTaskEntity::getTaskId, taskId)
                .orderByAsc(SubTaskEntity::getOrderIndex)
        );
        
        List<TaskDetailResponse.SubTaskInfoResponse> subTaskInfoList = subTaskEntities.stream()
            .map(st -> TaskDetailResponse.SubTaskInfoResponse.builder()
                .title(st.getTitle())
                .desc(st.getDescription() != null ? st.getDescription() : "")
                .processDesc(st.getProcessDesc() != null ? st.getProcessDesc() : "")
                .agentName(st.getAgentName() != null ? st.getAgentName() : "")
                .build())
            .collect(Collectors.toList());
        
        // 按 Agent 分组
        Map<String, List<TaskDetailResponse.SubTaskInfoResponse>> subTaskInfoMap = subTaskEntities.stream()
            .collect(Collectors.groupingBy(
                st -> st.getAgentName() != null && !st.getAgentName().isEmpty() ? st.getAgentName() : "未分配",
                Collectors.mapping(
                    st -> TaskDetailResponse.SubTaskInfoResponse.builder()
                        .title(st.getTitle())
                        .desc(st.getDescription() != null ? st.getDescription() : "")
                        .processDesc(st.getProcessDesc() != null ? st.getProcessDesc() : "")
                        .agentName(st.getAgentName() != null ? st.getAgentName() : "")
                        .build(),
                    Collectors.toList()
                )
            ));
        
        // 4. 查询活动日志（最近50条，按时间降序）
        List<TaskActivityEntity> activityEntities = taskActivityMapper.selectList(
            new LambdaQueryWrapper<TaskActivityEntity>()
                .eq(TaskActivityEntity::getTaskId, taskId)
                .orderByDesc(TaskActivityEntity::getActivityTime)
                .last("LIMIT 50")
        );
        
        List<TaskDetailResponse.ActivityInfoResponse> activityInfoList = activityEntities.stream()
            .map(act -> TaskDetailResponse.ActivityInfoResponse.builder()
                .activityTime(act.getActivityTime() != null ? 
                    act.getActivityTime().atZone(ZoneId.systemDefault()).toEpochSecond() : 0L)
                .agentName(act.getAgentName())
                .activityDesc(act.getActivityDesc())
                .build())
            .collect(Collectors.toList());
        
        // 按时间戳映射（使用组合键避免重复：时间戳_agent名称_描述前缀）
        Map<String, TaskDetailResponse.ActivityInfoResponse> activityInfoMap = new HashMap<>();
        for (TaskActivityEntity act : activityEntities) {
            long timestamp = act.getActivityTime() != null ? 
                act.getActivityTime().atZone(ZoneId.systemDefault()).toEpochSecond() : 0L;
            String agentName = act.getAgentName() != null ? act.getAgentName() : "";
            String activityDesc = act.getActivityDesc() != null ? act.getActivityDesc() : "";
            // 使用组合键：时间戳_agent名称_描述前缀（取前20个字符）
            String descPrefix = activityDesc.length() > 20 ? activityDesc.substring(0, 20) : activityDesc;
            String key = timestamp + "_" + agentName + "_" + descPrefix.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5]", "_");
            
            // 如果键已存在，添加序号后缀
            String finalKey = key;
            int suffix = 1;
            while (activityInfoMap.containsKey(finalKey)) {
                finalKey = key + "_" + suffix;
                suffix++;
            }
            
            activityInfoMap.put(finalKey, TaskDetailResponse.ActivityInfoResponse.builder()
                .activityTime(timestamp)
                .agentName(agentName)
                .activityDesc(activityDesc)
                .build());
        }
        
        // 5. 查询 Agent 信息列表
        List<TaskAgentEntity> agentEntities = taskAgentMapper.selectList(
            new LambdaQueryWrapper<TaskAgentEntity>()
                .eq(TaskAgentEntity::getTaskId, taskId)
        );
        
        List<TaskDetailResponse.AgentInfoResponse> agentInfoList;
        if (!agentEntities.isEmpty()) {
            agentInfoList = agentEntities.stream()
                .map(agent -> TaskDetailResponse.AgentInfoResponse.builder()
                    .agentName(agent.getAgentName())
                    .agentStatus(agent.getAgentStatus())
                    .completePercent(agent.getCompletePercent() != null ? 
                        agent.getCompletePercent().doubleValue() : 0.0)
                    .agentDesc(agent.getAgentDesc() != null ? agent.getAgentDesc() : "")
                    .agentStartTime(agent.getAgentStartTime() != null ? 
                        agent.getAgentStartTime().atZone(ZoneId.systemDefault()).toEpochSecond() : 0L)
                    .agentPriority(agent.getAgentPriority() != null ? agent.getAgentPriority() : 1)
                    .agentOutput(agent.getAgentOutput() != null ? agent.getAgentOutput() : "")
                    .build())
                .collect(Collectors.toList());
        } else {
            // 如果 Agent 列表为空，尝试从子任务和活动日志中提取 Agent 信息
            agentInfoList = extractAgentsFromSubtasksAndActivities(subTaskEntities, activityEntities);
        }
        
        // 6. 查询输出文件
        List<TaskOutputEntity> outputEntities = taskOutputMapper.selectList(
            new LambdaQueryWrapper<TaskOutputEntity>()
                .eq(TaskOutputEntity::getTaskId, taskId)
        );
        
        List<TaskDetailResponse.OutputInfoResponse> outputDetailInfoList = outputEntities.stream()
            .map(out -> {
                // 构建下载URL：/v1/task/output/download/{outputId}
                String downloadUrl = "/v1/task/output/download/" + out.getId();
                
                return TaskDetailResponse.OutputInfoResponse.builder()
                    .title(out.getTitle())
                    .desc(out.getDescription() != null ? out.getDescription() : "")
                    .url(downloadUrl) // 使用构建的下载URL
                    .sizeDesc(out.getSizeDesc() != null ? out.getSizeDesc() : "")
                    .pageSize(out.getPageSize() != null ? out.getPageSize() : 0)
                    .format(out.getFormat() != null && out.getFormat() <= 4 ? out.getFormat() : 4) // 修正无效的format值
                    .outputType(out.getOutputType() != null ? out.getOutputType() : 0) // 输出类型：0-日志文件，1-报告内容
                    .build();
            })
            .collect(Collectors.toList());
        
        // 主输出（取第一个终稿，output_type=1）
        TaskOutputEntity mainOutput = outputEntities.stream()
            .filter(o -> o.getOutputType() != null && o.getOutputType() == 1)
            .findFirst()
            .orElse(outputEntities.isEmpty() ? null : outputEntities.get(0));
        
        TaskDetailResponse.OutputInfoResponse outputSummaryInfo = null;
        if (mainOutput != null) {
            // 构建下载URL：/v1/task/output/download/{outputId}
            String downloadUrl = "/v1/task/output/download/" + mainOutput.getId();
            
            outputSummaryInfo = TaskDetailResponse.OutputInfoResponse.builder()
                .title(mainOutput.getTitle())
                .desc(mainOutput.getDescription() != null ? mainOutput.getDescription() : "")
                .url(downloadUrl) // 使用构建的下载URL
                .sizeDesc(mainOutput.getSizeDesc() != null ? mainOutput.getSizeDesc() : "")
                .pageSize(mainOutput.getPageSize() != null ? mainOutput.getPageSize() : 0)
                .format(mainOutput.getFormat() != null && mainOutput.getFormat() <= 4 ? 
                    mainOutput.getFormat() : 4) // 修正无效的format值
                .outputType(mainOutput.getOutputType() != null ? mainOutput.getOutputType() : 0) // 输出类型：0-日志文件，1-报告内容
                .build();
        }
        
        // 7. 查询上传的文件信息
        List<TaskFileEntity> taskFileEntities = taskFileMapper.selectList(
            new LambdaQueryWrapper<TaskFileEntity>()
                .eq(TaskFileEntity::getTaskId, taskId)
                .orderByAsc(TaskFileEntity::getFileOrder)
        );
        
        Map<Long, FileEntity> fileEntityById = new HashMap<>();
        if (!taskFileEntities.isEmpty()) {
            Set<Long> fileIds = taskFileEntities.stream()
                .map(TaskFileEntity::getFileId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
            if (!fileIds.isEmpty()) {
                List<FileEntity> fileEntities = fileMapper.selectBatchIds(fileIds);
                fileEntityById = fileEntities.stream()
                    .filter(Objects::nonNull)
                    .collect(Collectors.toMap(FileEntity::getId, file -> file));
            }
        }
        
        List<TaskDetailResponse.UploadedFileInfoResponse> uploadedFileInfoList = new ArrayList<>();
        for (TaskFileEntity taskFile : taskFileEntities) {
            FileEntity fileEntity = fileEntityById.get(taskFile.getFileId());
            if (fileEntity != null) {
                // 处理文件扩展名（去掉点）
                String fileType = fileEntity.getFileExtension();
                if (fileType != null && fileType.startsWith(".")) {
                    fileType = fileType.substring(1);
                }
                
                // 构建下载链接
                String downloadUrl = "/v1/file/download/" + fileEntity.getObjectId();
                
                uploadedFileInfoList.add(
                    TaskDetailResponse.UploadedFileInfoResponse.builder()
                        .objectId(fileEntity.getObjectId())
                        .fileName(fileEntity.getOriginalFilename())
                        .fileType(fileType)
                        .fileSize(fileEntity.getFileSize())
                        .uploadTime(fileEntity.getCreatedAt() != null ? 
                            fileEntity.getCreatedAt().atZone(ZoneId.systemDefault()).toEpochSecond() : null)
                        .downloadUrl(downloadUrl)
                        .build()
                );
            }
        }
        
        // 构建完整响应
        TaskDetailResponse response = TaskDetailResponse.builder()
            .taskBaseInfo(taskBaseInfo)
            .agentInfoList(agentInfoList)
            .subTaskInfoList(subTaskInfoList)
            .subTaskInfoMap(subTaskInfoMap)
            .activityInfoList(activityInfoList)
            .activityInfoMap(activityInfoMap)
            .outputSummaryInfo(outputSummaryInfo)
            .outputDetailInfoList(outputDetailInfoList)
            .uploadedFileInfoList(uploadedFileInfoList)
            .build();
        
        return Result.success(response);
    }
    
    /**
     * 从子任务和活动日志中提取 Agent 信息（当 task_agents 表为空时使用）
     */
    private List<TaskDetailResponse.AgentInfoResponse> extractAgentsFromSubtasksAndActivities(
            List<SubTaskEntity> subtasks,
            List<TaskActivityEntity> activities) {
        if (subtasks == null) {
            subtasks = new ArrayList<>();
        }
        if (activities == null) {
            activities = new ArrayList<>();
        }
        
        // 从子任务中提取 Agent 名称
        Set<String> agentNamesFromSubtasks = subtasks.stream()
            .filter(st -> st.getAgentName() != null && !st.getAgentName().trim().isEmpty())
            .map(st -> st.getAgentName().trim())
            .collect(Collectors.toSet());
        
        // 从活动日志中提取 Agent 名称
        Set<String> agentNamesFromActivities = activities.stream()
            .filter(act -> act.getAgentName() != null && !act.getAgentName().trim().isEmpty())
            .map(act -> act.getAgentName().trim())
            .collect(Collectors.toSet());
        
        // 合并所有 Agent 名称
        Set<String> allAgentNames = new HashSet<>();
        allAgentNames.addAll(agentNamesFromSubtasks);
        allAgentNames.addAll(agentNamesFromActivities);
        
        if (allAgentNames.isEmpty()) {
            return new ArrayList<>();
        }
        
        // 为每个 Agent 创建 AgentInfo
        List<TaskDetailResponse.AgentInfoResponse> agentInfoList = new ArrayList<>();
        for (String agentName : allAgentNames.stream().sorted().collect(Collectors.toList())) {
            // 计算该 Agent 的完成百分比（基于其负责的子任务）
            List<SubTaskEntity> agentSubtasks = subtasks.stream()
                .filter(st -> agentName.equals(st.getAgentName()))
                .collect(Collectors.toList());
            
            double completePercent = 0.0;
            if (!agentSubtasks.isEmpty()) {
                long completedCount = agentSubtasks.stream()
                    .filter(st -> st.getStatus() != null && st.getStatus() == 2) // 2-已完成
                    .count();
                completePercent = (completedCount * 100.0) / agentSubtasks.size();
            }
            
            // 获取 Agent 的开始时间（从活动日志中最早的时间）
            long agentStartTime = 0L;
            Optional<TaskActivityEntity> earliestActivity = activities.stream()
                .filter(act -> agentName.equals(act.getAgentName()))
                .min(Comparator.comparing(TaskActivityEntity::getActivityTime));
            if (earliestActivity.isPresent() && earliestActivity.get().getActivityTime() != null) {
                agentStartTime = earliestActivity.get().getActivityTime()
                    .atZone(ZoneId.systemDefault()).toEpochSecond();
            }
            
            agentInfoList.add(TaskDetailResponse.AgentInfoResponse.builder()
                .agentName(agentName)
                .agentStatus(2) // 默认运行中
                .completePercent(completePercent)
                .agentDesc("AI Agent: " + agentName)
                .agentStartTime(agentStartTime)
                .agentPriority(1)
                .agentOutput("")
                .build());
        }
        
        return agentInfoList;
    }
    
    @PostMapping("/rate")
    public Result<Void> rateTask(
            @Valid @RequestBody RateTaskRequest request,
            @RequestAttribute(value = "clerkUserId", required = false) String clerkUserId) {
        // 从拦截器获取用户ID（拦截器已验证token并将用户ID设置到request attribute）
        if (clerkUserId == null || clerkUserId.isEmpty()) {
            return Result.error("用户未登录");
        }
        
        // 验证任务是否属于当前用户
        TaskEntity taskEntity = taskMapper.selectById(request.getTaskId());
        if (taskEntity == null) {
            return Result.error(1003, "任务不存在");
        }
        if (!clerkUserId.equals(taskEntity.getClerkUserId())) {
            return Result.error(1004, "无权评价该任务");
        }
        
        // 将 API 层的 Request DTO 转换为应用层的 Request Model
        com.studyagent.service.application.request.RateTaskRequest appRequest = 
            com.studyagent.service.application.request.RateTaskRequest.builder()
                .taskId(request.getTaskId())
                .score(request.getScore())
                .content(request.getContent())
                .build();
        
        taskApplicationService.rateTask(appRequest);
        
        return Result.success(null);
    }
    
    @PostMapping("/clarify")
    public Result<ClarifyTaskResponse> clarifyTask(
            @RequestBody ClarifyTaskRequest request) {
        try {
            log.info("收到追问请求: taskTitle={}, taskDesc={}", request.getTaskTitle(), request.getTaskDesc());
            
            // 构建转发给 Python 后端的请求
            Map<String, Object> pythonRequest = new HashMap<>();
            if (request.getTaskTitle() != null) {
                pythonRequest.put("taskTitle", request.getTaskTitle());
            }
            if (request.getTaskDesc() != null) {
                pythonRequest.put("taskDesc", request.getTaskDesc());
            }
            if (request.getSubject() != null) {
                pythonRequest.put("subject", request.getSubject());
            }
            if (request.getAcademicLevel() != null) {
                pythonRequest.put("academicLevel", request.getAcademicLevel());
            }
            if (request.getPriorityLevel() != null) {
                pythonRequest.put("priorityLevel", request.getPriorityLevel());
            }
            if (request.getDueDate() != null) {
                pythonRequest.put("dueDate", request.getDueDate());
            }
            if (request.getObjectIds() != null) {
                pythonRequest.put("objectIds", request.getObjectIds());
            }
            if (request.getFormat() != null) {
                pythonRequest.put("format", request.getFormat());
            }
            if (request.getCitationStyle() != null) {
                pythonRequest.put("citationStyle", request.getCitationStyle());
            }
            if (request.getPageLength() != null) {
                pythonRequest.put("pageLength", request.getPageLength());
            }
            if (request.getSpecialInstructions() != null) {
                pythonRequest.put("specialInstructions", request.getSpecialInstructions());
            }
            
            log.info("转发请求到 Python 后端: {}", pythonRequest);
            
            // 调用 Python 后端
            PythonBackendClient.ClarifyTaskResult result = pythonBackendClient.clarifyTask(pythonRequest);
            
            log.info("Python 后端返回结果: questions={}, suggestions={}", 
                result.getQuestions().size(), result.getSuggestions());
            
            // 构建响应
            ClarifyTaskResponse response = ClarifyTaskResponse.builder()
                .questions(result.getQuestions())
                .suggestions(result.getSuggestions())
                .build();
            
            return Result.success(response);
        } catch (Exception e) {
            log.error("追问服务调用失败", e);
            return Result.error(500, "追问服务调用失败: " + e.getMessage());
        }
    }
    
    /**
     * 下载任务输出文件（通过 outputId）
     * @param outputId 输出文件ID（TaskOutputEntity的id）
     * @return 文件资源
     */
    @GetMapping("/output/download/{outputId}")
    public ResponseEntity<Resource> downloadOutput(@PathVariable Long outputId) {
        try {
            // 1. 查询输出文件记录
            TaskOutputEntity taskOutput = taskOutputMapper.selectById(outputId);
            if (taskOutput == null) {
                log.warn("输出文件不存在: outputId={}", outputId);
                return ResponseEntity.notFound().build();
            }
            
            // 2. 从数据库读取文件内容
            String content = null;
            if (taskOutput.getContentText() != null && !taskOutput.getContentText().isEmpty()) {
                content = taskOutput.getContentText();
            } else if (taskOutput.getLogText() != null && !taskOutput.getLogText().isEmpty()) {
                content = taskOutput.getLogText();
            }
            
            if (content == null || content.isEmpty()) {
                log.warn("输出文件内容为空: outputId={}", outputId);
                return ResponseEntity.notFound().build();
            }
            
            // 3. 根据 outputType 决定文件扩展名和 Content-Type
            String fileExtension;
            String contentType;
            String downloadFilename;
            
            Integer outputType = taskOutput.getOutputType() != null ? taskOutput.getOutputType() : 0;
            if (outputType == 1) {
                // 报告内容 -> .md 文件
                fileExtension = ".md";
                contentType = "text/markdown; charset=utf-8";
                downloadFilename = taskOutput.getTitle() != null && !taskOutput.getTitle().isEmpty() 
                    ? sanitizeFilename(taskOutput.getTitle()) + fileExtension
                    : "report_" + outputId + fileExtension;
            } else {
                // 日志文件 -> .txt 文件
                fileExtension = ".txt";
                contentType = "text/plain; charset=utf-8";
                downloadFilename = taskOutput.getTitle() != null && !taskOutput.getTitle().isEmpty()
                    ? sanitizeFilename(taskOutput.getTitle()) + fileExtension
                    : "log_" + outputId + fileExtension;
            }
            
            // 4. 转换为字节数组并创建资源
            byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
            Resource resource = new ByteArrayResource(contentBytes);
            
            // 5. 对文件名进行 URL 编码
            String encodedFilename = URLEncoder.encode(downloadFilename, StandardCharsets.UTF_8);
            
            log.info("下载输出文件: outputId={}, filename={}, size={} bytes", 
                outputId, downloadFilename, contentBytes.length);
            
            return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, 
                    "attachment; filename=\"" + encodedFilename + "\"; filename*=UTF-8''" + encodedFilename)
                .body(resource);
                
        } catch (Exception e) {
            log.error("下载输出文件失败: outputId={}", outputId, e);
            return ResponseEntity.internalServerError().build();
        }
    }
    
    /**
     * 清理文件名，移除不合法的字符
     */
    private String sanitizeFilename(String filename) {
        if (filename == null) {
            return "file";
        }
        // 移除或替换不合法的文件名字符
        return filename.replaceAll("[<>:\"/\\|?*]", "_")
                      .replaceAll("\\s+", "_")
                      .trim();
    }
}

