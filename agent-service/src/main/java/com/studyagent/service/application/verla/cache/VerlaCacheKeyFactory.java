package com.studyagent.service.application.verla.cache;

import com.studyagent.service.config.VerlaContextCacheProperties;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class VerlaCacheKeyFactory {

    private final VerlaContextCacheProperties properties;

    public String convLatestVersionKey(Long conversationId) {
        return basePrefix() + ":conv:{" + conversationId + "}:latest-version";
    }

    public String convSummaryKey(Long conversationId, Long convVersion, int messageLimit) {
        return basePrefix() + ":conv:{" + conversationId + "}:summary:v" + convVersion + ":ml:" + messageLimit;
    }

    public String convMessagesKey(Long conversationId, Long convVersion, Long beforeMessageId, int limit) {
        String cursor = beforeMessageId == null ? "latest" : String.valueOf(beforeMessageId);
        return basePrefix() + ":conv:{" + conversationId + "}:messages:v" + convVersion
                + ":before:" + cursor + ":limit:" + limit;
    }

    public String turnMetaKey(Long turnId) {
        return basePrefix() + ":turn:{" + turnId + "}:meta";
    }

    public String sessMetaKey(Long sessionId) {
        return basePrefix() + ":sess:{" + sessionId + "}:meta";
    }

    public String blockResponsesKey(Long sessionId) {
        return basePrefix() + ":sess:{" + sessionId + "}:block-responses";
    }

    public String convSummaryLockKey(Long conversationId, Long convVersion, int messageLimit) {
        return basePrefix() + ":lock:conv:{" + conversationId + "}:summary:v" + convVersion + ":ml:" + messageLimit;
    }

    private String basePrefix() {
        return properties.getKeyPrefix();
    }
}
