package com.studyagent.api.dto.admin.response;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.studyagent.api.dto.verla.response.VerlaConversationVO;
import com.studyagent.service.application.verla.admin.AdminConversationWorkspaceTaskType;
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
    private String workspaceTaskType;
    private boolean readOnly;

    public static AdminConversationRowVO from(VerlaConversation conversation,
                                            String dashboardStatus,
                                            String ownerDisplayName) {
        AdminConversationWorkspaceTaskType taskType =
                AdminConversationWorkspaceTaskType.fromConversation(conversation);
        return AdminConversationRowVO.builder()
                .conversation(VerlaConversationVO.from(conversation, dashboardStatus))
                .ownerClerkUserId(conversation.getUserId())
                .ownerDisplayName(ownerDisplayName)
                .workspaceTaskType(taskType.getRouteKey())
                .readOnly(true)
                .build();
    }
}
