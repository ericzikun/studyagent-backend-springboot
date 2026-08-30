package com.studyagent.api.controller.verla;

import com.studyagent.api.dto.verla.support.VerlaPublicIdVoSupport;
import com.studyagent.api.web.verla.VerlaPublicId;
import com.studyagent.common.verla.id.VerlaPublicIdType;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.api.common.Result;
import com.studyagent.api.dto.verla.request.SaveVerlaHumanizerDocumentEditRequest;
import com.studyagent.api.dto.verla.response.SaveVerlaHumanizerDocumentEditResponseVO;
import com.studyagent.api.dto.verla.response.VerlaHumanizerDocumentEditResponseVO;
import com.studyagent.common.api.ApiCode;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.infra.entity.verla.VerlaArtifactEntity;
import com.studyagent.infra.entity.verla.VerlaEditorContentEntity;
import com.studyagent.infra.mapper.verla.VerlaArtifactMapper;
import com.studyagent.infra.mapper.verla.VerlaEditorContentMapper;
import com.studyagent.service.application.verla.VerlaConversationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Humanizer 右栏编辑版本落库接口（需求 2，设计 2026-08-31）。
 * <p>
 * 复用 verla_editor_contents，editor_kind='humanizer'，维度 (conversation_id, source_artifact_uid)，
 * 一次 humanizer 结果一行（uk_conv_source_kind 天然保证），upsert 不记多版本、不做 title 处理。
 * "humanizer" 不进 {@link VerlaEditorContentController#SUPPORTED_EDITOR_KINDS} 白名单，
 * 两个 controller 各管各的 kind，命名空间硬隔离；再次 humanize 产生新 artifact uid = 新行，旧编辑自然作废。
 */
@Slf4j
@RestController
@RequestMapping("/v1/verla/conversations/{cid}/humanizer-edits")
@RequiredArgsConstructor
public class VerlaHumanizerDocumentEditController {

    private static final String EDITOR_KIND_HUMANIZER = "humanizer";

    private final VerlaConversationService conversationService;
    private final VerlaArtifactMapper artifactMapper;
    private final VerlaEditorContentMapper editorContentMapper;
    private final ObjectMapper objectMapper;

    @GetMapping("/{artifactUid}")
    public Result<VerlaHumanizerDocumentEditResponseVO> getDocumentEdit(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @VerlaPublicId(VerlaPublicIdType.CONVERSATION) @PathVariable("cid") Long conversationId,
            @PathVariable String artifactUid) {
        ensureLogin(clerkUserId);
        ensureConversationAndArtifactOwnership(clerkUserId, conversationId, artifactUid);

        VerlaEditorContentEntity saved = selectByKey(conversationId, artifactUid);
        if (saved == null || saved.getContentJson() == null) {
            return Result.success(VerlaHumanizerDocumentEditResponseVO.builder()
                    .conversationId(VerlaPublicIdVoSupport.conversation(conversationId, true))
                    .artifactUid(artifactUid)
                    .exists(false)
                    .build());
        }

        Map<String, Object> content = parseContent(saved);
        return Result.success(VerlaHumanizerDocumentEditResponseVO.builder()
                .conversationId(VerlaPublicIdVoSupport.conversation(conversationId, true))
                .artifactUid(artifactUid)
                .exists(true)
                .content(content)
                .updatedAt(saved.getUpdatedAt())
                .build());
    }

    @PutMapping("/{artifactUid}")
    public Result<SaveVerlaHumanizerDocumentEditResponseVO> saveDocumentEdit(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @VerlaPublicId(VerlaPublicIdType.CONVERSATION) @PathVariable("cid") Long conversationId,
            @PathVariable String artifactUid,
            @RequestBody @Valid SaveVerlaHumanizerDocumentEditRequest request) {
        ensureLogin(clerkUserId);
        ensureConversationAndArtifactOwnership(clerkUserId, conversationId, artifactUid);

        VerlaEditorContentEntity saved = selectByKey(conversationId, artifactUid);
        LocalDateTime now = LocalDateTime.now();
        boolean created = false;
        if (saved == null) {
            saved = new VerlaEditorContentEntity()
                    .setConversationId(conversationId)
                    .setSourceArtifactUid(artifactUid)
                    .setEditorKind(EDITOR_KIND_HUMANIZER)
                    .setContentSchemaVersion(1)
                    .setCreatedBy(clerkUserId)
                    .setCreatedAt(now);
            created = true;
        }

        saved.setContentJson(writeJson(request.getContent()));
        saved.setUpdatedBy(clerkUserId);
        saved.setUpdatedAt(now);

        if (created) {
            editorContentMapper.insert(saved);
        } else {
            editorContentMapper.updateById(saved);
        }

        // 右栏编辑视为一次用户改动，刷新 Recent Task 排序键 last_active_at
        conversationService.touchActivity(clerkUserId, conversationId);

        return Result.success(SaveVerlaHumanizerDocumentEditResponseVO.builder()
                .conversationId(VerlaPublicIdVoSupport.conversation(conversationId, true))
                .artifactUid(artifactUid)
                .saved(true)
                .updatedAt(now)
                .build());
    }

    private VerlaEditorContentEntity selectByKey(Long conversationId, String artifactUid) {
        return editorContentMapper.selectOne(
                new LambdaQueryWrapper<VerlaEditorContentEntity>()
                        .eq(VerlaEditorContentEntity::getConversationId, conversationId)
                        .eq(VerlaEditorContentEntity::getSourceArtifactUid, artifactUid)
                        .eq(VerlaEditorContentEntity::getEditorKind, EDITOR_KIND_HUMANIZER));
    }

    private void ensureConversationAndArtifactOwnership(String clerkUserId, Long conversationId,
                                                        String artifactUid) {
        conversationService.getOwned(clerkUserId, conversationId);
        VerlaArtifactEntity artifact = artifactMapper.selectByUid(artifactUid);
        if (artifact == null) {
            throw new BusinessException(ApiCode.TASK_NOT_FOUND, "artifact");
        }
        if (!conversationId.equals(artifact.getConversationId())) {
            throw new BusinessException(ApiCode.NO_PERMISSION);
        }
    }

    private Map<String, Object> parseContent(VerlaEditorContentEntity saved) {
        try {
            return objectMapper.readValue(saved.getContentJson(), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("[Verla/humanizer-edit] parse content failed id={}: {}", saved.getId(), e.getMessage());
            return null;
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BusinessException(ApiCode.PARAM_ERROR, "Invalid humanizer edit JSON payload");
        }
    }

    private void ensureLogin(String clerkUserId) {
        if (clerkUserId == null || clerkUserId.isBlank()) {
            throw new BusinessException(ApiCode.USER_NOT_LOGGED_IN);
        }
    }
}
