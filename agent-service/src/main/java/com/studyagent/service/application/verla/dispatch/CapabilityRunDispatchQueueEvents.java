package com.studyagent.service.application.verla.dispatch;

import com.studyagent.service.domain.mq.MqOutbox;

/**
 * AI Detection / Humanizer 派发门控排队 / 放出时向前端通知的出口。
 */
public interface CapabilityRunDispatchQueueEvents {

    /** outbox 因门控 defer 时通知排队。 */
    void notifyDeferred(MqOutbox message);

    /** outbox 已成功发往 MQ（进入派发中）时通知清排队。 */
    void notifyDispatched(MqOutbox message);
}
