package com.studyagent.infra.service.billing;

import java.util.*;

/** Test-only boundary for journaling remote creation and registering cleanup ownership. */
final class RecoverySandboxCreationTracker {
    @FunctionalInterface interface Journal { void record(String phase, Map<String, ?> values) throws Exception; }
    @FunctionalInterface interface Remote<T> { T create(Map<String, Object> parameters, String key) throws Exception; }
    @FunctionalInterface interface Registration<T> { void register(T resource) throws Exception; }
    private final String run;
    private final Journal journal;
    private final Set<String> attempted = new HashSet<>();
    private final Map<String, Map<String, Object>> pending = new LinkedHashMap<>();

    RecoverySandboxCreationTracker(String run, Journal journal) { this.run = run; this.journal = journal; }

    <T> T create(String action, Map<String, Object> parameters, Remote<T> remote, Registration<T> registration) throws Exception {
        if (!attempted.add(action)) throw new IllegalStateException("Create action already attempted: " + action);
        // Snapshot the actual request, especially dynamic clock time, before any remote side effect.
        Map<String, Object> frozen = freezeMap(parameters);
        String key = run + ":" + action;
        Map<String, Object> intent = Map.of("action", action, "idempotencyKey", key,
                "parameters", frozen, "state", "PENDING");
        pending.put(action, intent);
        journal.record("create-intent", intent);
        T resource = remote.create(frozen, key);
        // Keep pending on a lost response, ownership rejection, or failed cleanup registration.
        registration.register(resource);
        journal.record("create-confirmed", Map.of("action", action, "idempotencyKey", key,
                "parameters", frozen, "state", "CONFIRMED"));
        pending.remove(action);
        return resource;
    }

    List<Map<String, Object>> pending() { return List.copyOf(pending.values()); }

    String cleanupResult(int failedSteps) {
        return failedSteps > 0 ? "FAIL" : pending.isEmpty() ? "PASS" : "UNKNOWN";
    }

    private static Map<String, Object> freezeMap(Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (!(key instanceof String)) throw new IllegalArgumentException("Create parameter keys must be strings");
            copy.put((String) key, freeze(value));
        });
        return Collections.unmodifiableMap(copy);
    }

    private static Object freeze(Object value) {
        if (value instanceof Map<?, ?> map) return freezeMap(map);
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            for (Object element : list) copy.add(freeze(element));
            return Collections.unmodifiableList(copy);
        }
        if (value == null || value instanceof String || value instanceof Long || value instanceof Integer
                || value instanceof Boolean) return value;
        throw new IllegalArgumentException("Unsupported create parameter type: " + value.getClass().getName());
    }
}
