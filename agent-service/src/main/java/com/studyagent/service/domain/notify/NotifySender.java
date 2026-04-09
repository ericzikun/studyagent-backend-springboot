package com.studyagent.service.domain.notify;

public interface NotifySender {

    default boolean supportsTarget(String target) {
        return true;
    }

    NotifySendResult send(NotifyMessage message);
}
