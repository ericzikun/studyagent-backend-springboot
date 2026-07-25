package com.studyagent.infra.service.admin;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.studyagent.common.quota.FeatureCode;
import com.studyagent.infra.entity.UserProfileEntity;
import com.studyagent.infra.entity.UserSubscriptionEntity;
import com.studyagent.infra.mapper.UserMapper;
import com.studyagent.infra.mapper.UserSubscriptionMapper;
import com.studyagent.service.application.verla.admin.AdminConversationWorkspaceTaskType;
import com.studyagent.service.application.verla.admin.AdminOwnerProfile;
import com.studyagent.service.domain.quota.QuotaBalance;
import com.studyagent.service.domain.quota.QuotaDomainService;
import com.studyagent.service.domain.quota.QuotaVipAccessService;
import com.studyagent.service.domain.user.ClerkClient;
import com.studyagent.service.domain.verla.VerlaConversation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Batch-resolves owner profile fields for admin conversation browse rows.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminOwnerProfileEnricher {

    private static final Duration EMAIL_CACHE_TTL = Duration.ofMinutes(10);

    private final UserMapper userMapper;
    private final UserSubscriptionMapper userSubscriptionMapper;
    private final ClerkClient clerkClient;
    private final QuotaDomainService quotaDomainService;
    private final QuotaVipAccessService quotaVipAccessService;
    private final Cache<String, String> emailCache = Caffeine.newBuilder()
            .expireAfterWrite(EMAIL_CACHE_TTL)
            .maximumSize(20_000L)
            .build();

    public Map<String, AdminOwnerProfile> resolveProfiles(List<VerlaConversation> conversations) {
        List<String> clerkUserIds = distinctUserIds(conversations);
        if (clerkUserIds.isEmpty()) {
            return Map.of();
        }

        Map<String, UserProfileEntity> profiles = loadProfiles(clerkUserIds);
        Map<String, UserSubscriptionEntity> subscriptions = loadSubscriptions(clerkUserIds);

        Map<String, AdminOwnerProfile> result = new HashMap<>();
        for (String clerkUserId : clerkUserIds) {
            UserProfileEntity profile = profiles.get(clerkUserId);
            UserSubscriptionEntity subscription = subscriptions.get(clerkUserId);
            boolean isAdmin = profile != null && Boolean.TRUE.equals(profile.getIsAdmin());
            boolean isQuotaVip = quotaVipAccessService.isQuotaVip(clerkUserId);
            String tier = subscription == null || blank(subscription.getTier())
                    ? "free"
                    : subscription.getTier().trim().toLowerCase(Locale.ROOT);
            String planCode = subscription == null || blank(subscription.getPlanCode())
                    ? null
                    : subscription.getPlanCode().trim();
            String membershipType = resolveMembershipType(tier, isQuotaVip, isAdmin);
            String displayName = profile == null || blank(profile.getDisplayName())
                    ? null
                    : profile.getDisplayName().trim();
            String country = profile == null || blank(profile.getCountry())
                    ? null
                    : profile.getCountry().trim();
            result.put(clerkUserId, AdminOwnerProfile.builder()
                    .clerkUserId(clerkUserId)
                    .displayName(displayName)
                    .email(resolveEmail(clerkUserId))
                    .country(country)
                    .membershipType(membershipType)
                    .planCode(planCode)
                    .tier(tier)
                    .quotaVip(isQuotaVip)
                    .admin(isAdmin)
                    .build());
        }
        return result;
    }

    public FeatureCode resolveFeatureCode(VerlaConversation conversation) {
        AdminConversationWorkspaceTaskType taskType =
                AdminConversationWorkspaceTaskType.fromConversation(conversation);
        return switch (taskType) {
            case AI_DETECTION -> FeatureCode.AI_DETECTION;
            case AI_HUMANIZER -> FeatureCode.HUMANIZER;
            case ASSIGNMENT, UNKNOWN -> FeatureCode.TASK_CREATE;
        };
    }

    public Long resolveRemainingQuota(String clerkUserId, FeatureCode featureCode, boolean unlimited) {
        if (clerkUserId == null || clerkUserId.isBlank() || featureCode == null) {
            return null;
        }
        if (unlimited) {
            return null;
        }
        try {
            QuotaBalance balance = quotaDomainService.getUserQuota(clerkUserId.trim(), featureCode.getCode());
            return balance == null ? null : balance.totalAvailable();
        } catch (Exception ex) {
            log.warn("admin owner quota lookup failed: userId={}, feature={}, err={}",
                    clerkUserId, featureCode.getCode(), ex.getMessage());
            return null;
        }
    }

    private Map<String, UserProfileEntity> loadProfiles(List<String> clerkUserIds) {
        List<UserProfileEntity> rows = userMapper.selectByClerkUserIds(clerkUserIds);
        if (rows == null || rows.isEmpty()) {
            return Map.of();
        }
        Map<String, UserProfileEntity> map = new HashMap<>();
        for (UserProfileEntity row : rows) {
            if (row == null || blank(row.getClerkUserId())) {
                continue;
            }
            map.put(row.getClerkUserId().trim(), row);
        }
        return map;
    }

    private Map<String, UserSubscriptionEntity> loadSubscriptions(List<String> clerkUserIds) {
        List<UserSubscriptionEntity> rows = userSubscriptionMapper.selectByClerkUserIds(clerkUserIds);
        if (rows == null || rows.isEmpty()) {
            return Map.of();
        }
        Map<String, UserSubscriptionEntity> map = new HashMap<>();
        for (UserSubscriptionEntity row : rows) {
            if (row == null || blank(row.getClerkUserId())) {
                continue;
            }
            map.put(row.getClerkUserId().trim(), row);
        }
        return map;
    }

    private String resolveEmail(String clerkUserId) {
        String cached = emailCache.getIfPresent(clerkUserId);
        if (cached != null) {
            return cached.isEmpty() ? null : cached;
        }
        String email = null;
        try {
            email = clerkClient.getUserEmail(clerkUserId);
        } catch (Exception ex) {
            log.warn("admin owner email lookup failed: userId={}, err={}", clerkUserId, ex.getMessage());
        }
        String normalized = blank(email) ? "" : email.trim();
        emailCache.put(clerkUserId, normalized);
        return normalized.isEmpty() ? null : normalized;
    }

    private static String resolveMembershipType(String tier, boolean isQuotaVip, boolean isAdmin) {
        String base = blank(tier) ? "free" : tier;
        if (isQuotaVip) {
            return base + "+quota_vip";
        }
        if (isAdmin) {
            return base + "+admin";
        }
        return base;
    }

    private static List<String> distinctUserIds(List<VerlaConversation> conversations) {
        if (conversations == null || conversations.isEmpty()) {
            return List.of();
        }
        return conversations.stream()
                .map(VerlaConversation::getUserId)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(id -> !id.isEmpty())
                .distinct()
                .collect(Collectors.toList());
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
