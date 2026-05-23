package com.studyagent.service.application.verla;

import com.studyagent.common.verla.enums.VerlaAgentEventType;
import com.studyagent.common.verla.enums.VerlaCommandAction;
import com.studyagent.common.verla.enums.VerlaSessionKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VerlaFileChatProtocolTest {

    @Test
    void fileChatCommandAction_shouldMatchProtocolCode() {
        assertThat(VerlaCommandAction.CMD_FILE_CHAT.getCode()).isEqualTo("cmd.file.chat");
    }

    @Test
    void fileChatCompleted_shouldBeTerminal() {
        assertThat(VerlaAgentEventType.isTerminal(VerlaAgentEventType.FILE_CHAT_COMPLETED)).isTrue();
        assertThat(VerlaAgentEventType.isTerminal(VerlaAgentEventType.FILE_CHAT_FAILED)).isTrue();
        assertThat(VerlaAgentEventType.isTerminal(VerlaAgentEventType.FILE_CHAT_CANCELLED)).isTrue();
    }

    @Test
    void fileChatSessionKind_shouldBeAvailable() {
        assertThat(VerlaSessionKind.valueOf("FILE_CHAT")).isEqualTo(VerlaSessionKind.FILE_CHAT);
    }
}
