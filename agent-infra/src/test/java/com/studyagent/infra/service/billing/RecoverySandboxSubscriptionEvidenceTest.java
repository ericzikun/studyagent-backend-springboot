package com.studyagent.infra.service.billing;

import com.stripe.model.Subscription;
import com.stripe.net.ApiResource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecoverySandboxSubscriptionEvidenceTest {
    @Test void acceptsOnlyTheCompleteSingleOwnedItem(){
        var fact=StripePaymentRecoverySandboxIT.subFact(subscription());
        assertEquals("price_owned",fact.priceId());assertEquals("si_owned",fact.itemId());
    }
    @Test void rejectsTruncatedItems(){
        var s=subscription();s.getItems().setHasMore(true);assertThrows(AssertionError.class,()->StripePaymentRecoverySandboxIT.subFact(s));
    }
    @Test void rejectsAnAdditionalItemEvenWhenItemZeroIsUnchanged(){
        var s=subscription();s.getItems().getData().add(s.getItems().getData().get(0));assertThrows(AssertionError.class,()->StripePaymentRecoverySandboxIT.subFact(s));
    }
    @Test void rejectsMissingQuantity(){
        var s=subscription();s.getItems().getData().get(0).setQuantity(null);assertThrows(AssertionError.class,()->StripePaymentRecoverySandboxIT.subFact(s));
    }
    @Test void rejectsAChangedQuantityBeforeFirstPayment(){
        var s=subscription();s.getItems().getData().get(0).setQuantity(2L);assertThrows(AssertionError.class,()->StripePaymentRecoverySandboxIT.subFact(s));
    }
    @Test void rejectsLiveSubscription(){
        var s=subscription();s.setLivemode(true);assertThrows(AssertionError.class,()->StripePaymentRecoverySandboxIT.subFact(s));
    }
    private static Subscription subscription(){
        return ApiResource.GSON.fromJson("{\"id\":\"sub_owned\",\"customer\":\"cus_owned\",\"test_clock\":\"clock_owned\",\"livemode\":false,\"items\":{\"has_more\":false,\"data\":[{\"id\":\"si_owned\",\"quantity\":1,\"price\":{\"id\":\"price_owned\"}}]}}",Subscription.class);
    }
}
