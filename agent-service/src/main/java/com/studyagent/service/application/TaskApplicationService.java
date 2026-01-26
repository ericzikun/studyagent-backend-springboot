package com.studyagent.service.application;

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
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
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
    
    /**
     * 提交任务
     * @param request 提交任务请求
     * @return taskId
     */
    @Transactional
    public Long submitTask(SubmitTaskRequest request) {
        // 1. 验证用户身份
        ClerkClient.UserInfo userInfo = clerkClient.verifyToken(normalizeToken(request.getToken()));
        
        Task existing = null;
        if (request.getDraftId() != null) {
            existing = taskRepository.findById(TaskId.of(request.getDraftId()))
                .orElseThrow(() -> new RuntimeException("草稿不存在: " + request.getDraftId()));
            if (!userInfo.clerkUserId.equals(existing.getClerkUserId())) {
                throw new RuntimeException("无权限提交该草稿");
            }
            if (existing.getStatus() != TaskStatus.DRAFT) {
                throw new RuntimeException("仅允许提交草稿状态的任务");
            }
        }

        // 2. 创建任务领域模型（草稿存在时复用同一个 ID）
        // 如果 dueDate 为 null，默认设置为当前日期加一个月
        LocalDateTime dueDate = firstNonNull(request.getDueDate(), existing != null ? existing.getDueDate() : null);
        if (dueDate == null) {
            dueDate = LocalDateTime.now().plusMonths(1);
        }
        
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
            .requirementJson(existing != null ? existing.getRequirementJson() : null)
            .status(TaskStatus.DRAFT)
            .build();
        
        // 3. 验证任务（调用领域服务）
        taskDomainService.validateTask(task);
        
        // 4. 提交任务（调用领域行为）
        task = task.submit();
        
        // 5. 保存任务
        Task savedTask = taskRepository.save(task);
        Long taskId = savedTask.getId().getValue();
        
        // 6. 关联文件到任务（如果提供了文件objectIds）
        if (request.getObjectIds() != null) {
            taskFileRepository.removeByTaskId(taskId);
            int order = 0;
            for (String objectId : request.getObjectIds()) {
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
        
        // 7. 在事务提交后调用 Python 后端执行任务
        // 这样可以确保 Python 后端查询时，数据已经提交到数据库
        TransactionSynchronizationManager.registerSynchronization(
            new org.springframework.transaction.support.TransactionSynchronizationAdapter() {
                @Override
                public void afterCommit() {
                    log.info("事务已提交，开始调用 Python 后端执行任务: taskId={}", taskId);
                    try {
                        pythonBackendClient.executeTask(TaskId.of(taskId));
                        log.info("成功调用 Python 后端执行任务: taskId={}", taskId);
                    } catch (Exception e) {
                        log.error("调用 Python 后端执行任务失败: taskId={}", taskId, e);
                        // 任务已保存，Python 后端调用失败不影响任务创建
                    }
                }
            }
        );
        
        return taskId;
    }

    /**
     * 保存草稿
     * @param request 保存草稿请求
     * @return draftId
     */
    @Transactional
    public Long saveDraft(SaveDraftRequest request) {
        // 1. 验证用户身份
        ClerkClient.UserInfo userInfo = clerkClient.verifyToken(normalizeToken(request.getToken()));

        Task existing = null;
        if (request.getDraftId() != null) {
            existing = taskRepository.findById(TaskId.of(request.getDraftId()))
                .orElseThrow(() -> new RuntimeException("草稿不存在: " + request.getDraftId()));
            if (!userInfo.clerkUserId.equals(existing.getClerkUserId())) {
                throw new RuntimeException("无权限更新该草稿");
            }
            if (existing.getStatus() != TaskStatus.DRAFT) {
                throw new RuntimeException("仅允许更新草稿状态的任务");
            }
        }

        // 处理 dueDate：如果请求中没有且现有任务也没有，则设置为当前日期加一个月
        LocalDateTime dueDate = firstNonNull(request.getDueDate(), existing != null ? existing.getDueDate() : null);
        if (dueDate == null) {
            dueDate = LocalDateTime.now().plusMonths(1);
        }
        
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
            .requirementJson(firstNonNull(request.getRequirementsJson(), existing != null ? existing.getRequirementJson() : null))
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

    /**
     * 停止任务
     * @param request 停止任务请求
     * @return taskId
     */
    @Transactional
    public Long stopTask(com.studyagent.service.application.request.StopTaskRequest request) {
        ClerkClient.UserInfo userInfo = clerkClient.verifyToken(normalizeToken(request.getToken()));

        Task task = taskRepository.findById(TaskId.of(request.getTaskId()))
            .orElseThrow(() -> new RuntimeException("任务不存在: " + request.getTaskId()));

        if (!userInfo.clerkUserId.equals(task.getClerkUserId())) {
            throw new RuntimeException("无权限停止该任务");
        }

        if (task.getStatus() == TaskStatus.COMPLETED || task.getStatus() == TaskStatus.FAILED) {
            return task.getId().getValue();
        }

        if (task.getStatus() != TaskStatus.CANCELLED) {
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
                .errorMessage("任务已取消")
                .build();

            taskRepository.save(cancelled);
        }

        Long taskId = task.getId().getValue();

        TransactionSynchronizationManager.registerSynchronization(
            new org.springframework.transaction.support.TransactionSynchronizationAdapter() {
                @Override
                public void afterCommit() {
                    try {
                        pythonBackendClient.stopTask(TaskId.of(taskId));
                        log.info("成功调用 Python 后端停止任务: taskId={}", taskId);
                    } catch (Exception e) {
                        log.error("调用 Python 后端停止任务失败: taskId={}", taskId, e);
                    }
                }
            }
        );

        return taskId;
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
        
        return taskRepository.findWithPagination(
            request.getClerkUserId(), 
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
     * @param request 评价任务请求
     */
    @Transactional
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

