package com.studyagent.infra.service.billing;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.Set;

@Data
@Component
@ConfigurationProperties(prefix="billing.payment-recovery")
public class PaymentRecoveryProperties {
    private boolean acceptNew;
    private Instant acceptInvoicesCreatedAfter;
    private boolean automaticPayEnabled;
    private Set<String> allowedUserIds = Set.of();
    private Set<String> verifiedCohorts = Set.of();
    private int dispatchToleranceSeconds = 3600;
    private int batchSize = 100;
    private int leaseSeconds = 120;
    private int unknownEscalationHours = 24;
    private String portalManagementConfigurationId = "";
    private String portalMethodOnlyConfigurationId = "";
    private boolean emailEnabled;
    private String emailFrom = "";
    private String reviewAssignee = "";
    private Set<java.time.LocalDate> reviewHolidays = Set.of();
    private Set<java.time.LocalDate> reviewWorkingDates = Set.of();

    public void setAllowedUserIds(Set<String> ids) {
        allowedUserIds = clean(ids);
    }

    public void setVerifiedCohorts(Set<String> keys) {
        if (keys == null) throw new IllegalArgumentException("verified-cohorts cannot be null");
        Set<String> cleaned = clean(keys);
        cleaned.forEach(RecoveryCaseKey::parse);
        verifiedCohorts = Set.copyOf(cleaned);
    }

    /** Empty env placeholders arrive as a single blank entry; never treat that as a cohort. */
    private static Set<String> clean(Set<String> values) {
        if (values == null) {
            return Set.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
    public boolean admits(String userId, RecoveryCaseKey key, Instant invoiceCreated, Instant eventCreated) {
        return acceptNew && acceptInvoicesCreatedAfter != null && userId != null && allowedUserIds.contains(userId)
                && key != null && key.verifiedBy(verifiedCohorts) && invoiceCreated != null && eventCreated != null
                && !invoiceCreated.isBefore(acceptInvoicesCreatedAfter) && !eventCreated.isBefore(acceptInvoicesCreatedAfter);
    }
}
