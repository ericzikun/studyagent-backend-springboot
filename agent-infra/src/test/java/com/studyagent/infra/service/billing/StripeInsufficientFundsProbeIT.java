package com.studyagent.infra.service.billing;

import com.google.gson.Gson;
import com.stripe.Stripe;
import com.stripe.exception.CardException;
import com.stripe.exception.StripeException;
import com.stripe.model.*;
import com.stripe.model.testhelpers.TestClock;
import com.stripe.net.RequestOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Native capability probe only: never claims an automatic-retry or application E2E PASS. */
@EnabledIfEnvironmentVariable(named="STRIPE_RECOVERY_FUNDS_PROBE", matches="true")
class StripeInsufficientFundsProbeIT {
    private final String run="funds-probe-"+UUID.randomUUID();
    private final RecoverySandboxCreationTracker creates=new RecoverySandboxCreationTracker(run,this::record);
    private final List<String> clocks=new ArrayList<>(),prices=new ArrayList<>(),products=new ArrayList<>();
    private RequestOptions options;
    private Path journal;

    @Test void checksWhetherNativeInvoiceIntentCanFailForFundsAndRetainThatMethodForInvoicePay() throws Exception {
        String key=System.getenv("STRIPE_RECOVERY_TEST_SECRET_KEY");assertNotNull(key);assertTrue(key.startsWith("sk_test_")||key.startsWith("rk_test_"));
        assertEquals("24.0.0",Stripe.VERSION);assertEquals("2023-10-16",Stripe.API_VERSION);
        options=RequestOptions.builder().setApiKey(key).setMaxNetworkRetries(0).build();
        journal=Path.of(System.getProperty("recovery.evidence.dir","target/payment-recovery-evidence"),run+".jsonl");Files.createDirectories(journal.getParent());
        record("run",Map.of("runId",run,"sdk",Stripe.VERSION,"apiVersion",Stripe.API_VERSION,"scope","NATIVE_INVOICE_METHOD_CAPABILITY_ONLY","finalResult","NOT_RUN"));
        try {
            var clock=creates.create("clock",Map.of("frozen_time",Instant.now().getEpochSecond(),"name",run),
                    (p,k)->TestClock.create(p,writeOptions(k)),c->{assertEquals(Boolean.FALSE,c.getLivemode());assertEquals(run,c.getName());clocks.add(c.getId());});
            var product=creates.create("product",Map.of("name",run,"metadata",Map.of("recovery_probe",run)),
                    (p,k)->Product.create(p,writeOptions(k)),p->{owned(p.getLivemode(),p.getMetadata());products.add(p.getId());});
            var price=creates.create("price",Map.of("product",product.getId(),"currency","usd","unit_amount",2900L,"recurring",Map.of("interval","month"),"metadata",Map.of("recovery_probe",run)),
                    (p,k)->Price.create(p,writeOptions(k)),p->{owned(p.getLivemode(),p.getMetadata());prices.add(p.getId());});
            var customer=creates.create("customer",Map.of("name",run,"test_clock",clock.getId(),"metadata",Map.of("recovery_probe",run)),
                    (p,k)->Customer.create(p,writeOptions(k)),c->{owned(c.getLivemode(),c.getMetadata());assertEquals(clock.getId(),c.getTestClock());});
            var subscription=creates.create("subscription",Map.of("customer",customer.getId(),"items",List.of(Map.of("price",price.getId())),"payment_behavior","default_incomplete","metadata",Map.of("recovery_probe",run)),
                    (p,k)->Subscription.create(p,writeOptions(k)),s->{owned(s.getLivemode(),s.getMetadata());assertEquals(customer.getId(),s.getCustomer());assertEquals(clock.getId(),s.getTestClock());});
            var invoice=Invoice.retrieve(subscription.getLatestInvoice(),options);assertEquals(Boolean.FALSE,invoice.getLivemode());assertEquals(customer.getId(),invoice.getCustomer());assertEquals(subscription.getId(),invoice.getSubscription());assertEquals(0L,invoice.getAmountPaid());assertNotNull(invoice.getPaymentIntent());
            invoice.update(Map.of("auto_advance",false),intent("protect-invoice",invoice.getId(),Map.of("auto_advance",false)));
            subscription.update(Map.of("pause_collection",Map.of("behavior","keep_as_draft")),intent("protect-subscription",subscription.getId(),Map.of("pause_collection",Map.of("behavior","keep_as_draft"))));
            var pi=PaymentIntent.retrieve(invoice.getPaymentIntent(),options);assertEquals(customer.getId(),pi.getCustomer());
            String invoiceId=invoice.getId(),intentId=pi.getId();
            try {
                pi.confirm(Map.of("payment_method","pm_card_visa_chargeDeclinedInsufficientFunds"),intent("confirm-on-session-unattached-funds-method",intentId,Map.of("payment_method","pm_card_visa_chargeDeclinedInsufficientFunds")));
                fail("An insufficient-funds fixture must never collect cash");
            } catch(CardException e) {
                assertEquals("insufficient_funds",e.getDeclineCode());record("direct-confirm-declined",Map.of("requestId",Objects.toString(e.getRequestId(),""),"declineCode",e.getDeclineCode(),"invoiceId",invoiceId,"paymentIntentId",intentId));
            }
            pi=PaymentIntent.retrieve(intentId,options);assertNotNull(pi.getLastPaymentError());assertEquals("insufficient_funds",pi.getLastPaymentError().getDeclineCode());
            assertNotNull(pi.getLatestCharge());var charge=Charge.retrieve(pi.getLatestCharge(),options);assertEquals(invoiceId,charge.getInvoice());assertEquals(intentId,charge.getPaymentIntent());assertEquals("failed",charge.getStatus());assertEquals(Boolean.FALSE,charge.getLivemode());
            String firstCharge=charge.getId();
            record("native-receipt",Map.of("invoiceId",invoiceId,"paymentIntentId",intentId,"chargeId",firstCharge,"paymentMethodId",Objects.toString(pi.getPaymentMethod(),""),"firstFailureBusinessTime","NOT_PROVEN"));
            boolean reusableFunds=false;String outcome="UNEXPECTED_SUCCESS";
            try {
                Invoice.retrieve(invoiceId,options).pay(Map.of("off_session",true),intent("retry-exact-application-body",invoiceId,Map.of("off_session",true)));
            } catch(StripeException e) {
                var observed=new LinkedHashMap<String,Object>();observed.put("requestId",e.getRequestId());observed.put("errorType",e.getClass().getSimpleName());observed.put("errorCode",e.getCode());
                if(e instanceof CardException card){observed.put("declineCode",card.getDeclineCode());reusableFunds="insufficient_funds".equals(card.getDeclineCode());}
                outcome=e instanceof CardException card?Objects.toString(card.getDeclineCode(),"unknown_decline"):Objects.toString(e.getCode(),e.getClass().getSimpleName());record("application-body-result",observed);
            }
            var current=Invoice.retrieve(invoiceId,options);assertEquals(0L,current.getAmountPaid());assertFalse("paid".equals(current.getStatus()));
            var after=PaymentIntent.retrieve(intentId,options);if(reusableFunds){assertNotEquals(firstCharge,after.getLatestCharge());assertEquals("insufficient_funds",after.getLastPaymentError().getDeclineCode());}
            record("capability-result",Map.of("directNativeInsufficientFunds","PASS","invoicePayRetainsFailedFundsMethod",reusableFunds?"PASS":"FAIL","observedRetryOutcome",outcome,"automaticFourSlotsResult","NOT_RUN","applicationResult","NOT_RUN","finalResult","NOT_RUN"));
        } catch(Exception|AssertionError e) {
            record("probe-error",Map.of("type",e.getClass().getSimpleName(),"message",Objects.toString(e.getMessage(),"").replaceAll("(?:sk|rk)_(?:test|live)_[A-Za-z0-9]+","[REDACTED]").replaceAll("https?://\\S+","[URL REDACTED]")));throw e;
        } finally {cleanup();}
    }
    private RequestOptions writeOptions(String key){return RequestOptions.builder().setApiKey(options.getApiKey()).setMaxNetworkRetries(0).setIdempotencyKey(key).build();}
    private RequestOptions intent(String action,String target,Map<String,Object> parameters)throws Exception{
        String key=run+":"+action;record("write-intent",Map.of("action",action,"target",target,"parameters",parameters,"idempotencyKey",key));return writeOptions(key);
    }
    private void owned(Boolean live,Map<String,String> metadata){assertEquals(Boolean.FALSE,live);assertEquals(run,metadata.get("recovery_probe"));}
    private void cleanup()throws Exception{
        int failures=0;
        for(String id:clocks)try{var c=TestClock.retrieve(id,options);assertEquals(Boolean.FALSE,c.getLivemode());assertEquals(run,c.getName());c.delete(intent("cleanup-clock",id,Map.of()));}catch(Exception e){failures++;}
        for(String id:prices)try{var p=Price.retrieve(id,options);owned(p.getLivemode(),p.getMetadata());p.update(Map.of("active",false),intent("cleanup-price-"+id,id,Map.of("active",false)));}catch(Exception e){failures++;}
        for(String id:products)try{var p=Product.retrieve(id,options);owned(p.getLivemode(),p.getMetadata());p.update(Map.of("active",false),intent("cleanup-product-"+id,id,Map.of("active",false)));}catch(Exception e){failures++;}
        String result=creates.cleanupResult(failures);record("cleanup",Map.of("result",result,"failedSteps",failures,"pendingCreates",creates.pending()));assertEquals("PASS",result,"Resolve the exact recorded cleanup intents; never scan unrelated objects");
    }
    private void record(String phase,Map<String,?> values)throws Exception{
        var row=new LinkedHashMap<String,Object>(values);row.put("phase",phase);row.put("realTime",Instant.now().toString());Files.writeString(journal,new Gson().toJson(row)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
    }
}
