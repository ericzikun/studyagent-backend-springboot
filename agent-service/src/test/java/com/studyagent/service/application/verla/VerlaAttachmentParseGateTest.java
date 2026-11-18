package com.studyagent.service.application.verla;

import com.studyagent.service.domain.verla.VerlaAttachment;
import com.studyagent.service.domain.verla.repo.VerlaAttachmentRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerlaAttachmentParseGateTest {

    @Test
    void waitsForUploadedAndParsingThenReleases() throws Exception {
        for (String status : List.of("UPLOADED", "PARSING")) {
            FakeRepository repo = new FakeRepository(attachment(status));
            VerlaAttachmentParseGate gate = new VerlaAttachmentParseGate(repo);
            Thread updater = new Thread(() -> {
                sleep(260);
                repo.attachment.setStatus("PARSED");
            });
            updater.start();
            long started = System.nanoTime();
            assertTrue(gate.awaitRelease(7L, List.of("att-1"), Duration.ofSeconds(2)));
            assertTrue((System.nanoTime() - started) / 1_000_000L >= 180L);
            updater.join();
        }
    }

    @Test
    void emptyAndTerminalCollectionsReleaseImmediately() {
        VerlaAttachmentParseGate empty = new VerlaAttachmentParseGate(new FakeRepository(null));
        assertTrue(empty.awaitRelease(7L, List.of(), Duration.ofSeconds(1)));

        FakeRepository terminalRepo = new FakeRepository(attachment("PARSED"));
        assertTrue(new VerlaAttachmentParseGate(terminalRepo)
                .awaitRelease(7L, List.of("att-1"), Duration.ofSeconds(1)));
        terminalRepo.attachment.setStatus("FAILED");
        assertTrue(new VerlaAttachmentParseGate(terminalRepo)
                .awaitRelease(7L, List.of("att-1"), Duration.ofSeconds(1)));
    }

    @Test
    void timeoutReleasesWithoutThrowing() {
        FakeRepository repo = new FakeRepository(attachment("PARSING"));
        assertFalse(new VerlaAttachmentParseGate(repo)
                .awaitRelease(7L, List.of("att-1"), Duration.ofMillis(80)));
    }

    private static VerlaAttachment attachment(String status) {
        return VerlaAttachment.builder().id(1L).objectId("att-1").turnId(7L)
                .status(status).createdAt(LocalDateTime.now()).build();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class FakeRepository implements VerlaAttachmentRepository {
        private final VerlaAttachment attachment;

        private FakeRepository(VerlaAttachment attachment) {
            this.attachment = attachment;
        }

        @Override public VerlaAttachment save(VerlaAttachment a) { return a; }
        @Override public VerlaAttachment findById(Long id) { return attachment; }
        @Override public VerlaAttachment findByObjectId(String id) { return attachment; }
        @Override public List<VerlaAttachment> findByObjectIds(List<String> ids) {
            return attachment == null ? List.of() : List.of(attachment);
        }
        @Override public List<VerlaAttachment> listByConversation(Long id, int limit) { return List.of(); }
        @Override public List<VerlaAttachment> listByTurn(Long id) {
            return attachment == null ? List.of() : List.of(attachment);
        }
        @Override public long countActiveUserUploadsForConversation(Long id, LocalDateTime cutoff) { return 0; }
        @Override public VerlaAttachment softDeleteUserUpload(String user, String id) { return null; }
        @Override public VerlaAttachment updateParseProgress(VerlaAttachment patch) { return patch; }
        @Override public VerlaAttachment updateByObjectIdSelective(VerlaAttachment patch) { return patch; }
        @Override public int markStaleUploadedAgentOutputsFailed(LocalDateTime cutoff, int batch, String reason) { return 0; }
    }
}
