package com.studyagent.infra.service.billing;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.stripe.Stripe;
import com.stripe.model.*;
import com.stripe.model.testhelpers.TestClock;
import com.stripe.net.RequestOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;

/** Native TEST reads and fixed operator writes, pinned to the application's SDK/API. */
@Component
@ConditionalOnProperty(name="billing.payment-recovery.test-clock.enabled",havingValue="true")
class RecoveryTestClockStripe {
    private final String key;
    RecoveryTestClockStripe(@Value("${stripe.secret-key:}")String key){this.key=key;}
    record Capture(RecoveryOwnedClockEvidence.ClockState clock,RecoveryOwnedClockEvidence.SubscriptionState subscription,
                   Set<String> invoiceIds,Set<String> formalInvoiceIds,Set<String> usedPriceIds,
                   RecoveryOwnedClockEvidence.PreAttempt preAttempt,Instant wallStart){}
    RequestOptions options(String idempotency){
        check("2023-10-16".equals(Stripe.API_VERSION)&&key!=null&&(key.startsWith("sk_test_")||key.startsWith("rk_test_")),"TEST_KEY_AND_PINNED_API_REQUIRED");
        return RequestOptions.builder().setApiKey(key).setMaxNetworkRetries(0).setIdempotencyKey(idempotency).build();
    }
    Customer customer(String id)throws Exception{return Customer.retrieve(id,options(null));}
    Subscription subscription(String id)throws Exception{return Subscription.retrieve(id,options(null));}
    Invoice invoice(String id)throws Exception{return Invoice.retrieve(id,options(null));}
    TestClock clock(String id)throws Exception{return TestClock.retrieve(id,options(null));}
    List<Charge> charges(String pi)throws Exception{var out=new ArrayList<Charge>();for(var c:Charge.list(Map.of("payment_intent",pi,"limit",100L),options(null)).autoPagingIterable())out.add(c);return out;}
    List<Event> failures(String invoice)throws Exception{
        var out=new ArrayList<Event>();
        for(var e:Event.list(Map.of("types",List.of("invoice.payment_failed","invoice.payment_action_required"),"limit",100L),options(null)).autoPagingIterable())
            if(invoice.equals(id(raw(e),"id")))out.add(e);
        return out;
    }
    StripeObject write(String type,String target,Map<String,Object> params,String idempotency)throws Exception{
        return switch(type){
            case "PAUSE" -> subscription(target).update(params,options(idempotency));
            case "FINALIZE" -> invoice(target).finalizeInvoice(params,options(idempotency));
            case "PAY" -> invoice(target).pay(params,options(idempotency));
            case "ADVANCE" -> clock(target).advance(params,options(idempotency));
            default -> throw new IllegalArgumentException("Unknown TEST operator write");
        };
    }
    Capture before(RecoveryTestClockStore.Run run)throws Exception{
        var clock=clock(run.clockId);ready(clock);var sub=subscription(run.subscriptionId);var in=invoice(run.invoiceId);
        var fact=invoiceFact(in);var pi=PaymentIntent.retrieve(in.getPaymentIntent(),options(null));
        check(run.invoiceId.equals(in.getId())&&run.customerId.equals(in.getCustomer())&&run.subscriptionId.equals(in.getSubscription())
                &&run.subscriptionId.equals(sub.getId())&&run.customerId.equals(sub.getCustomer())&&run.clockId.equals(sub.getTestClock())
                &&Boolean.FALSE.equals(pi.getLivemode())&&run.customerId.equals(pi.getCustomer()),"PRE_ATTEMPT_SCOPE_REQUIRED");
        var allCharges=charges(pi.getId());var events=failures(in.getId());
        check("open".equals(in.getStatus())&&Boolean.FALSE.equals(in.getAutoAdvance())&&Long.valueOf(0).equals(in.getAmountPaid())
                &&in.getAmountRemaining()!=null&&in.getAmountRemaining()>0&&Boolean.FALSE.equals(in.getAttempted())&&Long.valueOf(0).equals(in.getAttemptCount()),"UNATTEMPTED_OPEN_INVOICE_REQUIRED");
        check("requires_confirmation".equals(pi.getStatus())&&Long.valueOf(0).equals(pi.getAmountReceived())&&pi.getLatestCharge()==null&&pi.getLastPaymentError()==null&&pi.getNextAction()==null
                &&allCharges.isEmpty()&&events.isEmpty(),"UNTOUCHED_PAYMENT_REQUIRED");
        var invoices=new LinkedHashSet<String>();var formal=new LinkedHashSet<String>();var prices=new LinkedHashSet<String>();var subFact=subFact(sub);
        for(var prior:Invoice.list(Map.of("subscription",sub.getId(),"limit",100L),options(null)).autoPagingIterable()){
            check(Boolean.FALSE.equals(prior.getLivemode())&&run.customerId.equals(prior.getCustomer()),"INVOICE_INVENTORY_SCOPE");invoices.add(prior.getId());
            check(prior.getLines()!=null&&Boolean.FALSE.equals(prior.getLines().getHasMore()),"COMPLETE_INVOICE_LINES_REQUIRED");
            for(var line:prior.getLines().getData())if(line.getPrice()!=null){prices.add(line.getPrice().getId());if(subFact.priceId().equals(line.getPrice().getId()))formal.add(prior.getId());}
        }
        var pre=new RecoveryOwnedClockEvidence.PreAttempt(fact,in.getStatus(),in.getAutoAdvance(),in.getAmountPaid(),in.getAmountRemaining(),in.getAttempted(),in.getAttemptCount(),
                in.getStatusTransitions().getFinalizedAt(),new RecoveryOwnedClockEvidence.PrePaymentIntent(pi.getId(),pi.getCustomer(),pi.getLivemode(),pi.getStatus(),pi.getAmountReceived(),null,null,null),
                Set.of(),Set.of(),Set.of(),true,true,true,true,true);
        return new Capture(clockFact(clock),subFact,invoices,formal,prices,pre,Instant.now());
    }
    RecoveryOwnedClockEvidence.Frame after(RecoveryTestClockStore.Run run,RecoveryTestClockStore.Operation operation)throws Exception{
        var in=invoice(run.invoiceId);var sub=subscription(run.subscriptionId);var pi=PaymentIntent.retrieve(in.getPaymentIntent(),options(null));
        var chargeFacts=new ArrayList<RecoveryOwnedClockEvidence.ChargeFact>();
        for(var c:charges(pi.getId()))chargeFacts.add(new RecoveryOwnedClockEvidence.ChargeFact(c.getId(),c.getCustomer(),c.getInvoice(),c.getPaymentIntent(),c.getStatus(),c.getLivemode(),c.getCreated()));
        var events=failures(in.getId());if(events.isEmpty())return null;
        var facts=new ArrayList<RecoveryOwnedClockEvidence.FailureEventFact>();
        for(var e:events){
            var raw=raw(e);String subId=id(raw,"subscription"),schema="ROOT_INVOICE";
            if(subId==null&&raw.has("parent")&&raw.get("parent").isJsonObject()){
                var parent=raw.getAsJsonObject("parent");if("subscription_details".equals(id(parent,"type"))&&parent.has("subscription_details")){
                    subId=id(parent.getAsJsonObject("subscription_details"),"subscription");schema="PARENT_SUBSCRIPTION_DETAILS";
                }
            }
            var envelope=JsonParser.parseString(e.toJson()).getAsJsonObject();var request=envelope.has("request")&&envelope.get("request").isJsonObject()?envelope.getAsJsonObject("request"):null;
            String requestId=request==null?null:id(request,"id"),idem=request==null?null:id(request,"idempotency_key");
            if(operation.requestId==null&&operation.key.equals(idem))operation.requestId=requestId;
            facts.add(new RecoveryOwnedClockEvidence.FailureEventFact(e.getId(),e.getType(),id(raw,"id"),id(raw,"customer"),subId,id(raw,"payment_intent"),e.getLivemode(),e.getCreated(),e.getApiVersion(),schema,requestId));
        }
        if(operation.requestId==null)return null;
        var before=run.before;var clock=clock(run.clockId);ready(clock);
        return new RecoveryOwnedClockEvidence.Frame(new RecoveryOwnedClockEvidence.Scope(run.runId,run.clockId,run.customerId,run.subscriptionId),before.clock(),clockFact(clock),
                before.subscription(),subFact(sub),before.invoiceIds(),before.formalInvoiceIds(),before.usedPriceIds(),operation.requestId,invoiceFact(in),
                new RecoveryOwnedClockEvidence.PaymentIntentFact(pi.getId(),pi.getCustomer(),pi.getLatestCharge(),pi.getLivemode()),chargeFacts,facts,"EXPLICIT_FIRST_PAY",before.preAttempt());
    }
    static RecoveryOwnedClockEvidence.ClockState clockFact(TestClock c){check(Boolean.FALSE.equals(c.getLivemode()),"TEST_CLOCK_REQUIRED");return new RecoveryOwnedClockEvidence.ClockState(c.getId(),c.getStatus(),Instant.ofEpochSecond(c.getFrozenTime()));}
    static RecoveryOwnedClockEvidence.SubscriptionState subFact(Subscription s){
        check(Boolean.FALSE.equals(s.getLivemode())&&s.getItems()!=null&&Boolean.FALSE.equals(s.getItems().getHasMore())&&s.getItems().getData().size()==1,"SINGLE_TEST_SUBSCRIPTION_ITEM_REQUIRED");
        var item=s.getItems().getData().get(0);return new RecoveryOwnedClockEvidence.SubscriptionState(s.getId(),s.getCustomer(),s.getTestClock(),item.getId(),item.getPrice().getId(),s.getLivemode(),item.getQuantity());
    }
    static RecoveryOwnedClockEvidence.InvoiceFact invoiceFact(Invoice in){
        check(Boolean.FALSE.equals(in.getLivemode())&&in.getLines()!=null&&Boolean.FALSE.equals(in.getLines().getHasMore())&&in.getLines().getData().size()==1&&in.getPaymentIntent()!=null,"SINGLE_TEST_INVOICE_LINE_REQUIRED");
        return new RecoveryOwnedClockEvidence.InvoiceFact(in.getId(),in.getCustomer(),in.getSubscription(),in.getPaymentIntent(),in.getLines().getData().get(0).getPrice().getId(),in.getLivemode(),in.getCreated());
    }
    static JsonObject raw(Event e){return JsonParser.parseString(e.getDataObjectDeserializer().getRawJson()).getAsJsonObject();}
    static String id(JsonObject object,String field){var v=object.get(field);return v==null||v.isJsonNull()?null:v.isJsonObject()?v.getAsJsonObject().get("id").getAsString():v.getAsString();}
    static void ready(TestClock c){check(Boolean.FALSE.equals(c.getLivemode())&&"ready".equals(c.getStatus()),"TEST_CLOCK_NOT_READY");}
    static void check(boolean ok,String reason){if(!ok)throw new IllegalStateException("RECOVERY_TEST_CLOCK_"+reason);}
}
