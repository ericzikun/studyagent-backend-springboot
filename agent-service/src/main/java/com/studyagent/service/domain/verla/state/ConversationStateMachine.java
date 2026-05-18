package com.studyagent.service.domain.verla.state;

import org.springframework.stereotype.Component;

import static com.studyagent.service.domain.verla.state.ConversationStatus.*;

/**
 * Conversation 状态机
 * <p>
 * 极简实现：active <-> archived；任意状态 -> deleted。
 * 详见 docs/verla-Java侧MVP技术方案.md §11.1。
 */
@Component
public class ConversationStateMachine {

    /** 归档 */
    public ConversationStatus archive(ConversationStatus current) {
        require(current, ACTIVE);
        return ARCHIVED;
    }

    /** 恢复 */
    public ConversationStatus restore(ConversationStatus current) {
        require(current, ARCHIVED);
        return ACTIVE;
    }

    /** 软删（幂等） */
    public ConversationStatus delete(ConversationStatus current) {
        return DELETED;
    }

    private void require(ConversationStatus current, ConversationStatus expect) {
        if (current != expect) {
            throw new IllegalStateException(
                    "Conversation state expected " + expect + " but was " + current);
        }
    }
}
