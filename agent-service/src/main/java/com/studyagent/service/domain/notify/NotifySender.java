package com.studyagent.service.domain.notify;

public interface NotifySender {

    NotifySendResult send(NotifyMessage message);
}
