package com.studyagent.api.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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

    /** 内测白名单用户（不限额度），通过配置 humanizer.whitelist-user-ids 设置 */
    @org.springframework.beans.factory.annotation.Value("${humanizer.whitelist-user-ids:}")
    private List<String> whitelistUserIds;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int PREVIEW_LENGTH = 50;

    // ===== 预估时间参数（基于实测数据） =====
    /** DETECT: 平均每句 3.5 秒（CPU 推理） */
    private static final double DETECT_SECONDS_PER_SENTENCE = 3.5;
    /** DETECT: 平均每句字符数 */
    private static final int DETECT_AVG_CHARS_PER_SENTENCE = 150;
    /** HUMANIZE: 每 1500 字符一个 chunk，每 chunk 约 26 秒 */
    private static final int HUMANIZE_CHUNK_SIZE = 1500;
    private static final double HUMANIZE_SECONDS_PER_CHUNK = 26.0;
    /** HUMANIZE: 最小处理时间 */
    private static final double HUMANIZE_MIN_SECONDS = 5.0;
    /** HUMANIZE: 并发数 */
    private static final int HUMANIZE_CONCURRENCY = 3;

    /**
     * 提交任务（入库排队）
     * 额度校验：admin 不限，普通用户按 word count 扣减对应 feature 额度
     */
    public HumanizerTaskResponse submitTask(String clerkUserId, String taskType, String text) {
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
        if (!isAdmin && !isWhitelisted) {
            // 检查额度是否足够
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

            // 扣减额度
            ConsumeResult consumeResult = quotaDomainService.consume(
                    clerkUserId, featureCode, wordCount,
                    "humanizer_task", null,
                    Map.of("task_type", taskType, "word_count", wordCount));
            quotaLedgerId = consumeResult.ledgerId();
            log.info("额度扣减成功: userId={}, feature={}, words={}, ledgerId={}",
                    clerkUserId, featureCode, wordCount, quotaLedgerId);
        }

        // 4. 入库
        HumanizerTaskEntity entity = new HumanizerTaskEntity();
        entity.setClerkUserId(clerkUserId);
        entity.setTaskType(taskType);
        entity.setInputText(text);
        entity.setStatus("PENDING");
        entity.setRetryCount(0);
        entity.setCompletedSentences(0);
        entity.setQuotaLedgerId(quotaLedgerId);

        repository.insert(entity);

        // 计算预估时间
        int queueAhead = repository.countQueueAhead(taskType, entity.getId());
        int estimatedSeconds = estimateTime(taskType, text.length(), queueAhead);

        log.info("任务已入库: id={}, type={}, userId={}, words={}, queueAhead={}, estimatedSeconds={}",
                entity.getId(), taskType, clerkUserId, wordCount, queueAhead, estimatedSeconds);

        return HumanizerTaskResponse.builder()
                .id(entity.getId())
                .taskType(taskType)
                .status("PENDING")
                .estimatedSeconds(estimatedSeconds)
                .queuePosition(queueAhead)
                .build();
    }

    /**
     * 统计英文单词数（按空格分词）
     */
    private int countWords(String text) {
        if (text == null || text.isBlank()) return 0;
        String[] words = text.trim().split("\\s+");
        return words.length;
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
    public HumanizerTaskListResponse listTasks(String clerkUserId, String taskType, int page, int size) {
        Page<HumanizerTaskEntity> result = repository.findByUserPaged(clerkUserId, taskType, page, size);

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
     * PENDING/PROCESSING 状态时带上预估剩余时间
     */
    private HumanizerTaskResponse toDetailResponse(HumanizerTaskEntity entity) {
        HumanizerTaskResponse.HumanizerTaskResponseBuilder builder = HumanizerTaskResponse.builder()
                .id(entity.getId())
                .taskType(entity.getTaskType())
                .status(entity.getStatus())
                .probability(entity.getProbability())
                .label(entity.getLabel())
                .sentencesJson(entity.getSentencesJson())
                .totalSentences(entity.getTotalSentences())
                .completedSentences(entity.getCompletedSentences())
                .resultText(entity.getResultText())
                .elapsedSeconds(entity.getElapsedSeconds())
                .errorMessage(entity.getErrorMessage())
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FMT) : null);

        // 未完成的任务带上预估时间
        String status = entity.getStatus();
        if ("PENDING".equals(status) || "PROCESSING".equals(status)) {
            int textLen = entity.getInputText() != null ? entity.getInputText().length() : 0;
            int queueAhead = repository.countQueueAhead(entity.getTaskType(), entity.getId());

            if ("PROCESSING".equals(status)) {
                // 正在处理，只算自身剩余时间
                int remaining = estimateRemaining(entity);
                builder.estimatedSeconds(remaining);
                builder.queuePosition(0);
            } else {
                // 排队中，算排队 + 自身处理时间
                int estimated = estimateTime(entity.getTaskType(), textLen, queueAhead);
                builder.estimatedSeconds(estimated);
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
     * 预估自身处理时间
     */
    private double estimateProcessTime(String taskType, int textLength) {
        if ("DETECT".equals(taskType)) {
            // 句子数 × 3.5s/句
            int sentences = Math.max(1, textLength / DETECT_AVG_CHARS_PER_SENTENCE);
            return sentences * DETECT_SECONDS_PER_SENTENCE;
        } else {
            // HUMANIZE: chunks × 26s/chunk，最少 5s
            int chunks = Math.max(1, (textLength + HUMANIZE_CHUNK_SIZE - 1) / HUMANIZE_CHUNK_SIZE);
            return Math.max(HUMANIZE_MIN_SECONDS, chunks * HUMANIZE_SECONDS_PER_CHUNK);
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
