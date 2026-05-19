package com.studyagent.api.controller.verla;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.api.common.Result;
import com.studyagent.api.dto.verla.request.SaveVerlaEditorContentRequest;
import com.studyagent.api.dto.verla.response.SaveVerlaEditorContentResponseVO;
import com.studyagent.api.dto.verla.response.VerlaEditorContentResponseVO;
import com.studyagent.common.api.ApiCode;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.infra.entity.verla.VerlaArtifactEntity;
import com.studyagent.infra.entity.verla.VerlaEditorContentEntity;
import com.studyagent.infra.entity.verla.VerlaEditorContentVersionEntity;
import com.studyagent.infra.mapper.verla.VerlaArtifactMapper;
import com.studyagent.infra.mapper.verla.VerlaEditorContentMapper;
import com.studyagent.infra.mapper.verla.VerlaEditorContentVersionMapper;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Conversation + artifact 维度的编辑器工作态接口。
 */
@Slf4j
@RestController
@RequestMapping("/v1/verla/conversations/{cid}/artifacts/{artifactUid}/editor-content")
@RequiredArgsConstructor
public class VerlaEditorContentController {

    private static final Set<String> SUPPORTED_EDITOR_KINDS = Set.of("document", "slides", "code");
    private static final int MAX_EDITOR_VERSIONS = 10;

    private final VerlaConversationService conversationService;
    private final VerlaArtifactMapper artifactMapper;
    private final VerlaEditorContentMapper editorContentMapper;
    private final VerlaEditorContentVersionMapper editorContentVersionMapper;
    private final ObjectMapper objectMapper;

    @GetMapping
    public Result<VerlaEditorContentResponseVO> getEditorContent(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @PathVariable("cid") Long conversationId,
            @PathVariable String artifactUid,
            @RequestParam("kind") String kind) {
        ensureLogin(clerkUserId);
        String editorKind = normalizeEditorKind(kind);
        ensureConversationAndArtifactOwnership(clerkUserId, conversationId, artifactUid);

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
                    .conversationId(conversationId)
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
            log.warn("[Verla/editor] parse content failed id={}: {}", editorContent.getId(), e.getMessage());
        }
        if (editorContent.getMetaJson() != null && !editorContent.getMetaJson().isBlank()) {
            try {
                meta = objectMapper.readValue(
                        editorContent.getMetaJson(),
                        new TypeReference<Map<String, Object>>() {
                        });
            } catch (Exception e) {
                parseError = true;
                log.warn("[Verla/editor] parse meta failed id={}: {}", editorContent.getId(), e.getMessage());
            }
        }

        return Result.success(VerlaEditorContentResponseVO.builder()
                .conversationId(conversationId)
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

    @PutMapping
    public Result<SaveVerlaEditorContentResponseVO> saveEditorContent(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @PathVariable("cid") Long conversationId,
            @PathVariable String artifactUid,
            @RequestParam("kind") String kind,
            @RequestBody @Valid SaveVerlaEditorContentRequest request) {
        ensureLogin(clerkUserId);
        String editorKind = normalizeEditorKind(kind);
        ensureConversationAndArtifactOwnership(clerkUserId, conversationId, artifactUid);

        VerlaEditorContentEntity editorContent = editorContentMapper.selectOne(
                new LambdaQueryWrapper<VerlaEditorContentEntity>()
                        .eq(VerlaEditorContentEntity::getConversationId, conversationId)
                        .eq(VerlaEditorContentEntity::getSourceArtifactUid, artifactUid)
                        .eq(VerlaEditorContentEntity::getEditorKind, editorKind)
                        .orderByDesc(VerlaEditorContentEntity::getUpdatedAt)
                        .last("LIMIT 1")
        );

        LocalDateTime now = LocalDateTime.now();
        boolean created = false;
        if (editorContent == null) {
            editorContent = new VerlaEditorContentEntity()
                    .setConversationId(conversationId)
                    .setSourceArtifactUid(artifactUid)
                    .setEditorKind(editorKind)
                    .setCreatedBy(clerkUserId)
                    .setCreatedAt(now);
            created = true;
        }

        editorContent.setTitle(resolveTitle(request, editorContent.getTitle()));
        editorContent.setContentJson(writeJson(request.getContent()));
        editorContent.setMetaJson(writeJson(request.getMeta()));
        editorContent.setSeedArtifactUid(resolveSeedArtifactUid(request.getSeedArtifactUid(), artifactUid));
        editorContent.setContentSchemaVersion(resolveContentSchemaVersion(request.getContentSchemaVersion()));
        editorContent.setUpdatedBy(clerkUserId);
        editorContent.setUpdatedAt(now);

        if (created) {
            editorContentMapper.insert(editorContent);
        } else {
            editorContentMapper.updateById(editorContent);
        }

        String saveSource = resolveSaveSource(request.getSaveSource());
        Integer latestVersionNo = findLatestVersionNo(editorContent.getId());
        int responseVersionNo = latestVersionNo;
        if (!"autosave".equals(saveSource)) {
            int nextVersionNo = latestVersionNo + 1;
            VerlaEditorContentVersionEntity versionEntity = new VerlaEditorContentVersionEntity()
                    .setEditorContentId(editorContent.getId())
                    .setVersionNo(nextVersionNo)
                    .setContentJson(editorContent.getContentJson())
                    .setMetaJson(editorContent.getMetaJson())
                    .setSaveSource(saveSource)
                    .setCreatedBy(clerkUserId)
                    .setCreatedAt(now);
            editorContentVersionMapper.insert(versionEntity);
            pruneOldVersions(editorContent.getId());
            responseVersionNo = nextVersionNo;
        }

        return Result.success(SaveVerlaEditorContentResponseVO.builder()
                .conversationId(conversationId)
                .artifactUid(artifactUid)
                .kind(editorKind)
                .editorContentId(editorContent.getId())
                .title(editorContent.getTitle())
                .versionNo(responseVersionNo)
                .updatedAt(now)
                .saved(true)
                .created(created)
                .build());
    }

    private void ensureConversationAndArtifactOwnership(String clerkUserId, Long conversationId, String artifactUid) {
        conversationService.getOwned(clerkUserId, conversationId);
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

    private void pruneOldVersions(Long editorContentId) {
        List<VerlaEditorContentVersionEntity> versions = editorContentVersionMapper.selectList(
                new LambdaQueryWrapper<VerlaEditorContentVersionEntity>()
                        .eq(VerlaEditorContentVersionEntity::getEditorContentId, editorContentId)
                        .orderByDesc(VerlaEditorContentVersionEntity::getVersionNo)
        );
        if (versions.size() <= MAX_EDITOR_VERSIONS) {
            return;
        }
        versions.stream()
                .skip(MAX_EDITOR_VERSIONS)
                .map(VerlaEditorContentVersionEntity::getId)
                .filter(java.util.Objects::nonNull)
                .forEach(editorContentVersionMapper::deleteById);
    }

    private String normalizeEditorKind(String kind) {
        String normalized = kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_EDITOR_KINDS.contains(normalized)) {
            throw new BusinessException(ApiCode.BAD_REQUEST, "Unsupported editor kind: " + kind);
        }
        return normalized;
    }

    private Integer resolveContentSchemaVersion(Integer version) {
        return version != null && version > 0 ? version : 1;
    }

    private String resolveSaveSource(String saveSource) {
        if (saveSource == null || saveSource.isBlank()) {
            return "manual_save";
        }
        return saveSource.trim();
    }

    private String resolveSeedArtifactUid(String seedArtifactUid, String artifactUid) {
        if (seedArtifactUid == null || seedArtifactUid.isBlank()) {
            return artifactUid;
        }
        return seedArtifactUid.trim();
    }

    private String resolveTitle(SaveVerlaEditorContentRequest request, String existingTitle) {
        if (request.getTitle() != null && !request.getTitle().isBlank()) {
            return request.getTitle().trim();
        }
        if (request.getContent() != null) {
            Object title = request.getContent().get("title");
            if (title instanceof String titleStr && !titleStr.isBlank()) {
                return titleStr.trim();
            }
            Object filename = request.getContent().get("filename");
            if (filename instanceof String filenameStr && !filenameStr.isBlank()) {
                return filenameStr.trim();
            }
        }
        if (existingTitle != null && !existingTitle.isBlank()) {
            return existingTitle;
        }
        return "Untitled";
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BusinessException(ApiCode.PARAM_ERROR, "Invalid editor JSON payload");
        }
    }

    private void ensureLogin(String clerkUserId) {
        if (clerkUserId == null || clerkUserId.isBlank()) {
            throw new BusinessException(ApiCode.USER_NOT_LOGGED_IN);
        }
    }
}
