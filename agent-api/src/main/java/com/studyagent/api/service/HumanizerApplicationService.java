package com.studyagent.api.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.studyagent.api.dto.response.HumanizerSubmitResult;
import com.studyagent.api.dto.response.HumanizerTaskItemResponse;
import com.studyagent.api.dto.response.HumanizerTaskListResponse;
import com.studyagent.api.dto.response.HumanizerTaskResponse;
import com.studyagent.common.datetime.DateTimeFormats;
import com.studyagent.common.exception.InsufficientQuotaData;
import com.studyagent.common.exception.InsufficientQuotaException;
import com.studyagent.common.quota.FeatureCode;
import com.studyagent.infra.entity.HumanizerTaskEntity;
import com.studyagent.infra.repository.humanizer.HumanizerTaskRepositoryImpl;
import com.studyagent.service.domain.quota.ConsumeResult;
import com.studyagent.service.domain.quota.QuotaDomainService;
import com.studyagent.service.domain.user.User;
import com.studyagent.service.domain.user.UserRepository;
import com.studyagent.service.domain.humanizer.HumanizerServiceClient;
import com.studyagent.service.application.verla.HumanizerTaskNameDispatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Humanizer 应用服务（异步队列版）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HumanizerApplicationService {
    private static final String STATUS_CHARGING = "CHARGING";
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_PROCESSING = "PROCESSING";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_QUOTA_EXHAUSTED = "QUOTA_EXHAUSTED";

    private final HumanizerTaskRepositoryImpl repository;
    private final QuotaDomainService quotaDomainService;
    private final UserRepository userRepository;
    private final HumanizerServiceClient humanizerServiceClient;
    private final HumanizerTaskNameDispatcher humanizerTaskNameDispatcher;
    private final PaymentResumeContextService paymentResumeContextService;

    /** 内测白名单用户（不限额度），通过配置 humanizer.whitelist-user-ids 设置 */
    @org.springframework.beans.factory.annotation.Value("${humanizer.whitelist-user-ids:}")
    private List<String> whitelistUserIds;

    private static final int PREVIEW_LENGTH = 512;

    // ===== 预估时间参数（基于实测数据 2026-03-12） =====
    // --- DETECT ---
    /** DETECT: 固定开销（秒） */
    private static final double DETECT_BASE_SECONDS = 3.0;
    /** DETECT: 每句平均耗时（秒），实测 4-5.5s 取中值 */
    private static final double DETECT_SECONDS_PER_SENTENCE = 4.8;
    /** DETECT: 平均每句字符数（实测 80-228，中位数约 180） */
    private static final int DETECT_AVG_CHARS_PER_SENTENCE = 180;
    // --- HUMANIZE ---
    /** HUMANIZE: 每字符耗时（秒），实测 ≤10000 chars 约 0.012s/char */
    private static final double HUMANIZE_SECONDS_PER_CHAR = 0.012;
    /** HUMANIZE: 大文本阈值，超过此值并发效果显著 */
    private static final int HUMANIZE_LARGE_TEXT_THRESHOLD = 10000;
    /** HUMANIZE: 大文本并发折扣系数（超出部分按此比例计算） */
    private static final double HUMANIZE_LARGE_TEXT_DISCOUNT = 0.5;
    /** HUMANIZE: 最小处理时间 */
    private static final double HUMANIZE_MIN_SECONDS = 5.0;
    /** HUMANIZE: 并发数（用于排队等待时间计算） */
    private static final int HUMANIZE_CONCURRENCY = 3;

    /**
     * 提交任务（入库排队）
     * <p>
     * DETECT: 只做前置校验（余额 >= 1），不预扣费，由 Worker 启动时扣 1 次
     * HUMANIZE: 提交时按每次启动扣 1 次
     *
     * @return 任务响应及是否发生了额度扣减
     */
    public HumanizerSubmitResult submitTask(String clerkUserId, String taskType, String text, String source) {
        // 1. 计算 word count（按空格分词）
        int wordCount = countWords(text);

        // 2. 确定 feature_code
        String featureCode = "DETECT".equals(taskType)
                ? FeatureCode.AI_DETECTION.getCode()
                : FeatureCode.HUMANIZER.getCode();

        // 3. 额度校验（admin 和白名单用户跳过）
        boolean isAdmin = userRepository.findByClerkUserId(clerkUserId)
                .map(User::getIsAdmin)
                .orElse(false);
        boolean isWhitelisted = whitelistUserIds != null && whitelistUserIds.contains(clerkUserId);

        Long quotaLedgerId = null;
        boolean quotaConsumed = false;
        boolean shouldChargeHumanizeAfterInsert = false;

        if (!isAdmin && !isWhitelisted) {
            if ("DETECT".equals(taskType)) {
                // DETECT: 先调 Python 分句，仅用于拿总字数/总分句做展示元数据；
                // V2 扣费口径改为每次启动扣 1 次，因此这里只校验余额 >= 1。
                var splitResult = humanizerServiceClient.splitSentences(text);
                int splitTotalChunks = 0;
                int splitTotalWords = 0;
                if (splitResult != null && splitResult.getCode() == 200
                        && splitResult.getChunks() != null && !splitResult.getChunks().isEmpty()) {
                    splitTotalChunks = splitResult.getTotalChunks();
                    splitTotalWords = splitResult.getTotalWords();
                }

                var balance = quotaDomainService.getUserQuota(clerkUserId, featureCode);
                if (balance.totalAvailable() < 1) {
                    // 额度不足：入库为 QUOTA_EXHAUSTED，返回 taskId 供前端恢复
                    HumanizerTaskEntity exhaustedEntity = new HumanizerTaskEntity();
                    exhaustedEntity.setClerkUserId(clerkUserId);
                    exhaustedEntity.setSource(source);
                    exhaustedEntity.setTaskType(taskType);
                    exhaustedEntity.setInputText(text);
                    exhaustedEntity.setStatus(STATUS_QUOTA_EXHAUSTED);
                    exhaustedEntity.setRetryCount(0);
                    exhaustedEntity.setCompletedSentences(0);
                    exhaustedEntity.setTotalWords(splitTotalWords > 0 ? splitTotalWords : wordCount);
                    exhaustedEntity.setConsumedWords(0);
                    exhaustedEntity.setErrorMessage("Insufficient quota at submission. Required: 1 run");
                    if (splitTotalChunks > 0) {
                        exhaustedEntity.setTotalSentences(splitTotalChunks);
                    }
                    exhaustedEntity.setCreatedAt(DateTimeFormats.now());
                    exhaustedEntity.setUpdatedAt(DateTimeFormats.now());
                    repository.insert(exhaustedEntity);
                    log.info("DETECT 额度不足，任务入库为 QUOTA_EXHAUSTED: id={}, userId={}, required=1, available={}",
                            exhaustedEntity.getId(), clerkUserId, balance.totalAvailable());

                    HumanizerTaskResponse response = buildQuotaExhaustedResponse(
                            exhaustedEntity,
                            clerkUserId,
                            taskType,
                            "detection_start",
                            splitTotalWords > 0 ? splitTotalWords : wordCount);
                    return new HumanizerSubmitResult(response, false);
                }
                // DETECT 不预扣费，quotaConsumed = false
            } else {
                // HUMANIZE: V2 改为每次启动扣 1 次
                if (!quotaDomainService.canConsume(clerkUserId, featureCode, 1L)) {
                    // 额度不足：入库为 QUOTA_EXHAUSTED，返回 taskId 供前端恢复
                    HumanizerTaskEntity exhaustedEntity = new HumanizerTaskEntity();
                    exhaustedEntity.setClerkUserId(clerkUserId);
                    exhaustedEntity.setSource(source);
                    exhaustedEntity.setTaskType(taskType);
                    exhaustedEntity.setInputText(text);
                    exhaustedEntity.setStatus(STATUS_QUOTA_EXHAUSTED);
                    exhaustedEntity.setRetryCount(0);
                    exhaustedEntity.setCompletedSentences(0);
                    exhaustedEntity.setTotalWords(wordCount);
                    exhaustedEntity.setConsumedWords(0);
                    exhaustedEntity.setErrorMessage("Insufficient quota at submission. Required: 1 run");
                    exhaustedEntity.setCreatedAt(DateTimeFormats.now());
                    exhaustedEntity.setUpdatedAt(DateTimeFormats.now());
                    repository.insert(exhaustedEntity);
                    log.info("HUMANIZE 额度不足，任务入库为 QUOTA_EXHAUSTED: id={}, userId={}, required=1",
                            exhaustedEntity.getId(), clerkUserId);

                    HumanizerTaskResponse response = buildQuotaExhaustedResponse(
                            exhaustedEntity,
                            clerkUserId,
                            taskType,
                            "humanizer_start",
                            wordCount);
                    return new HumanizerSubmitResult(response, false);
                }

                shouldChargeHumanizeAfterInsert = true;
            }
        }

        // 4. 入库
        HumanizerTaskEntity entity = new HumanizerTaskEntity();
        entity.setClerkUserId(clerkUserId);
        entity.setSource(source);
        entity.setTaskType(taskType);
        entity.setInputText(text);
        entity.setStatus(shouldChargeHumanizeAfterInsert ? STATUS_CHARGING : STATUS_PENDING);
        entity.setRetryCount(0);
        entity.setCompletedSentences(0);
        entity.setQuotaLedgerId(quotaLedgerId);
        entity.setTotalWords(wordCount);
        entity.setConsumedWords(0);
        entity.setCreatedAt(DateTimeFormats.now());
        entity.setUpdatedAt(DateTimeFormats.now());

        repository.insert(entity);

        if (shouldChargeHumanizeAfterInsert) {
            try {
                ConsumeResult consumeResult = quotaDomainService.consume(
                        clerkUserId, featureCode, 1L,
                        "humanizer_task", String.valueOf(entity.getId()),
                        Map.of(
                                "task_type", taskType,
                                "task_id", entity.getId(),
                                "word_count", wordCount,
                                "charged_mode", "per_run",
                                "charged_amount", 1L),
                        "humanizer:" + entity.getId() + ":start");
                quotaLedgerId = consumeResult.ledgerId();
                quotaConsumed = true;
                entity.setQuotaLedgerId(quotaLedgerId);

                HumanizerTaskEntity ledgerUpdate = new HumanizerTaskEntity();
                ledgerUpdate.setId(entity.getId());
                ledgerUpdate.setStatus(STATUS_PENDING);
                ledgerUpdate.setQuotaLedgerId(quotaLedgerId);
                repository.updateById(ledgerUpdate);
                log.info("HUMANIZE 额度扣减成功: userId={}, feature={}, amount=1, words={}, ledgerId={}, taskId={}",
                        clerkUserId, featureCode, wordCount, quotaLedgerId, entity.getId());
            } catch (RuntimeException ex) {
                HumanizerTaskEntity exhaustedUpdate = new HumanizerTaskEntity();
                exhaustedUpdate.setId(entity.getId());
                exhaustedUpdate.setStatus(STATUS_QUOTA_EXHAUSTED);
                exhaustedUpdate.setErrorMessage("Quota exhausted before humanize started");
                repository.updateById(exhaustedUpdate);

                log.warn("HUMANIZE 入库后启动扣费失败: taskId={}, userId={}, error={}",
                        entity.getId(), clerkUserId, ex.getMessage());
                HumanizerTaskResponse response = buildQuotaExhaustedResponse(
                        entity,
                        clerkUserId,
                        taskType,
                        "humanizer_start",
                        wordCount);
                return new HumanizerSubmitResult(response, false);
            }
        }

        // 提交即异步生成任务标题（复用 Python ConversationTitleService，经 MQ 回写 task_name）。
        // best-effort：dispatcher 内部已吞异常，绝不影响任务提交。
        humanizerTaskNameDispatcher.dispatch(entity.getId(), clerkUserId, text);

        // 计算预估时间（分开：排队 + 处理）
        int queueAhead = repository.countQueueAhead(taskType, entity.getId());
        double processTime = estimateProcessTime(taskType, text.length());
        double waitTime = estimateWaitTime(taskType, queueAhead);

        log.info("任务已入库: id={}, type={}, userId={}, words={}, queueAhead={}, processTime={}s, waitTime={}s",
                entity.getId(), taskType, clerkUserId, wordCount, queueAhead,
                Math.round(processTime), Math.round(waitTime));

        HumanizerTaskResponse response = HumanizerTaskResponse.builder()
                .id(entity.getId())
                .taskType(taskType)
                .status(STATUS_PENDING)
                .estimatedSeconds((int) Math.ceil(processTime))
                .estimatedQueueSeconds((int) Math.ceil(waitTime))
                .queuePosition(queueAhead)
                .totalWords(wordCount)
                .consumedWords(0)
                .progress(0)
                .build();

        return new HumanizerSubmitResult(response, quotaConsumed);
    }

    /**
     * 取消任务
     * CHARGING/PENDING/PROCESSING → CANCELLED，释放资源，退还未消耗的额度
     * COMPLETED/FAILED/CANCELLED/QUOTA_EXHAUSTED → 直接返回当前状态，不做操作
     */
    public HumanizerTaskResponse cancelTask(Long taskId, String clerkUserId) {
        HumanizerTaskEntity entity = repository.findById(taskId);
        if (entity == null) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        if (!entity.getClerkUserId().equals(clerkUserId)) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }

        String status = entity.getStatus();

        // 只有 PENDING/PROCESSING 才需要取消
        if (STATUS_CHARGING.equals(status) || STATUS_PENDING.equals(status) || STATUS_PROCESSING.equals(status)) {
            boolean cancelled = repository.cancelTask(taskId);
            if (cancelled) {
                log.info("任务已取消: taskId={}, previousStatus={}", taskId, status);
                // HUMANIZE + CHARGING/PENDING：提交时已扣费但还没开始处理，退还额度
                if ("HUMANIZE".equals(entity.getTaskType())
                        && (STATUS_CHARGING.equals(status) || STATUS_PENDING.equals(status))) {
                    refundOnCancel(entity);
                }
            } else {
                log.info("任务取消竞争失败（可能已完成）: taskId={}", taskId);
            }
        } else {
            log.info("任务无需取消，当前状态: taskId={}, status={}", taskId, status);
        }

        // 返回最新状态
        return toDetailResponse(repository.findById(taskId));
    }

    /**
     * HUMANIZE 排队态取消时退还额度
     */
    private void refundOnCancel(HumanizerTaskEntity entity) {
        if (entity.getQuotaLedgerId() == null) return;
        try {
            quotaDomainService.refund(entity.getQuotaLedgerId(), "humanizer_task_cancelled");
            log.info("HUMANIZE 排队态额度已退还: taskId={}, ledgerId={}", entity.getId(), entity.getQuotaLedgerId());
        } catch (Exception e) {
            log.error("取消任务退还额度失败: taskId={}, ledgerId={}", entity.getId(), entity.getQuotaLedgerId(), e);
        }
    }

    /**
     * 续跑 QUOTA_EXHAUSTED 的 DETECT 任务
     * 用户充值后调用，校验余额 >= 1 后将状态改回 PENDING，Worker 会从断点继续
     */
    public HumanizerTaskResponse resumeTask(Long taskId, String clerkUserId) {
        HumanizerTaskEntity entity = repository.findById(taskId);
        if (entity == null) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        if (!entity.getClerkUserId().equals(clerkUserId)) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        if (!STATUS_QUOTA_EXHAUSTED.equals(entity.getStatus())) {
            throw new IllegalStateException("Task is not in QUOTA_EXHAUSTED status, current: " + entity.getStatus());
        }

        // 校验余额 >= 1
        boolean isAdmin = userRepository.findByClerkUserId(clerkUserId)
                .map(User::getIsAdmin)
                .orElse(false);
        boolean isWhitelisted = whitelistUserIds != null && whitelistUserIds.contains(clerkUserId);

        if (!isAdmin && !isWhitelisted) {
            // 根据任务类型选择正确的 featureCode
            String featureCode = "DETECT".equals(entity.getTaskType())
                    ? FeatureCode.AI_DETECTION.getCode()
                    : FeatureCode.HUMANIZER.getCode();
            var balance = quotaDomainService.getUserQuota(clerkUserId, featureCode);

            if (balance.totalAvailable() < 1) {
                throw new InsufficientQuotaException(
                        "Insufficient quota to resume. Free: " + balance.freeBalance() + ", Paid: " + balance.paidBalance(),
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

            // HUMANIZE resume 时需要预扣费（原始提交时额度不足未扣费）
            if ("HUMANIZE".equals(entity.getTaskType())) {
                ConsumeResult consumeResult = quotaDomainService.consume(
                        clerkUserId, featureCode, 1L,
                        "humanizer_task", String.valueOf(taskId),
                        Map.of(
                                "task_type", entity.getTaskType(),
                                "task_id", taskId,
                                "word_count", entity.getTotalWords() != null ? entity.getTotalWords() : 0,
                                "charged_mode", "per_run",
                                "charged_amount", 1L),
                        "humanizer:" + taskId + ":start");
                // 更新 quotaLedgerId 以便后续退款
                HumanizerTaskEntity ledgerUpdate = new HumanizerTaskEntity();
                ledgerUpdate.setId(taskId);
                ledgerUpdate.setQuotaLedgerId(consumeResult.ledgerId());
                repository.updateById(ledgerUpdate);
                log.info("HUMANIZE resume 额度扣减成功: userId={}, amount=1, ledgerId={}",
                        clerkUserId, consumeResult.ledgerId());
            }
        }

        // 改回 PENDING，Worker 下一轮会捡起来继续
        HumanizerTaskEntity update = new HumanizerTaskEntity();
        update.setId(taskId);
        update.setStatus(STATUS_PENDING);
        update.setErrorMessage(null);
        repository.updateById(update);

        log.info("任务续跑: taskId={}, taskType={}, userId={}, completedSentences={}",
                taskId, entity.getTaskType(), clerkUserId, entity.getCompletedSentences());

        return toDetailResponse(repository.findById(taskId));
    }

    /**
     * 统计 word 数，与前端逻辑对齐：
     * - CJK 字符（中日韩）每个字算 1 word
     * - 非 CJK 部分按空格分词，每个词算 1 word
     * - 混合文本两者相加
     */
    private int countWords(String text) {
        if (text == null || text.isBlank()) return 0;
        // CJK Unicode ranges: 中文、CJK扩展A、平假名、片假名、韩文
        String cjkPattern = "[\\u4e00-\\u9fff\\u3400-\\u4dbf\\u3040-\\u309f\\u30a0-\\u30ff\\uac00-\\ud7af]";
        java.util.regex.Matcher cjkMatcher = java.util.regex.Pattern.compile(cjkPattern).matcher(text);
        int cjkCount = 0;
        while (cjkMatcher.find()) cjkCount++;
        // 把 CJK 字符替换成空格，剩余部分按空格分词
        String nonCjk = text.replaceAll(cjkPattern, " ").trim();
        int engCount = nonCjk.isEmpty() ? 0
                : (int) java.util.Arrays.stream(nonCjk.split("\\s+"))
                        .filter(w -> !w.isEmpty()).count();
        return cjkCount + engCount;
    }

    /**
     * 查询单个任务详情（完整数据，含大字段）
     */
    public HumanizerTaskResponse getTask(Long id, String clerkUserId) {
        HumanizerTaskEntity entity = repository.findById(id);
        if (entity == null) {
            throw new IllegalArgumentException("Task not found: " + id);
        }
        if (!entity.getClerkUserId().equals(clerkUserId)) {
            throw new IllegalArgumentException("Task not found: " + id);
        }
        return toDetailResponse(entity);
    }

    /**
     * 分页查询用户任务列表（精简字段）
     */
    public HumanizerTaskListResponse listTasks(String clerkUserId, String taskType, String source, int page, int size) {
        Page<HumanizerTaskEntity> result = repository.findByUserPaged(clerkUserId, taskType, source, page, size);

        List<HumanizerTaskItemResponse> items = result.getRecords().stream()
                .map(this::toItemResponse)
                .collect(Collectors.toList());

        return HumanizerTaskListResponse.builder()
                .items(items)
                .page(page)
                .size(size)
                .total(result.getTotal())
                .totalPages((int) result.getPages())
                .build();
    }

    /**
     * 详情响应（完整数据）
     * PENDING/PROCESSING 状态时带上预估剩余时间和进度百分比
     */
    private HumanizerTaskResponse toDetailResponse(HumanizerTaskEntity entity) {
        String status = externalStatus(entity.getStatus());
        HumanizerTaskResponse.HumanizerTaskResponseBuilder builder = HumanizerTaskResponse.builder()
                .id(entity.getId())
                .taskType(entity.getTaskType())
                .taskName(entity.getTaskName())
                .inputText(entity.getInputText())
                .status(status)
                .probability(entity.getProbability())
                .label(entity.getLabel())
                .sentencesJson(entity.getSentencesJson())
                .totalSentences(entity.getTotalSentences())
                .completedSentences(entity.getCompletedSentences())
                .resultText(entity.getResultText())
                .elapsedSeconds(entity.getElapsedSeconds())
                .errorMessage(entity.getErrorMessage())
                .totalWords(entity.getTotalWords())
                .consumedWords(entity.getConsumedWords())
                .createdAt(DateTimeFormats.formatApi(entity.getCreatedAt()));

        // 计算进度百分比
        int progress = calculateProgress(entity);
        builder.progress(progress);

        // 未完成的任务带上预估时间
        if (STATUS_PENDING.equals(status) || STATUS_PROCESSING.equals(status)) {
            int textLen = entity.getInputText() != null ? entity.getInputText().length() : 0;
            int queueAhead = repository.countQueueAhead(entity.getTaskType(), entity.getId());
            double processTime = estimateProcessTime(entity.getTaskType(), textLen);

            if (STATUS_PROCESSING.equals(status)) {
                // 正在处理，只返回处理剩余时间，排队时间为 0
                int remaining = estimateRemaining(entity);
                builder.estimatedSeconds(remaining);
                builder.estimatedQueueSeconds(0);
                builder.queuePosition(0);
            } else {
                // 排队中，分开返回排队时间和处理时间
                double waitTime = estimateWaitTime(entity.getTaskType(), queueAhead);
                builder.estimatedSeconds((int) Math.ceil(processTime));
                builder.estimatedQueueSeconds((int) Math.ceil(waitTime));
                builder.queuePosition(queueAhead);
            }
        }

        return builder.build();
    }

    /**
     * 列表单条响应（精简，大字段只取前50字符）
     */
    private HumanizerTaskItemResponse toItemResponse(HumanizerTaskEntity entity) {
        return HumanizerTaskItemResponse.builder()
                .id(entity.getId())
                .taskType(entity.getTaskType())
                .taskName(entity.getTaskName())
                .status(externalStatus(entity.getStatus()))
                .inputTextPreview(preview(entity.getInputText()))
                .probability(entity.getProbability())
                .label(entity.getLabel())
                .totalSentences(entity.getTotalSentences())
                .completedSentences(entity.getCompletedSentences())
                .resultTextPreview(preview(entity.getResultText()))
                .elapsedSeconds(entity.getElapsedSeconds())
                .errorMessage(entity.getErrorMessage())
                .createdAt(DateTimeFormats.formatApi(entity.getCreatedAt()))
                .build();
    }

    private HumanizerTaskResponse buildQuotaExhaustedResponse(
            HumanizerTaskEntity entity,
            String clerkUserId,
            String taskType,
            String scene,
            int totalWords) {
        return HumanizerTaskResponse.builder()
                .id(entity.getId())
                .taskType(taskType)
                .status(STATUS_QUOTA_EXHAUSTED)
                .totalWords(totalWords)
                .consumedWords(0)
                .progress(0)
                .resumeToken(paymentResumeContextService.createHumanizerResumeContext(
                        clerkUserId,
                        scene,
                        entity.getId(),
                        "humanizer:" + entity.getId() + ":start"))
                .build();
    }

    private String preview(String text) {
        if (text == null) return null;
        return text.length() <= PREVIEW_LENGTH ? text : text.substring(0, PREVIEW_LENGTH) + "...";
    }

    // ===== 进度百分比计算 =====

    /**
     * 计算进度百分比 (0~100)
     * - COMPLETED → 100
     * - CHARGING/PENDING → 0
     * - PROCESSING → 根据实际进度计算，最低 1
     * - FAILED / QUOTA_EXHAUSTED → 0
     */
    private int calculateProgress(HumanizerTaskEntity entity) {
        String status = externalStatus(entity.getStatus());

        if (STATUS_COMPLETED.equals(status)) return 100;
        if (STATUS_PENDING.equals(status)) return 0;

        if ("DETECT".equals(entity.getTaskType())) {
            Integer total = entity.getTotalSentences();
            Integer completed = entity.getCompletedSentences();
            if (total != null && total > 0 && completed != null && completed > 0) {
                int pct = (int) Math.round(completed * 100.0 / total);
                return Math.max(1, Math.min(99, pct));
            }
            if (STATUS_PROCESSING.equals(status)) return 1;
            return 0;
        } else {
            if (STATUS_PROCESSING.equals(status) && entity.getStartedAt() != null) {
                int textLen = entity.getInputText() != null ? entity.getInputText().length() : 0;
                double totalEstimate = estimateProcessTime("HUMANIZE", textLen);
                long elapsedSec = java.time.Duration.between(entity.getStartedAt(), DateTimeFormats.now()).getSeconds();
                if (totalEstimate > 0) {
                    int pct = (int) Math.round(elapsedSec * 100.0 / totalEstimate);
                    return Math.max(1, Math.min(95, pct));
                }
                return 1;
            }
            if (STATUS_PROCESSING.equals(status)) return 1;
            return 0;
        }
    }

    private String externalStatus(String status) {
        if (STATUS_CHARGING.equals(status)) {
            return STATUS_PENDING;
        }
        return status;
    }

    // ===== 预估时间计算 =====

    /**
     * 预估总时间 = 排队等待 + 自身处理
     */
    private int estimateTime(String taskType, int textLength, int queueAhead) {
        double selfTime = estimateProcessTime(taskType, textLength);
        double waitTime = estimateWaitTime(taskType, queueAhead);
        return (int) Math.ceil(selfTime + waitTime);
    }

    /**
     * 预估自身处理时间（不含排队等待）
     *
     * DETECT 公式（基于 130+ 条 COMPLETED 样本拟合）：
     *   sentences = max(1, textLength / 180)
     *   time = 3 + sentences × 4.8
     *   - 固定开销 3s（模型加载/网络）
     *   - 每句 4.8s（实测 4.0-5.5s 取中值）
     *   - 180 chars/sentence（实测 80-228 中位数）
     *
     * HUMANIZE 公式（基于 43 条 COMPLETED 样本拟合）：
     *   ≤10000 chars: time = textLength × 0.012
     *   >10000 chars: time = 10000×0.012 + (超出部分)×0.012×0.5
     *   - 0.012s/char（实测 0.010-0.013 取均值）
     *   - 大文本（>10000）并发 3 路效果显著，超出部分打 5 折
     */
    private double estimateProcessTime(String taskType, int textLength) {
        if ("DETECT".equals(taskType)) {
            int sentences = Math.max(1, textLength / DETECT_AVG_CHARS_PER_SENTENCE);
            return Math.max(HUMANIZE_MIN_SECONDS, DETECT_BASE_SECONDS + sentences * DETECT_SECONDS_PER_SENTENCE);
        } else {
            double estimated;
            if (textLength <= HUMANIZE_LARGE_TEXT_THRESHOLD) {
                estimated = textLength * HUMANIZE_SECONDS_PER_CHAR;
            } else {
                estimated = HUMANIZE_LARGE_TEXT_THRESHOLD * HUMANIZE_SECONDS_PER_CHAR
                        + (textLength - HUMANIZE_LARGE_TEXT_THRESHOLD) * HUMANIZE_SECONDS_PER_CHAR * HUMANIZE_LARGE_TEXT_DISCOUNT;
            }
            return Math.max(HUMANIZE_MIN_SECONDS, estimated);
        }
    }

    /**
     * 预估排队等待时间
     * DETECT 串行，HUMANIZE 3 并发
     */
    private double estimateWaitTime(String taskType, int queueAhead) {
        if (queueAhead <= 0) return 0;
        // 假设排队中的任务平均文本长度 2000 字符
        double avgTaskTime = estimateProcessTime(taskType, 2000);
        if ("HUMANIZE".equals(taskType)) {
            // 3 并发，排队轮次 = ceil(queueAhead / 3)
            int rounds = (queueAhead + HUMANIZE_CONCURRENCY - 1) / HUMANIZE_CONCURRENCY;
            return rounds * avgTaskTime;
        } else {
            // DETECT 串行
            return queueAhead * avgTaskTime;
        }
    }

    /**
     * PROCESSING 状态下预估剩余时间
     * DETECT: 根据 completedSentences / totalSentences 推算
     * HUMANIZE: 根据已开始时间和预估总时间推算
     */
    private int estimateRemaining(HumanizerTaskEntity entity) {
        if ("DETECT".equals(entity.getTaskType())) {
            Integer total = entity.getTotalSentences();
            Integer completed = entity.getCompletedSentences();
            if (total != null && total > 0 && completed != null) {
                int remaining = total - completed;
                return (int) Math.ceil(remaining * DETECT_SECONDS_PER_SENTENCE);
            }
            // 还不知道总句数，用文本长度估
            int textLen = entity.getInputText() != null ? entity.getInputText().length() : 0;
            return (int) Math.ceil(estimateProcessTime("DETECT", textLen));
        } else {
            // HUMANIZE 没有进度信息，用文本长度估总时间，减去已过时间
            int textLen = entity.getInputText() != null ? entity.getInputText().length() : 0;
            double totalEstimate = estimateProcessTime("HUMANIZE", textLen);
            if (entity.getStartedAt() != null) {
                long elapsedSec = java.time.Duration.between(entity.getStartedAt(), DateTimeFormats.now()).getSeconds();
                return (int) Math.max(0, Math.ceil(totalEstimate - elapsedSec));
            }
            return (int) Math.ceil(totalEstimate);
        }
    }
}
