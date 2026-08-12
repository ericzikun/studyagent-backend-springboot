package com.studyagent.service.application.verla.admin;

import com.studyagent.common.api.ApiCode;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.common.verla.enums.VerlaConversationListSegment;
import com.studyagent.service.application.verla.VerlaConversationService;
import com.studyagent.service.application.verla.dto.VerlaConversationListSlice;
import com.studyagent.service.domain.file.OssStorageService;
import com.studyagent.service.domain.user.User;
import com.studyagent.service.domain.user.UserRepository;
import com.studyagent.service.domain.verla.VerlaAttachment;
import com.studyagent.service.domain.verla.VerlaConversation;
import com.studyagent.service.domain.verla.repo.VerlaAttachmentRepository;
import com.studyagent.service.domain.verla.repo.VerlaConversationRepository;
import com.studyagent.service.domain.verla.repo.VerlaMessageRepository;
import com.studyagent.service.domain.verla.state.ConversationStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Admin read-only browse over all users' Verla conversations.
 */
@Service
@RequiredArgsConstructor
public class AdminConversationBrowseService {

    private final VerlaConversationRepository conversationRepository;
    private final VerlaConversationService conversationService;
    private final UserRepository userRepository;
    private final VerlaMessageRepository messageRepository;
    private final VerlaAttachmentRepository attachmentRepository;
    private final OssStorageService ossStorageService;

    public VerlaConversationListSlice listConversations(String ownerUserId,
                                                        int pageNo,
                                                        int pageSize,
                                                        VerlaConversationListSegment segment,
                                                        ConversationStatus statusFilter,
                                                        boolean excludeInternalUsers) {
        int page = Math.max(pageNo, 1);
        int size = Math.min(Math.max(pageSize, 1), 100);
        String segmentKey = segment == null ? null : segment.getQueryKey();
        String statusDb = statusFilter == null ? null : statusFilter.getDbValue();
        String owner = normalizeOwnerUserId(ownerUserId);
        long total = conversationRepository.countAdminFiltered(
                owner, segmentKey, statusDb, excludeInternalUsers);
        List<VerlaConversation> rows = conversationRepository.findAdminFilteredPaged(
                owner, segmentKey, statusDb, excludeInternalUsers, page, size);
        return new VerlaConversationListSlice(rows, total, page, size);
    }

    public VerlaConversationListSlice searchConversations(String ownerUserId,
                                                          String keyword,
                                                          int pageNo,
                                                          int pageSize,
                                                          VerlaConversationListSegment segment,
                                                          ConversationStatus statusFilter,
                                                          boolean excludeInternalUsers) {
        String trimmed = keyword == null ? "" : keyword.trim();
        if (trimmed.isEmpty()) {
            throw new BusinessException(ApiCode.PARAM_VALIDATION_FAILED, "keyword is required");
        }
        if (trimmed.length() > 200) {
            throw new BusinessException(ApiCode.PARAM_VALIDATION_FAILED, "keyword too long");
        }
        int page = Math.max(pageNo, 1);
        int size = Math.min(Math.max(pageSize, 1), 100);
        String keywordPattern = escapeLikePattern(trimmed);
        String segmentKey = segment == null ? null : segment.getQueryKey();
        String statusDb = statusFilter == null ? null : statusFilter.getDbValue();
        String owner = normalizeOwnerUserId(ownerUserId);
        long total = conversationRepository.countAdminKeyword(
                owner, keywordPattern, segmentKey, statusDb, excludeInternalUsers);
        List<VerlaConversation> rows = conversationRepository.searchAdminKeywordPaged(
                owner, keywordPattern, segmentKey, statusDb, excludeInternalUsers, page, size);
        return new VerlaConversationListSlice(rows, total, page, size);
    }

    public VerlaConversation requireReadable(Long conversationId) {
        return conversationService.getForInternal(conversationId);
    }

    public Map<String, String> resolveOwnerDisplayNames(List<VerlaConversation> conversations) {
        if (conversations == null || conversations.isEmpty()) {
            return Map.of();
        }
        List<String> clerkUserIds = conversations.stream()
                .map(VerlaConversation::getUserId)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(id -> !id.isEmpty())
                .distinct()
                .collect(Collectors.toList());
        if (clerkUserIds.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new HashMap<>();
        for (String clerkUserId : clerkUserIds) {
            User user = userRepository.findByClerkUserId(clerkUserId).orElse(null);
            if (user != null && user.getDisplayName() != null && !user.getDisplayName().isBlank()) {
                result.put(clerkUserId, user.getDisplayName().trim());
            }
        }
        return result;
    }

    public String resolveOwnerDisplayName(String clerkUserId) {
        if (clerkUserId == null || clerkUserId.isBlank()) {
            return null;
        }
        return userRepository.findByClerkUserId(clerkUserId.trim())
                .map(User::getDisplayName)
                .filter(name -> name != null && !name.isBlank())
                .map(String::trim)
                .orElse(null);
    }

    /**
     * 批量解析每个 conversation 的用户原始 query（第一条 user 主对话消息的 text_content + blocks_json 拼接）。
     */
    public Map<Long, String> resolveUserQueries(List<VerlaConversation> conversations) {
        List<Long> ids = conversationIds(conversations);
        if (ids.isEmpty()) {
            return Map.of();
        }
        return messageRepository.findFirstUserQueryByConversationIds(ids);
    }

    /**
     * 批量解析每个 conversation 的用户上传附件 OSS 直链列表（实时由 oss_key 拼接）。
     */
    public Map<Long, List<String>> resolveUploadUrls(List<VerlaConversation> conversations) {
        List<Long> ids = conversationIds(conversations);
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<VerlaAttachment>> byConversation =
                attachmentRepository.listUserUploadsByConversationIds(ids);
        Map<Long, List<String>> result = new HashMap<>();
        byConversation.forEach((conversationId, attachments) -> {
            List<String> urls = attachments.stream()
                    .map(att -> ossStorageService.getOssUrl(att.getOssKey()))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            if (!urls.isEmpty()) {
                result.put(conversationId, urls);
            }
        });
        return result;
    }

    private static List<Long> conversationIds(List<VerlaConversation> conversations) {
        if (conversations == null || conversations.isEmpty()) {
            return List.of();
        }
        return conversations.stream()
                .map(VerlaConversation::getId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
    }

    private static String normalizeOwnerUserId(String ownerUserId) {
        if (ownerUserId == null || ownerUserId.isBlank()) {
            return null;
        }
        return ownerUserId.trim();
    }

    private static String escapeLikePattern(String raw) {
        return raw.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
