package com.studyagent.common.verla.enums;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code segment} 是**跨端契约**：前端把 {@link VerlaConversationListSegment#getQueryKey()}
 * 直接放进查询串，Java 用它去选 SQL 分支。
 *
 * <p>这三条都可能被"顺手改大写"改坏，而且坏了不报错 —— 只会静默返回空列表：
 * <ul>
 *     <li>{@code video_tutor} 是小写：画板建会话时 {@code primaryIntent} 传的就是小写，
 *         {@code VerlaConversationService#create} 只 trim 不做大小写归一；</li>
 *     <li>{@code note} 对应的是大写 {@code NOTE_MAKER}（笔记域自己写库时用的形式）；</li>
 *     <li>queryKey 必须与 {@code VerlaConversationMapper} 里 {@code <when>} 的字面量一致。</li>
 * </ul>
 */
class VerlaConversationListSegmentContractTest {

    /** 顺序与前端 {@code DASHBOARD_SECTION_OPTIONS} 的登记顺序一致。 */
    private static final List<String> QUERY_KEYS = List.of(
            "assignment",
            "learning",
            "ai_writing",
            "ai_detection",
            "ai_humanizer",
            "note",
            "video_tutor");

    @Test
    void queryKeysMustMatchTheFrontendSegmentValuesVerbatim() {
        assertThat(Arrays.stream(VerlaConversationListSegment.values())
                .map(VerlaConversationListSegment::getQueryKey)
                .toList()).containsExactlyElementsOf(QUERY_KEYS);
    }

    @Test
    void videoTutorSegmentKeepsLowercaseStoredIntent() {
        // 库里存的是小写 video_tutor（见类注释）。写成大写不会编译失败、也不会运行时报错，
        // 只会让白板会话永远查不出来 —— 所以在这里钉死。
        assertThat(VerlaConversationListSegment.VIDEO_TUTOR.getQueryKey())
                .isEqualTo("video_tutor");
    }
}
