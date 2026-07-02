package com.studyagent.api.controller.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.api.common.Result;
import com.studyagent.api.dto.verla.response.VerlaEditorContentResponseVO;
import com.studyagent.api.dto.verla.support.VerlaPublicIdVoSupport;
import com.studyagent.api.web.verla.VerlaPublicId;
import com.studyagent.common.api.ApiCode;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.common.verla.id.VerlaPublicIdType;
import com.studyagent.infra.entity.verla.VerlaArtifactEntity;
import com.studyagent.infra.entity.verla.VerlaEditorContentEntity;
import com.studyagent.infra.entity.verla.VerlaEditorContentVersionEntity;
import com.studyagent.infra.mapper.verla.VerlaArtifactMapper;
import com.studyagent.infra.mapper.verla.VerlaEditorContentMapper;
import com.studyagent.infra.mapper.verla.VerlaEditorContentVersionMapper;
import com.studyagent.service.application.verla.VerlaConversationService;
import com.studyagent.service.application.verla.admin.VerlaAdminAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Admin read-only editor content for ops conversation view.
 */
@Slf4j
@RestController
@RequestMapping("/v1/admin/conversations/{cid}/artifacts/{artifactUid}/editor-content")
@RequiredArgsConstructor
public class AdminConversationEditorContentController {

    private static final Set<String> SUPPORTED_EDITOR_KINDS = Set.of("document", "slides", "code");

    private final VerlaAdminAccessService adminAccessService;
    private final VerlaConversationService conversationService;
    private final VerlaArtifactMapper artifactMapper;
    private final VerlaEditorContentMapper editorContentMapper;
    private final VerlaEditorContentVersionMapper editorContentVersionMapper;
    private final ObjectMapper objectMapper;

    @GetMapping
    public Result<VerlaEditorContentResponseVO> getEditorContent(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @VerlaPublicId(VerlaPublicIdType.CONVERSATION) @PathVariable("cid") Long conversationId,
            @PathVariable String artifactUid,
            @RequestParam("kind") String kind) {
        adminAccessService.assertAdmin(clerkUserId);
        String editorKind = normalizeEditorKind(kind);
        ensureConversationAndArtifact(conversationId, artifactUid);

        VerlaEditorContentEntity editorContent = editorContentMapper.selectOne(
                new LambdaQueryWrapper<VerlaEditorContentEntity>()
                        .eq(VerlaEditorContentEntity::getConversationId, conversationId)
                        .eq(VerlaEditorContentEntity::getSourceArtifactUid, artifactUid)
                        .eq(VerlaEditorContentEntity::getEditorKind, editorKind)
                        .orderByDesc(VerlaEditorContentEntity::getUpdatedAt)
                        .last("LIMIT 1")
        );

        if (editorContent == null || editorContent.getContentJson() == null) {
            return Result.success(VerlaEditorContentResponseVO.builder()
                    .conversationId(VerlaPublicIdVoSupport.conversation(conversationId, true))
                    .artifactUid(artifactUid)
                    .kind(editorKind)
                    .exists(false)
                    .parseError(false)
                    .build());
        }

        boolean parseError = false;
        Map<String, Object> content = null;
        Map<String, Object> meta = null;
        try {
            content = objectMapper.readValue(
                    editorContent.getContentJson(),
                    new TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception e) {
            parseError = true;
            log.warn("[admin/editor] parse content failed id={}: {}", editorContent.getId(), e.getMessage());
        }
        if (editorContent.getMetaJson() != null && !editorContent.getMetaJson().isBlank()) {
            try {
                meta = objectMapper.readValue(
                        editorContent.getMetaJson(),
                        new TypeReference<Map<String, Object>>() {
                        });
            } catch (Exception e) {
                parseError = true;
                log.warn("[admin/editor] parse meta failed id={}: {}", editorContent.getId(), e.getMessage());
            }
        }

        return Result.success(VerlaEditorContentResponseVO.builder()
                .conversationId(VerlaPublicIdVoSupport.conversation(conversationId, true))
                .artifactUid(artifactUid)
                .kind(editorKind)
                .exists(true)
                .editorContentId(editorContent.getId())
                .title(editorContent.getTitle())
                .content(content)
                .meta(meta)
                .versionNo(findLatestVersionNo(editorContent.getId()))
                .sourceArtifactUid(editorContent.getSourceArtifactUid())
                .seedArtifactUid(editorContent.getSeedArtifactUid())
                .parseError(parseError)
                .build());
    }

    private void ensureConversationAndArtifact(Long conversationId, String artifactUid) {
        conversationService.getForInternal(conversationId);
        VerlaArtifactEntity artifact = artifactMapper.selectByUid(artifactUid);
        if (artifact == null) {
            throw new BusinessException(ApiCode.TASK_NOT_FOUND, "artifact");
        }
        if (!conversationId.equals(artifact.getConversationId())) {
            throw new BusinessException(ApiCode.NO_PERMISSION);
        }
    }

    private Integer findLatestVersionNo(Long editorContentId) {
        VerlaEditorContentVersionEntity latest = editorContentVersionMapper.selectOne(
                new LambdaQueryWrapper<VerlaEditorContentVersionEntity>()
                        .eq(VerlaEditorContentVersionEntity::getEditorContentId, editorContentId)
                        .orderByDesc(VerlaEditorContentVersionEntity::getVersionNo)
                        .last("LIMIT 1")
        );
        return latest != null && latest.getVersionNo() != null ? latest.getVersionNo() : 0;
    }

    private String normalizeEditorKind(String kind) {
        String normalized = kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_EDITOR_KINDS.contains(normalized)) {
            throw new BusinessException(ApiCode.BAD_REQUEST, "Unsupported editor kind: " + kind);
        }
        return normalized;
    }
}
