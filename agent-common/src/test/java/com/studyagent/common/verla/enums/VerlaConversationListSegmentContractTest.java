package com.studyagent.common.verla.enums;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code segment} 是**跨端契约**：前端把 {@link VerlaConversationListSegment#getQueryKey()}
 * 直接放进查询串，Java 用它去选 SQL 分支。
 *
 * <p>{@code note} 对应的是大写 {@code NOTE_MAKER}（笔记域自己写库时用的形式）；
 * queryKey 必须与 {@code VerlaConversationMapper} 里 {@code <when>} 的字面量一致，
 * 写错不会报错，只会静默返回空列表。
 */
class VerlaConversationListSegmentContractTest {

    private static final List<String> QUERY_KEYS = List.of(
            "assignment",
            "learning",
            "ai_writing",
            "ai_detection",
            "ai_humanizer",
            "simulation",
            "note");

    @Test
    void queryKeysMustMatchTheFrontendSegmentValuesVerbatim() {
        assertThat(Arrays.stream(VerlaConversationListSegment.values())
                .map(VerlaConversationListSegment::getQueryKey)
                .toList()).containsExactlyElementsOf(QUERY_KEYS);
    }

    @Test
    void noteSegmentUsesLowercaseQueryKeyForUppercaseStoredIntent() {
        assertThat(VerlaConversationListSegment.NOTE.getQueryKey()).isEqualTo("note");
    }
}
