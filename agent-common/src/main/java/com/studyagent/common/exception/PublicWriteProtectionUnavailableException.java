package com.studyagent.common.exception;

/**
 * 公开写接口的防滥用保护不可用。
 *
 * <p>此时调用方必须停止持久化，避免 Redis 故障让匿名写接口失去上限。</p>
 */
public class PublicWriteProtectionUnavailableException extends RuntimeException {

    public PublicWriteProtectionUnavailableException() {
        super("Public write protection is temporarily unavailable");
    }

    public PublicWriteProtectionUnavailableException(Throwable cause) {
        super("Public write protection is temporarily unavailable", cause);
    }
}
