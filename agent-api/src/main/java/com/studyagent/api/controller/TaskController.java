package com.studyagent.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.studyagent.api.common.Result;
import com.studyagent.common.api.ApiCode;
import com.studyagent.api.dto.request.ClarifyTaskRequest;
import com.studyagent.api.dto.request.RateTaskRequest;
import com.studyagent.api.dto.request.SaveDraftRequest;
import com.studyagent.api.dto.request.StopTaskRequest;
import com.studyagent.api.dto.request.SubmitTaskRequest;
import com.studyagent.api.dto.request.DeleteTasksRequest;
import com.studyagent.api.dto.request.TaskDetailRequest;
import com.studyagent.api.dto.request.TaskListRequest;
import com.studyagent.api.dto.response.ClarifyTaskResponse;
import com.studyagent.api.dto.response.SaveDraftResponse;
import com.studyagent.api.dto.response.StopTaskResponse;
import com.studyagent.api.dto.response.SubmitQuotaInfo;
import com.studyagent.api.dto.response.SubmitTaskResponse;
import com.studyagent.api.dto.response.DeleteTasksResponse;
import com.studyagent.api.converter.TaskDetailConverter;
import com.studyagent.api.converter.TaskListConverter;
import com.studyagent.api.util.TaskIdEncoder;
import com.studyagent.api.dto.response.TaskDetailResponse;
import com.studyagent.api.dto.response.TaskListResponse;
import com.studyagent.api.dto.response.TaskSummaryResponse;
import com.studyagent.infra.entity.TaskEntity;
import com.studyagent.infra.entity.TaskActivityEntity;
import com.studyagent.infra.entity.TaskOutputEntity;
import com.studyagent.infra.mapper.TaskMapper;
import com.studyagent.infra.mapper.TaskActivityMapper;
import com.studyagent.infra.mapper.TaskOutputMapper;
import com.studyagent.service.application.TaskApplicationService;
import com.studyagent.service.application.request.GetTaskDetailRequest;
import com.studyagent.service.domain.task.PythonBackendClient;
import com.studyagent.service.domain.user.User;
import com.studyagent.service.domain.user.UserRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
    private final UserRepository userRepository;
    private final PythonBackendClient pythonBackendClient;
    private final TaskMapper taskMapper;
    private final TaskActivityMapper taskActivityMapper;
    private final TaskOutputMapper taskOutputMapper;
    
    /**
     * 查询当前用户的任务提交额度（提交前可调用以展示剩余次数）
     * 管理员或不限额时 quota 为 null
     */
    @GetMapping("/submit-quota")
    public Result<SubmitQuotaInfo> getSubmitQuota(
            @RequestAttribute(value = "clerkUserId", required = false) String clerkUserId) {
        if (clerkUserId == null || clerkUserId.isEmpty()) {
            return Result.error(ApiCode.USER_NOT_LOGGED_IN);
        }
        var quota = taskApplicationService.getSubmitQuota(clerkUserId);
        if (quota == null) {
            return Result.success(null);  // 管理员或不限额，无额度信息
        }
        SubmitQuotaInfo response = SubmitQuotaInfo.builder()
                .dailyLimit(quota.dailyLimit())
                .usedToday(quota.usedToday())
                .remainingQuota(quota.remainingQuota())
                .quotaResetAt(quota.quotaResetAt())
                .build();
        return Result.success(response);
    }

    @PostMapping("/submit")
    public Result<SubmitTaskResponse> submitTask(
            @Valid @RequestBody SubmitTaskRequest request,
            @RequestHeader("Authorization") String token) {
        Long internalDraftId = (request.getDraftId() != null && !request.getDraftId().isBlank())
                ? TaskIdEncoder.decode(request.getDraftId()) : null;
        // 将 API 层的 Request DTO 转换为应用层的 Request Model
        com.studyagent.service.application.request.SubmitTaskRequest appRequest = 
            com.studyagent.service.application.request.SubmitTaskRequest.builder()
                .draftId(internalDraftId)
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
        
        var result = taskApplicationService.submitTask(appRequest);

        SubmitQuotaInfo quota = null;
        if (result.quota() != null) {
            quota = SubmitQuotaInfo.builder()
                    .dailyLimit(result.quota().dailyLimit())
                    .usedToday(result.quota().usedToday())
                    .remainingQuota(result.quota().remainingQuota())
                    .quotaResetAt(result.quota().quotaResetAt())
                    .build();
        }

        SubmitTaskResponse response = SubmitTaskResponse.builder()
                .taskId(TaskIdEncoder.encode(result.taskId()))
                .quota(quota)
                .build();

        return Result.success(response, result.quotaConsumed());
    }

    @PostMapping("/save-draft")
    public Result<SaveDraftResponse> saveDraft(
            @RequestBody SaveDraftRequest request,
            @RequestHeader("Authorization") String token) {
        Long internalDraftId = (request.getDraftId() != null && !request.getDraftId().isBlank())
                ? TaskIdEncoder.decode(request.getDraftId()) : null;
        // 将 API 层的 Request DTO 转换为应用层的 Request Model
        com.studyagent.service.application.request.SaveDraftRequest appRequest =
            com.studyagent.service.application.request.SaveDraftRequest.builder()
                .draftId(internalDraftId)
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
            .draftId(TaskIdEncoder.encode(draftId))
            .savedAt(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
            .build();

        return Result.success(response);
    }

    @PostMapping("/stop")
    public Result<StopTaskResponse> stopTask(
            @Valid @RequestBody StopTaskRequest request,
            @RequestHeader("Authorization") String token) {
        Long internalTaskId = TaskIdEncoder.decode(request.getTaskId());
        if (internalTaskId == null) {
            return Result.error(ApiCode.TASK_NOT_FOUND);
        }
        com.studyagent.service.application.request.StopTaskRequest appRequest =
            com.studyagent.service.application.request.StopTaskRequest.builder()
                .taskId(internalTaskId)
                .token(token)
                .build();

        Long taskId = taskApplicationService.stopTask(appRequest);

        StopTaskResponse response = StopTaskResponse.builder()
            .taskId(TaskIdEncoder.encode(taskId))
            .message("任务已停止")
            .build();

        return Result.success(response);
    }
    
    @PostMapping("/list")
    public Result<TaskListResponse> getTaskList(
            @RequestBody(required = false) TaskListRequest request,
            @RequestHeader(value = "Authorization", required = false) String token,
            @RequestAttribute(value = "clerkUserId", required = false) String clerkUserId) {
        if (clerkUserId == null || clerkUserId.isEmpty()) {
            return Result.error(ApiCode.USER_NOT_LOGGED_IN);
        }
        if (request == null) {
            request = new TaskListRequest();
            request.setPageNo(1);
            request.setPageSize(10);
        }
        int pageNo = (request.getPageNo() == null || request.getPageNo() < 1) ? 1 : request.getPageNo();
        int pageSize = (request.getPageSize() == null || request.getPageSize() < 1) ? 10 : request.getPageSize();
        boolean isAdmin = userRepository.findByClerkUserId(clerkUserId).map(User::getIsAdmin).orElse(false);

        var appRequest = com.studyagent.service.application.request.GetTaskListRequest.builder()
                .clerkUserId(clerkUserId)
                .isAdmin(isAdmin)
                .status(request.getTaskStatus())
                .keyword(request.getTaskKeyword())
                .order(request.getOrder())
                .pageNo(pageNo)
                .pageSize(pageSize)
                .build();
        var result = taskApplicationService.getTaskList(appRequest);
        return Result.success(TaskListConverter.toResponse(result));
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
            return Result.error(ApiCode.USER_NOT_LOGGED_IN);
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
    
    @GetMapping("/list")
    public Result<TaskListResponse> getTaskListByGet(
            @RequestParam(value = "taskKeyword", required = false) String taskKeyword,
            @RequestParam(value = "taskStatus", required = false) Integer taskStatus,
            @RequestParam(value = "order", required = false) Integer order,
            @RequestParam(value = "pageNo", defaultValue = "1") Integer pageNo,
            @RequestParam(value = "pageSize", defaultValue = "10") Integer pageSize,
            @RequestHeader(value = "Authorization", required = false) String token,
            @RequestAttribute(value = "clerkUserId", required = false) String clerkUserId) {
        if (clerkUserId == null || clerkUserId.isEmpty()) {
            return Result.error(ApiCode.USER_NOT_LOGGED_IN);
        }
        int pn = (pageNo == null || pageNo < 1) ? 1 : pageNo;
        int ps = (pageSize == null || pageSize < 1) ? 10 : pageSize;
        boolean isAdmin = userRepository.findByClerkUserId(clerkUserId).map(User::getIsAdmin).orElse(false);

        var appRequest = com.studyagent.service.application.request.GetTaskListRequest.builder()
                .clerkUserId(clerkUserId)
                .isAdmin(isAdmin)
                .status(taskStatus)
                .keyword(taskKeyword)
                .order(order)
                .pageNo(pn)
                .pageSize(ps)
                .build();
        var result = taskApplicationService.getTaskList(appRequest);
        return Result.success(TaskListConverter.toResponse(result));
    }

    /**
     * 批量逻辑删除任务（不物理删除，仅将 is_deleted 置为 1）
     * 支持传入多个 taskId，逐个校验归属后删除，返回成功数与失败列表
     */
    @PostMapping("/delete")
    public Result<DeleteTasksResponse> deleteTasks(
            @Valid @RequestBody DeleteTasksRequest request,
            @RequestHeader(value = "Authorization", required = false) String token,
            @RequestAttribute(value = "clerkUserId", required = false) String clerkUserId) {
        if (clerkUserId == null || clerkUserId.isEmpty()) {
            return Result.error(ApiCode.USER_NOT_LOGGED_IN);
        }
        List<Long> internalTaskIds = request.getTaskIds().stream()
                .map(TaskIdEncoder::decode)
                .filter(id -> id != null)
                .toList();
        TaskApplicationService.DeleteTasksResult result = taskApplicationService.deleteTasks(
                internalTaskIds, clerkUserId);
        List<String> encodedFailedIds = result.failedTaskIds().stream()
                .map(TaskIdEncoder::encode)
                .toList();
        DeleteTasksResponse response = DeleteTasksResponse.builder()
                .deletedCount(result.deletedCount())
                .failedTaskIds(encodedFailedIds)
                .build();
        return Result.success(response);
    }
    
    
    @PostMapping("/detail")
    public Result<TaskDetailResponse> getTaskDetail(
            @Valid @RequestBody TaskDetailRequest request,
            @RequestHeader(value = "Authorization", required = false) String token,
            @RequestAttribute(value = "clerkUserId", required = false) String clerkUserId) {
        if (clerkUserId == null || clerkUserId.isEmpty()) {
            return Result.error(ApiCode.USER_NOT_LOGGED_IN);
        }
        Long internalTaskId = TaskIdEncoder.decode(request.getTaskId());
        if (internalTaskId == null) {
            return Result.error(ApiCode.TASK_NOT_FOUND);
        }
        GetTaskDetailRequest appRequest = GetTaskDetailRequest.builder()
                .taskId(internalTaskId)
                .clerkUserId(clerkUserId)
                .build();
        var dto = taskApplicationService.getTaskDetail(appRequest);
        return Result.success(TaskDetailConverter.toResponse(dto));
    }
    
    @PostMapping("/rate")
    public Result<Void> rateTask(
            @Valid @RequestBody RateTaskRequest request,
            @RequestAttribute(value = "clerkUserId", required = false) String clerkUserId) {
        // 从拦截器获取用户ID（拦截器已验证token并将用户ID设置到request attribute）
        if (clerkUserId == null || clerkUserId.isEmpty()) {
            return Result.error(ApiCode.USER_NOT_LOGGED_IN);
        }
        Long internalTaskId = TaskIdEncoder.decode(request.getTaskId());
        if (internalTaskId == null) {
            return Result.error(ApiCode.TASK_NOT_FOUND);
        }
        
        // 验证任务是否属于当前用户（排除已逻辑删除的任务）
        TaskEntity taskEntity = taskMapper.selectById(internalTaskId);
        if (taskEntity == null || (taskEntity.getIsDeleted() != null && taskEntity.getIsDeleted() == 1)) {
            return Result.error(ApiCode.TASK_NOT_FOUND);
        }
        if (!clerkUserId.equals(taskEntity.getClerkUserId())) {
            return Result.error(ApiCode.NO_PERMISSION);
        }
        
        // 将 API 层的 Request DTO 转换为应用层的 Request Model
        com.studyagent.service.application.request.RateTaskRequest appRequest = 
            com.studyagent.service.application.request.RateTaskRequest.builder()
                .taskId(internalTaskId)
                .score(request.getScore())
                .content(request.getContent())
                .build();
        
        taskApplicationService.rateTask(appRequest);
        
        return Result.success(null);
    }
    
    @PostMapping("/clarify")
    public Result<ClarifyTaskResponse> clarifyTask(
            @RequestBody ClarifyTaskRequest request,
            @RequestAttribute(value = "clerkUserId", required = false) String clerkUserId) {
        if (clerkUserId == null || clerkUserId.isEmpty()) {
            return Result.error(ApiCode.USER_NOT_LOGGED_IN);
        }

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
            return Result.error(ApiCode.INTERNAL_ERROR, e.getMessage());
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
    
    /**
     * 分页查询任务活动日志
     * 
     * GET /v1/task/{taskId}/activities?pageNo=1&pageSize=10
     * 
     * 用于按需加载更多活动日志，避免一次性返回大量数据导致 Broken pipe
     * 
     * @param taskId 任务ID
     * @param pageNo 页码（从1开始）
     * @param pageSize 每页大小（默认10条）
     * @param clerkUserId 用户ID（从拦截器获取）
     * @return 分页的活动日志列表
     */
    @GetMapping("/{taskId}/activities")
    public Result<TaskActivitiesPageResponse> getTaskActivities(
            @PathVariable String taskId,
            @RequestParam(value = "pageNo", defaultValue = "1") Integer pageNo,
            @RequestParam(value = "pageSize", defaultValue = "10") Integer pageSize,
            @RequestAttribute(value = "clerkUserId", required = false) String clerkUserId) {
        
        // 验证用户登录
        if (clerkUserId == null || clerkUserId.isEmpty()) {
            return Result.error(ApiCode.USER_NOT_LOGGED_IN);
        }
        Long internalTaskId = TaskIdEncoder.decode(taskId);
        if (internalTaskId == null) {
            return Result.error(ApiCode.TASK_NOT_FOUND);
        }
        
        // 验证任务是否存在且属于当前用户（排除已逻辑删除的任务）
        TaskEntity taskEntity = taskMapper.selectById(internalTaskId);
        if (taskEntity == null || (taskEntity.getIsDeleted() != null && taskEntity.getIsDeleted() == 1)) {
            return Result.error(ApiCode.TASK_NOT_FOUND);
        }
        if (!clerkUserId.equals(taskEntity.getClerkUserId())) {
            return Result.error(ApiCode.NO_PERMISSION);
        }
        
        // 参数校验
        if (pageNo == null || pageNo < 1) {
            pageNo = 1;
        }
        if (pageSize == null || pageSize < 1 || pageSize > 100) {
            pageSize = 10;
        }
        
        // 计算偏移量
        int offset = (pageNo - 1) * pageSize;
        
        // 查询总数
        Long total = taskActivityMapper.selectCount(
            new LambdaQueryWrapper<TaskActivityEntity>()
                .eq(TaskActivityEntity::getTaskId, internalTaskId)
        );
        
        // 分页查询活动日志
        List<TaskActivityEntity> activityEntities = taskActivityMapper.selectList(
            new LambdaQueryWrapper<TaskActivityEntity>()
                .eq(TaskActivityEntity::getTaskId, internalTaskId)
                .orderByDesc(TaskActivityEntity::getActivityTime)
                .last("LIMIT " + pageSize + " OFFSET " + offset)
        );
        
        // 转换为响应 DTO
        List<TaskDetailResponse.ActivityInfoResponse> activityList = activityEntities.stream()
            .map(act -> TaskDetailResponse.ActivityInfoResponse.builder()
                .activityTime(act.getActivityTime() != null ? 
                    act.getActivityTime().atZone(ZoneId.systemDefault()).toEpochSecond() : 0L)
                .agentName(act.getAgentName())
                .activityDesc(act.getActivityDesc())
                .build())
            .collect(Collectors.toList());
        
        TaskActivitiesPageResponse response = TaskActivitiesPageResponse.builder()
            .activityList(activityList)
            .total(total.intValue())
            .pageNo(pageNo)
            .pageSize(pageSize)
            .build();
        
        return Result.success(response);
    }
    
    /**
     * 任务活动日志分页响应
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class TaskActivitiesPageResponse {
        private List<TaskDetailResponse.ActivityInfoResponse> activityList;
        private Integer total;
        private Integer pageNo;
        private Integer pageSize;
    }
}

