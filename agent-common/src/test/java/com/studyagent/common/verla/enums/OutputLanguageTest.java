package com.studyagent.common.verla.enums;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OutputLanguageTest {
    @Test
    void thaiAliasesPreserveTheOutputPreference() {
        for (String raw : List.of("th", "th-TH", "thai", "Thai", " TH-TH ")) {
            assertThat(OutputLanguage.fromRaw(raw)).as(raw).isEqualTo(OutputLanguage.THAI);
            assertThat(OutputLanguage.fromRaw(raw).getValue()).isEqualTo("thai");
        }
    }

    @Test
    void ukrainianAndKazakhAliasesPreserveTheOutputPreference() {
        for (String raw : List.of("uk", "UK", "ukrainian", "Ukrainian", " uk ", "uk-UA")) {
            assertThat(OutputLanguage.fromRaw(raw)).as(raw).isEqualTo(OutputLanguage.UKRAINIAN);
        }
        for (String raw : List.of("kk", "KK", "kazakh", "Kazakh", " kk ", "kk-KZ")) {
            assertThat(OutputLanguage.fromRaw(raw)).as(raw).isEqualTo(OutputLanguage.KAZAKH);
        }
    }

    @Test
    void missingAndUnknownPreferencesKeepTheEnglishDefault() {
        assertThat(OutputLanguage.fromRaw(null)).isEqualTo(OutputLanguage.ENGLISH);
        assertThat(OutputLanguage.fromRaw(" ")).isEqualTo(OutputLanguage.ENGLISH);
        assertThat(OutputLanguage.fromRaw("klingon")).isEqualTo(OutputLanguage.ENGLISH);
        for (OutputLanguage language : OutputLanguage.values()) {
            assertThat(OutputLanguage.fromRaw(language.getValue())).isEqualTo(language);
        }
    }
}
