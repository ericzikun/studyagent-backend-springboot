package com.studyagent.common.log.util;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class LogUtilTest {

    @Test
    void toJson_shouldKeepSerializedLogsOnSingleLine() {
        String out = LogUtil.toJson("line1\nline2", null, 4096);

        assertThat(out).doesNotContain("\n", "\r");
        assertThat(out).contains("\\n");
    }

    @Test
    void toJson_shouldKeepFallbackLogsOnSingleLine() {
        String out = LogUtil.toJson(new FallbackValue(), null, 4096);

        assertThat(out).doesNotContain("\n", "\r");
        assertThat(out).contains("line1\\nline2");
    }

    @Test
    void toJson_shouldTruncateAfterSingleLineNormalization() {
        String out = LogUtil.toJson(new FallbackValue(), null, 8);

        assertThat(out).doesNotContain("\n", "\r");
        assertThat(out).contains("[TRUNCATED");
    }

    private static final class FallbackValue {
        @SuppressWarnings("unused")
        private final LocalDateTime time = LocalDateTime.of(2026, 6, 18, 14, 37, 28);

        @Override
        public String toString() {
            return "line1\nline2";
        }
    }
}
