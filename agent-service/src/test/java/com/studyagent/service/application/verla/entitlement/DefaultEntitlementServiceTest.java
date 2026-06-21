package com.studyagent.service.application.verla.entitlement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.api.ApiCode;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.common.exception.CommercialBlockData;
import com.studyagent.service.domain.billing.BillingDomainService;
import com.studyagent.service.domain.billing.BillingPlan;
import com.studyagent.service.domain.verla.FollowupEditUsage;
import com.studyagent.service.domain.verla.VerlaArtifact;
import com.studyagent.service.domain.verla.repo.FollowupEditUsageRepository;
import com.studyagent.service.domain.verla.repo.VerlaArtifactRepository;
import com.studyagent.service.domain.verla.repo.VerlaAttachmentRepository;
import com.studyagent.service.domain.verla.repo.VerlaSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    @Mock
    private VerlaSessionRepository sessionRepository;

    private DefaultEntitlementService service;

    @BeforeEach
    void setUp() {
        service = new DefaultEntitlementService(
                billingDomainService,
                attachmentRepository,
                artifactRepository,
                followupEditUsageRepository,
                sessionRepository,
                new ObjectMapper());
        ReflectionTestUtils.setField(service, "signTtlSeconds", 3600L);
    }

    @Test
    void assertAssignmentOutputAllowedRejectsPptForFree() {
        when(billingDomainService.getEffectivePlanOrFree("free_user"))
                .thenReturn(freePlan());

        BusinessException ex = assertThrows(BusinessException.class, () -> service.assertAssignmentOutputAllowed(
                "free_user",
                Map.of("deliverable_count", Map.of("markdown", 0, "ppt", 1, "code", 0))));

        CommercialBlockData data = assertInstanceOf(CommercialBlockData.class, ex.getData());
        assertEquals(List.of("ppt"), data.getUnsupportedOutputTypes());
        assertEquals("free", data.getCurrentPlan().getTier());
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
        CommercialBlockData data = assertInstanceOf(CommercialBlockData.class, ex.getData());
        assertEquals("file_limit_reached", data.getReasonCode());
        assertEquals(Integer.valueOf(3), data.getMaxFiles());
        assertEquals(Long.valueOf(3L), data.getActiveFiles());
    }

    @Test
    void reserveFollowupEditReturnsStructuredDataWhenLimitReached() {
        when(followupEditUsageRepository.findByUserMessageId(901L)).thenReturn(null);
        when(artifactRepository.findByUids(List.of("art_1"))).thenReturn(List.of(
                VerlaArtifact.builder().artifactUid("art_1").sessionId(700L).build()
        ));
        when(sessionRepository.findByIdForUpdate(700L)).thenReturn(null);
        when(followupEditUsageRepository.countActiveByAssignmentSessionId(700L)).thenReturn(3L);
        when(billingDomainService.getEffectivePlanOrFree("free_user")).thenReturn(freePlan());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reserveFollowupEdit("free_user", 74L, 901L, List.of("art_1")));

        assertEquals(ApiCode.FOLLOWUP_EDIT_LIMIT_REACHED.getCode(), ex.getCode());
        CommercialBlockData data = assertInstanceOf(CommercialBlockData.class, ex.getData());
        assertEquals("followup_edit_limit_reached", data.getReasonCode());
        assertEquals(Integer.valueOf(3), data.getMaxFollowupEdits());
        assertEquals(Long.valueOf(3L), data.getUsedFollowupEdits());
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
        when(sessionRepository.findByIdForUpdate(700L)).thenReturn(null);
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

    @Test
    void reserveFollowupEdit_blocksConcurrentRequestsThatWouldExceedLimit() throws Exception {
        Semaphore assignmentLock = new Semaphore(1);
        CountDownLatch firstLockAcquired = new CountDownLatch(1);
        InMemoryFollowupEditUsageRepository fakeRepository = new InMemoryFollowupEditUsageRepository();
        VerlaSessionRepository lockingSessionRepository = org.mockito.Mockito.mock(VerlaSessionRepository.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            assignmentLock.acquireUninterruptibly();
            firstLockAcquired.countDown();
            return null;
        }).when(lockingSessionRepository).findByIdForUpdate(700L);
        DefaultEntitlementService concurrentService = new DefaultEntitlementService(
                billingDomainService,
                attachmentRepository,
                artifactRepository,
                fakeRepository,
                lockingSessionRepository,
                new ObjectMapper());
        ReflectionTestUtils.setField(concurrentService, "signTtlSeconds", 3600L);

        when(artifactRepository.findByUids(List.of("art_1"))).thenReturn(List.of(
                VerlaArtifact.builder().artifactUid("art_1").sessionId(700L).build()
        ));
        when(billingDomainService.getEffectivePlanOrFree("u1")).thenReturn(
                BillingPlan.builder()
                        .planCode("plus_monthly")
                        .tier("plus")
                        .maxFollowupEdits(1)
                        .allowedOutputTypes("[\"writing\",\"ppt\",\"coding\"]")
                        .build());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<FollowupEditUsage> first = executor.submit(reserveTask(concurrentService, 901L));
            assertTrue(firstLockAcquired.await(5, TimeUnit.SECONDS), "first request should acquire assignment lock");
            Future<FollowupEditUsage> second = executor.submit(reserveTask(concurrentService, 902L));

            List<FollowupEditUsage> successes = new ArrayList<>();
            List<Throwable> failures = new ArrayList<>();
            successes.add(first.get(5, TimeUnit.SECONDS));
            assignmentLock.release();
            collectOutcome(second, successes, failures);

            assertEquals(1, successes.size(), "only one follow-up edit should reserve successfully");
            assertEquals(1, failures.size(), "one concurrent request should be rejected");
            assertTrue(failures.get(0) instanceof BusinessException, "concurrent overflow should raise BusinessException");
            assertEquals(ApiCode.FOLLOWUP_EDIT_LIMIT_REACHED.getCode(), ((BusinessException) failures.get(0)).getCode());
        } finally {
            executor.shutdownNow();
        }
    }

    private BillingPlan freePlan() {
        return BillingPlan.freePlan();
    }

    private Callable<FollowupEditUsage> reserveTask(DefaultEntitlementService target, Long userMessageId) {
        return () -> target.reserveFollowupEdit("u1", 74L, userMessageId, List.of("art_1"));
    }

    private void collectOutcome(
            Future<FollowupEditUsage> future,
            List<FollowupEditUsage> successes,
            List<Throwable> failures) throws InterruptedException {
        try {
            successes.add(future.get(5, TimeUnit.SECONDS));
        } catch (ExecutionException ex) {
            failures.add(ex.getCause());
        } catch (java.util.concurrent.TimeoutException ex) {
            failures.add(ex);
        }
    }

    private static final class InMemoryFollowupEditUsageRepository implements FollowupEditUsageRepository {
        private final ConcurrentMap<Long, FollowupEditUsage> byUserMessageId = new ConcurrentHashMap<>();
        private final AtomicLong ids = new AtomicLong(100);

        @Override
        public FollowupEditUsage findByUserMessageId(Long userMessageId) {
            return byUserMessageId.get(userMessageId);
        }

        @Override
        public FollowupEditUsage findByAssignmentChatSessionId(Long assignmentChatSessionId) {
            return byUserMessageId.values().stream()
                    .filter(usage -> assignmentChatSessionId.equals(usage.getAssignmentChatSessionId()))
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public long countActiveByAssignmentSessionId(Long assignmentSessionId) {
            return byUserMessageId.values().stream()
                    .filter(usage -> assignmentSessionId.equals(usage.getAssignmentSessionId()))
                    .filter(usage -> FollowupEditUsage.STATE_RESERVED.equals(usage.getState())
                            || FollowupEditUsage.STATE_COMPLETED.equals(usage.getState()))
                    .count();
        }

        @Override
        public FollowupEditUsage save(FollowupEditUsage usage) {
            usage.setId(ids.incrementAndGet());
            byUserMessageId.put(usage.getUserMessageId(), usage);
            return usage;
        }

        @Override
        public FollowupEditUsage updateState(Long userMessageId, String state, Long assignmentChatSessionId, String releaseReason) {
            FollowupEditUsage existing = byUserMessageId.get(userMessageId);
            if (existing == null) {
                return null;
            }
            existing.setState(state);
            existing.setAssignmentChatSessionId(assignmentChatSessionId);
            existing.setReleaseReason(releaseReason);
            return existing;
        }
    }
}
