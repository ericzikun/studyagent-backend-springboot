package com.studyagent.service.application.verla.dispatch;

import com.studyagent.service.domain.mq.MqOutbox;

/**
 * AI Detection / Humanizer 派发门控 defer 时向前端通知排队信息的出口。
 */
public interface CapabilityRunDispatchQueueEvents {

    void notifyDeferred(MqOutbox message);
}
