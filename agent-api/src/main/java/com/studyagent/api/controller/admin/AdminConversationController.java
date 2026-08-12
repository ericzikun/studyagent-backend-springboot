package com.studyagent.api.controller.admin;

import com.studyagent.api.common.Result;
import com.studyagent.api.dto.admin.response.AdminConversationPageVO;
import com.studyagent.api.dto.admin.response.AdminConversationRowVO;
import com.studyagent.api.dto.verla.response.AssignmentRuntimeSnapshotVO;
import com.studyagent.api.dto.verla.response.AiWritingRuntimeSnapshotVO;
import com.studyagent.api.dto.verla.response.FileChatPanelResponseVO;
import com.studyagent.api.dto.verla.response.MessagePageVO;
import com.studyagent.api.dto.verla.response.VerlaArtifactVO;
import com.studyagent.api.dto.verla.response.VerlaAttachmentVO;
import com.studyagent.api.dto.verla.response.VerlaMessageVO;
import com.studyagent.api.web.verla.VerlaPublicId;
import com.studyagent.common.api.ApiCode;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.common.verla.enums.VerlaConversationListSegment;
import com.studyagent.common.verla.id.VerlaPublicIdType;
import com.studyagent.service.application.verla.AiWritingRuntimeSnapshotService;
import com.studyagent.service.application.verla.AssignmentRuntimeSnapshotService;
import com.studyagent.service.application.verla.VerlaAttachmentService;
import com.studyagent.service.application.verla.VerlaCodeProjectService;
import com.studyagent.service.application.verla.VerlaConversationDashboardStatusService;
import com.studyagent.service.application.verla.VerlaConversationService;
import com.studyagent.service.application.verla.VerlaFileChatService;
import com.studyagent.service.application.verla.admin.AdminConversationBrowseService;
import com.studyagent.service.application.verla.admin.AdminOwnerProfile;
import com.studyagent.service.application.verla.admin.VerlaAdminAccessService;
import com.studyagent.service.application.verla.dto.VerlaConversationListSlice;
import com.studyagent.infra.service.admin.AdminOwnerProfileEnricher;
import com.studyagent.common.quota.FeatureCode;
import com.studyagent.service.domain.verla.VerlaArtifact;
import com.studyagent.service.domain.verla.VerlaConversation;
import com.studyagent.service.domain.verla.VerlaMessage;
import com.studyagent.service.domain.verla.repo.VerlaArtifactRepository;
import com.studyagent.service.domain.verla.repo.VerlaMessageRepository;
import com.studyagent.service.domain.verla.state.ConversationStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Ops console: admin read-only browse over all users' Verla conversations.
 */
@Slf4j
@RestController
@RequestMapping("/v1/admin/conversations")
@RequiredArgsConstructor
public class AdminConversationController {

    private final VerlaAdminAccessService adminAccessService;
    private final AdminConversationBrowseService browseService;
    private final AdminOwnerProfileEnricher ownerProfileEnricher;
    private final VerlaConversationDashboardStatusService dashboardStatusService;
    private final VerlaConversationService conversationService;
    private final VerlaMessageRepository messageRepository;
    private final VerlaArtifactRepository artifactRepository;
    private final VerlaAttachmentService attachmentService;
    private final VerlaFileChatService fileChatService;
    private final AssignmentRuntimeSnapshotService assignmentRuntimeSnapshotService;
    private final AiWritingRuntimeSnapshotService aiWritingRuntimeSnapshotService;
    private final VerlaCodeProjectService codeProjectService;

    @GetMapping("/access")
    public Result<Boolean> checkAccess(@RequestAttribute("clerkUserId") String clerkUserId) {
        return Result.success(adminAccessService.isAdmin(clerkUserId));
    }

    @GetMapping
    public Result<AdminConversationPageVO> list(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @RequestParam(value = "pageNo", defaultValue = "1") int pageNo,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize,
            @RequestParam(value = "segment", required = false) String segment,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "userId", required = false) String ownerUserId,
            @RequestParam(value = "excludeInternalUsers", defaultValue = "false") boolean excludeInternalUsers) {
        adminAccessService.assertAdmin(clerkUserId);
        VerlaConversationListSegment seg = parseSegment(segment);
        ConversationStatus st = parseConversationStatusFilter(status);
        VerlaConversationListSlice slice = browseService.listConversations(
                ownerUserId, pageNo, pageSize, seg, st, excludeInternalUsers);
        return Result.success(buildPage(slice));
    }

    @GetMapping("/search")
    public Result<AdminConversationPageVO> search(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @RequestParam("keyword") String keyword,
            @RequestParam(value = "pageNo", defaultValue = "1") int pageNo,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize,
            @RequestParam(value = "segment", required = false) String segment,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "userId", required = false) String ownerUserId,
            @RequestParam(value = "excludeInternalUsers", defaultValue = "false") boolean excludeInternalUsers) {
        adminAccessService.assertAdmin(clerkUserId);
        VerlaConversationListSegment seg = parseSegment(segment);
        ConversationStatus st = parseConversationStatusFilter(status);
        VerlaConversationListSlice slice = browseService.searchConversations(
                ownerUserId, keyword, pageNo, pageSize, seg, st, excludeInternalUsers);
        return Result.success(buildPage(slice));
    }

    @GetMapping("/{cid}")
    public Result<AdminConversationRowVO> get(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @VerlaPublicId(VerlaPublicIdType.CONVERSATION) @PathVariable Long cid) {
        adminAccessService.assertAdmin(clerkUserId);
        VerlaConversation conversation = browseService.requireReadable(cid);
        String dashboardStatus = dashboardStatusService.resolve(conversation);
        Map<String, AdminOwnerProfile> profiles =
                ownerProfileEnricher.resolveProfiles(List.of(conversation));
        AdminOwnerProfile profile = profiles.get(conversation.getUserId());
        FeatureCode featureCode = ownerProfileEnricher.resolveFeatureCode(conversation);
        boolean unlimited = profile != null && (profile.isQuotaVip() || profile.isAdmin());
        Long remaining = ownerProfileEnricher.resolveRemainingQuota(
                conversation.getUserId(), featureCode, unlimited);
        return Result.success(AdminConversationRowVO.from(
                conversation, dashboardStatus, profile, remaining, unlimited, featureCode));
    }

    @GetMapping("/{cid}/messages")
    public Result<MessagePageVO> listMessages(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @VerlaPublicId(VerlaPublicIdType.CONVERSATION) @PathVariable Long cid,
            @RequestParam(value = "cursor", required = false) Long cursor,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        adminAccessService.assertAdmin(clerkUserId);
        browseService.requireReadable(cid);
        int safeLimit = Math.max(1, Math.min(limit, 100));
        List<VerlaMessage> page = conversationService.listMessages(cid, cursor, safeLimit);
        Long nextCursor = page.isEmpty() ? null : page.get(page.size() - 1).getId();
        return Result.success(MessagePageVO.builder()
                .items(page.stream().map(VerlaMessageVO::from).collect(Collectors.toList()))
                .nextCursor(nextCursor)
                .build());
    }

    @GetMapping("/{cid}/assignment-chat/messages")
    public Result<MessagePageVO> listAssignmentChatMessages(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @VerlaPublicId(VerlaPublicIdType.CONVERSATION) @PathVariable Long cid,
            @RequestParam(value = "cursor", required = false) Long cursor,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        adminAccessService.assertAdmin(clerkUserId);
        browseService.requireReadable(cid);
        int safeLimit = Math.max(1, Math.min(limit, 100));
        List<VerlaMessage> page = messageRepository.findAssignmentChatByCursor(cid, cursor, safeLimit);
        Long nextCursor = page.isEmpty() ? null : page.get(page.size() - 1).getId();
        return Result.success(MessagePageVO.builder()
                .items(page.stream().map(VerlaMessageVO::from).collect(Collectors.toList()))
                .nextCursor(nextCursor)
                .build());
    }

    @GetMapping("/{cid}/assignment/runtime-snapshot")
    public Result<AssignmentRuntimeSnapshotVO> getAssignmentRuntimeSnapshot(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @VerlaPublicId(VerlaPublicIdType.CONVERSATION) @PathVariable Long cid) {
        adminAccessService.assertAdmin(clerkUserId);
        browseService.requireReadable(cid);
        return Result.success(AssignmentRuntimeSnapshotVO.from(
                assignmentRuntimeSnapshotService.getSnapshot(cid)));
    }

    @GetMapping("/{cid}/ai-writing/runtime-snapshot")
    public Result<AiWritingRuntimeSnapshotVO> getAiWritingRuntimeSnapshot(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @VerlaPublicId(VerlaPublicIdType.CONVERSATION) @PathVariable Long cid) {
        adminAccessService.assertAdmin(clerkUserId);
        browseService.requireReadable(cid);
        return Result.success(AiWritingRuntimeSnapshotVO.from(
                aiWritingRuntimeSnapshotService.getSnapshot(cid)));
    }

    @GetMapping("/{cid}/attachments")
    public Result<List<VerlaAttachmentVO>> listAttachments(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @VerlaPublicId(VerlaPublicIdType.CONVERSATION) @PathVariable Long cid,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        adminAccessService.assertAdmin(clerkUserId);
        int safe = Math.max(1, Math.min(limit, 100));
        return Result.success(attachmentService.listByConversationForAdmin(cid, safe).stream()
                .map(VerlaAttachmentVO::fromUser)
                .collect(Collectors.toList()));
    }

    @GetMapping("/{cid}/artifacts")
    public Result<List<VerlaArtifactVO>> listArtifacts(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @VerlaPublicId(VerlaPublicIdType.CONVERSATION) @PathVariable Long cid) {
        adminAccessService.assertAdmin(clerkUserId);
        browseService.requireReadable(cid);
        List<VerlaArtifact> list = artifactRepository.findByConversation(cid);
        return Result.success(list.stream()
                .filter(VerlaArtifactVO::isListVisible)
                .map(VerlaArtifactVO::from)
                .collect(Collectors.toList()));
    }

    @GetMapping("/{cid}/file-chat")
    public Result<FileChatPanelResponseVO> getFileChatPanel(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @VerlaPublicId(VerlaPublicIdType.CONVERSATION) @PathVariable Long cid,
            @RequestParam("objectId") String objectId,
            @RequestParam(value = "cursor", required = false) Long cursor,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        adminAccessService.assertAdmin(clerkUserId);
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return Result.success(FileChatPanelResponseVO.from(
                fileChatService.getPanelForAdmin(cid, objectId, cursor, safeLimit)));
    }

    @GetMapping("/{cid}/code-projects/{projectUid}/files")
    public ResponseEntity<Resource> getCodeProjectFile(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @VerlaPublicId(VerlaPublicIdType.CONVERSATION) @PathVariable Long cid,
            @PathVariable String projectUid,
            @RequestParam("relPath") String relPath,
            @RequestParam(value = "download", defaultValue = "0") String download) {
        adminAccessService.assertAdmin(clerkUserId);
        VerlaCodeProjectService.ResolvedFile file =
                codeProjectService.resolveFileForAdmin(cid, projectUid, relPath);
        ByteArrayResource body = new ByteArrayResource(file.bytes());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(file.mime()));
        if ("1".equals(download)) {
            headers.setContentDispositionFormData("attachment", file.filename());
        }
        return ResponseEntity.ok()
                .headers(headers)
                .cacheControl(CacheControl.noStore())
                .body(body);
    }

    @GetMapping("/{cid}/code-projects/{projectUid}/archive")
    public ResponseEntity<StreamingResponseBody> downloadCodeProjectArchive(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @VerlaPublicId(VerlaPublicIdType.CONVERSATION) @PathVariable Long cid,
            @PathVariable String projectUid) {
        adminAccessService.assertAdmin(clerkUserId);
        VerlaCodeProjectService.CodeProject project =
                codeProjectService.loadProjectForAdmin(cid, projectUid);
        StreamingResponseBody stream = outputStream -> writeArchive(project, outputStream);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"project.zip\"")
                .cacheControl(CacheControl.noStore())
                .body(stream);
    }

    private AdminConversationPageVO buildPage(VerlaConversationListSlice slice) {
        Map<Long, String> dashboardStatuses = dashboardStatusService.resolveAll(slice.records());
        Map<String, AdminOwnerProfile> ownerProfiles =
                ownerProfileEnricher.resolveProfiles(slice.records());
        AdminConversationPageVO page = AdminConversationPageVO.fromSlice(
                slice,
                dashboardStatuses,
                ownerProfiles,
                conversation -> {
                    AdminOwnerProfile profile = ownerProfiles.get(conversation.getUserId());
                    boolean unlimited = profile != null && (profile.isQuotaVip() || profile.isAdmin());
                    FeatureCode featureCode = ownerProfileEnricher.resolveFeatureCode(conversation);
                    return ownerProfileEnricher.resolveRemainingQuota(
                            conversation.getUserId(), featureCode, unlimited);
                },
                conversation -> {
                    AdminOwnerProfile profile = ownerProfiles.get(conversation.getUserId());
                    return profile != null && (profile.isQuotaVip() || profile.isAdmin());
                },
                ownerProfileEnricher::resolveFeatureCode);
        // 用户 query + 上传文件直链：bypass VO 的 public id，直接按 slice.records() 下标 1:1 对齐回填。
        Map<Long, String> userQueries = browseService.resolveUserQueries(slice.records());
        Map<Long, List<String>> uploadUrls = browseService.resolveUploadUrls(slice.records());
        List<VerlaConversation> conversations = slice.records();
        List<AdminConversationRowVO> rows = page.getRecords();
        for (int i = 0; i < rows.size() && i < conversations.size(); i++) {
            Long conversationId = conversations.get(i).getId();
            if (conversationId == null) {
                continue;
            }
            AdminConversationRowVO row = rows.get(i);
            row.setUserQuery(userQueries.get(conversationId));
            row.setUploadedFileUrls(uploadUrls.get(conversationId));
        }
        return page;
    }

    private void writeArchive(VerlaCodeProjectService.CodeProject project, OutputStream outputStream)
            throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(outputStream, StandardCharsets.UTF_8)) {
            for (VerlaCodeProjectService.CodeFile file : project.files()) {
                byte[] bytes = codeProjectService.readBytes(file);
                if (bytes == null) {
                    continue;
                }
                ZipEntry entry = new ZipEntry(codeProjectService.archiveEntryName(project.rootDir(), file.relPath()));
                zip.putNextEntry(entry);
                zip.write(bytes);
                zip.closeEntry();
            }
        }
    }

    private static VerlaConversationListSegment parseSegment(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        for (VerlaConversationListSegment segment : VerlaConversationListSegment.values()) {
            if (segment.getQueryKey().equals(key)) {
                return segment;
            }
        }
        throw new BusinessException(ApiCode.PARAM_VALIDATION_FAILED, "unknown conversation segment: " + raw);
    }

    private static ConversationStatus parseConversationStatusFilter(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            ConversationStatus status = ConversationStatus.fromDb(raw.trim());
            if (status == ConversationStatus.DELETED) {
                throw new BusinessException(ApiCode.PARAM_VALIDATION_FAILED, "status cannot be deleted");
            }
            return status;
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ApiCode.PARAM_VALIDATION_FAILED, "unknown conversation status: " + raw);
        }
    }
}
