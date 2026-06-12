package com.studyagent.api.controller.verla;

import com.studyagent.api.dto.verla.support.VerlaPublicIdVoSupport;
import com.studyagent.api.web.verla.VerlaPublicId;
import com.studyagent.common.verla.id.VerlaPublicIdType;

import com.studyagent.api.common.Result;
import com.studyagent.api.dto.verla.request.UpsertEditorPreviewRequest;
import com.studyagent.api.dto.verla.response.VerlaEditorPreviewVO;
import com.studyagent.common.api.ApiCode;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.infra.entity.verla.VerlaEditorPreviewEntity;
import com.studyagent.api.service.VerlaEditorPreviewService;
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

import java.util.Locale;
import java.util.Set;

@Slf4j
@RestController
@RequestMapping("/v1/verla/conversations/{cid}/artifacts/{artifactUid}/editor-preview")
@RequiredArgsConstructor
public class VerlaEditorPreviewController {

    private static final Set<String> SUPPORTED_EDITOR_KINDS = Set.of("document", "slides", "code");

    private final VerlaEditorPreviewService previewService;

    @GetMapping
    public Result<VerlaEditorPreviewVO> getPreview(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @VerlaPublicId(VerlaPublicIdType.CONVERSATION) @PathVariable("cid") Long conversationId,
            @PathVariable String artifactUid,
            @RequestParam("kind") String kind) {
        ensureLogin(clerkUserId);
        String editorKind = normalizeEditorKind(kind);
        previewService.ensureOwnership(clerkUserId, conversationId, artifactUid);

        VerlaEditorPreviewEntity entity = previewService.getPreview(conversationId, artifactUid, editorKind);
        if (entity == null) {
            VerlaEditorPreviewVO empty = VerlaEditorPreviewVO.builder()
                    .conversationId(VerlaPublicIdVoSupport.conversation(conversationId, true))
                    .artifactUid(artifactUid)
                    .kind(editorKind)
                    .build();
            return Result.success(empty);
        }
        return Result.success(VerlaEditorPreviewVO.fromEntity(entity));
    }

    @PutMapping
    public Result<VerlaEditorPreviewVO> upsertPreview(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @VerlaPublicId(VerlaPublicIdType.CONVERSATION) @PathVariable("cid") Long conversationId,
            @PathVariable String artifactUid,
            @RequestParam("kind") String kind,
            @RequestBody @Valid UpsertEditorPreviewRequest request) {
        ensureLogin(clerkUserId);
        String editorKind = normalizeEditorKind(kind);
        previewService.ensureOwnership(clerkUserId, conversationId, artifactUid);

        VerlaEditorPreviewEntity entity = previewService.upsertPreview(
                clerkUserId,
                conversationId,
                artifactUid,
                editorKind,
                request.getPreviewUrl(),
                request.getAttachmentObjectId(),
                request.getContentHash(),
                request.getCaptureSource(),
                request.getWidth(),
                request.getHeight());
        return Result.success(VerlaEditorPreviewVO.fromEntity(entity));
    }

    private String normalizeEditorKind(String kind) {
        String normalized = kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_EDITOR_KINDS.contains(normalized)) {
            throw new BusinessException(ApiCode.BAD_REQUEST, "Unsupported editor kind: " + kind);
        }
        return normalized;
    }

    private void ensureLogin(String clerkUserId) {
        if (clerkUserId == null || clerkUserId.isBlank()) {
            throw new BusinessException(ApiCode.USER_NOT_LOGGED_IN);
        }
    }
}
