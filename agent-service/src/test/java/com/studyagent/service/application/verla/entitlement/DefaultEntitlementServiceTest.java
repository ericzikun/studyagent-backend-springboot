package com.studyagent.service.application.verla.entitlement;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultEntitlementServiceTest {

    @Mock
    private BillingDomainService billingDomainService;
    @Mock
    private VerlaAttachmentRepository attachmentRepository;
    @Mock
    private VerlaArtifactRepository artifactRepository;
    @Mock
    private FollowupEditUsageRepository followupEditUsageRepository;

    private DefaultEntitlementService service;

    @BeforeEach
    void setUp() {
        service = new DefaultEntitlementService(
                billingDomainService,
                attachmentRepository,
                artifactRepository,
                followupEditUsageRepository,
                new ObjectMapper());
        ReflectionTestUtils.setField(service, "signTtlSeconds", 3600L);
    }

    @Test
    void assertAssignmentOutputAllowedRejectsPptForFree() {
        when(billingDomainService.getEffectivePlanOrFree("free_user"))
                .thenReturn(freePlan());

        assertThrows(BusinessException.class, () -> service.assertAssignmentOutputAllowed(
                "free_user",
                Map.of("deliverable_count", Map.of("markdown", 0, "ppt", 1, "code", 0))));
    }

    @Test
    void assertCanReserveUserUploadRejectsWhenLimitReached() {
        when(billingDomainService.getEffectivePlanOrFree("free_user"))
                .thenReturn(freePlan());
        when(attachmentRepository.countActiveUserUploadsForConversation(eq(74L), any(LocalDateTime.class)))
                .thenReturn(3L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.assertCanReserveUserUpload("free_user", 74L));

        assertEquals(ApiCode.FILE_LIMIT_REACHED.getCode(), ex.getCode());
    }

    @Test
    void reserveFollowupEditIsIdempotentForSameUserMessageId() {
        FollowupEditUsage existing = FollowupEditUsage.builder()
                .id(1L)
                .conversationId(74L)
                .assignmentSessionId(500L)
                .clerkUserId("u1")
                .userMessageId(901L)
                .state(FollowupEditUsage.STATE_RESERVED)
                .build();
        when(followupEditUsageRepository.findByUserMessageId(901L)).thenReturn(existing);

        FollowupEditUsage result = service.reserveFollowupEdit("u1", 74L, 901L, List.of("art_1"));

        assertEquals(existing.getId(), result.getId());
    }

    @Test
    void reserveFollowupEditRejectsCrossAssignmentArtifactMix() {
        when(followupEditUsageRepository.findByUserMessageId(901L)).thenReturn(null);
        when(artifactRepository.findByUids(List.of("art_a", "art_b"))).thenReturn(List.of(
                VerlaArtifact.builder().artifactUid("art_a").sessionId(700L).build(),
                VerlaArtifact.builder().artifactUid("art_b").sessionId(701L).build()
        ));

        assertThrows(BusinessException.class,
                () -> service.reserveFollowupEdit("u1", 74L, 901L, List.of("art_a", "art_b")));
    }

    @Test
    void reserveFollowupEditPersistsNewReservation() {
        when(followupEditUsageRepository.findByUserMessageId(901L)).thenReturn(null);
        when(artifactRepository.findByUids(List.of("art_1"))).thenReturn(List.of(
                VerlaArtifact.builder().artifactUid("art_1").sessionId(700L).build()
        ));
        when(followupEditUsageRepository.countActiveByAssignmentSessionId(700L)).thenReturn(0L);
        when(billingDomainService.getEffectivePlanOrFree("u1")).thenReturn(
                BillingPlan.builder()
                        .planCode("plus_monthly")
                        .tier("plus")
                        .maxFollowupEdits(10)
                        .allowedOutputTypes("[\"writing\",\"ppt\",\"coding\"]")
                        .build());
        when(followupEditUsageRepository.save(any(FollowupEditUsage.class))).thenAnswer(invocation -> {
            FollowupEditUsage usage = invocation.getArgument(0);
            usage.setId(11L);
            return usage;
        });

        FollowupEditUsage result = service.reserveFollowupEdit("u1", 74L, 901L, List.of("art_1"));

        assertEquals(11L, result.getId());
        assertEquals(FollowupEditUsage.STATE_RESERVED, result.getState());
        verify(followupEditUsageRepository).save(any(FollowupEditUsage.class));
    }

    private BillingPlan freePlan() {
        return BillingPlan.freePlan();
    }
}
