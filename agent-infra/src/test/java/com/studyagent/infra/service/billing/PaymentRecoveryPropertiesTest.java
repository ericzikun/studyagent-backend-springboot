package com.studyagent.infra.service.billing;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class PaymentRecoveryPropertiesTest {
    private final Instant cutoff=Instant.parse("2026-11-11T00:00:00Z");
    private final RecoveryCaseKey pro=new RecoveryCaseKey("formal","pro_monthly","month",1,"NO_SCHEDULE","RENEW");
    @Test void allIndependentAdmissionGatesMustPass() {
        var p=new PaymentRecoveryProperties();
        assertFalse(p.admits("user",pro,cutoff,cutoff));
        p.setAcceptNew(true);p.setAcceptInvoicesCreatedAfter(cutoff);
        assertFalse(p.admits("user",pro,cutoff,cutoff));
        p.setAllowedUserIds(Set.of("user"));assertFalse(p.admits("user",pro,cutoff,cutoff));
        p.setVerifiedCohorts(Set.of(pro.value()));assertTrue(p.admits("user",pro,cutoff,cutoff));
        assertFalse(p.admits("other",pro,cutoff,cutoff));
        assertFalse(p.admits("user",pro,cutoff.minusSeconds(1),cutoff));
        assertFalse(p.admits("user",pro,cutoff,cutoff.minusSeconds(1)));
        assertFalse(p.admits("user",pro,null,cutoff));
        assertFalse(p.admits("user",pro,cutoff,null));
    }
    @Test void invalidConfigCannotSilentlyTurnIntoBroadCohort() {
        var p=new PaymentRecoveryProperties();
        assertThrows(IllegalArgumentException.class,()->p.setVerifiedCohorts(Set.of("formal_monthly")));
        assertThrows(IllegalArgumentException.class,()->p.setVerifiedCohorts(Set.of("*")));
        assertTrue(p.getVerifiedCohorts().isEmpty());
    }
    @Test void wideningUserListDoesNotAdmitDifferentPlanOrCancellation() {
        var p=new PaymentRecoveryProperties();p.setAcceptNew(true);p.setAcceptInvoicesCreatedAfter(cutoff);
        p.setAllowedUserIds(Set.of("user","new_user"));p.setVerifiedCohorts(Set.of(pro.value()));
        assertFalse(p.admits("new_user",new RecoveryCaseKey("formal","basic_monthly","month",1,"NO_SCHEDULE","RENEW"),cutoff,cutoff));
        assertFalse(p.admits("new_user",new RecoveryCaseKey("formal","pro_monthly","month",1,"NO_SCHEDULE","PERIOD_END"),cutoff,cutoff));
    }
    @Test void emptyEnvironmentPlaceholdersBindWithoutEnablingAnything() {
        var source=new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(java.util.Map.of(
                "billing.payment-recovery.accept-new","false",
                "billing.payment-recovery.accept-invoices-created-after","2099-01-01T00:00:00Z",
                "billing.payment-recovery.allowed-user-ids","",
                "billing.payment-recovery.verified-cohorts","",
                "billing.payment-recovery.email-from","",
                "billing.payment-recovery.portal-method-only-configuration-id","",
                "billing.payment-recovery.review-assignee",""));
        var bound=new org.springframework.boot.context.properties.bind.Binder(source)
                .bind("billing.payment-recovery",org.springframework.boot.context.properties.bind.Bindable.of(PaymentRecoveryProperties.class));
        assertTrue(bound.isBound());
        var p=bound.get();
        assertTrue(p.getAllowedUserIds().isEmpty());
        assertTrue(p.getVerifiedCohorts().isEmpty());
        assertEquals("",p.getEmailFrom());
        assertFalse(p.admits("user",pro,cutoff,cutoff));
    }
    @Test void failClosedCutoffKeepsEverythingOutEvenWhenAcceptNewIsSet() {
        var p=new PaymentRecoveryProperties();
        p.setAcceptNew(true);
        p.setAcceptInvoicesCreatedAfter(Instant.parse("2099-01-01T00:00:00Z"));
        p.setAllowedUserIds(Set.of("user"));
        p.setVerifiedCohorts(Set.of(pro.value()));
        assertFalse(p.admits("user",pro,Instant.parse("2026-11-17T00:00:00Z"),Instant.parse("2026-11-17T00:00:00Z")));
    }
}
