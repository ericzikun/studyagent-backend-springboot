package com.studyagent.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.studyagent.api.dto.response.ConsumeTriggerResponse;
import com.studyagent.api.dto.response.SubmitFeedbackResponse;
import com.studyagent.common.api.ApiCode;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.infra.entity.FeedbackPromptSessionEntity;
import com.studyagent.infra.entity.FeedbackSubmissionEntity;
import com.studyagent.infra.mapper.FeedbackPromptSessionMapper;
import com.studyagent.infra.mapper.FeedbackSubmissionMapper;
import com.studyagent.infra.mapper.TaskMapper;
import com.studyagent.infra.entity.TaskEntity;
import com.studyagent.infra.entity.HumanizerTaskEntity;
import com.studyagent.infra.mapper.HumanizerTaskMapper;
import com.studyagent.api.util.TaskIdEncoder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.google.gson.Gson;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 反馈应用服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedbackApplicationService {

    private final FeedbackPromptSessionMapper promptSessionMapper;
    private final FeedbackSubmissionMapper submissionMapper;
    private final TaskMapper taskMapper;
    private final HumanizerTaskMapper humanizerTaskMapper;

    private static final String PREFIX_PROMPT_SESSION = "fps_";
    private static final String PREFIX_SUBMISSION = "fsub_";

    /** triggerCode + subjectType -> (variant, configKey, configVersion) */
    private static final Map<String, TriggerConfig> TRIGGER_CONFIG = new HashMap<>();

    static {
        TRIGGER_CONFIG.put("task_download_first:task", new TriggerConfig("rating", "task-rating-v1", 1));
        TRIGGER_CONFIG.put("editor_back_first:task", new TriggerConfig("rating", "task-rating-v1", 1));
        TRIGGER_CONFIG.put("editor_copy_first:task", new TriggerConfig("rating", "task-rating-v1", 1));
        TRIGGER_CONFIG.put("detection_complete_first:humanizer_task", new TriggerConfig("thumb", "detection-thumb-v1", 1));
        TRIGGER_CONFIG.put("humanizer_complete_first:humanizer_task", new TriggerConfig("thumb", "humanizer-thumb-v1", 1));
    }

    /**
     * 消费触发：判断是否应弹窗，若第一次则创建 session 并返回模板信息
     */
    @Transactional(rollbackFor = Exception.class)
    public ConsumeTriggerResponse consumeTrigger(String clerkUserId, String triggerCode, String subjectType,
                                                 Object subjectId, String sourcePage) {
        String subjectIdStr = subjectId != null ? String.valueOf(subjectId) : "";

        // 1. 查询是否已存在
        FeedbackPromptSessionEntity existing = promptSessionMapper.selectOne(
                new LambdaQueryWrapper<FeedbackPromptSessionEntity>()
                        .eq(FeedbackPromptSessionEntity::getClerkUserId, clerkUserId)
                        .eq(FeedbackPromptSessionEntity::getSubjectType, subjectType)
                        .eq(FeedbackPromptSessionEntity::getSubjectId, subjectIdStr)
                        .eq(FeedbackPromptSessionEntity::getTriggerCode, triggerCode)
                        .last("LIMIT 1"));

        if (existing != null) {
            return ConsumeTriggerResponse.builder()
                    .shouldPrompt(false)
                    .triggerCode(triggerCode)
                    .subjectType(subjectType)
                    .subjectId(subjectId)
                    .build();
        }

        // 2. 获取模板配置
        String configKey = triggerCode + ":" + subjectType;
        TriggerConfig config = TRIGGER_CONFIG.get(configKey);
        if (config == null) {
            log.warn("Unknown trigger config: {}, using default task-rating-v1", configKey);
            config = new TriggerConfig("rating", "task-rating-v1", 1);
        }

        // 3. （可选）校验 subject 存在且归属当前用户
        validateSubjectOwnership(clerkUserId, subjectType, subjectIdStr);

        // 4. 创建 session
        String promptSessionId = PREFIX_PROMPT_SESSION + UUID.randomUUID().toString().replace("-", "");
        FeedbackPromptSessionEntity entity = new FeedbackPromptSessionEntity();
        entity.setPromptSessionId(promptSessionId);
        entity.setClerkUserId(clerkUserId);
        entity.setSubjectType(subjectType);
        entity.setSubjectId(subjectIdStr);
        entity.setTriggerCode(triggerCode);
        entity.setVariant(config.variant);
        entity.setConfigKey(config.configKey);
        entity.setConfigVersion(config.configVersion);
        entity.setStatus("shown");
        entity.setSourcePage(sourcePage);
        entity.setShownAt(LocalDateTime.now());
        promptSessionMapper.insert(entity);

        return ConsumeTriggerResponse.builder()
                .shouldPrompt(true)
                .promptSessionId(promptSessionId)
                .triggerCode(triggerCode)
                .subjectType(subjectType)
                .subjectId(subjectId)
                .variant(config.variant)
                .configKey(config.configKey)
                .configVersion(config.configVersion)
                .build();
    }

    private void validateSubjectOwnership(String clerkUserId, String subjectType, String subjectIdStr) {
        Long numericId = parseFeedbackSubjectId(subjectIdStr);
        if (numericId == null) {
            throw new BusinessException(ApiCode.NO_PERMISSION);
        }
        if ("task".equals(subjectType)) {
            TaskEntity task = taskMapper.selectById(numericId);
            if (task == null || !clerkUserId.equals(task.getClerkUserId())) {
                throw new BusinessException(ApiCode.NO_PERMISSION);
            }
        } else if ("humanizer_task".equals(subjectType)) {
            HumanizerTaskEntity task = humanizerTaskMapper.selectById(numericId);
            if (task == null || !clerkUserId.equals(task.getClerkUserId())) {
                throw new BusinessException(ApiCode.NO_PERMISSION);
            }
        }
    }

    /** 数字串或 Sqids 短码（与 TaskIdEncoder 一致） */
    private static Long parseFeedbackSubjectId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return TaskIdEncoder.decode(s);
        }
    }

    /**
     * 提交反馈
     */
    @Transactional(rollbackFor = Exception.class)
    public SubmitFeedbackResponse submitFeedback(String clerkUserId, String promptSessionId, Integer score,
                                                  String vote, List<String> selectedTagCodes, String comment, String contact) {
        FeedbackPromptSessionEntity session = promptSessionMapper.selectOne(
                new LambdaQueryWrapper<FeedbackPromptSessionEntity>()
                        .eq(FeedbackPromptSessionEntity::getPromptSessionId, promptSessionId)
                        .last("LIMIT 1"));

        if (session == null) {
            throw new BusinessException(ApiCode.FEEDBACK_SESSION_NOT_FOUND);
        }
        if (!clerkUserId.equals(session.getClerkUserId())) {
            throw new BusinessException(ApiCode.NO_PERMISSION);
        }

        // 检查是否已提交
        FeedbackSubmissionEntity existingSub = submissionMapper.selectOne(
                new LambdaQueryWrapper<FeedbackSubmissionEntity>()
                        .eq(FeedbackSubmissionEntity::getPromptSessionId, promptSessionId)
                        .last("LIMIT 1"));
        if (existingSub != null) {
            throw new BusinessException(ApiCode.FEEDBACK_ALREADY_SUBMITTED);
        }

        String variant = session.getVariant();
        if ("rating".equals(variant)) {
            if (score == null || score < 1 || score > 5) {
                throw new BusinessException(ApiCode.FEEDBACK_INVALID_REQUEST, "score (1-5) required for rating");
            }
            if (vote != null && !vote.isEmpty()) {
                throw new BusinessException(ApiCode.FEEDBACK_INVALID_REQUEST, "vote not allowed for rating");
            }
        } else if ("thumb".equals(variant)) {
            if (vote == null || (!"up".equals(vote) && !"down".equals(vote))) {
                throw new BusinessException(ApiCode.FEEDBACK_INVALID_REQUEST, "vote (up/down) required for thumb");
            }
            if (score != null) {
                throw new BusinessException(ApiCode.FEEDBACK_INVALID_REQUEST, "score not allowed for thumb");
            }
        } else {
            throw new BusinessException(ApiCode.FEEDBACK_INVALID_REQUEST, "unknown variant: " + variant);
        }

        String tagCodesJson = new Gson().toJson(selectedTagCodes != null ? selectedTagCodes : Collections.emptyList());

        String submissionId = PREFIX_SUBMISSION + UUID.randomUUID().toString().replace("-", "");
        FeedbackSubmissionEntity subEntity = new FeedbackSubmissionEntity();
        subEntity.setSubmissionId(submissionId);
        subEntity.setPromptSessionId(promptSessionId);
        subEntity.setScore(score);
        subEntity.setVote(vote);
        subEntity.setSelectedTagCodesJson(tagCodesJson);
        subEntity.setComment(comment != null ? comment : "");
        subEntity.setContact(contact);
        subEntity.setCreatedAt(LocalDateTime.now());
        submissionMapper.insert(subEntity);

        session.setStatus("submitted");
        session.setSubmittedAt(LocalDateTime.now());
        promptSessionMapper.updateById(session);

        return SubmitFeedbackResponse.builder()
                .success(true)
                .submissionId(submissionId)
                .build();
    }

    private record TriggerConfig(String variant, String configKey, int configVersion) {}
}
