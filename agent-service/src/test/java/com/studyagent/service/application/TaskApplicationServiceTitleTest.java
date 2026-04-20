package com.studyagent.service.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TaskApplicationServiceTitleTest {

    @Test
    void shouldGenerateTitleFromTrimmedDescription() {
        String title = TaskApplicationService.generateTaskTitleFromDescription("  Analyze Netflix strategy  ");

        assertThat(title).isEqualTo("Analyze Netflix strategy");
    }

    @Test
    void shouldLimitGeneratedTitleToFirstFiftyCharacters() {
        String description = "12345678901234567890123456789012345678901234567890extra content";

        String title = TaskApplicationService.generateTaskTitleFromDescription(description);

        assertThat(title)
                .hasSize(TaskApplicationService.GENERATED_TASK_TITLE_MAX_LENGTH)
                .isEqualTo("12345678901234567890123456789012345678901234567890");
    }

    @Test
    void shouldUseDefaultTitleWhenDescriptionBlank() {
        assertThat(TaskApplicationService.generateTaskTitleFromDescription("   "))
                .isEqualTo("Assignment");
        assertThat(TaskApplicationService.generateTaskTitleFromDescription(null))
                .isEqualTo("Assignment");
    }

    @Test
    void shouldUseDefaultDescriptionWhenDescriptionBlank() {
        assertThat(TaskApplicationService.normalizeTaskDescription("   "))
                .isEqualTo("Task without description");
        assertThat(TaskApplicationService.normalizeTaskDescription(null))
                .isEqualTo("Task without description");
    }
}
