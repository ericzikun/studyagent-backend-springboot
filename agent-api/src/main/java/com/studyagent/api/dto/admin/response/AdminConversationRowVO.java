package com.studyagent.api.dto.admin.response;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.studyagent.api.dto.verla.response.VerlaConversationVO;
import com.studyagent.common.quota.FeatureCode;
import com.studyagent.service.application.verla.admin.AdminConversationWorkspaceTaskType;
import com.studyagent.service.application.verla.admin.AdminOwnerProfile;
import com.studyagent.service.domain.verla.VerlaConversation;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Flattened admin conversation row for list/detail APIs.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminConversationRowVO {

    @JsonUnwrapped
    private VerlaConversationVO conversation;
    private String ownerClerkUserId;
    private String ownerDisplayName;
    private String ownerEmail;
    private String ownerCountry;
    /** free / plus / pro / free+quota_vip … */
    private String membershipType;
    private String planCode;
    private String tier;
    private Boolean isQuotaVip;
    /** Remaining quota for the conversation's feature; null when unlimited/unknown. */
    private Long remainingQuota;
    private Boolean quotaUnlimited;
    private String quotaFeatureCode;
    private String workspaceTaskType;
    private boolean readOnly;

    public static AdminConversationRowVO from(VerlaConversation conversation,
                                              String dashboardStatus,
                                              String ownerDisplayName) {
        return from(conversation, dashboardStatus, null, ownerDisplayName, null, false, null);
    }

    public static AdminConversationRowVO from(VerlaConversation conversation,
                                              String dashboardStatus,
                                              AdminOwnerProfile ownerProfile,
                                              Long remainingQuota,
                                              boolean quotaUnlimited,
                                              FeatureCode featureCode) {
        String displayName = ownerProfile == null ? null : ownerProfile.getDisplayName();
        return from(conversation, dashboardStatus, ownerProfile, displayName,
                remainingQuota, quotaUnlimited, featureCode);
    }

    private static AdminConversationRowVO from(VerlaConversation conversation,
                                               String dashboardStatus,
                                               AdminOwnerProfile ownerProfile,
                                               String ownerDisplayName,
                                               Long remainingQuota,
                                               boolean quotaUnlimited,
                                               FeatureCode featureCode) {
        AdminConversationWorkspaceTaskType taskType =
                AdminConversationWorkspaceTaskType.fromConversation(conversation);
        AdminConversationRowVOBuilder builder = AdminConversationRowVO.builder()
                .conversation(VerlaConversationVO.from(conversation, dashboardStatus))
                .ownerClerkUserId(conversation.getUserId())
                .ownerDisplayName(ownerDisplayName)
                .remainingQuota(remainingQuota)
                .quotaUnlimited(quotaUnlimited)
                .quotaFeatureCode(featureCode == null ? null : featureCode.getCode())
                .workspaceTaskType(taskType.getRouteKey())
                .readOnly(true);
        if (ownerProfile != null) {
            builder.ownerEmail(ownerProfile.getEmail())
                    .ownerCountry(ownerProfile.getCountry())
                    .membershipType(ownerProfile.getMembershipType())
                    .planCode(ownerProfile.getPlanCode())
                    .tier(ownerProfile.getTier())
                    .isQuotaVip(ownerProfile.isQuotaVip());
            if (ownerDisplayName == null || ownerDisplayName.isBlank()) {
                builder.ownerDisplayName(ownerProfile.getDisplayName());
            }
        }
        return builder.build();
    }
}
