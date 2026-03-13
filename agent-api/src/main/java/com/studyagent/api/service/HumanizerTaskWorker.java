package com.studyagent.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.quota.FeatureCode;
import com.studyagent.infra.client.humanizer.HumanizerServiceClientImpl;
import com.studyagent.infra.entity.HumanizerTaskEntity;
import com.studyagent.infra.repository.humanizer.HumanizerTaskRepositoryImpl;
import com.studyagent.service.domain.humanizer.HumanizerServiceClient;
import com.studyagent.service.domain.humanizer.HumanizerServiceClient.DetectResult;
import com.studyagent.service.domain.humanizer.HumanizerServiceClient.HumanizerResult;
import com.studyagent.service.domain.quota.QuotaDomainService;
import com.studyagent.service.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 后台 Worker：轮询 humanizer_tasks 表，处理 PENDING 任务
 * <p>
 * DETECT 任务：调 Python /predict_stream，逐句更新 sentences_json
 * HUMANIZE 任务：调 Python /process，写回 result_text
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HumanizerTaskWorker {

    private final HumanizerTaskRepositoryImpl repository;
    private final HumanizerServiceClient humanizerServiceClient;
    private final HumanizerServiceClientImpl humanizerServiceClientImpl;
    private final ObjectMapper objectMapper;
    private final QuotaDomainService quotaDomainService;
    private final UserRepository userRepository;

    /** 内测白名单用户（不限额度） */
    @Value("${humanizer.whitelist-user-ids:}")
    private List<String> whitelistUserIds;

    private static final int MAX_RETRY = 3;
    /** PROCESSING 超过这个时间（分钟）自动回收 */
    private static final int PROCESSING_TIMEOUT_MINUTES = 20;
    /** DETECT 最大并发数 */
    private static final int DETECT_CONCURRENCY = 2;
    /** HUMANIZE 最大并发数（调外部 API，不占本地 CPU） */
    private static final int HUMANIZE_CONCURRENCY = 3;

    /** 当前正在跑的 DETECT 任务数 */
    private final AtomicInteger detectRunningCount = new AtomicInteger(0);
    /** 当前正在跑的 HUMANIZE 任务数 */
    private final AtomicInteger humanizeRunningCount = new AtomicInteger(0);
    /** DETECT 并发线程池 */
    private final ExecutorService detectExecutor = Executors.newFixedThreadPool(DETECT_CONCURRENCY,
            r -> { Thread t = new Thread(r, "detect-worker"); t.setDaemon(true); return t; });
    /** HUMANIZE 并发线程池 */
    private final ExecutorService humanizeExecutor = Executors.newFixedThreadPool(HUMANIZE_CONCURRENCY,
            r -> { Thread t = new Thread(r, "humanize-worker"); t.setDaemon(true); return t; });

    /**
     * 每 60 秒检查一次：回收卡在 PROCESSING 超过 10 分钟的任务
     * 防止 Java 重启或 Python 挂掉导致任务永远卡死
     */
    @Scheduled(fixedDelay = 60000)
    public void recoverTimeoutTasks() {
        try {
            int recovered = repository.recoverTimeoutTasks(PROCESSING_TIMEOUT_MINUTES, MAX_RETRY);
            if (recovered > 0) {
                log.warn("回收超时任务 {} 个（超过 {} 分钟未完成）", recovered, PROCESSING_TIMEOUT_MINUTES);
            }
        } catch (Exception e) {
            log.error("回收超时任务异常", e);
        }
    }

    /**
     * 每 3 秒轮询一次 DETECT 任务
     * 最多同时跑 DETECT_CONCURRENCY 个
     */
    @Scheduled(fixedDelay = 3000)
    public void pollDetectTasks() {
        try {
            int running = detectRunningCount.get();
            int available = DETECT_CONCURRENCY - running;
            if (available <= 0) {
                return; // 已满载
            }

            List<HumanizerTaskEntity> tasks = repository.findPendingTasks("DETECT", available);
            for (HumanizerTaskEntity task : tasks) {
                if (!repository.claimTask(task.getId())) {
                    continue; // 被别的实例抢了
                }
                detectRunningCount.incrementAndGet();
                detectExecutor.submit(() -> {
                    try {
                        processDetectTask(task);
                    } finally {
                        detectRunningCount.decrementAndGet();
                    }
                });
            }
        } catch (Exception e) {
            log.error("轮询 DETECT 任务异常", e);
        }
    }

    /**
     * 每 5 秒轮询一次 HUMANIZE 任务
     * 最多同时跑 HUMANIZE_CONCURRENCY 个（调外部 API，不占本地 CPU）
     */
    @Scheduled(fixedDelay = 5000)
    public void pollHumanizeTasks() {
        try {
            int running = humanizeRunningCount.get();
            int available = HUMANIZE_CONCURRENCY - running;
            if (available <= 0) {
                return; // 已满载
            }

            List<HumanizerTaskEntity> tasks = repository.findPendingTasks("HUMANIZE", available);
            for (HumanizerTaskEntity task : tasks) {
                if (!repository.claimTask(task.getId())) {
                    continue;
                }
                humanizeRunningCount.incrementAndGet();
                humanizeExecutor.submit(() -> {
                    try {
                        processHumanizeTask(task);
                    } finally {
                        humanizeRunningCount.decrementAndGet();
                    }
                });
            }
        } catch (Exception e) {
            log.error("轮询 HUMANIZE 任务异常", e);
        }
    }

    /**
     * 处理 DETECT 任务：调 Python /predict_stream，逐句更新
     * <p>
     * 逐块扣费逻辑（按 PM 要求）：
     * - 每收到一个 sentence chunk，计算该句 word 数并扣费
     * - 如果扣费失败（余额不足），标记 QUOTA_EXHAUSTED 并中断
     * - admin/白名单用户跳过扣费
     * - 续跑时从 completedSentences 位置继续
     */
    private void processDetectTask(HumanizerTaskEntity task) {
        long startTime = System.currentTimeMillis();
        log.info("开始处理 DETECT 任务: id={}, textLength={}, completedSentences={}",
                task.getId(), task.getInputText().length(), task.getCompletedSentences());

        // 判断是否需要扣费
        boolean skipQuota = false;
        try {
            var userOpt = userRepository.findByClerkUserId(task.getClerkUserId());
            boolean isAdmin = userOpt.map(u -> u.getIsAdmin()).orElse(false);
            boolean isWhitelisted = whitelistUserIds != null && whitelistUserIds.contains(task.getClerkUserId());
            skipQuota = isAdmin || isWhitelisted;
        } catch (Exception e) {
            log.warn("查询用户信息失败，按普通用户处理: userId={}", task.getClerkUserId(), e);
        }

        // 判断是否使用宽松阈值：检查用户是否 humanize 过相同内容
        boolean relaxed = false;
        try {
            String inputHash = computeTextHash(task.getInputText());
            if (inputHash != null && repository.existsHumanizeResultHash(task.getClerkUserId(), inputHash)) {
                relaxed = true;
                log.info("DETECT 任务命中 humanize 结果，使用宽松阈值: taskId={}, userId={}", task.getId(), task.getClerkUserId());
            }
        } catch (Exception e) {
            log.warn("检查 humanize 匹配失败，使用默认阈值: taskId={}", task.getId(), e);
        }

        // 续跑时已有的句子数据
        int alreadyCompleted = task.getCompletedSentences() != null ? task.getCompletedSentences() : 0;
        int consumedWords = task.getConsumedWords() != null ? task.getConsumedWords() : 0;

        try {
            List<Map<String, Object>> sentences = new ArrayList<>();

            // 如果是续跑，先恢复已有的句子数据
            if (alreadyCompleted > 0 && task.getSentencesJson() != null) {
                try {
                    sentences = objectMapper.readValue(task.getSentencesJson(), new TypeReference<>() {});
                    log.info("续跑 DETECT 任务: id={}, 已有 {} 句，从第 {} 句继续",
                            task.getId(), sentences.size(), alreadyCompleted);
                } catch (Exception e) {
                    log.warn("解析已有 sentencesJson 失败，从头开始: id={}", task.getId(), e);
                    sentences = new ArrayList<>();
                    alreadyCompleted = 0;
                    consumedWords = 0;
                }
            }

            // 用 blockingIterable 消费 SSE 流，每收到一条就更新数据库
            Iterable<String> stream = humanizerServiceClientImpl.detectAIStream(task.getInputText(), relaxed)
                    .toIterable();

            int chunkIndex = 0;
            boolean quotaExhausted = false;

            for (String rawLine : stream) {
                try {
                    Map<String, Object> data = objectMapper.readValue(rawLine, new TypeReference<>() {});

                    if (data.containsKey("index")) {
                        chunkIndex++;

                        // 续跑时跳过已完成的句子
                        if (chunkIndex <= alreadyCompleted) {
                            continue;
                        }

                        // 逐块扣费：计算这句的 word 数（用 fullSentence 完整句子，不是截断的 sentence 摘要）
                        if (!skipQuota) {
                            String sentenceText = data.get("fullSentence") != null
                                    ? data.get("fullSentence").toString()
                                    : (data.get("sentence") != null ? data.get("sentence").toString() : "");
                            int sentenceWords = countWords(sentenceText);
                            if (sentenceWords < 1) sentenceWords = 1; // 至少扣 1 word

                            try {
                                String featureCode = FeatureCode.AI_DETECTION.getCode();
                                quotaDomainService.consume(
                                        task.getClerkUserId(), featureCode, sentenceWords,
                                        "humanizer_task", String.valueOf(task.getId()),
                                        Map.of("task_type", "DETECT",
                                                "task_id", task.getId(),
                                                "sentence_index", chunkIndex,
                                                "sentence_words", sentenceWords));
                                consumedWords += sentenceWords;
                            } catch (Exception e) {
                                // 余额不足，标记 QUOTA_EXHAUSTED
                                log.warn("DETECT 逐块扣费失败（余额不足）: taskId={}, sentence={}, error={}",
                                        task.getId(), chunkIndex, e.getMessage());

                                HumanizerTaskEntity update = new HumanizerTaskEntity();
                                update.setId(task.getId());
                                update.setStatus("QUOTA_EXHAUSTED");
                                update.setSentencesJson(objectMapper.writeValueAsString(sentences));
                                update.setCompletedSentences(sentences.size());
                                update.setConsumedWords(consumedWords);
                                update.setErrorMessage("Quota exhausted at sentence " + chunkIndex);
                                repository.updateById(update);

                                log.info("DETECT 任务因余额不足暂停: id={}, completedSentences={}, consumedWords={}",
                                        task.getId(), sentences.size(), consumedWords);
                                quotaExhausted = true;
                                break;
                            }
                        }

                        // chunk 数据：一句的检测结果
                        sentences.add(data);

                        Integer total = data.get("total") != null
                                ? ((Number) data.get("total")).intValue() : null;

                        // 增量更新数据库
                        HumanizerTaskEntity update = new HumanizerTaskEntity();
                        update.setId(task.getId());
                        update.setSentencesJson(objectMapper.writeValueAsString(sentences));
                        update.setCompletedSentences(sentences.size());
                        update.setConsumedWords(consumedWords);
                        if (total != null) {
                            update.setTotalSentences(total);
                        }
                        repository.updateById(update);

                    } else if (data.containsKey("totalChunks")) {
                        // done 数据：最终结果
                        Double probability = data.get("probability") != null
                                ? ((Number) data.get("probability")).doubleValue() : null;
                        String label = data.get("label") != null
                                ? data.get("label").toString() : null;
                        Double elapsed = data.get("elapsed_seconds") != null
                                ? ((Number) data.get("elapsed_seconds")).doubleValue() : null;

                        HumanizerTaskEntity update = new HumanizerTaskEntity();
                        update.setId(task.getId());
                        update.setStatus("COMPLETED");
                        update.setProbability(probability);
                        update.setLabel(label);
                        update.setElapsedSeconds(elapsed);
                        update.setSentencesJson(objectMapper.writeValueAsString(sentences));
                        update.setCompletedSentences(sentences.size());
                        update.setConsumedWords(consumedWords);
                        update.setFinishedAt(LocalDateTime.now());
                        update.setErrorMessage(null);
                        repository.updateById(update);

                        log.info("DETECT 任务完成: id={}, label={}, prob={}, consumedWords={}, 耗时={}s",
                                task.getId(), label, probability, consumedWords, elapsed);
                        return;

                    } else if (data.containsKey("msg")) {
                        // error 数据
                        throw new RuntimeException("Python SSE error: " + data.get("msg"));
                    }
                } catch (JsonProcessingException e) {
                    log.warn("解析 SSE 数据失败: {}", rawLine, e);
                }
            }

            if (quotaExhausted) {
                return; // 已在上面处理过了
            }

            // 流结束但没收到 done 事件，用已有数据计算结果
            if (!sentences.isEmpty()) {
                finishDetectFromSentences(task, sentences, startTime, consumedWords, relaxed);
            } else {
                markFailed(task, "No data received from Python service");
            }

        } catch (Exception e) {
            log.error("DETECT 任务失败: id={}", task.getId(), e);
            handleFailure(task, e.getMessage());
        }
    }

    /**
     * 从已收集的句子数据计算最终结果
     */
    private void finishDetectFromSentences(HumanizerTaskEntity task,
                                            List<Map<String, Object>> sentences,
                                            long startTime,
                                            int consumedWords,
                                            boolean relaxed) {
        try {
            double weightedSum = 0;
            int totalWeight = 0;
            for (Map<String, Object> s : sentences) {
                double prob = s.get("probability") != null
                        ? ((Number) s.get("probability")).doubleValue() : 0;
                int weight = s.get("weight") != null
                        ? ((Number) s.get("weight")).intValue() : 1;
                weightedSum += prob * weight;
                totalWeight += weight;
            }
            double finalProb = totalWeight > 0 ? weightedSum / totalWeight : 0;
            double elapsed = (System.currentTimeMillis() - startTime) / 1000.0;
            // relaxed 模式下阈值 0.7，否则 0.5（与 Python 端一致）
            double threshold = relaxed ? 0.7 : 0.5;

            HumanizerTaskEntity update = new HumanizerTaskEntity();
            update.setId(task.getId());
            update.setStatus("COMPLETED");
            update.setProbability(Math.round(finalProb * 10000.0) / 10000.0);
            update.setLabel(finalProb >= threshold ? "AI Generated" : "Human Written");
            update.setElapsedSeconds(Math.round(elapsed * 100.0) / 100.0);
            update.setSentencesJson(objectMapper.writeValueAsString(sentences));
            update.setCompletedSentences(sentences.size());
            update.setTotalSentences(sentences.size());
            update.setConsumedWords(consumedWords);
            update.setFinishedAt(LocalDateTime.now());
            update.setErrorMessage(null);
            repository.updateById(update);

            log.info("DETECT 任务完成(从句子汇总): id={}, prob={}, consumedWords={}", task.getId(), finalProb, consumedWords);
        } catch (Exception e) {
            log.error("汇总 DETECT 结果失败: id={}", task.getId(), e);
            markFailed(task, "Failed to aggregate results: " + e.getMessage());
        }
    }

    /**
     * 处理 HUMANIZE 任务：调 Python /process
     */
    private void processHumanizeTask(HumanizerTaskEntity task) {
        log.info("开始处理 HUMANIZE 任务: id={}, textLength={}", task.getId(), task.getInputText().length());

        try {
            HumanizerResult result = humanizerServiceClient.humanize(task.getInputText());

            HumanizerTaskEntity update = new HumanizerTaskEntity();
            update.setId(task.getId());

            if (result.getCode() == 200 && result.getResult() != null) {
                update.setStatus("COMPLETED");
                update.setResultText(result.getResult());
                update.setResultHash(computeTextHash(result.getResult()));
                update.setElapsedSeconds(result.getElapsedSeconds());
                update.setFinishedAt(LocalDateTime.now());
                update.setErrorMessage(null); // 清掉重试残留的错误信息
                log.info("HUMANIZE 任务完成: id={}, 耗时={}s", task.getId(), result.getElapsedSeconds());
            } else {
                throw new RuntimeException(result.getMsg() != null ? result.getMsg() : "Humanize failed with code " + result.getCode());
            }

            repository.updateById(update);

        } catch (Exception e) {
            log.error("HUMANIZE 任务失败: id={}", task.getId(), e);
            handleFailure(task, e.getMessage());
        }
    }

    private void handleFailure(HumanizerTaskEntity task, String errorMsg) {
        int retryCount = task.getRetryCount() != null ? task.getRetryCount() : 0;
        HumanizerTaskEntity update = new HumanizerTaskEntity();
        update.setId(task.getId());

        if (retryCount < MAX_RETRY) {
            // 还能重试，改回 PENDING
            update.setStatus("PENDING");
            update.setRetryCount(retryCount + 1);
            update.setErrorMessage(errorMsg);
            log.warn("任务将重试: id={}, retry={}/{}", task.getId(), retryCount + 1, MAX_RETRY);
        } else {
            // 重试耗尽，标记失败
            update.setStatus("FAILED");
            update.setRetryCount(retryCount);
            update.setErrorMessage(errorMsg);
            update.setFinishedAt(LocalDateTime.now());
            log.error("任务最终失败: id={}, error={}", task.getId(), errorMsg);
            // 退还额度
            refundQuota(task);
        }

        repository.updateById(update);
    }

    private void markFailed(HumanizerTaskEntity task, String errorMsg) {
        HumanizerTaskEntity update = new HumanizerTaskEntity();
        update.setId(task.getId());
        update.setStatus("FAILED");
        update.setErrorMessage(errorMsg);
        update.setFinishedAt(LocalDateTime.now());
        repository.updateById(update);
        // 退还额度
        refundQuota(task);
    }

    /**
     * 统计 word 数，与前端逻辑对齐：
     * - CJK 字符（中日韩）每个字算 1 word
     * - 非 CJK 部分按空格分词，每个词算 1 word
     * - 混合文本两者相加
     */
    private int countWords(String text) {
        if (text == null || text.isBlank()) return 0;
        String cjkPattern = "[\\u4e00-\\u9fff\\u3400-\\u4dbf\\u3040-\\u309f\\u30a0-\\u30ff\\uac00-\\ud7af]";
        java.util.regex.Matcher cjkMatcher = java.util.regex.Pattern.compile(cjkPattern).matcher(text);
        int cjkCount = 0;
        while (cjkMatcher.find()) cjkCount++;
        String nonCjk = text.replaceAll(cjkPattern, " ").trim();
        int engCount = nonCjk.isEmpty() ? 0
                : (int) java.util.Arrays.stream(nonCjk.split("\\s+"))
                        .filter(w -> !w.isEmpty()).count();
        return cjkCount + engCount;
    }

    /**
     * 任务失败时退还额度
     */
    private void refundQuota(HumanizerTaskEntity task) {
        Long ledgerId = task.getQuotaLedgerId();
        if (ledgerId != null) {
            try {
                quotaDomainService.refund(ledgerId, "humanizer_task_failed");
                log.info("额度已退还: taskId={}, ledgerId={}", task.getId(), ledgerId);
            } catch (Exception e) {
                log.error("额度退还失败: taskId={}, ledgerId={}", task.getId(), ledgerId, e);
            }
        }
    }

    /**
     * 计算文本前 200 字符的 SHA-256 hash
     * 用于 HUMANIZE result_text 存储和 DETECT input_text 匹配
     */
    private String computeTextHash(String text) {
        if (text == null || text.isEmpty()) return null;
        try {
            String prefix = text.length() <= 200 ? text : text.substring(0, 200);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(prefix.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("计算文本 hash 失败", e);
            return null;
        }
    }
}
