package com.studyagent.service.application.verla.tts;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VideoTutorTtsServiceTest {

    @Test
    void toSpokenStripsMarkupMathAndSlashes() {
        String spoken = VideoTutorTtsService.toSpoken(
                "<h1>顶点式</h1><b>y = a(x-h)²+k</b>$x^2$ **重点**", 280);
        assertThat(spoken).doesNotContain("<h1>", "<b>", "$", "**");
        assertThat(spoken).contains("顶点式");
        assertThat(spoken).contains("公式");
    }

    @Test
    void toSpokenTruncatesLongNarration() {
        String spoken = VideoTutorTtsService.toSpoken("一".repeat(400), 280);
        assertThat(spoken).hasSize(280);
    }

    @Test
    void toSpokenOnBlankTextIsEmpty() {
        assertThat(VideoTutorTtsService.toSpoken("  <br>  ", 280)).isEmpty();
    }
}
