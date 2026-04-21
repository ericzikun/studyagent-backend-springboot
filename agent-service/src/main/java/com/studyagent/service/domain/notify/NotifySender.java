package com.studyagent.service.domain.notify;

public interface NotifySender {

    // 预校验 target route 是否已配置（例如 default/payment/monitoring）。
    // 放在 sender 抽象层，便于 service 层复用真实 route 来源做业务校验。
    default boolean supportsTarget(String target) {
        return true;
    }

    NotifySendResult send(NotifyMessage message);
}
