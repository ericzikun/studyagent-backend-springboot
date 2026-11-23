package com.studyagent.service.domain.billing;

/** A provider read failed; neither a rejected customer request nor proof that a write failed. */
public final class PaymentRecoveryReadException extends IllegalStateException {
    private final Integer providerStatus;
    private final String providerCode;
    private final String providerRequestId;

    public PaymentRecoveryReadException(String resourceId, Integer providerStatus, String providerCode,
                                        String providerRequestId, Throwable cause) {
        super("RECOVERY_PROVIDER_READ_FAILED:" + (resourceId == null ? "unknown" : resourceId), cause);
        this.providerStatus = providerStatus;
        this.providerCode = providerCode;
        this.providerRequestId = providerRequestId;
    }

    public Integer getProviderStatus() { return providerStatus; }
    public String getProviderCode() { return providerCode; }
    public String getProviderRequestId() { return providerRequestId; }
}
