package com.studyagent.service.application.verla;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.studyagent.service.application.MqOutboxService;
import com.studyagent.service.domain.mq.MqOutbox;
import com.studyagent.service.domain.mq.MqOutboxRepository;
import com.studyagent.service.domain.verla.VerlaArtifact;
import com.studyagent.service.domain.verla.repo.VerlaArtifactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerlaSlidesConvertCommandServiceTest {

    private FakeMqOutboxRepository mqOutboxRepository;
    private FakeArtifactRepository artifactRepository;
    private VerlaSlidesConvertCommandService service;

    @BeforeEach
    void setup() {
        mqOutboxRepository = new FakeMqOutboxRepository();
        artifactRepository = new FakeArtifactRepository();
        MqOutboxService mqOutboxService = new MqOutboxService(
                mqOutboxRepository,
                event -> { },
                new ObjectMapper().registerModule(new JavaTimeModule()));
        service = new VerlaSlidesConvertCommandService(mqOutboxService, artifactRepository);
        ReflectionTestUtils.setField(service, "commandExchange", "studyagent.command");
    }

    @Test
    void triggerIfNeeded_queues_convert_command_for_source_artifact() {
        VerlaArtifact source = VerlaArtifact.builder()
                .artifactUid("artifact_11_22_33_assignment_slides_pptxgenjs")
                .conversationId(11L)
                .turnId(22L)
                .sessionId(33L)
                .kind("assignment_slides_pptxgenjs")
                .summary("deck.js")
                .status("READY")
                .build();

        service.triggerIfNeeded(source);

        assertNotNull(mqOutboxRepository.saved);
        assertEquals("cmd.slides.convert_to_editor_json", mqOutboxRepository.saved.getAction());
        assertEquals("cmd.slides.convert_to_editor_json", mqOutboxRepository.saved.getRoutingKey());
        assertTrue(mqOutboxRepository.saved.getPayload().contains("\"sourceArtifactUid\":\"artifact_11_22_33_assignment_slides_pptxgenjs\""));
        assertTrue(mqOutboxRepository.saved.getPayload().contains("\"targetArtifactUid\":\"artifact_11_22_33_assignment_slides_editor_json\""));
        assertTrue(mqOutboxRepository.saved.getPayload().contains("\"sourceKind\":\"assignment_slides_pptxgenjs\""));
        assertTrue(mqOutboxRepository.saved.getPayload().contains("\"sourceFilename\":\"deck.js\""));
    }

    @Test
    void triggerIfNeeded_skips_when_target_artifact_already_exists() {
        VerlaArtifact source = VerlaArtifact.builder()
                .artifactUid("artifact_11_22_33_assignment_slides_pptxgenjs")
                .conversationId(11L)
                .turnId(22L)
                .sessionId(33L)
                .kind("assignment_slides_pptxgenjs")
                .status("READY")
                .build();
        artifactRepository.byUid = VerlaArtifact.builder()
                .artifactUid("artifact_11_22_33_assignment_slides_editor_json")
                .kind("assignment_slides_editor_json")
                .build();

        service.triggerIfNeeded(source);

        assertNull(mqOutboxRepository.saved);
    }

    private static final class FakeArtifactRepository implements VerlaArtifactRepository {
        VerlaArtifact byUid;

        @Override
        public VerlaArtifact findById(Long id) {
            return null;
        }

        @Override
        public VerlaArtifact findByUid(String artifactUid) {
            if (byUid != null && artifactUid.equals(byUid.getArtifactUid())) {
                return byUid;
            }
            return null;
        }

        @Override
        public List<VerlaArtifact> findByConversation(Long conversationId) {
            return List.of();
        }

        @Override
        public List<VerlaArtifact> findBySession(Long sessionId) {
            return List.of();
        }

        @Override
        public List<VerlaArtifact> findByUids(List<String> artifactUids) {
            return List.of();
        }

        @Override
        public VerlaArtifact upsertByUid(VerlaArtifact artifact) {
            return artifact;
        }
    }

    private static final class FakeMqOutboxRepository implements MqOutboxRepository {
        MqOutbox saved;

        @Override
        public MqOutbox save(MqOutbox mqOutbox) {
            this.saved = mqOutbox;
            return mqOutbox;
        }

        @Override
        public MqOutbox findById(Long id) {
            return null;
        }

        @Override
        public MqOutbox findByEventId(String eventId) {
            return null;
        }

        @Override
        public List<MqOutbox> findPendingMessages(int limit, LocalDateTime currentTime) {
            return List.of();
        }

        @Override
        public List<MqOutbox> claimPendingMessages(int limit, String workerId, LocalDateTime currentTime, LocalDateTime leaseUntil) {
            return List.of();
        }

        @Override
        public MqOutbox claimMessage(Long id, String workerId, LocalDateTime currentTime, LocalDateTime leaseUntil) {
            return null;
        }

        @Override
        public void markAsSent(Long id) { }

        @Override
        public void markAsSent(Long id, String workerId) { }

        @Override
        public void markForRetry(Long id, String errorMessage, LocalDateTime nextRetryAt) { }

        @Override
        public void markForRetry(Long id, String workerId, String errorMessage, LocalDateTime nextRetryAt) { }

        @Override
        public void markAsFailed(Long id, String errorMessage) { }

        @Override
        public void markAsFailed(Long id, String workerId, String errorMessage) { }

        @Override
        public void releaseClaim(Long id, String workerId) { }

        @Override
        public int countDeferredAssignmentRunAhead(Long id, LocalDateTime createdAt) {
            return 0;
        }

        @Override
        public int countDeferredCapabilityRunAhead(Long id, String action, LocalDateTime createdAt) {
            return 0;
        }
    }
}
