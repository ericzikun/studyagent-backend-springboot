package com.studyagent.api.dto.admin.response;

import com.studyagent.common.quota.FeatureCode;
import com.studyagent.service.application.verla.admin.AdminOwnerProfile;
import com.studyagent.service.application.verla.dto.VerlaConversationListSlice;
import com.studyagent.service.domain.verla.VerlaConversation;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminConversationPageVO {

    private List<AdminConversationRowVO> records;
    private long total;
    private int pageNo;
    private int pageSize;

    public static AdminConversationPageVO fromSlice(
            VerlaConversationListSlice slice,
            Map<Long, String> dashboardStatusByConversationId,
            Map<String, String> ownerDisplayNameByClerkUserId) {
        return fromSlice(slice, dashboardStatusByConversationId, ownerDisplayNameByClerkUserId, Map.of(),
                conversation -> null, conversation -> false, conversation -> null);
    }

    public static AdminConversationPageVO fromSlice(
            VerlaConversationListSlice slice,
            Map<Long, String> dashboardStatusByConversationId,
            Map<String, AdminOwnerProfile> ownerProfileByClerkUserId,
            Function<VerlaConversation, Long> remainingQuotaResolver,
            Function<VerlaConversation, Boolean> quotaUnlimitedResolver,
            Function<VerlaConversation, FeatureCode> featureCodeResolver) {
        return fromSlice(
                slice,
                dashboardStatusByConversationId,
                Map.of(),
                ownerProfileByClerkUserId,
                remainingQuotaResolver,
                quotaUnlimitedResolver,
                featureCodeResolver);
    }

    private static AdminConversationPageVO fromSlice(
            VerlaConversationListSlice slice,
            Map<Long, String> dashboardStatusByConversationId,
            Map<String, String> ownerDisplayNameByClerkUserId,
            Map<String, AdminOwnerProfile> ownerProfileByClerkUserId,
            Function<VerlaConversation, Long> remainingQuotaResolver,
            Function<VerlaConversation, Boolean> quotaUnlimitedResolver,
            Function<VerlaConversation, FeatureCode> featureCodeResolver) {
        if (slice == null) {
            return AdminConversationPageVO.builder()
                    .records(List.of())
                    .total(0L)
                    .pageNo(1)
                    .pageSize(20)
                    .build();
        }
        List<AdminConversationRowVO> records = slice.records() == null
                ? List.of()
                : slice.records().stream()
                .map(conversation -> toRow(
                        conversation,
                        dashboardStatusByConversationId,
                        ownerDisplayNameByClerkUserId,
                        ownerProfileByClerkUserId,
                        remainingQuotaResolver,
                        quotaUnlimitedResolver,
                        featureCodeResolver))
                .collect(Collectors.toList());
        return AdminConversationPageVO.builder()
                .records(records)
                .total(slice.total())
                .pageNo(slice.pageNo())
                .pageSize(slice.pageSize())
                .build();
    }

    private static AdminConversationRowVO toRow(
            VerlaConversation conversation,
            Map<Long, String> dashboardStatusByConversationId,
            Map<String, String> ownerDisplayNameByClerkUserId,
            Map<String, AdminOwnerProfile> ownerProfileByClerkUserId,
            Function<VerlaConversation, Long> remainingQuotaResolver,
            Function<VerlaConversation, Boolean> quotaUnlimitedResolver,
            Function<VerlaConversation, FeatureCode> featureCodeResolver) {
        String dashboardStatus = dashboardStatusByConversationId == null
                ? null
                : dashboardStatusByConversationId.get(conversation.getId());
        AdminOwnerProfile profile = ownerProfileByClerkUserId == null
                ? null
                : ownerProfileByClerkUserId.get(conversation.getUserId());
        if (profile != null) {
            Long remaining = remainingQuotaResolver == null ? null : remainingQuotaResolver.apply(conversation);
            boolean unlimited = quotaUnlimitedResolver != null
                    && Boolean.TRUE.equals(quotaUnlimitedResolver.apply(conversation));
            FeatureCode featureCode = featureCodeResolver == null
                    ? null
                    : featureCodeResolver.apply(conversation);
            return AdminConversationRowVO.from(
                    conversation, dashboardStatus, profile, remaining, unlimited, featureCode);
        }
        String ownerDisplayName = ownerDisplayNameByClerkUserId == null
                ? null
                : ownerDisplayNameByClerkUserId.get(conversation.getUserId());
        return AdminConversationRowVO.from(conversation, dashboardStatus, ownerDisplayName);
    }
}
