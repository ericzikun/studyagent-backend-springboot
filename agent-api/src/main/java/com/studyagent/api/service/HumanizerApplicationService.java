package com.studyagent.api.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.studyagent.api.dto.response.HumanizerSubmitResult;
import com.studyagent.api.dto.response.HumanizerTaskItemResponse;
import com.studyagent.api.dto.response.HumanizerTaskListResponse;
import com.studyagent.api.dto.response.HumanizerTaskResponse;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
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

    private final HumanizerTaskRepositoryImpl repository;
    private final QuotaDomainService quotaDomainService;
    private final UserRepository userRepository;
    private final HumanizerServiceClient humanizerServiceClient;

    /** 内测白名单用户（不限额度），通过配置 humanizer.whitelist-user-ids 设置 */
    @org.springframework.beans.factory.annotation.Value("${humanizer.whitelist-user-ids:}")
    private List<String> whitelistUserIds;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
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
     * DETECT: 只做前置校验（余额 >= 1），不预扣费，由 Worker 逐块扣费
     * HUMANIZE: 提交时一次性按总 words 扣费（PM 要求按整体粒度判断）
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

        if (!isAdmin && !isWhitelisted) {
            if ("DETECT".equals(taskType)) {
                // DETECT: 先调 Python 分句，获取第一个 chunk 的 wordCount
                // 然后校验余额 >= 第一个 chunk 的 wordCount
                var splitResult = humanizerServiceClient.splitSentences(text);
                int firstChunkWords = 1;
                int splitTotalChunks = 0;
                int splitTotalWords = 0;
                if (splitResult != null && splitResult.getCode() == 200
                        && splitResult.getChunks() != null && !splitResult.getChunks().isEmpty()) {
                    firstChunkWords = Math.max(1, splitResult.getChunks().get(0).getWordCount());
                    splitTotalChunks = splitResult.getTotalChunks();
                    splitTotalWords = splitResult.getTotalWords();
                }

                var balance = quotaDomainService.getUserQuota(clerkUserId, featureCode);
                if (balance.totalAvailable() < firstChunkWords) {
                    throw new InsufficientQuotaException(
                            "Insufficient quota. Free: " + balance.freeBalance() + ", Paid: " + balance.paidBalance()
                                    + ", Required: at least " + firstChunkWords + " words (first chunk)",
                            InsufficientQuotaData.builder()
                                    .featureCode(balance.featureCode())
                                    .featureName(balance.featureName())
                                    .quotaUnit(balance.quotaUnit())
                                    .freeBalance(balance.freeBalance())
                                    .freePeriodTotal(balance.freePeriodTotal())
                                    .paidBalance(balance.paidBalance())
                                    .totalAvailable(balance.totalAvailable())
                                    .firstChunkWords(firstChunkWords)
                                    .totalChunks(splitTotalChunks)
                                    .totalWords(splitTotalWords)
                                    .build());
                }
                // DETECT 不预扣费，quotaConsumed = false
            } else {
                // HUMANIZE: 一次性按总 words 扣费
                if (!quotaDomainService.canConsume(clerkUserId, featureCode, wordCount)) {
                    var balance = quotaDomainService.getUserQuota(clerkUserId, featureCode);
                    throw new InsufficientQuotaException(
                            "Insufficient quota. Free: " + balance.freeBalance() + ", Paid: " + balance.paidBalance() + ", Required: " + wordCount,
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

                ConsumeResult consumeResult = quotaDomainService.consume(
                        clerkUserId, featureCode, wordCount,
                        "humanizer_task", null,
                        Map.of("task_type", taskType, "word_count", wordCount));
                quotaLedgerId = consumeResult.ledgerId();
                quotaConsumed = true;
                log.info("HUMANIZE 额度扣减成功: userId={}, feature={}, words={}, ledgerId={}",
                        clerkUserId, featureCode, wordCount, quotaLedgerId);
            }
        }

        // 4. 入库
        HumanizerTaskEntity entity = new HumanizerTaskEntity();
        entity.setClerkUserId(clerkUserId);
        entity.setSource(source);
        entity.setTaskType(taskType);
        entity.setInputText(text);
        entity.setStatus("PENDING");
        entity.setRetryCount(0);
        entity.setCompletedSentences(0);
        entity.setQuotaLedgerId(quotaLedgerId);
        entity.setTotalWords(wordCount);
        entity.setConsumedWords(0);

        repository.insert(entity);

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
                .status("PENDING")
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
     * PENDING/PROCESSING → CANCELLED，释放资源，退还未消耗的额度
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
        if ("PENDING".equals(status) || "PROCESSING".equals(status)) {
            boolean cancelled = repository.cancelTask(taskId);
            if (cancelled) {
                log.info("任务已取消: taskId={}, previousStatus={}", taskId, status);
                // HUMANIZE + PENDING：提交时已扣费但还没开始处理，退还额度
                if ("HUMANIZE".equals(entity.getTaskType()) && "PENDING".equals(status)) {
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
     * HUMANIZE PENDING 取消时退还额度
     */
    private void refundOnCancel(HumanizerTaskEntity entity) {
        if (entity.getQuotaLedgerId() == null) return;
        try {
            quotaDomainService.refund(entity.getQuotaLedgerId(), "humanizer_task_cancelled");
            log.info("HUMANIZE PENDING 额度已退还: taskId={}, ledgerId={}", entity.getId(), entity.getQuotaLedgerId());
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
        if (!"QUOTA_EXHAUSTED".equals(entity.getStatus())) {
            throw new IllegalStateException("Task is not in QUOTA_EXHAUSTED status, current: " + entity.getStatus());
        }

        // 校验余额 >= 1
        boolean isAdmin = userRepository.findByClerkUserId(clerkUserId)
                .map(User::getIsAdmin)
                .orElse(false);
        boolean isWhitelisted = whitelistUserIds != null && whitelistUserIds.contains(clerkUserId);

        if (!isAdmin && !isWhitelisted) {
            String featureCode = FeatureCode.AI_DETECTION.getCode();
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
        }

        // 改回 PENDING，Worker 下一轮会捡起来从 completedSentences 继续
        HumanizerTaskEntity update = new HumanizerTaskEntity();
        update.setId(taskId);
        update.setStatus("PENDING");
        update.setErrorMessage(null);
        repository.updateById(update);

        log.info("DETECT 任务续跑: taskId={}, userId={}, completedSentences={}",
                taskId, clerkUserId, entity.getCompletedSentences());

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
        HumanizerTaskResponse.HumanizerTaskResponseBuilder builder = HumanizerTaskResponse.builder()
                .id(entity.getId())
                .taskType(entity.getTaskType())
                .inputText(entity.getInputText())
                .status(entity.getStatus())
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
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FMT) : null);

        String status = entity.getStatus();

        // 计算进度百分比
        int progress = calculateProgress(entity);
        builder.progress(progress);

        // 未完成的任务带上预估时间
        if ("PENDING".equals(status) || "PROCESSING".equals(status)) {
            int textLen = entity.getInputText() != null ? entity.getInputText().length() : 0;
            int queueAhead = repository.countQueueAhead(entity.getTaskType(), entity.getId());
            double processTime = estimateProcessTime(entity.getTaskType(), textLen);

            if ("PROCESSING".equals(status)) {
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
                .status(entity.getStatus())
                .inputTextPreview(preview(entity.getInputText()))
                .probability(entity.getProbability())
                .label(entity.getLabel())
                .totalSentences(entity.getTotalSentences())
                .completedSentences(entity.getCompletedSentences())
                .resultTextPreview(preview(entity.getResultText()))
                .elapsedSeconds(entity.getElapsedSeconds())
                .errorMessage(entity.getErrorMessage())
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FMT) : null)
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
     * - PENDING → 0
     * - PROCESSING → 根据实际进度计算，最低 1
     * - FAILED / QUOTA_EXHAUSTED → 0
     */
    private int calculateProgress(HumanizerTaskEntity entity) {
        String status = entity.getStatus();

        if ("COMPLETED".equals(status)) return 100;
        if ("PENDING".equals(status)) return 0;

        if ("DETECT".equals(entity.getTaskType())) {
            Integer total = entity.getTotalSentences();
            Integer completed = entity.getCompletedSentences();
            if (total != null && total > 0 && completed != null && completed > 0) {
                int pct = (int) Math.round(completed * 100.0 / total);
                return Math.max(1, Math.min(99, pct));
            }
            if ("PROCESSING".equals(status)) return 1;
            return 0;
        } else {
            if ("PROCESSING".equals(status) && entity.getStartedAt() != null) {
                int textLen = entity.getInputText() != null ? entity.getInputText().length() : 0;
                double totalEstimate = estimateProcessTime("HUMANIZE", textLen);
                long elapsedSec = java.time.Duration.between(entity.getStartedAt(), java.time.LocalDateTime.now()).getSeconds();
                if (totalEstimate > 0) {
                    int pct = (int) Math.round(elapsedSec * 100.0 / totalEstimate);
                    return Math.max(1, Math.min(95, pct));
                }
                return 1;
            }
            if ("PROCESSING".equals(status)) return 1;
            return 0;
        }
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
                long elapsedSec = java.time.Duration.between(entity.getStartedAt(), java.time.LocalDateTime.now()).getSeconds();
                return (int) Math.max(0, Math.ceil(totalEstimate - elapsedSec));
            }
            return (int) Math.ceil(totalEstimate);
        }
    }
}
