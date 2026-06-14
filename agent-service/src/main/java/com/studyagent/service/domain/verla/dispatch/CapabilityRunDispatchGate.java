package com.studyagent.service.domain.verla.dispatch;

/**
 * AI Detection / Humanizer 全局派发并发门控。
 */
public interface CapabilityRunDispatchGate {

    boolean isEnabled(String action);

    int maxConcurrency(String action);

    int activeCount(String action);

    /**
     * 当前是否还能向 MQ 再派发一条 capability run 命令。
     */
    boolean canDispatchNow(String action);
}
