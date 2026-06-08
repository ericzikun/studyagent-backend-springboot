package com.studyagent.service.application.verla.dispatch;

import com.studyagent.service.domain.mq.MqOutbox;

/**
 * 派发门控 defer 时向前端通知排队信息的出口。
 */
public interface AssignmentRunDispatchQueueEvents {

    void notifyDeferred(MqOutbox message);
}
