package com.studyagent.service.application.verla.entitlement;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.api.ApiCode;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.service.domain.billing.BillingDomainService;
import com.studyagent.service.domain.billing.BillingPlan;
import com.studyagent.service.domain.verla.FollowupEditUsage;
import com.studyagent.service.domain.verla.VerlaArtifact;
import com.studyagent.service.domain.verla.repo.FollowupEditUsageRepository;
import com.studyagent.service.domain.verla.repo.VerlaArtifactRepository;
import com.studyagent.service.domain.verla.repo.VerlaAttachmentRepository;
import com.studyagent.service.domain.verla.repo.VerlaSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DefaultEntitlementService implements EntitlementService {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final Set<String> DEFAULT_ALLOWED_OUTPUT_TYPES = Set.of("writing");

    private final BillingDomainService billingDomainService;
    private final VerlaAttachmentRepository attachmentRepository;
    private final VerlaArtifactRepository artifactRepository;
    private final FollowupEditUsageRepository followupEditUsageRepository;
    private final VerlaSessionRepository sessionRepository;
    private final ObjectMapper objectMapper;

    @Value("${verla.attachment.sign-ttl-seconds:3600}")
    private long signTtlSeconds;

    @Override
    public EffectiveEntitlements getEffectiveEntitlements(String clerkUserId) {
        BillingPlan plan = billingDomainService.getEffectivePlanOrFree(clerkUserId);
        return new EffectiveEntitlements(
                plan == null ? "free" : plan.getPlanCode(),
                plan == null ? "free" : plan.getTier(),
                plan == null ? Integer.valueOf(3) : plan.getMaxFiles(),
                plan == null ? Integer.valueOf(3) : plan.getMaxFollowupEdits(),
                allowedOutputTypes(plan));
    }

    @Override
    public void assertAssignmentOutputAllowed(String clerkUserId, Map<String, Object> requirementForm) {
        assertAssignmentOutputAllowed(getEffectiveEntitlements(clerkUserId), requirementForm);
    }

    @Override
    public void assertAssignmentOutputAllowed(EffectiveEntitlements entitlements, Map<String, Object> requirementForm) {
        Set<String> requested = requestedOutputTypes(requirementForm);
        if (!entitlements.allowedOutputTypes().containsAll(requested)) {
            throw new BusinessException(ApiCode.OUTPUT_TYPE_NOT_ALLOWED);
        }
    }

    @Override
    public void assertCanReserveUserUpload(String clerkUserId, Long conversationId) {
        if (conversationId == null) {
            throw new BusinessException(ApiCode.PARAM_ERROR, "conversationId required");
        }
        EffectiveEntitlements entitlements = getEffectiveEntitlements(clerkUserId);
        Integer limit = entitlements.maxFiles();
        if (limit == null || limit <= 0) {
            return;
        }
        LocalDateTime pendingCutoff = LocalDateTime.now().minusSeconds(Math.max(60L, signTtlSeconds));
        long activeCount = attachmentRepository.countActiveUserUploadsForConversation(conversationId, pendingCutoff);
        if (activeCount >= limit) {
            throw new BusinessException(ApiCode.FILE_LIMIT_REACHED);
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public FollowupEditUsage reserveFollowupEdit(String clerkUserId, Long conversationId,
                                                 Long userMessageId, List<String> artifactUids) {
        if (conversationId == null) {
            throw new BusinessException(ApiCode.PARAM_ERROR, "conversationId required");
        }
        if (userMessageId == null) {
            throw new BusinessException(ApiCode.PARAM_ERROR, "userMessageId required");
        }

        FollowupEditUsage existing = followupEditUsageRepository.findByUserMessageId(userMessageId);
        if (existing != null) {
            return existing;
        }

        Long assignmentSessionId = resolveAssignmentScopeSessionId(conversationId, artifactUids);
        sessionRepository.findByIdForUpdate(assignmentSessionId);
        Integer limit = getEffectiveEntitlements(clerkUserId).maxFollowupEdits();
        if (limit != null && limit > 0) {
            long activeCount = followupEditUsageRepository.countActiveByAssignmentSessionId(assignmentSessionId);
            if (activeCount >= limit) {
                throw new BusinessException(ApiCode.FOLLOWUP_EDIT_LIMIT_REACHED);
            }
        }

        FollowupEditUsage usage = FollowupEditUsage.builder()
                .conversationId(conversationId)
                .assignmentSessionId(assignmentSessionId)
                .clerkUserId(clerkUserId)
                .userMessageId(userMessageId)
                .state(FollowupEditUsage.STATE_RESERVED)
                .build();
        try {
            return followupEditUsageRepository.save(usage);
        } catch (DuplicateKeyException duplicateKeyException) {
            FollowupEditUsage raced = followupEditUsageRepository.findByUserMessageId(userMessageId);
            if (raced != null) {
                return raced;
            }
            throw duplicateKeyException;
        }
    }

    @Override
    public void bindFollowupEditSession(Long userMessageId, Long assignmentChatSessionId) {
        if (userMessageId == null || assignmentChatSessionId == null) {
            return;
        }
        FollowupEditUsage usage = followupEditUsageRepository.findByUserMessageId(userMessageId);
        if (usage == null) {
            return;
        }
        followupEditUsageRepository.updateState(userMessageId,
                FollowupEditUsage.STATE_RESERVED,
                assignmentChatSessionId,
                null);
    }

    @Override
    public void markFollowupEditCompleted(Long assignmentChatSessionId) {
        FollowupEditUsage usage = followupEditUsageRepository.findByAssignmentChatSessionId(assignmentChatSessionId);
        if (usage == null) {
            return;
        }
        followupEditUsageRepository.updateState(usage.getUserMessageId(),
                FollowupEditUsage.STATE_COMPLETED,
                assignmentChatSessionId,
                null);
    }

    @Override
    public void releaseFollowupEdit(Long assignmentChatSessionId, String reason) {
        FollowupEditUsage usage = followupEditUsageRepository.findByAssignmentChatSessionId(assignmentChatSessionId);
        if (usage == null) {
            return;
        }
        followupEditUsageRepository.updateState(usage.getUserMessageId(),
                FollowupEditUsage.STATE_RELEASED,
                assignmentChatSessionId,
                reason);
    }

    private Long resolveAssignmentScopeSessionId(Long conversationId, List<String> artifactUids) {
        if (artifactUids == null || artifactUids.isEmpty()) {
            return latestAssignmentSessionId(conversationId);
        }
        List<VerlaArtifact> artifacts = artifactRepository.findByUids(artifactUids);
        if (artifacts == null || artifacts.size() != artifactUids.size()) {
            throw new BusinessException(ApiCode.PARAM_ERROR, "artifactUids must belong to one assignment batch");
        }
        Set<Long> sessionIds = artifacts.stream()
                .filter(Objects::nonNull)
                .map(VerlaArtifact::getSessionId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (sessionIds.size() != 1) {
            throw new BusinessException(ApiCode.PARAM_ERROR, "artifactUids must belong to one assignment batch");
        }
        return sessionIds.iterator().next();
    }

    private Long latestAssignmentSessionId(Long conversationId) {
        List<VerlaArtifact> all = artifactRepository.findByConversation(conversationId);
        if (all == null || all.isEmpty()) {
            throw new BusinessException(ApiCode.PARAM_ERROR, "assignment artifacts required");
        }
        Long latestSessionId = all.stream()
                .map(VerlaArtifact::getSessionId)
                .filter(Objects::nonNull)
                .max(Long::compareTo)
                .orElse(null);
        if (latestSessionId == null) {
            throw new BusinessException(ApiCode.PARAM_ERROR, "assignment artifacts required");
        }
        return latestSessionId;
    }

    private Set<String> requestedOutputTypes(Map<String, Object> requirementForm) {
        Map<String, Object> deliverableCount = castMap(requirementForm == null
                ? null
                : requirementForm.get("deliverable_count"));
        int markdown = intValue(deliverableCount.get("markdown"));
        int ppt = intValue(deliverableCount.get("ppt"));
        int code = intValue(deliverableCount.get("code"));

        Set<String> requested = new LinkedHashSet<>();
        if (markdown > 0) {
            requested.add("writing");
        }
        if (ppt > 0) {
            requested.add("ppt");
        }
        if (code > 0) {
            requested.add("coding");
        }
        return requested.isEmpty() ? DEFAULT_ALLOWED_OUTPUT_TYPES : requested;
    }

    private Set<String> allowedOutputTypes(BillingPlan plan) {
        if (plan == null || plan.getAllowedOutputTypes() == null || plan.getAllowedOutputTypes().isBlank()) {
            return DEFAULT_ALLOWED_OUTPUT_TYPES;
        }
        try {
            List<String> values = objectMapper.readValue(plan.getAllowedOutputTypes(), STRING_LIST);
            if (values == null || values.isEmpty()) {
                return DEFAULT_ALLOWED_OUTPUT_TYPES;
            }
            Set<String> normalized = values.stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .map(value -> normalizeOutputType(value.toLowerCase(Locale.ROOT)))
                    .collect(Collectors.toCollection(HashSet::new));
            return normalized.isEmpty() ? DEFAULT_ALLOWED_OUTPUT_TYPES : Collections.unmodifiableSet(normalized);
        } catch (Exception ex) {
            throw new BusinessException(ApiCode.INTERNAL_ERROR, "invalid allowed_output_types config");
        }
    }

    private String normalizeOutputType(String rawValue) {
        if ("code".equals(rawValue)) {
            return "coding";
        }
        return rawValue;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Collections.emptyMap();
    }

    private int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }
}
