package com.studyagent.api.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.studyagent.api.dto.response.HumanizerTaskItemResponse;
import com.studyagent.api.dto.response.HumanizerTaskListResponse;
import com.studyagent.api.dto.response.HumanizerTaskResponse;
import com.studyagent.infra.entity.HumanizerTaskEntity;
import com.studyagent.infra.repository.humanizer.HumanizerTaskRepositoryImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Humanizer 应用服务（异步队列版）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HumanizerApplicationService {

    private final HumanizerTaskRepositoryImpl repository;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int PREVIEW_LENGTH = 50;

    /**
     * 提交任务（入库排队）
     */
    public HumanizerTaskResponse submitTask(String clerkUserId, String taskType, String text) {
        HumanizerTaskEntity entity = new HumanizerTaskEntity();
        entity.setClerkUserId(clerkUserId);
        entity.setTaskType(taskType);
        entity.setInputText(text);
        entity.setStatus("PENDING");
        entity.setRetryCount(0);
        entity.setCompletedSentences(0);

        repository.insert(entity);

        log.info("任务已入库: id={}, type={}, userId={}", entity.getId(), taskType, clerkUserId);

        return HumanizerTaskResponse.builder()
                .id(entity.getId())
                .taskType(taskType)
                .status("PENDING")
                .build();
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
     */
    private HumanizerTaskResponse toDetailResponse(HumanizerTaskEntity entity) {
        return HumanizerTaskResponse.builder()
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
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FMT) : null)
                .build();
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
}
