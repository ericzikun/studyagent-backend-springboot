package com.studyagent.service.application.verla;

import com.studyagent.common.verla.enums.VerlaAttachmentStatus;
import com.studyagent.service.domain.verla.VerlaAttachment;
import com.studyagent.service.domain.verla.repo.VerlaAttachmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Waits outside a transaction until this turn's uploaded attachments finish parsing. */
@Slf4j
@Component
@RequiredArgsConstructor
public class VerlaAttachmentParseGate {

    private static final long POLL_INTERVAL_MILLIS = 200L;

    private final VerlaAttachmentRepository attachmentRepository;

    /**
     * @return true when released by terminal statuses, false when the bounded wait timed out.
     *         A timeout deliberately releases the caller; parsing continues in the background.
     */
    public boolean awaitRelease(Long turnId, List<String> objectIds, Duration timeout) {
        long started = System.nanoTime();
        long timeoutNanos = Math.max(0L, timeout == null ? 0L : timeout.toNanos());
        long deadline = started + timeoutNanos;
        List<String> ids = objectIds == null ? List.of() : objectIds;
        int pending = 0;
        String released = "immediate";
        boolean firstPoll = true;
        while (true) {
            List<VerlaAttachment> attachments = loadAttachments(turnId, ids);
            pending = (int) attachments.stream()
                    .filter(a -> !VerlaAttachmentStatus.fromCode(a.getStatus()).isTerminal())
                    .count();
            if (pending == 0) {
                released = firstPoll ? "immediate" : "parsed";
                log.info("[Verla] attachment parse gate turnId={}, waitedMs={}, pendingCount={}, released={}",
                        turnId, elapsedMillis(started), pending, released);
                return true;
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) {
                released = "timeout";
                log.info("[Verla] attachment parse gate turnId={}, waitedMs={}, pendingCount={}, released={}",
                        turnId, elapsedMillis(started), pending, released);
                return false;
            }
            try {
                Thread.sleep(Math.min(POLL_INTERVAL_MILLIS,
                        Math.max(1L, remaining / 1_000_000L)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("[Verla] attachment parse gate turnId={}, waitedMs={}, pendingCount={}, released=timeout",
                        turnId, elapsedMillis(started), pending);
                return false;
            }
            firstPoll = false;
        }
    }

    private List<VerlaAttachment> loadAttachments(Long turnId, List<String> objectIds) {
        Map<String, VerlaAttachment> merged = new LinkedHashMap<>();
        if (turnId != null) {
            for (VerlaAttachment a : safe(attachmentRepository.listByTurn(turnId))) {
                if (a != null) {
                    merged.put(a.getObjectId() == null ? "id:" + a.getId() : a.getObjectId(), a);
                }
            }
        }
        List<String> ids = objectIds.stream().filter(id -> id != null && !id.isBlank()).distinct().toList();
        if (!ids.isEmpty()) {
            for (VerlaAttachment a : safe(attachmentRepository.findByObjectIds(ids))) {
                if (a != null) {
                    merged.put(a.getObjectId() == null ? "id:" + a.getId() : a.getObjectId(), a);
                }
            }
        }
        return new ArrayList<>(merged.values());
    }

    private static List<VerlaAttachment> safe(List<VerlaAttachment> attachments) {
        return attachments == null ? List.of() : attachments;
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }
}
