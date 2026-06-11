package com.studyagent.api.controller.verla;

import com.studyagent.api.web.verla.VerlaPublicId;
import com.studyagent.common.verla.id.VerlaPublicIdType;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.api.ApiCode;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.infra.entity.verla.VerlaArtifactEntity;
import com.studyagent.infra.entity.verla.VerlaEditorContentEntity;
import com.studyagent.infra.mapper.verla.VerlaArtifactMapper;
import com.studyagent.infra.mapper.verla.VerlaEditorContentMapper;
import com.studyagent.service.application.verla.VerlaConversationService;
import com.studyagent.service.application.verla.VerlaEditorPdfExportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/v1/verla/conversations/{cid}/artifacts/{artifactUid}/exports/pdf")
@RequiredArgsConstructor
public class VerlaEditorPdfExportController {

    private final VerlaConversationService conversationService;
    private final VerlaArtifactMapper artifactMapper;
    private final VerlaEditorContentMapper editorContentMapper;
    private final VerlaEditorPdfExportService pdfExportService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${verla.editor.pdf-export.max-content-json-bytes:10485760}")
    private long maxContentJsonBytes;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> exportPdf(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @VerlaPublicId(VerlaPublicIdType.CONVERSATION) @PathVariable("cid") Long conversationId,
            @PathVariable String artifactUid,
            @RequestBody(required = false) Map<String, Object> body) {

        ensureLogin(clerkUserId);
        ensureArtifactOwnership(clerkUserId, conversationId, artifactUid);

        VerlaEditorContentEntity editorContent = editorContentMapper.selectOne(
                new LambdaQueryWrapper<VerlaEditorContentEntity>()
                        .eq(VerlaEditorContentEntity::getConversationId, conversationId)
                        .eq(VerlaEditorContentEntity::getSourceArtifactUid, artifactUid)
                        .eq(VerlaEditorContentEntity::getEditorKind, "document")
                        .orderByDesc(VerlaEditorContentEntity::getUpdatedAt)
                        .last("LIMIT 1")
        );

        if (editorContent == null || editorContent.getContentJson() == null) {
            throw new BusinessException(ApiCode.TASK_NOT_FOUND,
                    "No saved editor content found for this artifact");
        }

        byte[] jsonBytes = editorContent.getContentJson().getBytes(StandardCharsets.UTF_8);
        if (jsonBytes.length > maxContentJsonBytes) {
            throw new BusinessException(ApiCode.BAD_REQUEST,
                    "Editor content too large for PDF export (max " + (maxContentJsonBytes / 1024 / 1024) + " MB)");
        }

        String title = resolveTitle(body, editorContent);
        JsonNode docNode = parseDocNode(editorContent.getContentJson());

        byte[] pdfBytes = pdfExportService.renderTiptapToPdf(title, docNode);

        String downloadFilename = resolvePdfFilename(title);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(downloadFilename)
                                .build()
                                .toString())
                .body(pdfBytes);
    }

    private String resolveTitle(Map<String, Object> body, VerlaEditorContentEntity editorContent) {
        if (body != null && body.get("title") instanceof String s && !s.isBlank()) {
            return s;
        }
        if (editorContent.getTitle() != null && !editorContent.getTitle().isBlank()) {
            return editorContent.getTitle();
        }
        return "Untitled document";
    }

    private JsonNode parseDocNode(String contentJson) {
        try {
            JsonNode root = objectMapper.readTree(contentJson);
            JsonNode doc = root.get("doc");
            if (doc == null) {
                throw new BusinessException(ApiCode.INTERNAL_ERROR,
                        "Editor content JSON is missing 'doc' field");
            }
            return doc;
        } catch (Exception e) {
            log.error("Failed to parse editor content JSON", e);
            throw new BusinessException(ApiCode.INTERNAL_ERROR,
                    "Invalid editor content JSON format");
        }
    }

    private void ensureArtifactOwnership(String clerkUserId, Long conversationId, String artifactUid) {
        conversationService.getOwned(clerkUserId, conversationId);
        VerlaArtifactEntity artifact = artifactMapper.selectByUid(artifactUid);
        if (artifact == null) {
            throw new BusinessException(ApiCode.TASK_NOT_FOUND, "artifact");
        }
        if (!conversationId.equals(artifact.getConversationId())) {
            throw new BusinessException(ApiCode.NO_PERMISSION);
        }
    }

    private String resolvePdfFilename(String title) {
        if (title != null && !title.isBlank()) {
            String sanitized = title.trim()
                    .replaceAll("[/\\\\?%*:|\"<>]", "_");
            if (!sanitized.toLowerCase().endsWith(".pdf")) {
                return sanitized + ".pdf";
            }
            return sanitized;
        }
        return "document.pdf";
    }

    private void ensureLogin(String clerkUserId) {
        if (clerkUserId == null || clerkUserId.isBlank()) {
            throw new BusinessException(ApiCode.USER_NOT_LOGGED_IN);
        }
    }
}
