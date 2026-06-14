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
import com.studyagent.service.domain.quota.QuotaDomainService;
import com.studyagent.service.domain.task.PythonBackendClient;
import com.studyagent.service.domain.task.TaskFileRepository;
import com.studyagent.service.domain.task.TaskDetailReader;
import com.studyagent.service.domain.task.TaskExecutionCleanup;
import com.studyagent.service.domain.user.ClerkClient;
import com.studyagent.service.domain.user.User;
import com.studyagent.service.domain.user.UserRepository;
import com.studyagent.service.domain.file.FileRepository;
import com.studyagent.service.domain.file.FileId;
import com.studyagent.service.domain.mq.MqOutbox;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import com.studyagent.common.datetime.DateTimeFormats;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.studyagent.common.api.ApiCode;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.common.exception.InsufficientQuotaData;
import com.studyagent.common.exception.InsufficientQuotaException;
import com.studyagent.common.log.util.TraceIdUtil;
import com.studyagent.common.quota.FeatureCode;
import com.studyagent.common.exception.QuotaExceededData;
import com.studyagent.common.exception.QuotaExceededException;
import com.studyagent.service.application.dto.SaveDraftResult;
import com.studyagent.service.application.dto.StopTaskApplicationResult;
import com.studyagent.service.application.dto.TaskDetailDTO;
import com.studyagent.service.config.TaskSubmitConfig;
import com.studyagent.service.application.dto.TaskListItemDTO;
import com.studyagent.service.application.dto.TaskListResult;
import com.studyagent.service.application.request.GetTaskDetailRequest;

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
    private final MqOutboxService mqOutboxService;
    private final ClerkClient clerkClient;
    private final FileRepository fileRepository;
    private final TaskFileRepository taskFileRepository;
    private final TaskDetailReader taskDetailReader;
    private final TaskExecutionCleanup taskExecutionCleanup;
    private final UserRepository userRepository;
    private final TaskSubmitConfig taskSubmitConfig;
    private final QuotaDomainService quotaDomainService;
    private final Gson gson = new Gson();

    /**
     * 新建草稿短时间幂等：key = clerkUserId + '#' + sha256(内容指纹)，value 为已提交成功的草稿 id 与过期时间。
     * 仅 JVM 内有效；多实例部署可日后换 Redis。
     */
    private final ConcurrentHashMap<String, SaveDraftIdemEntry> saveDraftIdemCache = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, Object> saveDraftIdemLocks = new ConcurrentHashMap<>();

    /** 新建草稿写入进行中，供并发请求在提交完成前自旋等待，避免重复 INSERT */
    private final ConcurrentHashMap<String, Boolean> saveDraftIdemInflight = new ConcurrentHashMap<>();

    private record SaveDraftIdemEntry(long draftId, long expireAtMillis) {
    }

    /** 提交任务结果 */
    public record SubmitTaskResult(long taskId, QuotaInfo quota, boolean quotaConsumed) {
        public record QuotaInfo(int dailyLimit, int usedToday, int remainingQuota, String quotaResetAt) {
        }
    }

    /**
     * 提交任务
     *
     * @param request 提交任务请求
     * @return 提交结果（含 taskId 和额度信息）
     * @throws QuotaExceededException 普通用户当日提交次数达上限时抛出
     *                                <p>
     *                                优化说明：移除方法级 @Transactional，只在必要的数据库操作时开启事务
     *                                这样可以：
     *                                1. 减少事务持有时间，避免阻塞其他查询
     *                                2. 验证和构建对象在事务外执行
     *                                3. Python 后端调用在事务外异步执行
     */
    public SubmitTaskResult submitTask(SubmitTaskRequest request) {
        // 1. 验证用户身份（事务外执行）
        ClerkClient.UserInfo userInfo = clerkClient.verifyToken(normalizeToken(request.getToken()));

        // 2. 额度校验
        SubmitTaskResult.QuotaInfo quotaInfo;
        boolean shouldConsumeQuota = false; // 是否扣减额度（admin 不扣减）
        if (taskSubmitConfig.isQuotaEnabled()) {
            // AI 额度体系：admin 无限使用，普通用户检查额度
            boolean isAdmin = userRepository.findByClerkUserId(userInfo.clerkUserId)
                    .map(User::getIsAdmin)
                    .orElse(false);
            if (isAdmin) {
                shouldConsumeQuota = false; // admin 不扣减
            } else {
                if (!quotaDomainService.canConsume(userInfo.clerkUserId, FeatureCode.TASK_CREATE.getCode(), 1)) {
                    var balance = quotaDomainService.getUserQuota(userInfo.clerkUserId,
                            FeatureCode.TASK_CREATE.getCode());
                    throw new InsufficientQuotaException(
                            "Insufficient quota. Free: " + balance.freeBalance() + ", Paid: " + balance.paidBalance(),
                            InsufficientQuotaData.builder()
                                    .featureCode(balance.featureCode())
                                    .featureName(balance.featureName())
                                    .quotaUnit(balance.quotaUnit())
                                    .freeBalance(balance.freeBalance())
                                    .freePeriodTotal(balance.freePeriodTotal())
                                    .paidBalance(balance.paidBalance())
                                    .totalAvailable(balance.totalAvailable())
                                    .build());
                }
                shouldConsumeQuota = true;
            }
            quotaInfo = null; // AI 额度模式下不返回旧的 QuotaInfo
        } else {
            quotaInfo = checkAndBuildQuota(userInfo.clerkUserId);
        }

        // 3. 查询现有草稿（只读操作，使用 readOnly 事务）
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
                request.getClarifyingQuestions(),
                request.getOutputLanguage());

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
                .traceId(existing != null && existing.getTraceId() != null ? existing.getTraceId()
                        : TraceIdUtil.getTraceId())
                .build();

        // 4. 验证任务（事务外执行，纯业务逻辑）
        taskDomainService.validateTask(task);

        // 5. 提交任务（修改状态，纯内存操作）
        task = task.submit();

        // 6. 在短事务内保存任务和关联文件
        Long taskId = saveTaskAndFilesInTransaction(task, request.getObjectIds(),
                shouldConsumeQuota ? userInfo.clerkUserId : null);

        // 8. 更新额度信息（仅每日次数模式）
        SubmitTaskResult.QuotaInfo finalQuota = quotaInfo != null
                ? new SubmitTaskResult.QuotaInfo(quotaInfo.dailyLimit(), quotaInfo.usedToday() + 1,
                        quotaInfo.remainingQuota() - 1, quotaInfo.quotaResetAt())
                : null;

        // 是否发生了额度扣减：AI 额度模式时 shouldConsumeQuota，每日次数模式时使用了当日配额
        boolean quotaConsumed = shouldConsumeQuota || (finalQuota != null);

        return new SubmitTaskResult(taskId, finalQuota, quotaConsumed);
    }

    /**
     * 额度校验：普通用户且启用限额时，检查当日提交次数；超限则抛出 QuotaExceededException
     * 管理员或不限额时返回 null
     */
    private SubmitTaskResult.QuotaInfo checkAndBuildQuota(String clerkUserId) {
        if (!taskSubmitConfig.isLimitEnabled()) {
            return null;
        }
        boolean isAdmin = userRepository.findByClerkUserId(clerkUserId)
                .map(User::getIsAdmin)
                .orElse(false);
        if (isAdmin) {
            return null;
        }
        int limit = taskSubmitConfig.getDailyLimitPerUser();
        long usedToday = taskRepository.countSubmittedToday(clerkUserId);
        LocalDateTime resetLocal = LocalDate.now().plusDays(1).atStartOfDay();
        String quotaResetAt = resetLocal.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        ZoneId serverZone = DateTimeFormats.APP_ZONE;
        String quotaResetAtUtc = ZonedDateTime.of(resetLocal, serverZone)
                .withZoneSameInstant(ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " UTC";
        int remaining = Math.max(0, limit - (int) usedToday);

        if (usedToday >= limit) {
            QuotaExceededData data = QuotaExceededData.builder()
                    .dailyLimit(limit)
                    .usedToday((int) usedToday)
                    .remainingQuota(0)
                    .quotaResetAt(quotaResetAt)
                    .quotaResetAtUtc(quotaResetAtUtc)
                    .build();
            String message = ApiCode.QUOTA_EXCEEDED.formatEn(limit)
                    + " Quota resets at " + quotaResetAt + " " + serverZone.getId() + " (" + quotaResetAtUtc + ").";
            throw new QuotaExceededException(message, data);
        }

        return new SubmitTaskResult.QuotaInfo(limit, (int) usedToday, remaining, quotaResetAt);
    }

    /**
     * 追问前额度校验：追问属于任务创建流程
     * - quotaEnabled: admin 豁免，普通用户检查 AI 额度
     * - 每日次数模式: admin 豁免，检查当日是否达上限
     */
    public void checkQuotaBeforeClarify(String clerkUserId) {
        if (taskSubmitConfig.isQuotaEnabled()) {
            boolean isAdmin = userRepository.findByClerkUserId(clerkUserId)
                    .map(User::getIsAdmin)
                    .orElse(false);
            if (!isAdmin && !quotaDomainService.canConsume(clerkUserId, FeatureCode.TASK_CREATE.getCode(), 1)) {
                var balance = quotaDomainService.getUserQuota(clerkUserId, FeatureCode.TASK_CREATE.getCode());
                throw new InsufficientQuotaException(
                        "Insufficient quota for clarify. Free: " + balance.freeBalance() + ", Paid: "
                                + balance.paidBalance(),
                        InsufficientQuotaData.builder()
                                .featureCode(balance.featureCode())
                                .featureName(balance.featureName())
                                .quotaUnit(balance.quotaUnit())
                                .freeBalance(balance.freeBalance())
                                .freePeriodTotal(balance.freePeriodTotal())
                                .paidBalance(balance.paidBalance())
                                .totalAvailable(balance.totalAvailable())
                                .build());
            }
        } else {
            checkAndBuildQuota(clerkUserId);
        }
    }

    /**
     * 获取当前用户的任务提交额度（用于提交前展示）
     * - quotaEnabled 时：返回 null，请使用 QuotaController.getBalance
     * - 每日次数模式：管理员或不限额时返回 null
     */
    public SubmitTaskResult.QuotaInfo getSubmitQuota(String clerkUserId) {
        if (clerkUserId == null || clerkUserId.isEmpty()) {
            return null;
        }
        if (taskSubmitConfig.isQuotaEnabled()) {
            return null; // AI 额度模式，使用 GET /v1/quota/balance
        }
        if (!taskSubmitConfig.isLimitEnabled()) {
            return null;
        }
        boolean isAdmin = userRepository.findByClerkUserId(clerkUserId)
                .map(User::getIsAdmin)
                .orElse(false);
        if (isAdmin) {
            return null;
        }
        int limit = taskSubmitConfig.getDailyLimitPerUser();
        long usedToday = taskRepository.countSubmittedToday(clerkUserId);
        String quotaResetAt = LocalDate.now().plusDays(1).atStartOfDay()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        int remaining = Math.max(0, limit - (int) usedToday);
        return new SubmitTaskResult.QuotaInfo(limit, (int) usedToday, remaining, quotaResetAt);
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
     * 在短事务内保存任务、扣减额度（如启用）、关联文件
     * 事务超时设置为 10 秒，避免长时间持有锁
     *
     * @param clerkUserIdForQuota 启用 AI 额度时传入，用于扣减；否则为 null
     */
    @Transactional(timeout = 10)
    protected Long saveTaskAndFilesInTransaction(Task task, List<String> objectIds, String clerkUserIdForQuota) {
        // 保存任务
        Task savedTask = taskRepository.save(task);
        Long taskId = savedTask.getId().getValue();

        // 扣减 AI 额度（与任务保存同事务，失败则整体回滚）
        if (clerkUserIdForQuota != null) {
            quotaDomainService.consume(
                    clerkUserIdForQuota,
                    FeatureCode.TASK_CREATE.getCode(),
                    1,
                    "task",
                    String.valueOf(taskId),
                    Map.of("task_id", taskId));
        }

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

        // 写入本地消息表
        mqOutboxService.createMessage("EXECUTE_TASK", taskId, "{}");

        return taskId;
    }

    /**
     * 保存草稿
     *
     * @param request 保存草稿请求
     * @return draftId 与是否命中短时间内容幂等
     *         <p>
     *         优化：添加事务超时，避免长时间持有锁
     */
    @Transactional(timeout = 10)
    public SaveDraftResult saveDraft(SaveDraftRequest request) {
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
                request.getClarifyingQuestions(),
                request.getOutputLanguage());

        if (existing == null) {
            int ttlSec = taskSubmitConfig.getSaveDraftIdempotencyTtlSeconds();
            if (ttlSec > 0) {
                String idemKey = buildSaveDraftIdempotencyKey(userInfo.clerkUserId, request, mergedRequirementJson);
                Object lock = saveDraftIdemLocks.computeIfAbsent(idemKey, k -> new Object());
                synchronized (lock) {
                    try {
                        evictExpiredSaveDraftIdemEntries();
                        SaveDraftIdemEntry hit = saveDraftIdemCache.get(idemKey);
                        if (hit != null && hit.expireAtMillis >= System.currentTimeMillis()
                                && isDraftOwnedByUser(hit.draftId, userInfo.clerkUserId)) {
                            log.debug("save-draft idempotent hit: keyEnding={}, draftId={}",
                                    idemKey.substring(Math.max(0, idemKey.length() - 12)), hit.draftId);
                            return new SaveDraftResult(hit.draftId, true);
                        }
                        if (hit != null) {
                            saveDraftIdemCache.remove(idemKey);
                        }

                        long spinDeadline = System.currentTimeMillis() + 10_000;
                        while (saveDraftIdemInflight.containsKey(idemKey) && System.currentTimeMillis() < spinDeadline) {
                            try {
                                Thread.sleep(20);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                            evictExpiredSaveDraftIdemEntries();
                            hit = saveDraftIdemCache.get(idemKey);
                            if (hit != null && hit.expireAtMillis >= System.currentTimeMillis()
                                    && isDraftOwnedByUser(hit.draftId, userInfo.clerkUserId)) {
                                return new SaveDraftResult(hit.draftId, true);
                            }
                        }

                        hit = saveDraftIdemCache.get(idemKey);
                        if (hit != null && hit.expireAtMillis >= System.currentTimeMillis()
                                && isDraftOwnedByUser(hit.draftId, userInfo.clerkUserId)) {
                            return new SaveDraftResult(hit.draftId, true);
                        }
                        if (hit != null) {
                            saveDraftIdemCache.remove(idemKey);
                        }

                        saveDraftIdemInflight.put(idemKey, Boolean.TRUE);
                        try {
                            Long draftId = persistDraftRowAndFiles(request, userInfo, null, dueDate, mergedRequirementJson);
                            registerSaveDraftIdemCallbacks(idemKey, draftId, ttlSec);
                            return new SaveDraftResult(draftId, false);
                        } catch (RuntimeException e) {
                            saveDraftIdemInflight.remove(idemKey);
                            throw e;
                        }
                    } finally {
                        saveDraftIdemLocks.remove(idemKey, lock);
                    }
                }
            }
        }

        Long draftId = persistDraftRowAndFiles(request, userInfo, existing, dueDate, mergedRequirementJson);
        return new SaveDraftResult(draftId, false);
    }

    private Long persistDraftRowAndFiles(SaveDraftRequest request, ClerkClient.UserInfo userInfo, Task existing,
            LocalDateTime dueDate, String mergedRequirementJson) {
        Task.TaskBuilder builder = Task.builder()
                .id(existing != null ? existing.getId() : null)
                .clerkUserId(userInfo.clerkUserId)
                .taskTitle(firstNonNull(request.getTaskTitle(), existing != null ? existing.getTaskTitle() : null))
                .taskDesc(firstNonNull(request.getTaskDesc(), existing != null ? existing.getTaskDesc() : null))
                .subject(firstNonNull(request.getSubject(), existing != null ? existing.getSubject() : null))
                .academicLevel(
                        firstNonNull(request.getAcademicLevel(), existing != null ? existing.getAcademicLevel() : null))
                .priorityLevel(
                        firstNonNull(request.getPriorityLevel(), existing != null ? existing.getPriorityLevel() : null))
                .dueDate(dueDate)
                .format(firstNonNull(request.getFormat(), existing != null ? existing.getFormat() : List.of()))
                .citationStyle(
                        firstNonNull(request.getCitationStyle(), existing != null ? existing.getCitationStyle() : null))
                .pageLength(firstNonNull(request.getPageLength(), existing != null ? existing.getPageLength() : null))
                .specialInstructions(firstNonNull(request.getSpecialInstructions(),
                        existing != null ? existing.getSpecialInstructions() : null))
                .requirementJson(mergedRequirementJson)
                .status(TaskStatus.DRAFT)
                .traceId(existing != null && existing.getTraceId() != null ? existing.getTraceId()
                        : TraceIdUtil.getTraceId());

        Task savedTask = taskRepository.save(builder.build());
        Long draftId = savedTask.getId().getValue();

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

    private void evictExpiredSaveDraftIdemEntries() {
        long now = System.currentTimeMillis();
        saveDraftIdemCache.entrySet().removeIf(e -> e.getValue().expireAtMillis < now);
    }

    /**
     * 与即将落库的字段一致；请求未带 dueDate 时用固定占位，避免「默认截止日」随当前时间变化导致指纹不一致。
     */
    private String buildSaveDraftIdempotencyKey(String clerkUserId, SaveDraftRequest request,
            String mergedRequirementJson) {
        Map<String, Object> fp = new TreeMap<>();
        fp.put("academicLevel", request.getAcademicLevel());
        fp.put("citationStyle", request.getCitationStyle());
        fp.put("clarifyingQuestions", request.getClarifyingQuestions());
        fp.put("dueDate", request.getDueDate() != null ? request.getDueDate().toString() : "");
        fp.put("formatJson", gson.toJson(request.getFormat() != null ? request.getFormat() : List.of()));
        fp.put("objectIdsJson", gson.toJson(request.getObjectIds() != null ? request.getObjectIds() : List.of()));
        fp.put("pageLength", request.getPageLength());
        fp.put("priorityLevel", request.getPriorityLevel());
        fp.put("requirementsJson", request.getRequirementsJson());
        fp.put("specialInstructions", request.getSpecialInstructions());
        fp.put("subject", request.getSubject());
        fp.put("taskDesc", request.getTaskDesc());
        fp.put("taskTitle", request.getTaskTitle());
        fp.put("mergedRequirementJson", mergedRequirementJson != null ? mergedRequirementJson : "");
        fp.put("outputLanguage", request.getOutputLanguage());
        String payload = gson.toJson(fp);
        return clerkUserId + "#" + sha256Hex(payload);
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private void registerSaveDraftIdemCallbacks(String idemKey, long draftId, int ttlSeconds) {
        long ttlMs = TimeUnit.SECONDS.toMillis(ttlSeconds);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            saveDraftIdemCache.put(idemKey, new SaveDraftIdemEntry(draftId, System.currentTimeMillis() + ttlMs));
            saveDraftIdemInflight.remove(idemKey);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                saveDraftIdemCache.put(idemKey, new SaveDraftIdemEntry(draftId, System.currentTimeMillis() + ttlMs));
                saveDraftIdemInflight.remove(idemKey);
            }

            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    saveDraftIdemInflight.remove(idemKey);
                }
            }
        });
    }

    private boolean isDraftOwnedByUser(long draftId, String clerkUserId) {
        return taskRepository.findById(TaskId.of(draftId))
                .filter(t -> clerkUserId.equals(t.getClerkUserId()) && t.getStatus() == TaskStatus.DRAFT)
                .isPresent();
    }

    private String mergeRequirementJson(String existingJson, String requirementsJson, String clarifyingQuestions,
            String outputLanguage) {
        boolean hasRequirements = requirementsJson != null && !requirementsJson.trim().isEmpty();
        boolean hasClarifying = clarifyingQuestions != null && !clarifyingQuestions.trim().isEmpty();
        boolean hasOutputLang = outputLanguage != null && !outputLanguage.trim().isEmpty();
        if (!hasRequirements && !hasClarifying && !hasOutputLang) {
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
            merged.put("clarifyingQuestions", clarifyingQuestions); // 原样存储传入的 JSON 字符串，不解析
        }
        if (hasOutputLang) {
            merged.put("outputLanguage", outputLanguage.trim());
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
     * @param taskIds     任务ID列表
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
    public record DeleteTasksResult(int deletedCount, List<Long> failedTaskIds) {
    }

    /**
     * 停止任务：将任务恢复为可编辑草稿（DRAFT），清理执行态数据，不逻辑删除主任务。
     * 已完成/失败：幂等成功，不改变数据。已是草稿：幂等成功。
     */
    public StopTaskApplicationResult stopTask(com.studyagent.service.application.request.StopTaskRequest request) {
        ClerkClient.UserInfo userInfo = clerkClient.verifyToken(normalizeToken(request.getToken()));
        Task task = findTaskWithValidation(request.getTaskId(), userInfo.clerkUserId);
        long taskId = task.getId().getValue();

        if (task.getStatus() == TaskStatus.COMPLETED || task.getStatus() == TaskStatus.FAILED) {
            return new StopTaskApplicationResult(taskId, task.getStatus().getCode(), false, false);
        }

        if (task.getStatus() == TaskStatus.DRAFT) {
            return new StopTaskApplicationResult(taskId, TaskStatus.DRAFT.getCode(), false, false);
        }

        taskExecutionCleanup.resetTaskToDraftAndClearExecution(taskId);
        mqOutboxService.createMessageInNewTransaction("STOP_TASK", taskId, "{}");

        return new StopTaskApplicationResult(taskId, TaskStatus.DRAFT.getCode(), false, true);
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

    private String normalizeToken(String token) {
        if (token == null) {
            return null;
        }
        return token.startsWith("Bearer ") ? token.substring(7) : token;
    }

    private <T> T firstNonNull(T value, T fallback) {
        return value != null ? value : fallback;
    }

    private static final int TOTAL_ESTIMATED_SECONDS = 20 * 60;
    private static final int SIMULATED_PROGRESS_WINDOW_SECONDS = 120;
    private static final double SIMULATED_PROGRESS_MAX_PERCENT = 10.0;
    /**
     * 查询任务列表（分页），含队列信息与列表项转换
     * <p>
     * 优化：使用只读事务，批量获取队列信息，避免 N+1 查询
     */
    @Transactional(readOnly = true, timeout = 5)
    public TaskListResult getTaskList(GetTaskListRequest request) {
        TaskStatus taskStatus = null;
        if (request.getStatus() != null && request.getStatus() >= 0 && request.getStatus() <= 4) {
            taskStatus = TaskStatus.fromCode(request.getStatus());
        }

        Integer pageNo = request.getPageNo();
        Integer pageSize = request.getPageSize();
        if (pageNo == null || pageNo < 1)
            pageNo = 1;
        if (pageSize == null || pageSize < 1)
            pageSize = 10;
        if (pageSize > 100)
            pageSize = 100;

        String filterByUserId = (Boolean.TRUE.equals(request.getIsAdmin())) ? null : request.getClerkUserId();
        TaskRepository.PageResult<Task> pageResult = taskRepository.findWithPagination(
                filterByUserId, taskStatus, request.getKeyword(), request.getOrder(), pageNo, pageSize);

        Map<Long, PythonBackendClient.TaskQueueInfo> queueInfoMap = fetchQueueInfoBatch(pageResult.getItems());
        List<TaskListItemDTO> items = pageResult.getItems().stream()
                .map(task -> convertToTaskListItemDTO(task, queueInfoMap.get(task.getId().getValue())))
                .toList();

        return TaskListResult.builder()
                .taskList(items)
                .total(pageResult.getTotal().intValue())
                .pageNo(pageNo)
                .pageSize(pageSize)
                .build();
    }

    private Map<Long, PythonBackendClient.TaskQueueInfo> fetchQueueInfoBatch(List<Task> tasks) {
        try {
            List<TaskId> taskIds = tasks.stream()
                    .filter(t -> t.getStatus() == TaskStatus.IN_PROGRESS)
                    .map(Task::getId)
                    .toList();
            if (taskIds.isEmpty())
                return Map.of();
            return pythonBackendClient.getTaskQueueBatchInfo(taskIds);
        } catch (Exception e) {
            log.warn("批量获取任务队列信息失败: error={}", e.getMessage());
            return Map.of();
        }
    }

    private TaskListItemDTO convertToTaskListItemDTO(Task task, PythonBackendClient.TaskQueueInfo queueInfo) {
        int queueAheadCount = 0;
        if (task.getStatus() == TaskStatus.IN_PROGRESS && queueInfo != null) {
            queueAheadCount = queueInfo.getAheadCount();
        }
        double effectivePercent = resolveCompletePercent(task);
        return TaskListItemDTO.builder()
                .id(TaskListItemDTO.IdValue.builder().value(task.getId().getValue()).build())
                .clerkUserId(task.getClerkUserId())
                .taskTitle(task.getTaskTitle())
                .taskDesc(task.getTaskDesc())
                .subject(task.getSubject())
                .academicLevel(task.getAcademicLevel())
                .priorityLevel(task.getPriorityLevel())
                .dueDate(DateTimeFormats.formatApi(task.getDueDate()))
                .format(task.getFormat())
                .citationStyle(task.getCitationStyle())
                .pageLength(task.getPageLength())
                .specialInstructions(task.getSpecialInstructions())
                .status(task.getStatus().name())
                .startTime(DateTimeFormats.formatApi(task.getStartTime()))
                .finishTime(DateTimeFormats.formatApi(task.getFinishTime()))
                .costTime(task.getCostTime())
                .completePercent(java.math.BigDecimal.valueOf(effectivePercent))
                .taskCompletedSize(task.getTaskCompletedSize())
                .activeAgentSize(task.getActiveAgentSize())
                .estRemainingTime(computeEstRemainingTime(task.getStatus(), effectivePercent))
                .requirementJson(task.getRequirementJson())
                .finalResult(task.getFinalResult())
                .errorMessage(task.getErrorMessage())
                .queueAheadCount(queueAheadCount)
                .build();
    }

    private int computeEstRemainingTime(Task task) {
        return computeEstRemainingTime(
                task.getStatus(),
                task.getCompletePercent() != null ? task.getCompletePercent().doubleValue() : 0.0);
    }

    private int computeEstRemainingTime(TaskStatus status, double effectivePercent) {
        Integer code = status != null ? status.getCode() : TaskStatus.DRAFT.getCode();
        if (code.equals(TaskStatus.COMPLETED.getCode()) || code.equals(TaskStatus.FAILED.getCode())
                || code.equals(TaskStatus.CANCELLED.getCode())) {
            return 0;
        }
        double p = Math.max(0.0, Math.min(100.0, effectivePercent));
        return Math.max(0, (int) Math.round(TOTAL_ESTIMATED_SECONDS * (1.0 - p / 100.0)));
    }

    /**
     * 与 TaskDetailReaderImpl.resolveCompletePercent 一致：前 2 分钟线性模拟至 10%，之后模拟底保持 10%，
     * 与真实进度取较大值，避免满 2 分钟或 Python 上报 0% 时进度条跌回 0。
     */
    private double resolveCompletePercent(Task task) {
        double realPercent = task.getCompletePercent() != null
                ? task.getCompletePercent().doubleValue() : 0.0;

        if (task.getStatus() == null || task.getStatus() != TaskStatus.IN_PROGRESS) {
            return realPercent;
        }
        if (task.getStartTime() == null) {
            return realPercent;
        }

        long nowEpoch = System.currentTimeMillis() / 1000;
        long startEpoch = DateTimeFormats.toEpochSecond(task.getStartTime());
        long elapsedSeconds = Math.max(0, nowEpoch - startEpoch);

        long effectiveElapsed = Math.min(elapsedSeconds, SIMULATED_PROGRESS_WINDOW_SECONDS);
        double simulatedPercent = (effectiveElapsed * SIMULATED_PROGRESS_MAX_PERCENT) / SIMULATED_PROGRESS_WINDOW_SECONDS;
        simulatedPercent = Math.min(simulatedPercent, SIMULATED_PROGRESS_MAX_PERCENT);

        return Math.max(realPercent, simulatedPercent);
    }

    /**
     * 查询任务详情（仅返回任务基本信息）
     */
    public Optional<Task> findTaskById(Long taskId) {
        return taskRepository.findById(TaskId.of(taskId));
    }

    /**
     * 获取任务详情（含子任务、Agent、活动、输出等完整信息）
     * 包含权限校验：管理员可查看所有任务，普通用户仅能查看自己的任务
     */
    @Transactional(readOnly = true, timeout = 10)
    public TaskDetailDTO getTaskDetail(GetTaskDetailRequest request) {
        Long taskId = request.getTaskId();
        String clerkUserId = request.getClerkUserId();
        if (taskId == null || clerkUserId == null || clerkUserId.isEmpty()) {
            throw new BusinessException(ApiCode.PARAM_ERROR.getCode(), ApiCode.PARAM_ERROR.getMessage());
        }

        Optional<Task> taskOpt = taskRepository.findById(TaskId.of(taskId));
        if (taskOpt.isEmpty()) {
            throw new BusinessException(ApiCode.TASK_NOT_FOUND.getCode(), ApiCode.TASK_NOT_FOUND.getMessage());
        }
        Task task = taskOpt.get();

        boolean isAdmin = userRepository.findByClerkUserId(clerkUserId)
                .map(User::getIsAdmin)
                .orElse(false);
        if (!isAdmin && !clerkUserId.equals(task.getClerkUserId())) {
            throw new BusinessException(ApiCode.NO_PERMISSION.getCode(), ApiCode.NO_PERMISSION.getMessage());
        }

        Optional<TaskDetailDTO> detailOpt = taskDetailReader.loadByTaskId(taskId);
        if (detailOpt.isEmpty()) {
            throw new BusinessException(ApiCode.TASK_NOT_FOUND.getCode(), ApiCode.TASK_NOT_FOUND.getMessage());
        }
        TaskDetailDTO detail = detailOpt.get();

        int queueAheadCount = 0;
        try {
            PythonBackendClient.TaskQueueInfo queueInfo = pythonBackendClient.getTaskQueueInfo(TaskId.of(taskId));
            if (queueInfo != null) {
                queueAheadCount = queueInfo.getAheadCount();
            }
        } catch (Exception e) {
            log.warn("获取任务队列信息失败: taskId={}, error={}", taskId, e.getMessage());
        }
        detail.getTaskBaseInfo().setQueueAheadCount(queueAheadCount);

        return detail;
    }

    /**
     * 获取任务统计数据（当前用户）
     * 
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
