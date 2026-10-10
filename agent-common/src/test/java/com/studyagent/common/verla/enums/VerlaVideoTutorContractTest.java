package com.studyagent.common.verla.enums;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Video Tutor 的跨端契约：枚举名与 command code 必须与 Python 侧
 * {@code app/models/enums.py} 逐字一致。
 * <p>
 * 这个仓没有 CI 兜这件事，而两侧不一致的症状是**静默**的：
 * Java 对未注册 eventType 是 processed 且只打 debug 日志，Python 侧则是命令进到
 * {@code _run_with_context} 找不到分支而抛错，或者更糟 —— 落到 ASSIGNMENT 分支上。
 * 所以这里把名字逐个写死，改任何一端都会红。
 */
class VerlaVideoTutorContractTest {

    /** 顺序与 Python 的登记顺序一致（Python 单测也从同一份 design.md §1.2 派生）。 */
    private static final List<String> PYTHON_EVENT_NAMES = List.of(
            "VIDEO_TUTOR_STARTED",
            "VIDEO_TUTOR_CHAT_STREAM_CHUNK",
            "VIDEO_TUTOR_BOARD_OPS",
            "VIDEO_TUTOR_BOARD_COMMIT",
            "VIDEO_TUTOR_TURN_COMPLETED",
            "VIDEO_TUTOR_FAILED",
            "VIDEO_TUTOR_CANCELLED");

    @Test
    void eventNamesMustMatchThePythonRegistryVerbatim() {
        List<String> javaNames = java.util.Arrays.stream(VerlaAgentEventType.values())
                .map(Enum::name)
                .filter(n -> n.startsWith("VIDEO_TUTOR_"))
                .toList();
        assertThat(javaNames).containsExactlyElementsOf(PYTHON_EVENT_NAMES);
    }

    @Test
    void terminalsSetMustHoldExactlyTheThreeTerminalEvents() {
        // 注意：真正决定终态判定的是底部那个 TERMINALS Set，不是构造参数（后者因为与静态
        // isTerminal(type) 重名，Lombok 根本没生成 getter，属装饰性字段）
        for (String terminal : List.of("VIDEO_TUTOR_TURN_COMPLETED", "VIDEO_TUTOR_FAILED",
                "VIDEO_TUTOR_CANCELLED")) {
            assertThat(VerlaAgentEventType.isTerminal(VerlaAgentEventType.valueOf(terminal)))
                    .as(terminal).isTrue();
        }
        for (String renderOnly : List.of("VIDEO_TUTOR_BOARD_OPS", "VIDEO_TUTOR_CHAT_STREAM_CHUNK",
                "VIDEO_TUTOR_STARTED", "VIDEO_TUTOR_BOARD_COMMIT")) {
            assertThat(VerlaAgentEventType.isTerminal(VerlaAgentEventType.valueOf(renderOnly)))
                    .as(renderOnly).isFalse();
        }
    }

    @Test
    void commandCodesMustRoundTripAndMatchPython() {
        assertThat(VerlaCommandAction.CMD_VIDEO_TUTOR_CHAT.getCode())
                .isEqualTo("cmd.video_tutor.chat");
        assertThat(VerlaCommandAction.CMD_VIDEO_TUTOR_CHAT_CONTROL_CANCEL.getCode())
                .isEqualTo("cmd.video_tutor.chat.control.cancel");

        assertThat(VerlaCommandAction.fromCode("cmd.video_tutor.chat"))
                .isEqualTo(VerlaCommandAction.CMD_VIDEO_TUTOR_CHAT);
        assertThat(VerlaCommandAction.fromCode("cmd.video_tutor.chat.control.cancel"))
                .isEqualTo(VerlaCommandAction.CMD_VIDEO_TUTOR_CHAT_CONTROL_CANCEL);
    }

    @Test
    void cancelCodeKeepsTheSuffixPythonShortCircuitsOn() {
        // Python runtime 按 ".control.cancel" 后缀短路；改名会让取消命令真的进执行分支
        assertThat(VerlaCommandAction.CMD_VIDEO_TUTOR_CHAT_CONTROL_CANCEL.getCode())
                .endsWith(".control.cancel");
    }

    @Test
    void noRetryCodeExistsForVideoTutorInV1() {
        // 白板一轮的产物是"板上现有什么"，重打会重复上板，所以 v1 故意没有 retry code
        assertThat(java.util.Arrays.stream(VerlaCommandAction.values())
                .map(VerlaCommandAction::getCode))
                .noneMatch(code -> code.startsWith("cmd.video_tutor") && code.contains("retry"));
    }

    @Test
    void sessionKindNameIsWhatTheMessageSceneColumnsUse() {
        // turn 写入与 handler 幂等查重都用这个串，所以它同时是 scene 值
        assertThat(VerlaSessionKind.VIDEO_TUTOR.name()).isEqualTo("VIDEO_TUTOR");
        assertThat(VerlaSessionKind.VIDEO_TUTOR.name().length()).isLessThanOrEqualTo(16);
    }

    @Test
    void unknownCommandCodeMustThrowInsteadOfSilentlyResolving() {
        assertThatThrownBy(() -> VerlaCommandAction.fromCode("cmd.video_tutor.retry"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
