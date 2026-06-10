package com.studyagent.service.application.verla;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.verla.enums.VerlaAgentEventType;
import com.studyagent.service.domain.verla.WorkforceTaskProgressSnapshot;
import com.studyagent.service.domain.verla.VerlaEventInbox;
import com.studyagent.service.domain.verla.VerlaWorkforceTask;
import com.studyagent.service.domain.verla.repo.VerlaEventInboxRepository;
import com.studyagent.service.domain.verla.repo.VerlaWorkforceTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class AssignmentRuntimeProgressEstimatorTest {

    private FakeEventInboxRepository eventInboxRepository;
    private FakeWorkforceTaskRepository workforceTaskRepository;
    private AssignmentRuntimeProgressEstimator estimator;

    @BeforeEach
    void setUp() {
        eventInboxRepository = new FakeEventInboxRepository();
        workforceTaskRepository = new FakeWorkforceTaskRepository();
        estimator = new AssignmentRuntimeProgressEstimator(
                new ObjectMapper(), eventInboxRepository, workforceTaskRepository);
    }

    @Test
    void estimateFromAgentNodes_usesCompletedAndRunningWeights() {
        List<Map<String, Object>> nodes = List.of(
                node("assignment-plan", "completed"),
                node("draft-writer", "running"),
                node("quality-check", "queued"));

        var estimate = estimator.estimateFromAgentNodes(nodes, LocalDateTime.now().minusSeconds(30));

        assertEquals("draft-writer", estimate.label());
        assertEquals(50.0, estimate.completePercent(), 0.01);
        assertEquals(600, estimate.estimatedRemainingSeconds());
    }

    @Test
    void estimateFromWorkforceSnapshot_usesV1TwoPhaseFormulaForSubtasks() {
        WorkforceTaskProgressSnapshot workforce = new WorkforceTaskProgressSnapshot(5, 2, 1, 10, null);

        var estimate = estimator.estimateFromWorkforceSnapshot(
                workforce,
                List.of(),
                LocalDateTime.now().minusMinutes(5),
                100L);

        assertEquals(25.0, estimate.completePercent(), 0.01);
        assertEquals(900, estimate.estimatedRemainingSeconds());
        assertEquals(2, estimate.completedTaskCount());
        assertEquals(5, estimate.totalTaskCount());
    }

    @Test
    void estimateFromWorkforceSnapshot_prefersEventTaskCountsOverStaleDbAggregate() {
        // DB 聚合显示 5 任务完成 2，但事件折叠显示 3 任务完成 2 运行 1 —— 事件优先生效。
        WorkforceTaskProgressSnapshot workforce = new WorkforceTaskProgressSnapshot(5, 2, 1, null, null);
        List<VerlaEventInbox> events = List.of(
                event(4L, 100L, VerlaAgentEventType.ASSIGNMENT_AGENT_NODE_UPDATED,
                        "{\"payload\":{\"node\":{\"id\":\"task-1\",\"nodeType\":\"task\",\"status\":\"completed\"}}}"),
                event(3L, 100L, VerlaAgentEventType.ASSIGNMENT_AGENT_NODE_UPDATED,
                        "{\"payload\":{\"node\":{\"id\":\"task-2\",\"nodeType\":\"task\",\"status\":\"completed\"}}}"),
                event(2L, 100L, VerlaAgentEventType.ASSIGNMENT_AGENT_NODE_UPDATED,
                        "{\"payload\":{\"node\":{\"id\":\"task-3\",\"nodeType\":\"task\",\"status\":\"running\"}}}"),
                event(1L, 100L, VerlaAgentEventType.ASSIGNMENT_AGENT_FLOW_STARTED,
                        "{\"payload\":{\"stage\":\"assignment_run\"}}"));

        var estimate = estimator.estimateFromWorkforceSnapshot(
                workforce, events, LocalDateTime.now().minusMinutes(5), 100L);

        // (2 + 0.5) / 3 * 50% = 41.67%，来自事件而非 DB 的 25%。
        assertEquals(41.67, estimate.completePercent(), 0.01);
        assertEquals(700, estimate.estimatedRemainingSeconds());
        assertEquals(3, estimate.totalTaskCount());
        assertEquals(2, estimate.completedTaskCount());
    }

    @Test
    void resolveProgress_entersWorkforcePhaseFromEventsWhenDbAggregateIsEmpty() {
        // verla_workforce_tasks 尚未写入（snapshot 为空），但事件已有 task 节点 —— 直接进入 workforce 阶段。
        List<VerlaEventInbox> events = List.of(
                event(3L, 100L, VerlaAgentEventType.ASSIGNMENT_AGENT_NODE_UPDATED,
                        "{\"payload\":{\"node\":{\"id\":\"task-1\",\"nodeType\":\"task\",\"status\":\"completed\"}}}"),
                event(2L, 100L, VerlaAgentEventType.ASSIGNMENT_AGENT_NODE_UPDATED,
                        "{\"payload\":{\"node\":{\"id\":\"task-2\",\"nodeType\":\"task\",\"status\":\"running\"}}}"),
                event(1L, 100L, VerlaAgentEventType.ASSIGNMENT_AGENT_FLOW_STARTED,
                        "{\"payload\":{\"stage\":\"assignment_run\"}}"));

        Map<String, Object> progress = estimator.resolveProgress(events);

        assertNotNull(progress);
        // (1 + 0.5) / 2 * 50% = 37.5% —— 证明未走 plan-only 阶段。
        assertEquals(37.5, progress.get("completePercent"));
        assertEquals(750, progress.get("estimatedRemainingSeconds"));
        assertEquals(2, progress.get("totalTaskCount"));
        assertEquals(1, progress.get("completedTaskCount"));
    }

    @Test
    void estimateFromWorkforceSnapshot_usesComposeRoundForSecondPhase() {
        WorkforceTaskProgressSnapshot workforce = new WorkforceTaskProgressSnapshot(5, 5, 0, 10, null);
        List<VerlaEventInbox> events = List.of(
                event(2L, 100L, VerlaAgentEventType.ASSIGNMENT_AGENT_NODE_UPDATED,
                        "{\"payload\":{\"node\":{\"id\":\"compose\",\"title\":\"Composing part 3/10\",\"status\":\"RUNNING\"}}}"),
                event(1L, 100L, VerlaAgentEventType.ASSIGNMENT_AGENT_FLOW_STARTED,
                        "{\"payload\":{\"stage\":\"assignment_run\"}}"));

        var estimate = estimator.estimateFromWorkforceSnapshot(
                workforce, events, LocalDateTime.now().minusMinutes(5), 100L);

        assertEquals(65.0, estimate.completePercent(), 0.01);
        assertEquals(420, estimate.estimatedRemainingSeconds());
        assertEquals("Composing part 3/10", estimate.label());
        assertEquals(3, estimate.composeCurrentRound());
        assertEquals(10, estimate.composeTotalRounds());
    }

    @Test
    void estimateFromWorkforceSnapshot_keepsComposeRoundAfterNodeFlipsToTaskOnCompletion() {
        // 回归：compose 完成时 Python 把同一 node id (task-1.6) 的 nodeType 从 "compose" 翻成
        // "task"，且 completed 事件不再带 composeCurrentRound。DB 聚合同样丢了当前轮（compose 行
        // 被 task 行覆盖 → composeCurrentRound=null）。修复前当前轮解析成 0，进度回落到 50% 相位
        // 地板、剩余时间从接近 0 跳回 600s（10min）。修复后应读到折叠节点残留的 composeCurrentRound=9。
        WorkforceTaskProgressSnapshot workforce = new WorkforceTaskProgressSnapshot(1, 1, 0, 9, null);
        List<VerlaEventInbox> events = List.of(
                event(4L, 100L, VerlaAgentEventType.ASSIGNMENT_AGENT_NODE_UPDATED,
                        "{\"payload\":{\"node\":{\"id\":\"task-1.6\",\"nodeType\":\"task\",\"status\":\"completed\"}}}"),
                event(3L, 100L, VerlaAgentEventType.ASSIGNMENT_AGENT_NODE_UPDATED,
                        "{\"payload\":{\"node\":{\"id\":\"task-1.6\",\"nodeType\":\"compose\",\"status\":\"running\","
                                + "\"composeCurrentRound\":9,\"composeTotalRounds\":9}}}"),
                event(2L, 100L, VerlaAgentEventType.ASSIGNMENT_AGENT_NODE_UPDATED,
                        "{\"payload\":{\"node\":{\"id\":\"task-1.6\",\"nodeType\":\"compose\",\"status\":\"running\","
                                + "\"composeCurrentRound\":5,\"composeTotalRounds\":9}}}"),
                event(1L, 100L, VerlaAgentEventType.ASSIGNMENT_AGENT_FLOW_STARTED,
                        "{\"payload\":{\"stage\":\"assignment_run\"}}"));

        var estimate = estimator.estimateFromWorkforceSnapshot(
                workforce, events, LocalDateTime.now().minusMinutes(5), 100L);

        // 50% + (9/9) * 50% = 100% → 剩余 0s，而不是卡在 50% / 600s。
        assertEquals(100.0, estimate.completePercent(), 0.01);
        assertEquals(0, estimate.estimatedRemainingSeconds());
        assertEquals(9, estimate.composeCurrentRound());
        assertEquals(9, estimate.composeTotalRounds());
    }

    @Test
    void resolveProgress_prefersWorkforceAggregateOverAgentNodes() {
        workforceTaskRepository.snapshotBySession.put(100L, new WorkforceTaskProgressSnapshot(5, 2, 1, 10, null));
        List<VerlaEventInbox> events = List.of(
                event(2L, 100L, VerlaAgentEventType.ASSIGNMENT_AGENT_NODE_UPDATED,
                        "{\"payload\":{\"node\":{\"id\":\"assignment-plan\",\"title\":\"Make plan\",\"status\":\"RUNNING\"}}}"),
                event(1L, 100L, VerlaAgentEventType.ASSIGNMENT_AGENT_FLOW_STARTED,
                        "{\"payload\":{\"stage\":\"assignment_run\"}}"));

        Map<String, Object> progress = estimator.resolveProgress(events);

        assertNotNull(progress);
        assertEquals(25.0, progress.get("completePercent"));
        assertEquals(900, progress.get("estimatedRemainingSeconds"));
        assertEquals(2, progress.get("completedTaskCount"));
        assertEquals(5, progress.get("totalTaskCount"));
    }

    @Test
    void resolveProgress_prefersExplicitPythonEtaOverComputedValue() {
        workforceTaskRepository.snapshotBySession.put(100L, new WorkforceTaskProgressSnapshot(5, 2, 1, 10, null));
        List<VerlaEventInbox> events = List.of(
                event(2L, 100L, VerlaAgentEventType.AGENT_PROGRESS,
                        "{\"payload\":{\"progress\":{\"label\":\"Planning\",\"estimatedRemainingSeconds\":960}}}"),
                event(1L, 100L, VerlaAgentEventType.ASSIGNMENT_AGENT_FLOW_STARTED,
                        "{\"payload\":{\"stage\":\"assignment_run\"}}"));

        Map<String, Object> progress = estimator.resolveProgress(events);

        assertEquals("Planning", progress.get("label"));
        assertEquals(960, progress.get("estimatedRemainingSeconds"));
    }

    @Test
    void estimateFromPlanPhase_countsDownMonotonicallyOnTotalAxis() {
        // Plan 阶段映射到 20min 总轴的 warm-up（0→10%），倒计时从 1200s 单调下降，
        // 与后续 workforce 阶段衔接处不回弹。
        var atStart = estimator.estimateFromPlanPhase(
                LocalDateTime.now(), List.of(node("assignment-plan", "running")));
        assertEquals(1200, atStart.estimatedRemainingSeconds());
        assertEquals(0.0, atStart.completePercent(), 0.01);

        var halfway = estimator.estimateFromPlanPhase(
                LocalDateTime.now().minusSeconds(60),
                List.of(node("assignment-plan", "running")));
        assertEquals(1140, halfway.estimatedRemainingSeconds());
        assertEquals(5.0, halfway.completePercent(), 0.01);

        // 超过 2 分钟窗口后 warm-up 封顶 10%，倒计时停在 1080s（≥ workforce 起点，不回弹）。
        var afterWindow = estimator.estimateFromPlanPhase(
                LocalDateTime.now().minusSeconds(180),
                List.of(node("assignment-plan", "running")));
        assertEquals(1080, afterWindow.estimatedRemainingSeconds());
        assertEquals(10.0, afterWindow.completePercent(), 0.01);
    }

    @Test
    void resolveProgress_usesPlanPhaseBeforeTaskRowsExist() {
        List<VerlaEventInbox> events = List.of(
                eventAt(2L, 100L, LocalDateTime.now().minusSeconds(30),
                        VerlaAgentEventType.ASSIGNMENT_AGENT_NODE_UPDATED,
                        "{\"payload\":{\"node\":{\"id\":\"assignment-plan\",\"title\":\"Make plan\",\"status\":\"RUNNING\"}}}"),
                eventAt(1L, 100L, LocalDateTime.now().minusSeconds(30),
                        VerlaAgentEventType.ASSIGNMENT_AGENT_FLOW_STARTED,
                        "{\"payload\":{\"stage\":\"assignment_run\"}}}"));

        Map<String, Object> progress = estimator.resolveProgress(events);

        assertNotNull(progress);
        assertEquals("Make plan", progress.get("label"));
        assertEquals(1170, progress.get("estimatedRemainingSeconds"));
        assertNull(progress.get("totalTaskCount"));
    }

    @Test
    void resolveProgress_ignoresStaleExplicitEtaWhenLatestEventHasNoEta() {
        workforceTaskRepository.snapshotBySession.put(100L, new WorkforceTaskProgressSnapshot(5, 2, 1, 10, null));
        List<VerlaEventInbox> events = List.of(
                event(3L, 100L, VerlaAgentEventType.ASSIGNMENT_AGENT_NODE_UPDATED,
                        "{\"payload\":{\"node\":{\"id\":\"task-1\",\"taskName\":\"Research\",\"status\":\"running\"}}}"),
                event(2L, 100L, VerlaAgentEventType.AGENT_PROGRESS,
                        "{\"payload\":{\"progress\":{\"label\":\"Generating assignment\",\"estimatedRemainingSeconds\":720}}}"),
                event(1L, 100L, VerlaAgentEventType.ASSIGNMENT_AGENT_FLOW_STARTED,
                        "{\"payload\":{\"stage\":\"assignment_run\"}}"));

        Map<String, Object> progress = estimator.resolveProgress(events);

        assertNotNull(progress);
        assertEquals(25.0, progress.get("completePercent"));
        assertEquals(900, progress.get("estimatedRemainingSeconds"));
    }

    @Test
    void estimateFromWorkforceSnapshot_prefersComposeTitleTotalOverStalePlanCount() {
        WorkforceTaskProgressSnapshot workforce = new WorkforceTaskProgressSnapshot(5, 5, 0, 5, null);
        List<VerlaEventInbox> events = List.of(
                event(2L, 100L, VerlaAgentEventType.ASSIGNMENT_AGENT_NODE_UPDATED,
                        "{\"payload\":{\"node\":{\"id\":\"problem-solving-expert\",\"title\":\"Composing part 3/10\",\"status\":\"RUNNING\"}}}"),
                event(1L, 100L, VerlaAgentEventType.ASSIGNMENT_AGENT_FLOW_STARTED,
                        "{\"payload\":{\"stage\":\"assignment_run\"}}"));

        var estimate = estimator.estimateFromWorkforceSnapshot(
                workforce, events, LocalDateTime.now().minusMinutes(5), 100L);

        assertEquals(65.0, estimate.completePercent(), 0.01);
        assertEquals(420, estimate.estimatedRemainingSeconds());
        assertEquals(3, estimate.composeCurrentRound());
        assertEquals(10, estimate.composeTotalRounds());
    }

    @Test
    void resolveProgress_computesEtaWhenPythonOmitsIt() {
        List<VerlaEventInbox> events = List.of(
                eventAt(2L, 100L, LocalDateTime.now().minusSeconds(30),
                        VerlaAgentEventType.ASSIGNMENT_AGENT_NODE_UPDATED,
                        "{\"payload\":{\"node\":{\"id\":\"assignment-plan\",\"title\":\"Make plan\",\"status\":\"RUNNING\"}}}"),
                eventAt(1L, 100L, LocalDateTime.now().minusSeconds(30),
                        VerlaAgentEventType.ASSIGNMENT_AGENT_FLOW_STARTED,
                        "{\"payload\":{\"stage\":\"assignment_run\"}}}"));

        Map<String, Object> progress = estimator.resolveProgress(events);

        assertNotNull(progress);
        assertEquals("Make plan", progress.get("label"));
        assertEquals(1170, progress.get("estimatedRemainingSeconds"));
    }

    @Test
    void resolveProgress_clearsEtaOnTerminalCompletion() {
        List<VerlaEventInbox> events = List.of(
                event(2L, null, VerlaAgentEventType.ASSIGNMENT_AGENT_FLOW_COMPLETED,
                        "{\"payload\":{\"summary\":\"done\"}}"),
                event(1L, null, VerlaAgentEventType.AGENT_PROGRESS,
                        "{\"payload\":{\"progress\":{\"label\":\"QA\",\"estimatedRemainingSeconds\":120}}}"));

        Map<String, Object> progress = estimator.resolveProgress(events);

        assertEquals("Assignment ready", progress.get("label"));
        assertEquals(0, progress.get("estimatedRemainingSeconds"));
    }

    @Test
    void enrichAssignmentRunPayload_addsComputedProgressForSse() {
        LocalDateTime startedAt = LocalDateTime.now().minusSeconds(20);
        eventInboxRepository.add(10L, eventAt(
                1L,
                null,
                startedAt,
                VerlaAgentEventType.ASSIGNMENT_AGENT_FLOW_STARTED,
                "{\"payload\":{\"stage\":\"assignment_run\"}}"));

        Map<String, Object> payload = Map.of(
                "node", Map.of("id", "assignment-plan", "title", "Make plan", "status", "RUNNING"));
        VerlaEventInbox current = eventAt(
                2L,
                null,
                startedAt,
                VerlaAgentEventType.ASSIGNMENT_AGENT_NODE_UPDATED,
                "{\"payload\":{\"node\":{\"id\":\"assignment-plan\",\"title\":\"Make plan\",\"status\":\"RUNNING\"}}}");

        Map<String, Object> enriched = estimator.enrichAssignmentRunPayload(
                current.getEventType(), payload, 10L, current);

        assertNotNull(enriched.get("progress"));
        @SuppressWarnings("unchecked")
        Map<String, Object> progress = (Map<String, Object>) enriched.get("progress");
        assertEquals("Make plan", progress.get("label"));
        assertEquals(1180, progress.get("estimatedRemainingSeconds"));
    }

    @Test
    void resolveProgress_returnsNullWhenRunHasNotStarted() {
        assertNull(estimator.resolveProgress(List.of(
                event(1L, null, VerlaAgentEventType.ASSIGNMENT_CLARIFY_COMPLETED,
                        "{\"payload\":{\"isReadyForGeneration\":true}}"))));
    }

    private static Map<String, Object> node(String id, String status) {
        Map<String, Object> node = new HashMap<>();
        node.put("id", id);
        node.put("title", id);
        node.put("status", status);
        return node;
    }

    private static VerlaEventInbox event(Long id, Long sessionId, VerlaAgentEventType type, String payloadJson) {
        return eventAt(id, sessionId, LocalDateTime.now().minusMinutes(5), type, payloadJson);
    }

    private static VerlaEventInbox eventAt(
            Long id,
            Long sessionId,
            LocalDateTime receivedAt,
            VerlaAgentEventType type,
            String payloadJson) {
        return VerlaEventInbox.builder()
                .id(id)
                .conversationId(10L)
                .sessionId(sessionId)
                .eventType(type.name())
                .payloadJson(payloadJson)
                .receivedAt(receivedAt)
                .status(VerlaEventInbox.STATUS_PROCESSED)
                .build();
    }

    private static class FakeEventInboxRepository implements VerlaEventInboxRepository {
        private final Map<Long, List<VerlaEventInbox>> eventsByConversation = new HashMap<>();

        void add(Long conversationId, VerlaEventInbox event) {
            eventsByConversation.computeIfAbsent(conversationId, key -> new ArrayList<>()).add(event);
        }

        @Override
        public boolean tryInsert(VerlaEventInbox row) {
            return false;
        }

        @Override
        public VerlaEventInbox findByMessageId(String messageId) {
            return null;
        }

        @Override
        public VerlaEventInbox findReady(Long sessionId, Long expectedSeq) {
            return null;
        }

        @Override
        public int markProcessed(Long id) {
            return 0;
        }

        @Override
        public int markSkipped(Long id, String reason) {
            return 0;
        }

        @Override
        public int markFailed(Long id, String reason) {
            return 0;
        }

        @Override
        public List<Long> findStuckSessions(int limit) {
            return List.of();
        }

        @Override
        public List<VerlaEventInbox> findReplayByConversation(Long conversationId, Long afterId, int limit) {
            return List.of();
        }

        @Override
        public List<VerlaEventInbox> findRecentProcessedByConversation(Long conversationId, int limit) {
            return eventsByConversation.getOrDefault(conversationId, List.of())
                    .stream()
                    .sorted((a, b) -> Long.compare(b.getId(), a.getId()))
                    .limit(limit)
                    .toList();
        }

        @Override
        public VerlaEventInbox findLatestProcessedBySession(Long sessionId) {
            return null;
        }
    }

    private static class FakeWorkforceTaskRepository implements VerlaWorkforceTaskRepository {
        private final Map<Long, WorkforceTaskProgressSnapshot> snapshotBySession = new HashMap<>();
        private final Map<Long, List<VerlaWorkforceTask>> tasksBySession = new HashMap<>();

        @Override
        public Optional<VerlaWorkforceTask> findBySessionAndNode(Long sessionId, String nodeId) {
            return Optional.empty();
        }

        @Override
        public List<VerlaWorkforceTask> listBySession(Long sessionId) {
            return tasksBySession.getOrDefault(sessionId, List.of());
        }

        @Override
        public List<VerlaWorkforceTask> listByConversation(Long conversationId) {
            return List.of();
        }

        @Override
        public WorkforceTaskProgressSnapshot aggregateProgressBySession(Long sessionId) {
            return snapshotBySession.getOrDefault(sessionId, WorkforceTaskProgressSnapshot.empty());
        }

        @Override
        public VerlaWorkforceTask upsertBySessionNode(VerlaWorkforceTask patch) {
            return patch;
        }
    }
}
