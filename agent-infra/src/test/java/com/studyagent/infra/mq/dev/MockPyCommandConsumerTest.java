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
        assertThat(MockPyCommandConsumer.resolvePlanIntent("code-project test generated folder", "router"))
                .isEqualTo("assignment");
        assertThat(MockPyCommandConsumer.resolvePlanIntent("fasting is unrelated", "router"))
                .isEqualTo("qa");
        assertThat(MockPyCommandConsumer.resolvePlanIntent("mixed test rich markdown stream", "router"))
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
        assertThat(MockPyCommandConsumer.resolveAssignmentStreamScenario("code-project assignment folder"))
                .isEqualTo("code-project");
        assertThat(MockPyCommandConsumer.resolveAssignmentStreamScenario("  code_project: assignment folder"))
                .isEqualTo("code-project");
        assertThat(MockPyCommandConsumer.resolveAssignmentStreamScenario("  mixed: assignment markdown"))
                .isEqualTo("default");
        assertThat(MockPyCommandConsumer.resolveAssignmentStreamScenario("fasting should stay normal"))
                .isEqualTo("default");
        assertThat(MockPyCommandConsumer.resolveAssignmentStreamScenario("normal assignment"))
                .isEqualTo("default");
    }

    @Test
    void resolveAssignmentArtifactScenario_acceptsExplicitAndFormSignals() {
        assertThat(MockPyCommandConsumer.resolveAssignmentArtifactScenario(Map.of(
                "mockScenario", "code-project")))
                .isEqualTo("code-project");
        assertThat(MockPyCommandConsumer.resolveAssignmentArtifactScenario(Map.of(
                "requirementForm", Map.of(
                        "subject", "Coding project",
                        "format", "工程目录"))))
                .isEqualTo("code-project");
        assertThat(MockPyCommandConsumer.resolveAssignmentArtifactScenario(Map.of(
                "requirementForm", Map.of("subject", "History essay"))))
                .isEqualTo("default");
        assertThat(MockPyCommandConsumer.resolveAssignmentArtifactScenario(
                Map.of(),
                "code-project"))
                .isEqualTo("code-project");
        assertThat(MockPyCommandConsumer.resolveAssignmentArtifactScenario(
                Map.of("requirementForm", Map.of("subject", "History essay")),
                "code-project"))
                .isEqualTo("code-project");
    }

    @Test
    void assignmentDefaultInitCompletedPayload_stopsAtInitialChoiceMoment() {
        Map<String, Object> payload = MockPyAssignmentFixtures.defaultInitCompletedPayload();

        assertThat(payload).containsEntry("ready", true);
        assertThat(payload).containsEntry("isReadyForGeneration", false);
        assertThat(payload.get("nextActions"))
                .isEqualTo(List.of("deep_understanding", "generation"));
        assertThat(payload.get("requirementUnderstanding"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("nextStep", "choose walkthrough or assignment setup");
    }

    @Test
    void assignmentCodeProjectInitCompletedPayload_persistsMockScenario() {
        Map<String, Object> payload = MockPyAssignmentFixtures.defaultInitCompletedPayload("code-project");

        assertThat(payload).containsEntry("mockScenario", "code-project");
        assertThat(payload.get("requirementUnderstanding"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("outputType", "code-project")
                .containsEntry("nextStep", "start generation to emit an assignment_code_project fixture");
    }

    @Test
    void buildMockRequirementForm_includesThreeAssignmentTypeOptions() {
        Map<String, Object> form = MockPyAssignmentFixtures.buildRequirementForm();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> schema = (List<Map<String, Object>>) form.get("schema");

        assertThat(schema)
                .anySatisfy(field -> assertThat(field)
                        .containsEntry("key", "assignment_type")
                        .containsEntry("label", "Assignment Type")
                        .containsEntry("type", "select")
                        .containsEntry("defaultValue", "Case Study")
                        .containsEntry("options", List.of("Essay", "Lab Report", "Case Study")));
    }

    @Test
    void assignmentFastTextChunks_coverHighFrequencyPureTextFixture() {
        var chunks = MockPyAssignmentFixtures.fastTextChunks();
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
    void assignmentInitScenarioCompletionDelay_waitsForLastChunk() {
        long delayMs = MockPyAssignmentFixtures.initScenarioCompletionDelay(100, 120, 25, 220);

        assertThat(delayMs).isEqualTo(2815L);
    }

    @Test
    void assignmentThinkingChunks_useRequirementAnalysisCaseFixture() {
        var chunks = MockPyAssignmentFixtures.initThinkingChunks();
        String thinking = String.join("", chunks);

        assertThat(chunks).hasSizeGreaterThanOrEqualTo(10);
        assertThat(thinking)
                .contains("Thinking Process:")
                .contains("Requirement Analysis Agent")
                .contains("deep-research-report.md")
                .contains("Kimi K2.6")
                .contains("no specific assignment task")
                .contains("not specified");
        assertThat(chunks.get(chunks.size() - 1))
                .contains("The uploaded file contains a comprehensive deep research report");
    }

    @Test
    void assignmentDefaultInitTiming_completesAfterThinkingAndContentChunks() {
        var thinkingChunks = MockPyAssignmentFixtures.initThinkingChunks();
        int contentChunkCount = 2;
        MockPyCommandConsumer.AssignmentInitTiming timing =
                MockPyCommandConsumer.defaultAssignmentInitTiming(thinkingChunks.size(), contentChunkCount);

        long lastThinkingDelayMs = timing.thinkingFirstDelayMs()
                + (thinkingChunks.size() - 1L) * timing.thinkingIntervalMs();
        long lastContentDelayMs = timing.contentFirstDelayMs()
                + (contentChunkCount - 1L) * timing.contentIntervalMs();

        assertThat(timing.contentFirstDelayMs()).isGreaterThan(lastThinkingDelayMs);
        assertThat(timing.completedDelayMs()).isGreaterThan(lastContentDelayMs);
    }

    @Test
    void assignmentGeneratedArtifactBody_matchesRichAssignmentTopic() {
        String body = MockPyAssignmentFixtures.generatedArtifactBody();

        assertThat(body).contains("# Revise Case Study on Indigenous Australian Business Protocols");
        assertThat(body).contains("| Section | Revision Goal | Evidence Needed |");
        assertThat(body).contains("## Checklist Before Submission");
    }

    @Test
    void assignmentGeneratedArtifacts_coverEditableDocumentSlidesAndCode() {
        List<Map<String, Object>> artifacts =
                MockPyAssignmentFixtures.generatedArtifacts("assignment", "test1234");

        assertThat(artifacts)
                .extracting(artifact -> artifact.get("kind"))
                .containsExactly(
                        "document_markdown",
                        "document_markdown",
                        "assignment_slides_editor_json",
                        "assignment_code_text");
        assertThat(artifacts)
                .extracting(artifact -> artifact.get("artifactUid"))
                .containsExactly(
                        "assignment_mock_document_test1234",
                        "assignment_mock_citation_gallery_document_test1234",
                        "assignment_mock_slides_editor_json_test1234",
                        "assignment_mock_code_text_test1234");
        assertThat((String) artifacts.get(0).get("bodyOrRef"))
                .contains("# Revise Case Study on Indigenous Australian Business Protocols");
        assertThat((String) artifacts.get(2).get("bodyOrRef"))
                .contains("\"slides\"")
                .contains("Case Study Revision Deck");
        assertThat((String) artifacts.get(3).get("bodyOrRef"))
                .contains("def build_argument")
                .contains("needs_citation_pass");
    }

    @Test
    void assignmentGeneratedArtifacts_includeCitationStyleGalleryDocument() {
        List<Map<String, Object>> artifacts =
                MockPyAssignmentFixtures.generatedArtifacts("assignment", "test1234");

        assertThat(artifacts)
                .extracting(artifact -> artifact.get("summary"))
                .contains("Citation Style Gallery.md");

        String gallery = String.valueOf(artifacts.get(1).get("bodyOrRef"));
        assertThat(gallery)
                .contains("# Citation Style Hover Gallery")
                .contains("## APA")
                .contains("## Harvard")
                .contains("## Chicago")
                .contains("## MLA")
                .contains("## IEEE")
                .contains("## Vancouver")
                .contains("## GB7714")
                .contains("Expected trigger text: `(Nguyen, 2025, p. 118)`")
                .contains("Expected trigger text: `(Nguyen 2025, 118)`")
                .contains("Expected trigger text: `(Nguyen 118)`")
                .contains("Expected trigger text: `[1]`")
                .contains("\"id\": \"acad_apa_style\"")
                .contains("\"id\": \"web_apa_style\"")
                .contains("\"id\": \"upload_apa_style\"")
                .contains("\"citationStyle\": \"APA\"")
                .contains("\"citationStyle\": \"IEEE\"")
                .contains("\"id\": \"acad_gb7714_style\"")
                .contains("\"id\": \"web_gb7714_style\"")
                .contains("\"id\": \"upload_gb7714_style\"")
                .contains("[--CITATION_STYLE--]\nAPA");
    }

    @Test
    void assignmentGeneratedArtifacts_canEmitCodeProjectFixture() {
        List<Map<String, Object>> artifacts =
                MockPyAssignmentFixtures.generatedArtifacts("assignment", "test1234", "code-project");

        assertThat(artifacts)
                .extracting(artifact -> artifact.get("kind"))
                .containsExactly(
                        "document_markdown",
                        "assignment_slides_editor_json",
                        "assignment_code_file",
                        "assignment_code_file",
                        "assignment_code_file",
                        "assignment_code_file",
                        "assignment_code_file",
                        "assignment_code_project");
        List<String> relPaths = artifacts.stream()
                .filter(artifact -> "assignment_code_file".equals(artifact.get("kind")))
                .map(artifact -> String.valueOf(((Map<?, ?>) artifact.get("meta")).get("relPath")))
                .toList();
        assertThat(relPaths).containsExactly(
                "src/main.py",
                "src/analyzer.py",
                "next.config.js",
                "package.json",
                "tailwind.config.ts");

        Map<String, Object> project = artifacts.get(artifacts.size() - 1);
        assertThat(project)
                .containsEntry("artifactUid", "assignment_mock_code_project_test1234")
                .containsEntry("kind", "assignment_code_project")
                .containsEntry("summary", "homework-analyzer");
        assertThat((String) project.get("bodyOrRef"))
                .contains("\"rootDir\": \"homework-analyzer\"")
                .contains("\"relPath\": \"src/main.py\"")
                .contains("\"relPath\": \"package.json\"");
    }

    @Test
    void assignmentNodeDetailPayload_matchesWorkflowDetailContract() {
        Map<String, Object> payload = MockPyAssignmentFixtures.nodeDetailPayload(
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
