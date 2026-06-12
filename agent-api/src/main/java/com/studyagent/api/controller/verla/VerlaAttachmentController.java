package com.studyagent.api.controller.verla;

import com.studyagent.api.web.verla.VerlaPublicId;
import com.studyagent.common.verla.id.VerlaPublicIdType;

import com.studyagent.api.common.Result;
import com.studyagent.api.dto.verla.response.VerlaAttachmentVO;
import com.studyagent.common.api.ApiCode;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.service.application.verla.VerlaAttachmentService;
import com.studyagent.service.domain.verla.VerlaAttachment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Verla 附件查询（列表/详情）。上传请走 {@link VerlaV2AttachmentUploadController V2 OSS 上传接口}。
 */
@Slf4j
@RestController
@RequestMapping("/v1/verla")
@RequiredArgsConstructor
public class VerlaAttachmentController {

    private final VerlaAttachmentService attachmentService;

    @GetMapping("/conversations/{cid}/attachments")
    public Result<List<VerlaAttachmentVO>> listByConversation(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @VerlaPublicId(VerlaPublicIdType.CONVERSATION) @PathVariable Long cid,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        ensureLogin(clerkUserId);
        int safe = Math.max(1, Math.min(limit, 100));
        List<VerlaAttachmentVO> items = attachmentService.listByConversation(clerkUserId, cid, safe).stream()
                .map(VerlaAttachmentVO::fromUser)
                .collect(Collectors.toList());
        return Result.success(items);
    }

    @GetMapping("/attachments/{objectId}")
    public Result<VerlaAttachmentVO> getOne(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @PathVariable String objectId) {
        ensureLogin(clerkUserId);
        VerlaAttachment a = attachmentService.getOwned(clerkUserId, objectId);
        return Result.success(VerlaAttachmentVO.fromUser(a));
    }

    @GetMapping("/attachments/{objectId}/content")
    public ResponseEntity<Resource> getContent(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @PathVariable String objectId) {
        ensureLogin(clerkUserId);
        VerlaAttachment attachment = attachmentService.getOwned(clerkUserId, objectId);
        byte[] content = attachmentService.loadAttachmentBytes(objectId);
        if (content == null || content.length == 0) {
            return ResponseEntity.notFound().build();
        }

        String mimeType = attachment.getMime();
        MediaType mediaType = MediaType.APPLICATION_OCTET_STREAM;
        if (mimeType != null && !mimeType.isBlank()) {
            mediaType = MediaType.parseMediaType(mimeType.trim());
        }

        return ResponseEntity.ok()
                .contentType(mediaType)
                .cacheControl(CacheControl.noCache().cachePrivate())
                .body(new ByteArrayResource(content));
    }

    private static void ensureLogin(String clerkUserId) {
        if (clerkUserId == null || clerkUserId.isBlank()) {
            throw new BusinessException(ApiCode.USER_NOT_LOGGED_IN);
        }
    }
}
