package com.studyagent.api.controller.admin;

import com.studyagent.api.common.Result;
import com.studyagent.api.dto.admin.response.AdminConversationPageVO;
import com.studyagent.api.dto.admin.response.AdminConversationRowVO;
import com.studyagent.common.quota.FeatureCode;
import com.studyagent.infra.service.admin.AdminOwnerProfileEnricher;
import com.studyagent.service.application.verla.VerlaConversationDashboardStatusService;
import com.studyagent.service.application.verla.admin.AdminConversationBrowseService;
import com.studyagent.service.application.verla.admin.AdminOwnerProfile;
import com.studyagent.service.application.verla.admin.VerlaAdminAccessService;
import com.studyagent.service.application.verla.dto.VerlaConversationListSlice;
import com.studyagent.service.domain.verla.VerlaConversation;
import com.studyagent.service.domain.verla.state.ConversationStatus;
import com.studyagent.service.domain.verla.state.IntentLifecycle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminConversationControllerTest {

    private VerlaAdminAccessService adminAccessService;
    private AdminConversationBrowseService browseService;
    private AdminOwnerProfileEnricher ownerProfileEnricher;
    private VerlaConversationDashboardStatusService dashboardStatusService;
    private AdminConversationController controller;

    @BeforeEach
    void setUp() {
        adminAccessService = mock(VerlaAdminAccessService.class);
        browseService = mock(AdminConversationBrowseService.class);
        ownerProfileEnricher = mock(AdminOwnerProfileEnricher.class);
        dashboardStatusService = mock(VerlaConversationDashboardStatusService.class);
        controller = new AdminConversationController(
                adminAccessService,
                browseService,
                ownerProfileEnricher,
                dashboardStatusService,
                mock(com.studyagent.service.application.verla.VerlaConversationService.class),
                mock(com.studyagent.service.domain.verla.repo.VerlaMessageRepository.class),
                mock(com.studyagent.service.domain.verla.repo.VerlaArtifactRepository.class),
                mock(com.studyagent.service.application.verla.VerlaAttachmentService.class),
                mock(com.studyagent.service.application.verla.VerlaFileChatService.class),
                mock(com.studyagent.service.application.verla.AssignmentRuntimeSnapshotService.class),
                mock(com.studyagent.service.application.verla.AiWritingRuntimeSnapshotService.class),
                mock(com.studyagent.service.application.verla.VerlaCodeProjectService.class));
    }

    @Test
    void list_shouldReturnAdminConversationRowsForAllUsers() {
        VerlaConversation conversation = detectionConversation();
        when(browseService.listConversations(null, 1, 20, null, null, false))
                .thenReturn(new VerlaConversationListSlice(List.of(conversation), 1, 1, 20));
        when(dashboardStatusService.resolveAll(anyList()))
                .thenReturn(Map.of(conversation.getId(), "completed"));
        when(ownerProfileEnricher.resolveProfiles(anyList()))
                .thenReturn(Map.of("user_2", AdminOwnerProfile.builder()
                        .clerkUserId("user_2")
                        .displayName("Alice")
                        .email("alice@example.com")
                        .country("US")
                        .membershipType("plus")
                        .tier("plus")
                        .planCode("plus_monthly")
                        .quotaVip(false)
                        .admin(false)
                        .build()));
        when(ownerProfileEnricher.resolveFeatureCode(any())).thenReturn(FeatureCode.AI_DETECTION);
        when(ownerProfileEnricher.resolveRemainingQuota(eq("user_2"), eq(FeatureCode.AI_DETECTION), eq(false)))
                .thenReturn(12L);

        Result<AdminConversationPageVO> result = controller.list(
                "admin_user", 1, 20, null, null, null, false);

        verify(adminAccessService).assertAdmin("admin_user");
        assertThat(result.getData().getRecords()).hasSize(1);
        assertThat(result.getData().getRecords().get(0).getWorkspaceTaskType())
                .isEqualTo("ai-detection");
        assertThat(result.getData().getRecords().get(0).getOwnerDisplayName()).isEqualTo("Alice");
        assertThat(result.getData().getRecords().get(0).getOwnerEmail()).isEqualTo("alice@example.com");
        assertThat(result.getData().getRecords().get(0).getOwnerCountry()).isEqualTo("US");
        assertThat(result.getData().getRecords().get(0).getMembershipType()).isEqualTo("plus");
        assertThat(result.getData().getRecords().get(0).getRemainingQuota()).isEqualTo(12L);
    }

    @Test
    void list_shouldPassExcludeInternalUsersFlag() {
        when(browseService.listConversations(null, 1, 20, null, null, true))
                .thenReturn(new VerlaConversationListSlice(List.of(), 0, 1, 20));
        when(dashboardStatusService.resolveAll(anyList())).thenReturn(Map.of());
        when(ownerProfileEnricher.resolveProfiles(anyList())).thenReturn(Map.of());

        controller.list("admin_user", 1, 20, null, null, null, true);

        verify(browseService).listConversations(null, 1, 20, null, null, true);
    }

    @Test
    void get_shouldResolveWorkspaceTaskType() {
        VerlaConversation conversation = assignmentConversation();
        when(browseService.requireReadable(101L)).thenReturn(conversation);
        when(dashboardStatusService.resolve(conversation)).thenReturn("progressing");
        when(ownerProfileEnricher.resolveProfiles(anyList()))
                .thenReturn(Map.of("user_1", AdminOwnerProfile.builder()
                        .clerkUserId("user_1")
                        .displayName("Bob")
                        .membershipType("free")
                        .tier("free")
                        .quotaVip(false)
                        .admin(false)
                        .build()));
        when(ownerProfileEnricher.resolveFeatureCode(conversation)).thenReturn(FeatureCode.TASK_CREATE);
        when(ownerProfileEnricher.resolveRemainingQuota(eq("user_1"), eq(FeatureCode.TASK_CREATE), eq(false)))
                .thenReturn(3L);

        Result<AdminConversationRowVO> result = controller.get("admin_user", 101L);

        assertThat(result.getData().getWorkspaceTaskType()).isEqualTo("assignment");
        assertThat(result.getData().isReadOnly()).isTrue();
        assertThat(result.getData().getOwnerDisplayName()).isEqualTo("Bob");
        assertThat(result.getData().getRemainingQuota()).isEqualTo(3L);
    }

    private static VerlaConversation assignmentConversation() {
        return baseConversation("ASSIGNMENT");
    }

    private static VerlaConversation detectionConversation() {
        VerlaConversation conversation = baseConversation("AI_DETECTION");
        conversation.setUserId("user_2");
        return conversation;
    }

    private static VerlaConversation baseConversation(String intent) {
        LocalDateTime now = LocalDateTime.of(2026, 7, 1, 12, 0);
        return VerlaConversation.builder()
                .id(101L)
                .userId("user_1")
                .title("Sample task")
                .status(ConversationStatus.ACTIVE.getDbValue())
                .primaryIntent(intent)
                .intentLifecycle(IntentLifecycle.COMMITTED.getDbValue())
                .turnCount(2)
                .lastMessageAt(now)
                .lastActiveAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
