package com.studyagent.common.verla.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class VerlaAttachmentStatusTest {

    @Test
    void nullCodeKeepsUploadedDefault() {
        assertThat(VerlaAttachmentStatus.fromCode(null)).isEqualTo(VerlaAttachmentStatus.UPLOADED);
    }

    @Test
    void knownCodeIsMatchedCaseInsensitively() {
        assertThat(VerlaAttachmentStatus.fromCode("PARSED")).isEqualTo(VerlaAttachmentStatus.PARSED);
        assertThat(VerlaAttachmentStatus.fromCode("parsed")).isEqualTo(VerlaAttachmentStatus.PARSED);
    }

    @Test
    void unknownCodeFallsBackToFailedWithoutThrowing() {
        assertThatCode(() -> VerlaAttachmentStatus.fromCode("SOME_FUTURE_STATUS"))
                .doesNotThrowAnyException();
        assertThat(VerlaAttachmentStatus.fromCode("SOME_FUTURE_STATUS"))
                .isEqualTo(VerlaAttachmentStatus.FAILED);
    }

    @Test
    void terminalStatesAreParsedAndFailedOnly() {
        assertThat(VerlaAttachmentStatus.PARSED.isTerminal()).isTrue();
        assertThat(VerlaAttachmentStatus.FAILED.isTerminal()).isTrue();
        assertThat(VerlaAttachmentStatus.UPLOADED.isTerminal()).isFalse();
    }
}
