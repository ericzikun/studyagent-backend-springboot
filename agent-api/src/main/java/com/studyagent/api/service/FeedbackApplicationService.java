package com.studyagent.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.studyagent.api.dto.response.ConsumeTriggerResponse;
import com.studyagent.api.dto.response.SubmitFeedbackResponse;
import com.studyagent.api.util.TaskIdEncoder;
import com.studyagent.common.api.ApiCode;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.common.verla.id.VerlaPublicIdCodec;
import com.studyagent.common.verla.id.VerlaPublicIdType;
import com.studyagent.infra.entity.FeedbackPromptSessionEntity;
import com.studyagent.infra.entity.FeedbackSubmissionEntity;
import com.studyagent.infra.entity.HumanizerTaskEntity;
import com.studyagent.infra.entity.TaskEntity;
import com.studyagent.infra.entity.verla.VerlaConversationEntity;
import com.studyagent.infra.entity.verla.VerlaSessionEntity;
import com.studyagent.infra.entity.verla.VerlaTurnEntity;
import com.studyagent.infra.mapper.FeedbackPromptSessionMapper;
import com.studyagent.infra.mapper.FeedbackSubmissionMapper;
import com.studyagent.infra.mapper.HumanizerTaskMapper;
import com.studyagent.infra.mapper.TaskMapper;
import com.studyagent.infra.mapper.verla.VerlaConversationMapper;
import com.studyagent.infra.mapper.verla.VerlaSessionMapper;
import com.studyagent.infra.mapper.verla.VerlaTurnMapper;
import com.google.gson.Gson;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 反馈应用服务
 * <p>
 * 支持 1.0（task / humanizer_task）与 V2 Verla（verla_conversation / verla_turn / verla_session）subject 粒度。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedbackApplicationService {

    private final FeedbackPromptSessionMapper promptSessionMapper;
    private final FeedbackSubmissionMapper submissionMapper;
    private final TaskMapper taskMapper;
    private final HumanizerTaskMapper humanizerTaskMapper;
    private final VerlaConversationMapper verlaConversationMapper;
    private final VerlaTurnMapper verlaTurnMapper;
    private final VerlaSessionMapper verlaSessionMapper;

    private static final String PREFIX_PROMPT_SESSION = "fps_";
    private static final String PREFIX_SUBMISSION = "fsub_";
    private static final String PREFIX_VERLA_CONVERSATION_SUBJECT = "verla_conversation:";

    /** triggerCode + subjectType -> (variant, configKey, configVersion) */
    private static final Map<String, TriggerConfig> TRIGGER_CONFIG = new HashMap<>();

    static {
        // 1.0 / V2 Assignment：星级评分（subjectType=task，subjectId 可为 legacy taskId 或 verla_conversation:{id}）
        register("task_download_first", "task", "rating", "task-rating-v1");
        register("editor_back_first", "task", "rating", "task-rating-v1");
        register("editor_copy_first", "task", "rating", "task-rating-v1");
        register("editor_stay_1min_first", "task", "rating", "task-rating-v1");

        // 1.0 Detection / Humanizer：点赞点踩（独立 humanizer_tasks 队列）
        register("detection_complete_first", "humanizer_task", "thumb", "detection-thumb-v1");
        register("humanizer_complete_first", "humanizer_task", "thumb", "humanizer-thumb-v1");

        // V2 AI Writing（Verla conversation 粒度）
        register("detection_complete_first", "verla_conversation", "thumb", "detection-thumb-v1");
        register("humanizer_complete_first", "verla_conversation", "thumb", "humanizer-thumb-v1");

        // V2 Assignment / Editor：原生 verla_conversation subject（可选，与 task+前缀等价）
        register("task_download_first", "verla_conversation", "rating", "task-rating-v1");
        register("editor_back_first", "verla_conversation", "rating", "task-rating-v1");
        register("editor_copy_first", "verla_conversation", "rating", "task-rating-v1");
        register("editor_stay_1min_first", "verla_conversation", "rating", "task-rating-v1");

        // V2 Verla 大作业：按 turn / session 粒度评分
        register("verla_turn_complete_first", "verla_turn", "rating", "task-rating-v1");
        register("verla_agent_session_complete_first", "verla_session", "rating", "task-rating-v1");
    }

    private static void register(String triggerCode, String subjectType, String variant,
                                 String configKey) {
        TRIGGER_CONFIG.put(configKey(triggerCode, subjectType), new TriggerConfig(variant, configKey, 1));
    }

    private static String configKey(String triggerCode, String subjectType) {
        return triggerCode + ":" + subjectType;
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
        TriggerConfig config = TRIGGER_CONFIG.get(configKey(triggerCode, subjectType));
        if (config == null) {
            log.warn("Unknown trigger config: {}, using default task-rating-v1", configKey(triggerCode, subjectType));
            config = new TriggerConfig("rating", "task-rating-v1", 1);
        }

        // 3. 校验 subject 存在且归属当前用户
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
        switch (subjectType) {
            case "task" -> validateTaskSubjectOwnership(clerkUserId, subjectIdStr);
            case "humanizer_task" -> validateHumanizerTaskOwnership(clerkUserId, subjectIdStr);
            case "verla_conversation" -> validateVerlaConversationOwnership(clerkUserId, subjectIdStr);
            case "verla_turn" -> validateVerlaTurnOwnership(clerkUserId, subjectIdStr);
            case "verla_session" -> validateVerlaSessionOwnership(clerkUserId, subjectIdStr);
            default -> throw new BusinessException(ApiCode.NO_PERMISSION);
        }
    }

    private void validateTaskSubjectOwnership(String clerkUserId, String subjectIdStr) {
        if (isOwnedLegacyTask(clerkUserId, subjectIdStr)
                || isOwnedVerlaConversation(clerkUserId, subjectIdStr)) {
            return;
        }
        throw new BusinessException(ApiCode.NO_PERMISSION);
    }

    private void validateHumanizerTaskOwnership(String clerkUserId, String subjectIdStr) {
        Long numericId = parseFeedbackSubjectId(subjectIdStr);
        if (numericId == null) {
            throw new BusinessException(ApiCode.NO_PERMISSION);
        }
        HumanizerTaskEntity task = humanizerTaskMapper.selectById(numericId);
        if (task == null || !clerkUserId.equals(task.getClerkUserId())) {
            throw new BusinessException(ApiCode.NO_PERMISSION);
        }
    }

    private void validateVerlaConversationOwnership(String clerkUserId, String subjectIdStr) {
        Long conversationId = parseVerlaConversationSubjectId(subjectIdStr);
        if (conversationId == null || !isOwnedVerlaConversationById(clerkUserId, conversationId)) {
            throw new BusinessException(ApiCode.NO_PERMISSION);
        }
    }

    private void validateVerlaTurnOwnership(String clerkUserId, String subjectIdStr) {
        Long turnId = parseVerlaEntitySubjectId(VerlaPublicIdType.TURN, subjectIdStr);
        if (turnId == null) {
            throw new BusinessException(ApiCode.NO_PERMISSION);
        }
        VerlaTurnEntity turn = verlaTurnMapper.selectById(turnId);
        if (turn == null || turn.getConversationId() == null) {
            throw new BusinessException(ApiCode.NO_PERMISSION);
        }
        if (!isOwnedVerlaConversationById(clerkUserId, turn.getConversationId())) {
            throw new BusinessException(ApiCode.NO_PERMISSION);
        }
    }

    private void validateVerlaSessionOwnership(String clerkUserId, String subjectIdStr) {
        Long sessionId = parseVerlaEntitySubjectId(VerlaPublicIdType.SESSION, subjectIdStr);
        if (sessionId == null) {
            throw new BusinessException(ApiCode.NO_PERMISSION);
        }
        VerlaSessionEntity session = verlaSessionMapper.selectById(sessionId);
        if (session == null || session.getConversationId() == null) {
            throw new BusinessException(ApiCode.NO_PERMISSION);
        }
        if (!isOwnedVerlaConversationById(clerkUserId, session.getConversationId())) {
            throw new BusinessException(ApiCode.NO_PERMISSION);
        }
    }

    private boolean isOwnedLegacyTask(String clerkUserId, String subjectIdStr) {
        if (subjectIdStr != null && subjectIdStr.trim().startsWith(PREFIX_VERLA_CONVERSATION_SUBJECT)) {
            return false;
        }
        Long numericId = parseFeedbackSubjectId(subjectIdStr);
        if (numericId == null) {
            return false;
        }
        TaskEntity task = taskMapper.selectById(numericId);
        return task != null && clerkUserId.equals(task.getClerkUserId());
    }

    private boolean isOwnedVerlaConversation(String clerkUserId, String subjectIdStr) {
        Long conversationId = parseVerlaConversationSubjectId(subjectIdStr);
        if (conversationId == null) {
            return false;
        }
        return isOwnedVerlaConversationById(clerkUserId, conversationId);
    }

    private boolean isOwnedVerlaConversationById(String clerkUserId, Long conversationId) {
        VerlaConversationEntity conversation = verlaConversationMapper.selectById(conversationId);
        return conversation != null
                && clerkUserId.equals(conversation.getUserId())
                && !"deleted".equals(conversation.getStatus());
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
     * 解析 verla_conversation subjectId：纯数字、{@code vc_*} public id，或 {@code verla_conversation:{id}} 前缀。
     */
    private static Long parseVerlaConversationSubjectId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        if (s.startsWith(PREFIX_VERLA_CONVERSATION_SUBJECT)) {
            s = s.substring(PREFIX_VERLA_CONVERSATION_SUBJECT.length());
        }
        return parseVerlaEntitySubjectId(VerlaPublicIdType.CONVERSATION, s);
    }

    /**
     * 解析 Verla 实体 subjectId：纯数字或对应类型的 public id（如 {@code vt_*} / {@code vs_*}）。
     */
    private static Long parseVerlaEntitySubjectId(VerlaPublicIdType expectedType, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return VerlaPublicIdCodec.requireInternalId(expectedType, raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
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
