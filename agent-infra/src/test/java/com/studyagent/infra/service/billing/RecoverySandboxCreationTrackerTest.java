package com.studyagent.infra.service.billing;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RecoverySandboxCreationTrackerTest {
    @Test void lostCreateResponseRetainsExactFrozenRequestAndPreventsCleanupPass() throws Exception {
        List<Map<String, ?>> rows = new ArrayList<>();
        var tracker = new RecoverySandboxCreationTracker("run-1", (phase, row) -> rows.add(row));
        var known = new ArrayList<String>();
        long frozenTime = 1794731234L;
        var request = new LinkedHashMap<String, Object>(Map.of("frozen_time", frozenTime, "name", "run-1"));
        AtomicInteger remoteObjects = new AtomicInteger();
        assertThrows(IOException.class, () -> tracker.<String>create("create-clock", request, (payload, key) -> {
            remoteObjects.incrementAndGet(); // Server commits, but the caller never receives its ID.
            throw new IOException("response lost after remote commit");
        }, known::add));
        assertEquals(1, remoteObjects.get());
        assertTrue(known.isEmpty());
        assertEquals(1, tracker.pending().size(), "An empty known-ID list cannot prove remote cleanup");
        request.put("frozen_time", 1L);
        assertEquals(Map.of("frozen_time", frozenTime, "name", "run-1"), tracker.pending().get(0).get("parameters"));
        assertEquals("run-1:create-clock", tracker.pending().get(0).get("idempotencyKey"));
        assertEquals(tracker.pending().get(0), rows.get(0), "The recoverable request must be durable before the call");
        assertEquals("UNKNOWN", tracker.cleanupResult(0));
        assertEquals("FAIL", tracker.cleanupResult(1));
    }

    @Test void confirmsOnlyAfterOwnershipValidationAndKnownCleanupRegistration() throws Exception {
        List<String> phases = new ArrayList<>();
        List<Map<String, ?>> rows = new ArrayList<>();
        var tracker = new RecoverySandboxCreationTracker("run-2", (phase, row) -> { phases.add(phase); rows.add(row); });
        var known = new ArrayList<String>();
        AtomicInteger calls = new AtomicInteger();
        Map<String, Object> payload = Map.of("product", "prod_owned", "recurring", Map.of("interval", "week"), "unit_amount", 299L);
        assertEquals("price_owned", tracker.create("create-intro-price", payload, (parameters, key) -> {
            assertEquals(payload, parameters);
            assertEquals("run-2:create-intro-price", key);
            assertEquals(List.of("create-intent"), phases, "Intent must precede the network call");
            calls.incrementAndGet();
            return "price_owned";
        }, id -> {
            assertEquals(1, tracker.pending().size(), "A response alone does not establish cleanup ownership");
            known.add(id);
        }));
        assertEquals(List.of("price_owned"), known);
        assertEquals(1, calls.get());
        assertTrue(tracker.pending().isEmpty());
        assertEquals("PASS", tracker.cleanupResult(0));
        assertEquals(payload, rows.get(0).get("parameters"));
        assertEquals(List.of("create-intent", "create-confirmed"), phases);
        assertThrows(IllegalStateException.class, () -> tracker.create("create-intro-price", payload,
                (parameters, key) -> { calls.incrementAndGet(); return "another_price"; }, known::add));
        assertEquals(1, calls.get(), "A duplicate action must not perform another remote creation");
    }

    @Test void failedOwnershipRegistrationKeepsResponsePending() {
        var tracker = new RecoverySandboxCreationTracker("run", (phase, row) -> {});
        assertThrows(AssertionError.class, () -> tracker.create("create-product", Map.of("name", "run"),
                (payload, key) -> "foreign-product", id -> { throw new AssertionError("wrong owner"); }));
        assertEquals(1, tracker.pending().size());
        assertEquals("UNKNOWN", tracker.cleanupResult(0));
    }

    @Test void journalFailurePreventsRemoteMutation() {
        var tracker = new RecoverySandboxCreationTracker("run", (phase, row) -> { throw new IOException("disk unavailable"); });
        AtomicInteger calls = new AtomicInteger();
        assertThrows(IOException.class, () -> tracker.create("create-customer", Map.of("test_clock", "clock_owned"),
                (payload, key) -> { calls.incrementAndGet(); return "customer"; }, id -> {}));
        assertEquals(0, calls.get());
    }
}
