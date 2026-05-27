package com.studyagent.infra.mq.dev;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MockPyCommandConsumerTest {

    @Test
    void resolvePlanIntent_ignoresRouterHintForAssignmentDraft() {
        String intent = MockPyCommandConsumer.resolvePlanIntent(
                "写一个1000字关于巴列维王朝的历史作业",
                "router");

        assertThat(intent).isEqualTo("assignment");
    }

    @Test
    void resolvePlanIntent_respectsDedicatedHint() {
        String intent = MockPyCommandConsumer.resolvePlanIntent(
                "写一个1000字关于巴列维王朝的历史作业",
                "summary");

        assertThat(intent).isEqualTo("summary");
    }

    @Test
    void resolvePlanIntent_treatsStreamScenarioPrefixesAsAssignment() {
        assertThat(MockPyCommandConsumer.resolvePlanIntent("fast test high frequency stream", "router"))
                .isEqualTo("assignment");
        assertThat(MockPyCommandConsumer.resolvePlanIntent("mixed test rich markdown stream", "router"))
                .isEqualTo("assignment");
        assertThat(MockPyCommandConsumer.resolvePlanIntent("fasting is unrelated", "router"))
                .isEqualTo("qa");
    }

    @Test
    void resolvePlanIntent_defaultsToQaWhenRouterHintHasNoRecognizedDraft() {
        String intent = MockPyCommandConsumer.resolvePlanIntent(
                "今天有什么安排",
                "router");

        assertThat(intent).isEqualTo("qa");
    }

    @Test
    void resolveAssignmentStreamScenario_onlyAcceptsLeadingCommandWord() {
        assertThat(MockPyCommandConsumer.resolveAssignmentStreamScenario("fast assignment smoothing"))
                .isEqualTo("fast");
        assertThat(MockPyCommandConsumer.resolveAssignmentStreamScenario("  mixed: assignment markdown"))
                .isEqualTo("mixed");
        assertThat(MockPyCommandConsumer.resolveAssignmentStreamScenario("fasting should stay normal"))
                .isEqualTo("default");
        assertThat(MockPyCommandConsumer.resolveAssignmentStreamScenario("normal assignment"))
                .isEqualTo("default");
    }

    @Test
    void assignmentDefaultInitCompletedPayload_stopsAtInitialChoiceMoment() {
        Map<String, Object> payload = MockPyCommandConsumer.assignmentDefaultInitCompletedPayload();

        assertThat(payload).containsEntry("ready", true);
        assertThat(payload).containsEntry("isReadyForGeneration", false);
        assertThat(payload.get("nextActions"))
                .isEqualTo(List.of("deep_understanding", "generation"));
        assertThat(payload.get("requirementUnderstanding"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("nextStep", "choose walkthrough or assignment setup");
    }

    @Test
    void assignmentFastTextChunks_coverHighFrequencyPureTextFixture() {
        var chunks = MockPyCommandConsumer.assignmentFastTextChunks();
        String visibleText = String.join("", chunks);

        assertThat(chunks).hasSizeGreaterThan(80);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.length()).isLessThanOrEqualTo(15));
        assertThat(visibleText).contains("I reviewed your assignment brief");
        assertThat(visibleText).doesNotContain("##");
        assertThat(visibleText).doesNotContain("```");
        assertThat(visibleText).doesNotContain("| Area |");
        assertThat(visibleText).doesNotContain("\n- ");
    }

    @Test
    void assignmentRunVisibleChunks_coverStreamSmoothingFixtures() {
        var chunks = MockPyCommandConsumer.assignmentRunVisibleChunks();
        String visibleText = String.join("", chunks);

        assertThat(chunks).hasSizeGreaterThanOrEqualTo(8);
        assertThat(visibleText).contains("## What I’m checking");
        assertThat(visibleText).contains("| Area | What I will verify |");
        assertThat(visibleText).contains("```text");
        assertThat(MockPyCommandConsumer.assignmentRunStreamDelayMs(0))
                .isEqualTo(1200L);
        assertThat(MockPyCommandConsumer.assignmentRunStreamDelayMs(chunks.size() - 1))
                .isEqualTo(6400L);
    }

    @Test
    void assignmentInitScenarioCompletionDelay_waitsForLastChunk() {
        long delayMs = MockPyCommandConsumer.assignmentInitScenarioCompletionDelay(100, 120, 25, 220);

        assertThat(delayMs).isEqualTo(2815L);
    }

    @Test
    void assignmentGeneratedArtifactBody_matchesRichAssignmentTopic() {
        String body = MockPyCommandConsumer.assignmentGeneratedArtifactBody();

        assertThat(body).contains("# Revise Case Study on Indigenous Australian Business Protocols");
        assertThat(body).contains("| Section | Revision Goal | Evidence Needed |");
        assertThat(body).contains("## Checklist Before Submission");
    }

    @Test
    void assignmentNodeDetailPayload_matchesWorkflowDetailContract() {
        Map<String, Object> payload = MockPyCommandConsumer.assignmentNodeDetailPayload(
                "draft-writer",
                "Draft Writer",
                "Writing",
                "COMPLETED",
                List.of(Map.of(
                        "type", "search_serper",
                        "detailed", List.of(Map.of("name", "Mock source scan")))),
                "Draft Writer completed. Generated the main assignment draft.\n");

        assertThat(payload).containsEntry("id", "draft-writer");
        assertThat(payload).containsEntry("status", "COMPLETED");
        assertThat(payload).containsEntry("taskName", "Draft Writer");
        assertThat(payload).containsEntry("taskAgent", "Writing");
        assertThat(payload.get("startStamp")).isInstanceOf(String.class);
        assertThat(payload.get("contentChunk"))
                .isEqualTo("Draft Writer completed. Generated the main assignment draft.\n");
        assertThat(payload.get("detailChunk")).isEqualTo(List.of(Map.of(
                "type", "search_serper",
                "detailed", List.of(Map.of("name", "Mock source scan")))));
    }

}
