package com.studyagent.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.infra.client.humanizer.HumanizerServiceClientImpl;
import com.studyagent.infra.entity.HumanizerTaskEntity;
import com.studyagent.infra.repository.humanizer.HumanizerTaskRepositoryImpl;
import com.studyagent.service.domain.humanizer.HumanizerServiceClient;
import com.studyagent.service.domain.humanizer.HumanizerServiceClient.DetectResult;
import com.studyagent.service.domain.humanizer.HumanizerServiceClient.HumanizerResult;
import com.studyagent.service.domain.quota.QuotaDomainService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
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

    private static final int MAX_RETRY = 3;
    /** PROCESSING 超过这个时间（分钟）自动回收 */
    private static final int PROCESSING_TIMEOUT_MINUTES = 20;
    /** HUMANIZE 最大并发数（调外部 API，不占本地 CPU） */
    private static final int HUMANIZE_CONCURRENCY = 3;

    // 防止并发执行
    private final AtomicBoolean detectRunning = new AtomicBoolean(false);
    /** 当前正在跑的 HUMANIZE 任务数 */
    private final AtomicInteger humanizeRunningCount = new AtomicInteger(0);
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
     */
    @Scheduled(fixedDelay = 3000)
    public void pollDetectTasks() {
        if (!detectRunning.compareAndSet(false, true)) {
            return; // 上一轮还没跑完，跳过
        }
        try {
            List<HumanizerTaskEntity> tasks = repository.findPendingTasks("DETECT", 1);
            for (HumanizerTaskEntity task : tasks) {
                if (!repository.claimTask(task.getId())) {
                    continue; // 被别的实例抢了
                }
                processDetectTask(task);
            }
        } catch (Exception e) {
            log.error("轮询 DETECT 任务异常", e);
        } finally {
            detectRunning.set(false);
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
     */
    private void processDetectTask(HumanizerTaskEntity task) {
        long startTime = System.currentTimeMillis();
        log.info("开始处理 DETECT 任务: id={}, textLength={}", task.getId(), task.getInputText().length());

        try {
            List<Map<String, Object>> sentences = new ArrayList<>();

            // 用 blockingIterable 消费 SSE 流，每收到一条就更新数据库
            Iterable<String> stream = humanizerServiceClientImpl.detectAIStream(task.getInputText())
                    .toIterable();

            for (String rawLine : stream) {
                try {
                    Map<String, Object> data = objectMapper.readValue(rawLine, new TypeReference<>() {});

                    if (data.containsKey("index")) {
                        // chunk 数据：一句的检测结果
                        sentences.add(data);

                        Integer total = data.get("total") != null
                                ? ((Number) data.get("total")).intValue() : null;

                        // 增量更新数据库
                        HumanizerTaskEntity update = new HumanizerTaskEntity();
                        update.setId(task.getId());
                        update.setSentencesJson(objectMapper.writeValueAsString(sentences));
                        update.setCompletedSentences(sentences.size());
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
                        update.setFinishedAt(LocalDateTime.now());
                        update.setErrorMessage(null); // 清掉重试残留的错误信息
                        repository.updateById(update);

                        log.info("DETECT 任务完成: id={}, label={}, prob={}, 耗时={}s",
                                task.getId(), label, probability, elapsed);
                        return;

                    } else if (data.containsKey("msg")) {
                        // error 数据
                        throw new RuntimeException("Python SSE error: " + data.get("msg"));
                    }
                } catch (JsonProcessingException e) {
                    log.warn("解析 SSE 数据失败: {}", rawLine, e);
                }
            }

            // 流结束但没收到 done 事件，用已有数据计算结果
            if (!sentences.isEmpty()) {
                finishDetectFromSentences(task, sentences, startTime);
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
                                            long startTime) {
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

            HumanizerTaskEntity update = new HumanizerTaskEntity();
            update.setId(task.getId());
            update.setStatus("COMPLETED");
            update.setProbability(Math.round(finalProb * 10000.0) / 10000.0);
            update.setLabel(finalProb >= 0.5 ? "AI Generated" : "Human Written");
            update.setElapsedSeconds(Math.round(elapsed * 100.0) / 100.0);
            update.setSentencesJson(objectMapper.writeValueAsString(sentences));
            update.setCompletedSentences(sentences.size());
            update.setTotalSentences(sentences.size());
            update.setFinishedAt(LocalDateTime.now());
            update.setErrorMessage(null); // 清掉之前可能残留的错误信息
            repository.updateById(update);

            log.info("DETECT 任务完成(从句子汇总): id={}, prob={}", task.getId(), finalProb);
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
}
