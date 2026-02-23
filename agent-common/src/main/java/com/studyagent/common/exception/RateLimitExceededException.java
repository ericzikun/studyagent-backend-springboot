package com.studyagent.common.exception;

/**
 * 限流超限异常
 * 当全局请求频率超过配置的阈值时抛出
 */
public class RateLimitExceededException extends RuntimeException {

    private final String endpoint;

    public RateLimitExceededException(String endpoint) {
        super("Rate limit exceeded for " + endpoint + ", please try again later");
        this.endpoint = endpoint;
    }

    public String getEndpoint() {
        return endpoint;
    }
}
