package com.studyagent.service.application;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.studyagent.service.application.request.SubmitTaskRequest;
import com.studyagent.service.application.request.SaveDraftRequest;
import com.studyagent.service.application.request.GetTaskListRequest;
import com.studyagent.service.application.request.RateTaskRequest;
import com.studyagent.service.domain.task.Task;
import com.studyagent.service.domain.task.TaskId;
import com.studyagent.service.domain.task.TaskRepository;
import com.studyagent.service.domain.task.TaskStatus;
import com.studyagent.service.domain.task.TaskDomainService;
import com.studyagent.service.domain.task.PythonBackendClient;
import com.studyagent.service.domain.task.TaskFileRepository;
import com.studyagent.service.domain.user.ClerkClient;
import com.studyagent.service.domain.file.FileRepository;
import com.studyagent.service.domain.file.FileId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;

/**
 * 任务应用服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskApplicationService {

    private final TaskRepository taskRepository;
    private final TaskDomainService taskDomainService;
    private final PythonBackendClient pythonBackendClient;
    private final ClerkClient clerkClient;
    private final FileRepository fileRepository;
    private final TaskFileRepository taskFileRepository;
    private final Gson gson = new Gson();

    /**
     * 提交任务
     *
     * @param request 提交任务请求
     * @return taskId
     * <p>
     * 优化说明：移除方法级 @Transactional，只在必要的数据库操作时开启事务
     * 这样可以：
     * 1. 减少事务持有时间，避免阻塞其他查询
     * 2. 验证和构建对象在事务外执行
     * 3. Python 后端调用在事务外异步执行
     */
    public Long submitTask(SubmitTaskRequest request) {
        // 1. 验证用户身份（事务外执行）
        ClerkClient.UserInfo userInfo = clerkClient.verifyToken(normalizeToken(request.getToken()));

        // 2. 查询现有草稿（只读操作，使用 readOnly 事务）
        Task existing = null;
        if (request.getDraftId() != null) {
            existing = findExistingDraftWithValidation(request.getDraftId(), userInfo.clerkUserId);
        }

        // 3. 创建任务领域模型（事务外执行，纯内存操作）
        LocalDateTime dueDate = firstNonNull(request.getDueDate(), existing != null ? existing.getDueDate() : null);
        if (dueDate == null) {
            dueDate = LocalDateTime.now().plusMonths(1);
        }

        String mergedRequirementJson = mergeRequirementJson(
                existing != null ? existing.getRequirementJson() : null,
                request.getRequirementsJson(),
                request.getClarifyingQuestions()
        );

        Task task = Task.builder()
                .id(existing != null ? existing.getId() : null)
                .clerkUserId(existing != null ? existing.getClerkUserId() : userInfo.clerkUserId)
                .taskTitle(request.getTaskTitle())
                .taskDesc(request.getTaskDesc())
                .subject(request.getSubject())
                .academicLevel(request.getAcademicLevel())
                .priorityLevel(request.getPriorityLevel())
                .dueDate(dueDate)
                .format(request.getFormat() != null ? request.getFormat() : List.of())
                .citationStyle(request.getCitationStyle())
                .pageLength(request.getPageLength())
                .specialInstructions(request.getSpecialInstructions())
                .requirementJson(mergedRequirementJson)
                .status(TaskStatus.DRAFT)
                .build();

        // 4. 验证任务（事务外执行，纯业务逻辑）
        taskDomainService.validateTask(task);

        // 5. 提交任务（修改状态，纯内存操作）
        task = task.submit();

        // 6. 在短事务内保存任务和关联文件
        Long taskId = saveTaskAndFilesInTransaction(task, request.getObjectIds());

        // 7. 事务外异步调用 Python 后端执行任务
        // 使用 CompletableFuture 异步执行，不阻塞当前请求
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                log.info("开始异步调用 Python 后端执行任务: taskId={}", taskId);
                pythonBackendClient.executeTask(TaskId.of(taskId));
                log.info("成功调用 Python 后端执行任务: taskId={}", taskId);
            } catch (Exception e) {
                log.error("调用 Python 后端执行任务失败: taskId={}", taskId, e);
                // 任务已保存，Python 后端调用失败不影响任务创建
                // TODO: 可以考虑添加重试机制或将任务状态更新为失败
            }
        });

        return taskId;
    }

    /**
     * 查询现有草稿并验证权限（只读事务）
     */
    @Transactional(readOnly = true, timeout = 5)
    protected Task findExistingDraftWithValidation(Long draftId, String clerkUserId) {
        Task existing = taskRepository.findById(TaskId.of(draftId))
                .orElseThrow(() -> new RuntimeException("Draft not found: " + draftId));

        if (!clerkUserId.equals(existing.getClerkUserId())) {
            throw new RuntimeException("No permission to submit this draft");
        }
        if (existing.getStatus() != TaskStatus.DRAFT) {
            throw new RuntimeException("Only tasks with DRAFT status can be submitted");
        }

        return existing;
    }

    /**
     * 在短事务内保存任务和关联文件
     * 事务超时设置为 10 秒，避免长时间持有锁
     */
    @Transactional(timeout = 10)
    protected Long saveTaskAndFilesInTransaction(Task task, List<String> objectIds) {
        // 保存任务
        Task savedTask = taskRepository.save(task);
        Long taskId = savedTask.getId().getValue();

        // 关联文件到任务（如果提供了文件objectIds）
        if (objectIds != null && !objectIds.isEmpty()) {
            taskFileRepository.removeByTaskId(taskId);
            int order = 0;
            for (String objectId : objectIds) {
                // 根据 objectId 查找文件
                Optional<com.studyagent.service.domain.file.File> fileOpt = fileRepository.findByObjectId(objectId);
                if (fileOpt.isPresent()) {
                    com.studyagent.service.domain.file.File file = fileOpt.get();
                    // 创建任务文件关联记录
                    taskFileRepository.associateFileToTask(taskId, file.getId().getValue(), order++);
                    log.info("任务文件关联成功: taskId={}, fileId={}, objectId={}, order={}",
                            taskId, file.getId().getValue(), objectId, order - 1);
                } else {
                    log.warn("文件不存在，跳过关联: objectId={}", objectId);
                }
            }
        }

        return taskId;
    }

    /**
     * 保存草稿
     *
     * @param request 保存草稿请求
     * @return draftId
     * <p>
     * 优化：添加事务超时，避免长时间持有锁
     */
    @Transactional(timeout = 10)
    public Long saveDraft(SaveDraftRequest request) {
        // 1. 验证用户身份
        ClerkClient.UserInfo userInfo = clerkClient.verifyToken(normalizeToken(request.getToken()));

        Task existing = null;
        if (request.getDraftId() != null) {
            existing = taskRepository.findById(TaskId.of(request.getDraftId()))
                    .orElseThrow(() -> new RuntimeException("Draft not found: " + request.getDraftId()));
            if (!userInfo.clerkUserId.equals(existing.getClerkUserId())) {
                throw new RuntimeException("No permission to update this draft");
            }
            if (existing.getStatus() != TaskStatus.DRAFT) {
                throw new RuntimeException("Only tasks with DRAFT status can be updated");
            }
        }

        // 处理 dueDate：如果请求中没有且现有任务也没有，则设置为当前日期加一个月
        LocalDateTime dueDate = firstNonNull(request.getDueDate(), existing != null ? existing.getDueDate() : null);
        if (dueDate == null) {
            dueDate = LocalDateTime.now().plusMonths(1);
        }

        String mergedRequirementJson = mergeRequirementJson(
                existing != null ? existing.getRequirementJson() : null,
                request.getRequirementsJson(),
                request.getClarifyingQuestions()
        );

        Task.TaskBuilder builder = Task.builder()
                .id(existing != null ? existing.getId() : null)
                .clerkUserId(userInfo.clerkUserId)
                .taskTitle(firstNonNull(request.getTaskTitle(), existing != null ? existing.getTaskTitle() : null))
                .taskDesc(firstNonNull(request.getTaskDesc(), existing != null ? existing.getTaskDesc() : null))
                .subject(firstNonNull(request.getSubject(), existing != null ? existing.getSubject() : null))
                .academicLevel(firstNonNull(request.getAcademicLevel(), existing != null ? existing.getAcademicLevel() : null))
                .priorityLevel(firstNonNull(request.getPriorityLevel(), existing != null ? existing.getPriorityLevel() : null))
                .dueDate(dueDate)
                .format(firstNonNull(request.getFormat(), existing != null ? existing.getFormat() : List.of()))
                .citationStyle(firstNonNull(request.getCitationStyle(), existing != null ? existing.getCitationStyle() : null))
                .pageLength(firstNonNull(request.getPageLength(), existing != null ? existing.getPageLength() : null))
                .specialInstructions(firstNonNull(request.getSpecialInstructions(), existing != null ? existing.getSpecialInstructions() : null))
                .requirementJson(mergedRequirementJson)
                .status(TaskStatus.DRAFT);

        Task savedTask = taskRepository.save(builder.build());
        Long draftId = savedTask.getId().getValue();

        // 2. 更新任务文件关联（如果提供 objectIds）
        if (request.getObjectIds() != null) {
            taskFileRepository.removeByTaskId(draftId);
            int order = 0;
            for (String objectId : request.getObjectIds()) {
                Optional<com.studyagent.service.domain.file.File> fileOpt = fileRepository.findByObjectId(objectId);
                if (fileOpt.isPresent()) {
                    com.studyagent.service.domain.file.File file = fileOpt.get();
                    taskFileRepository.associateFileToTask(draftId, file.getId().getValue(), order++);
                } else {
                    log.warn("文件不存在，跳过关联: objectId={}", objectId);
                }
            }
        }

        return draftId;
    }

    private String mergeRequirementJson(String existingJson, String requirementsJson, String clarifyingQuestions) {
        boolean hasRequirements = requirementsJson != null && !requirementsJson.trim().isEmpty();
        boolean hasClarifying = clarifyingQuestions != null && !clarifyingQuestions.trim().isEmpty();
        if (!hasRequirements && !hasClarifying) {
            return existingJson;
        }

        Map<String, Object> merged = new HashMap<>();
        if (existingJson != null && !existingJson.trim().isEmpty()) {
            Object parsedExisting = parseJsonOrString(existingJson);
            if (parsedExisting instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> existingMap = (Map<String, Object>) parsedExisting;
                merged.putAll(existingMap);
            } else {
                merged.put("existingRequirementJson", parsedExisting);
            }
        }
        if (hasRequirements) {
            merged.put("requirementsJson", parseJsonOrString(requirementsJson));
        }
        if (hasClarifying) {
            merged.put("clarifyingQuestions", parseJsonOrString(clarifyingQuestions));
        }

        return gson.toJson(merged);
    }

    private Object parseJsonOrString(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return raw;
        }
        try {
            return gson.fromJson(raw, Object.class);
        } catch (JsonSyntaxException e) {
            return raw;
        }
    }

    /**
     * 批量逻辑删除任务（将 is_deleted 置为 1，不物理删除数据）
     * 逐个校验归属，成功删除的计入 deletedCount，失败（不存在/无权限）的计入 failedTaskIds
     *
     * @param taskIds 任务ID列表
     * @param clerkUserId 当前用户ID
     * @return [deletedCount, failedTaskIds]
     */
    @Transactional(timeout = 10)
    public DeleteTasksResult deleteTasks(List<Long> taskIds, String clerkUserId) {
        int deletedCount = 0;
        List<Long> failedTaskIds = new java.util.ArrayList<>();
        for (Long taskId : taskIds) {
            try {
                Task task = taskRepository.findById(TaskId.of(taskId))
                        .orElseThrow(() -> new RuntimeException("任务不存在或已删除: " + taskId));
                if (!clerkUserId.equals(task.getClerkUserId())) {
                    failedTaskIds.add(taskId);
                    continue;
                }
                taskRepository.logicalDelete(TaskId.of(taskId));
                deletedCount++;
                log.debug("任务逻辑删除成功: taskId={}", taskId);
            } catch (RuntimeException e) {
                failedTaskIds.add(taskId);
            }
        }
        if (deletedCount > 0) {
            log.info("批量逻辑删除任务完成: deletedCount={}, failedCount={}", deletedCount, failedTaskIds.size());
        }
        return new DeleteTasksResult(deletedCount, failedTaskIds);
    }

    /** 批量删除结果 */
    public record DeleteTasksResult(int deletedCount, List<Long> failedTaskIds) {}

    /**
     * 停止任务
     *
     * @param request 停止任务请求
     * @return taskId
     * <p>
     * 优化：缩小事务范围，Python 后端调用改为异步
     */
    public Long stopTask(com.studyagent.service.application.request.StopTaskRequest request) {
        // 1. 验证用户身份（事务外）
        ClerkClient.UserInfo userInfo = clerkClient.verifyToken(normalizeToken(request.getToken()));

        // 2. 查询任务（只读事务）
        Task task = findTaskWithValidation(request.getTaskId(), userInfo.clerkUserId);

        // 3. 如果任务已经是终态，直接返回
        if (task.getStatus() == TaskStatus.COMPLETED || task.getStatus() == TaskStatus.FAILED) {
            return task.getId().getValue();
        }

        Long taskId = task.getId().getValue();

        // 4. 在短事务内更新任务状态为已取消
        if (task.getStatus() != TaskStatus.CANCELLED) {
            cancelTaskInTransaction(task);
        }

        // 5. 逻辑删除任务（停止后从用户任务列表移除）
        taskRepository.logicalDelete(TaskId.of(taskId));

        // 6. 异步调用 Python 后端停止任务
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                pythonBackendClient.stopTask(TaskId.of(taskId));
                log.info("成功调用 Python 后端停止任务: taskId={}", taskId);
            } catch (Exception e) {
                log.error("调用 Python 后端停止任务失败: taskId={}", taskId, e);
            }
        });

        return taskId;
    }

    /**
     * 查询任务并验证权限（只读事务）
     */
    @Transactional(readOnly = true, timeout = 5)
    protected Task findTaskWithValidation(Long taskId, String clerkUserId) {
        Task task = taskRepository.findById(TaskId.of(taskId))
                .orElseThrow(() -> new RuntimeException("Task not found: " + taskId));

        if (!clerkUserId.equals(task.getClerkUserId())) {
            throw new RuntimeException("No permission to stop this task");
        }

        return task;
    }

    /**
     * 在事务内取消任务
     */
    @Transactional(timeout = 5)
    protected void cancelTaskInTransaction(Task task) {
        Task cancelled = Task.builder()
                .id(task.getId())
                .clerkUserId(task.getClerkUserId())
                .taskTitle(task.getTaskTitle())
                .taskDesc(task.getTaskDesc())
                .subject(task.getSubject())
                .academicLevel(task.getAcademicLevel())
                .priorityLevel(task.getPriorityLevel())
                .dueDate(task.getDueDate())
                .format(task.getFormat())
                .citationStyle(task.getCitationStyle())
                .pageLength(task.getPageLength())
                .specialInstructions(task.getSpecialInstructions())
                .status(TaskStatus.CANCELLED)
                .startTime(task.getStartTime())
                .finishTime(LocalDateTime.now())
                .costTime(task.getStartTime() != null
                        ? (int) java.time.Duration.between(task.getStartTime(), LocalDateTime.now()).getSeconds()
                        : null)
                .completePercent(task.getCompletePercent())
                .taskCompletedSize(task.getTaskCompletedSize())
                .activeAgentSize(task.getActiveAgentSize())
                .estRemainingTime(task.getEstRemainingTime())
                .requirementJson(task.getRequirementJson())
                .finalResult(task.getFinalResult())
                .errorMessage("Task cancelled")
                .build();

        taskRepository.save(cancelled);
    }

    private String normalizeToken(String token) {
        if (token == null) {
            return null;
        }
        return token.startsWith("Bearer ") ? token.substring(7) : token;
    }

    private <T> T firstNonNull(T value, T fallback) {
        return value != null ? value : fallback;
    }

    /**
     * 查询任务列表（支持分页和排序）
     * @param request 查询任务列表请求
     * @return 分页结果
     */
    /**
     * 查询任务列表（分页）
     * <p>
     * 优化：使用只读事务，提高并发性能
     * - readOnly = true: 告诉数据库这是只读操作，可以优化锁策略
     * - timeout = 5: 5秒超时，避免慢查询
     */
    @Transactional(readOnly = true, timeout = 5)
    public TaskRepository.PageResult<Task> getTaskList(GetTaskListRequest request) {
        // 转换状态
        TaskStatus taskStatus = null;
        // 修复：允许状态为 0（DRAFT），之前使用 > 0 导致 DRAFT 状态无法筛选
        // 只有当 status 不为 null 时才进行转换（包括 0）
        if (request.getStatus() != null) {
            // 验证状态码是否有效（0-4）
            Integer statusCode = request.getStatus();
            if (statusCode >= 0 && statusCode <= 4) {
                taskStatus = TaskStatus.fromCode(statusCode);
            }
            // 如果状态码无效，taskStatus 保持为 null，将查询所有状态
        }

        // 设置默认值
        Integer pageNo = request.getPageNo();
        Integer pageSize = request.getPageSize();
        if (pageNo == null || pageNo < 1) {
            pageNo = 1;
        }
        if (pageSize == null || pageSize < 1) {
            pageSize = 10;
        }
        // 限制每页最大数量，防止一次性查询过多数据
        if (pageSize > 100) {
            pageSize = 100;
        }

        // 管理员可查看全部任务（传 null 不按用户过滤）；普通用户仅能查看自己的任务
        String filterByUserId = (Boolean.TRUE.equals(request.getIsAdmin())) ? null : request.getClerkUserId();

        return taskRepository.findWithPagination(
                filterByUserId,
                taskStatus,
                request.getKeyword(),
                request.getOrder(),
                pageNo,
                pageSize
        );
    }

    /**
     * 查询任务详情（仅返回任务基本信息）
     */
    public Optional<Task> getTaskDetail(Long taskId) {
        return taskRepository.findById(TaskId.of(taskId));
    }

    /**
     * 获取任务统计数据（当前用户）
     * @param clerkUserId 用户ID
     * @return 任务统计数据（包含已完成数量、进行中数量、平均质量分）
     */
    /**
     * 获取任务统计数据
     * <p>
     * 优化：使用只读事务
     */
    @Transactional(readOnly = true, timeout = 5)
    public TaskSummaryData getTaskSummary(String clerkUserId) {
        // 直接在数据库侧统计，避免加载全部任务
        long completedCount = taskRepository.countByStatus(clerkUserId, TaskStatus.COMPLETED);
        long inProgressCount = taskRepository.countByStatus(clerkUserId, TaskStatus.IN_PROGRESS);

        return new TaskSummaryData((int) completedCount, (int) inProgressCount, 0.0);
    }

    /**
     * 任务统计数据内部类
     */
    public static class TaskSummaryData {
        private final Integer taskCompletedSize;
        private final Integer taskInProgressSize;
        private final Double avgQuality;

        public TaskSummaryData(Integer taskCompletedSize, Integer taskInProgressSize, Double avgQuality) {
            this.taskCompletedSize = taskCompletedSize;
            this.taskInProgressSize = taskInProgressSize;
            this.avgQuality = avgQuality;
        }

        public Integer getTaskCompletedSize() {
            return taskCompletedSize;
        }

        public Integer getTaskInProgressSize() {
            return taskInProgressSize;
        }

        public Double getAvgQuality() {
            return avgQuality;
        }
    }

    /**
     * 评价任务
     *
     * @param request 评价任务请求
     */
    /**
     * 评价任务
     *
     * 优化：添加事务超时
     */
    @Transactional(timeout = 5)
    public void rateTask(RateTaskRequest request) {
        Optional<Task> taskOpt = taskRepository.findById(TaskId.of(request.getTaskId()));
        if (taskOpt.isEmpty()) {
            throw new RuntimeException("Task not found: " + request.getTaskId());
        }

        Task task = taskOpt.get();

        // 验证任务是否可以评价
        taskDomainService.validateTaskCanBeRated(task);

        // TODO: 保存评价记录到 task_ratings 表
        // 需要创建 TaskRatingRepository 和 TaskRating 领域模型
        log.info("任务评价: taskId={}, score={}, content={}",
                request.getTaskId(), request.getScore(), request.getContent());
    }
}

