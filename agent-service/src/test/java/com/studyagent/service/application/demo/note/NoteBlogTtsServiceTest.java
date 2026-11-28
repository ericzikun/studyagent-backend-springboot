package com.studyagent.service.application.demo.note;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NoteBlogTtsServiceTest {

    @Test
    void splitTurnsParsesTwoSpeakersAndAlignsParagraphIndexes() {
        String markdown = """
                # 注意力机制

                **主持人**：今天我们聊注意力。

                **讲解人**: 它让模型自己选择重点。

                **主持人**：最后总结一下。
                """;

        List<NoteBlogTtsService.Turn> turns = NoteBlogTtsService.splitTurns(markdown);

        assertThat(turns).hasSize(4);
        assertThat(turns.get(0).paragraphIndex()).isZero();
        assertThat(turns.get(0).speaker()).isNull();
        assertThat(turns.get(1).paragraphIndex()).isEqualTo(1);
        assertThat(turns.get(1).speaker()).isEqualTo("主持人");
        assertThat(turns.get(1).text()).isEqualTo("今天我们聊注意力。");
        assertThat(turns.get(2).speaker()).isEqualTo("讲解人");
        assertThat(turns.get(3).paragraphIndex()).isEqualTo(3);
    }

    @Test
    void speakerRoleMapsHostAndGuestLabelsInChineseAndEnglish() {
        assertThat(NoteBlogTtsService.speakerRole("主持人")).isEqualTo("host");
        assertThat(NoteBlogTtsService.speakerRole("Host")).isEqualTo("host");
        assertThat(NoteBlogTtsService.speakerRole("讲解人")).isEqualTo("guest");
        assertThat(NoteBlogTtsService.speakerRole("Explainer")).isEqualTo("guest");
        assertThat(NoteBlogTtsService.speakerRole("小智")).isNull();
    }

    @Test
    void splitTurnsKeepsLegacyArticleAsUnlabeledParagraphs() {
        String markdown = """
                # 标题

                第一段正文。

                第二段正文。
                """;

        List<NoteBlogTtsService.Turn> turns = NoteBlogTtsService.splitTurns(markdown);

        assertThat(turns).hasSize(3);
        assertThat(turns).allSatisfy(turn -> assertThat(turn.speaker()).isNull());
    }
}
