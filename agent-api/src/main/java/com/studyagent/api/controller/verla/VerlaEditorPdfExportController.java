package com.studyagent.api.controller.verla;

import com.studyagent.common.api.ApiCode;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.infra.entity.verla.VerlaArtifactEntity;
import com.studyagent.infra.mapper.verla.VerlaArtifactMapper;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@Slf4j
@RestController
@RequestMapping("/v1/verla/conversations/{cid}/artifacts/{artifactUid}/exports/pdf")
@RequiredArgsConstructor
public class VerlaEditorPdfExportController {

    private static final String DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final VerlaConversationService conversationService;
    private final VerlaArtifactMapper artifactMapper;
    private final VerlaEditorPdfExportService pdfExportService;

    @Value("${verla.editor.pdf-export.max-docx-size-bytes:20971520}")
    private long maxDocxSizeBytes;

    @PostMapping(consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> exportPdf(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @PathVariable("cid") Long conversationId,
            @PathVariable String artifactUid,
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "filename", required = false) String filename) throws IOException {

        ensureLogin(clerkUserId);
        ensureArtifactOwnership(clerkUserId, conversationId, artifactUid);

        if (file.isEmpty()) {
            throw new BusinessException(ApiCode.BAD_REQUEST, "Empty DOCX file");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.toLowerCase().endsWith(".docx")) {
            throw new BusinessException(ApiCode.BAD_REQUEST, "File must be a .docx document");
        }

        String mime = file.getContentType();
        if (mime != null && !mime.isBlank() && !DOCX_MIME.equals(mime)) {
            throw new BusinessException(ApiCode.BAD_REQUEST,
                    "Unsupported file type: " + mime);
        }

        long size = file.getSize();
        if (size > maxDocxSizeBytes) {
            throw new BusinessException(ApiCode.BAD_REQUEST,
                    "DOCX file too large (max " + (maxDocxSizeBytes / 1024 / 1024) + " MB)");
        }

        byte[] docxBytes = file.getBytes();
        byte[] pdfBytes = pdfExportService.convertDocxToPdf(docxBytes, size);

        String downloadFilename = resolvePdfFilename(filename);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(downloadFilename)
                                .build()
                                .toString())
                .body(pdfBytes);
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

    private String resolvePdfFilename(String filename) {
        if (filename != null && !filename.isBlank()) {
            String trimmed = filename.trim();
            if (!trimmed.toLowerCase().endsWith(".pdf")) {
                return trimmed + ".pdf";
            }
            return trimmed;
        }
        return "document.pdf";
    }

    private void ensureLogin(String clerkUserId) {
        if (clerkUserId == null || clerkUserId.isBlank()) {
            throw new BusinessException(ApiCode.USER_NOT_LOGGED_IN);
        }
    }
}
