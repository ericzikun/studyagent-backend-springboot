package com.studyagent.infra.service.billing;

import com.stripe.model.Invoice;
import com.studyagent.infra.entity.SubscriptionRecoveryActionEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** No Stripe, SQL or keys: exercises the native harness's actual closure acceptance guard. */
class RecoverySandboxClosureEvidenceTest {
    private static final String INTRO="in_owned_intro_draft",FORMAL="in_owned_formal",PURCHASE="in_paid_intro_purchase";

    @Test void acceptsProtectedIntroDraftAlongsideVoidedFormalAndPaidInitialPurchase(){
        var before=before();var after=after(invoice(INTRO,"draft",false,0));
        var residuals=RecoverySandboxApplicationHarness.closureResiduals(before,after,Set.of(INTRO),"OPEN",List.of(finalizeAction("SKIPPED","SKIPPED_UNVERIFIED_TARGET")));
        assertEquals(1,residuals.size());assertEquals(INTRO,residuals.get(0).get("invoiceId"));
    }

    @Test void rejectsIntroDraftThatWasSilentlyFinalizedAndVoided(){
        assertThrows(AssertionError.class,()->RecoverySandboxApplicationHarness.closureResiduals(before(),after(invoice(INTRO,"void",false,0)),Set.of(INTRO),"OPEN",List.of()));
    }

    @Test void rejectsIntroDraftThatWasPaidInsteadOfRetained(){
        assertThrows(AssertionError.class,()->RecoverySandboxApplicationHarness.closureResiduals(before(),after(invoice(INTRO,"paid",false,299)),Set.of(INTRO),"OPEN",List.of()));
    }

    @Test void rejectsMissingRequiredIntroDraft(){
        var missing=Map.of(FORMAL,invoice(FORMAL,"void",false,0),PURCHASE,invoice(PURCHASE,"paid",false,299));
        assertThrows(AssertionError.class,()->RecoverySandboxApplicationHarness.closureResiduals(before(),missing,Set.of(INTRO),"OPEN",List.of()));
    }

    @Test void rejectsAutoAdvancingRequiredIntroDraft(){
        assertThrows(AssertionError.class,()->RecoverySandboxApplicationHarness.closureResiduals(before(),after(invoice(INTRO,"draft",true,0)),Set.of(INTRO),"OPEN",List.of()));
    }

    @Test void rejectsPartiallyPaidRequiredIntroDraft(){
        assertThrows(AssertionError.class,()->RecoverySandboxApplicationHarness.closureResiduals(before(),after(invoice(INTRO,"draft",false,1)),Set.of(INTRO),"OPEN",List.of()));
    }

    @Test void rejectsClosedReviewForRequiredIntroDraft(){
        assertThrows(AssertionError.class,()->RecoverySandboxApplicationHarness.closureResiduals(before(),after(invoice(INTRO,"draft",false,0)),Set.of(INTRO),"CLOSED",List.of()));
    }

    @Test void rejectsSucceededFinalizeReceiptForRetainedIntroDraft(){
        assertThrows(AssertionError.class,()->RecoverySandboxApplicationHarness.closureResiduals(before(),after(invoice(INTRO,"draft",false,0)),Set.of(INTRO),"OPEN",List.of(finalizeAction("SUCCEEDED",null))));
    }

    @Test void rejectsFinalizeSkipWithWrongReasonForRequiredIntroDraft(){
        assertThrows(AssertionError.class,()->RecoverySandboxApplicationHarness.closureResiduals(before(),after(invoice(INTRO,"draft",false,0)),Set.of(INTRO),"OPEN",List.of(finalizeAction("SKIPPED","OTHER_REASON"))));
    }

    @Test void rejectsRequiredDraftThatWasAlreadyVoidAtBaseline(){
        var invalidBefore=after(invoice(INTRO,"void",false,0));
        assertThrows(AssertionError.class,()->RecoverySandboxApplicationHarness.closureResiduals(invalidBefore,after(invoice(INTRO,"draft",false,0)),Set.of(INTRO),"OPEN",List.of()));
    }

    private static Map<String,Invoice> before(){return Map.of(INTRO,invoice(INTRO,"draft",false,0),FORMAL,invoice(FORMAL,"open",false,0),PURCHASE,invoice(PURCHASE,"paid",false,299));}
    private static Map<String,Invoice> after(Invoice intro){return Map.of(INTRO,intro,FORMAL,invoice(FORMAL,"void",false,0),PURCHASE,invoice(PURCHASE,"paid",false,299));}
    private static Invoice invoice(String id,String status,boolean advance,long paid){var invoice=new Invoice();invoice.setId(id);invoice.setStatus(status);invoice.setAutoAdvance(advance);invoice.setAmountPaid(paid);return invoice;}
    private static SubscriptionRecoveryActionEntity finalizeAction(String status,String skipReason){var action=new SubscriptionRecoveryActionEntity();action.setTargetId(INTRO);action.setActionType("FINALIZE_INVOICE");action.setStatus(status);action.setSkipReason(skipReason);return action;}
}
