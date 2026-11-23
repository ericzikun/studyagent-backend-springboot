package com.studyagent.infra.service.billing;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.stripe.exception.*;
import com.stripe.model.*;
import com.stripe.model.checkout.Session;
import com.stripe.model.testhelpers.TestClock;
import com.stripe.net.RequestOptions;
import com.stripe.param.SubscriptionScheduleUpdateParams;
import com.studyagent.infra.entity.SubscriptionRecoveryActionEntity;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
import java.util.*;

/** All recovery Stripe I/O lives here. Writes require a claimed, frozen, persisted action. */
@Component
public class SubscriptionRecoveryStripeGateway {
    @Autowired(required=false) private RecoveryTestClockSupport testClockSupport;
    private final Clock evidenceClock;
    public SubscriptionRecoveryStripeGateway(){this(Clock.systemUTC());}
    SubscriptionRecoveryStripeGateway(Clock clock){this.evidenceClock=clock;}
    public record FirstFailure(Instant occurredAt,String source,String eventId,String chargeId,Instant businessObservedAt){
        public FirstFailure(Instant occurredAt,String source,String eventId,String chargeId){this(occurredAt,source,eventId,chargeId,null);}
    }
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.USE_LONG_FOR_INTS).enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    public record Snapshot(Invoice invoice, Subscription subscription, SubscriptionSchedule schedule,
            List<InvoiceLineItem> lines, PaymentIntent paymentIntent, Charge charge, Instant businessNow) {}
    public record Preview(Invoice invoice,List<InvoiceLineItem> lines){}
    public record WriteResult(String state, String requestId, String error, StripeObject object) {}

    static Map<String,Object> retryParams(){return Map.of("off_session",true);}
    static Map<String,Object> cancelParams(){return Map.of("invoice_now",false,"prorate",false);}
    static Map<String,Object> finalizeParams(){return Map.of("auto_advance",false);}
    static Map<String,Object> shiftParams(String invoice,Instant end){
        return Map.of("trial_end",end.getEpochSecond(),"proration_behavior","none","pause_collection","",
                "metadata",Map.of("payment_recovery_invoice_id",invoice));
    }
    static String freeze(Map<String,Object> map){
        try{return JSON.writeValueAsString(map);}catch(Exception e){throw new IllegalArgumentException("Invalid recovery action payload",e);}
    }
    static Map<String,Object> thaw(String json){
        try{return JSON.readValue(json,new TypeReference<LinkedHashMap<String,Object>>(){});}catch(Exception e){throw new IllegalArgumentException("Invalid frozen recovery payload",e);}
    }
    public Invoice readInvoiceHeader(String id){outsideTransaction();try{return Invoice.retrieve(id);}catch(StripeException e){throw readFailure(id,e);}}
    public Snapshot readInvoice(String id) {
        outsideTransaction();
        if(testClockSupport!=null)testClockSupport.safeRuntime();
        try {
            Invoice invoice=Invoice.retrieve(id);
            if(invoice.getSubscription()==null) return new Snapshot(invoice,null,null,List.of(),null,null,Instant.now());
            List<InvoiceLineItem> lines=new ArrayList<>();
            if(invoice.getLines()==null)throw new IllegalStateException("RECOVERY_INVOICE_LINES_UNKNOWN:"+id);
            if(Boolean.FALSE.equals(invoice.getLines().getHasMore()))lines.addAll(invoice.getLines().getData());
            else for(var line:invoice.getLines().list(Map.of("limit",100L)).autoPagingIterable())lines.add(line);
            PaymentIntent pi=invoice.getPaymentIntent()==null?null:PaymentIntent.retrieve(invoice.getPaymentIntent());
            Charge charge=pi==null||pi.getLatestCharge()==null?null:Charge.retrieve(pi.getLatestCharge());
            Subscription sub=null;SubscriptionSchedule schedule=null;
            try {
                sub=Subscription.retrieve(invoice.getSubscription());
                if(sub.getSchedule()!=null)schedule=SubscriptionSchedule.retrieve(sub.getSchedule());
            } catch(StripeException metadataFailure) {
                // Cash proof is independent of renewal metadata. Unpaid reads still fail closed.
                if(!"paid".equals(invoice.getStatus()))throw metadataFailure;
            }
            Instant businessNow=Instant.now();
            if(sub!=null && Boolean.FALSE.equals(sub.getLivemode())&&sub.getTestClock()!=null){
                var clock=TestClock.retrieve(sub.getTestClock());
                if(testClockSupport!=null)testClockSupport.observeReadyClock(sub,clock);
                businessNow=Instant.ofEpochSecond(clock.getFrozenTime());
            }
            return new Snapshot(invoice,sub,schedule,List.copyOf(lines),pi,charge,businessNow);
        } catch(StripeException e){throw readFailure(id,e);}
    }
    public FirstFailure readFirstFailure(Snapshot snapshot,Event incoming){
        outsideTransaction();
        var invoice=snapshot.invoice();
        if(invoice.getCreated()==null || incoming.getCreated()==null)throw new IllegalStateException("RECOVERY_FIRST_FAILURE_HISTORY_UNKNOWN:"+invoice.getId());
        boolean testClock=snapshot.subscription()!=null && Boolean.FALSE.equals(snapshot.subscription().getLivemode()) && snapshot.subscription().getTestClock()!=null;
        if(testClock){
            if(testClockSupport!=null)return testClockSupport.firstFailure(snapshot,incoming);
            throw new IllegalStateException("RECOVERY_FIRST_FAILURE_TEST_CLOCK_BUSINESS_EVIDENCE_REQUIRED:"+invoice.getId());
        }
        try{
            long historyStart=invoice.getCreated();
            if(Instant.ofEpochSecond(historyStart).isBefore(evidenceClock.instant().minus(Duration.ofDays(30))))throw new IllegalStateException("RECOVERY_FIRST_FAILURE_HISTORY_EXPIRED:"+invoice.getId());
            var charges=new LinkedHashMap<String,Charge>();FirstFailure first=null;
            if(invoice.getPaymentIntent()!=null)for(var charge:Charge.list(Map.of("payment_intent",invoice.getPaymentIntent(),"limit",100L)).autoPagingIterable()){
                if(!Objects.equals(invoice.getCustomer(),charge.getCustomer()) || !Objects.equals(invoice.getPaymentIntent(),charge.getPaymentIntent())
                        || charge.getInvoice()!=null && !Objects.equals(invoice.getId(),charge.getInvoice()))throw new IllegalStateException("RECOVERY_FIRST_FAILURE_CHARGE_SCOPE_UNKNOWN:"+invoice.getId());
                charges.put(charge.getId(),charge);
            }
            var events=Event.list(Map.of("types",List.of("invoice.payment_failed","invoice.payment_action_required"),"created",Map.of("gte",historyStart),"limit",100L));
            for(var event:events.autoPagingIterable())first=firstFailureEvent(first,event,invoice,charges);
            first=firstFailureEvent(first,incoming,invoice,charges);
            if(first==null || first.occurredAt().isBefore(Instant.ofEpochSecond(invoice.getCreated())))throw new IllegalStateException("RECOVERY_FIRST_FAILURE_HISTORY_UNKNOWN:"+invoice.getId());
            if(Instant.ofEpochSecond(historyStart).isBefore(evidenceClock.instant().minus(Duration.ofDays(30))))throw new IllegalStateException("RECOVERY_FIRST_FAILURE_HISTORY_EXPIRED:"+invoice.getId());
            Instant observed=evidenceClock.instant();
            return new FirstFailure(first.occurredAt(),first.source(),first.eventId(),first.chargeId(),observed);
        }catch(StripeException e){throw readFailure(invoice.getId(),e);}
    }
    private FirstFailure firstFailureEvent(FirstFailure first,Event event,Invoice invoice,Map<String,Charge> charges) throws StripeException {
        if(!Set.of("invoice.payment_failed","invoice.payment_action_required").contains(Objects.toString(event.getType(),"")))return first;
        var raw=com.google.gson.JsonParser.parseString(event.getDataObjectDeserializer().getRawJson()).getAsJsonObject();
        if(!raw.has("id") || !invoice.getId().equals(raw.get("id").getAsString()))return first;
        if(event.getCreated()==null)throw new IllegalStateException("RECOVERY_FIRST_FAILURE_EVENT_TIME_UNKNOWN:"+invoice.getId());
        String chargeId=raw.has("charge") && !raw.get("charge").isJsonNull()?raw.get("charge").isJsonPrimitive()?raw.get("charge").getAsString():raw.getAsJsonObject("charge").get("id").getAsString():null;
        Charge charge=chargeId==null?null:charges.get(chargeId);
        if(charge==null && chargeId!=null){charge=Charge.retrieve(chargeId);if(!Objects.equals(invoice.getCustomer(),charge.getCustomer()) || !Objects.equals(invoice.getId(),charge.getInvoice()))throw new IllegalStateException("RECOVERY_FIRST_FAILURE_EVENT_SCOPE_UNKNOWN:"+invoice.getId());charges.put(chargeId,charge);}
        first=earlier(first,new FirstFailure(Instant.ofEpochSecond(event.getCreated()),"stripe_failure_event_history",event.getId(),chargeId));
        return first;
    }
    private static FirstFailure earlier(FirstFailure current,FirstFailure candidate){return current==null || candidate.occurredAt().isBefore(current.occurredAt())?candidate:current;}
    public List<SubscriptionSchedule> listSubscriptionSchedules(String customerId,String subscriptionId){
        outsideTransaction();try{
            var matches=new ArrayList<SubscriptionSchedule>();
            for(var schedule:SubscriptionSchedule.list(Map.of("customer",customerId,"limit",100L)).autoPagingIterable())
                if(Objects.equals(subscriptionId,schedule.getSubscription()) || Objects.equals(subscriptionId,schedule.getReleasedSubscription()))matches.add(schedule);
            return List.copyOf(matches);
        }catch(StripeException e){throw readFailure(subscriptionId,e);}
    }
    public Subscription readSubscription(String id){outsideTransaction();try{return Subscription.retrieve(id);}catch(StripeException e){throw readFailure(id,e);}}
    public SubscriptionSchedule readSchedule(String id){outsideTransaction();try{return SubscriptionSchedule.retrieve(id);}catch(StripeException e){throw readFailure(id,e);}}
    public Preview preview(String subscriptionId){
        outsideTransaction();try{
            var invoice=Invoice.upcoming(Map.of("subscription",subscriptionId));var lines=new ArrayList<InvoiceLineItem>();
            for(var line:Invoice.upcomingLines(Map.of("subscription",subscriptionId,"limit",100L)).autoPagingIterable())lines.add(line);
            return new Preview(invoice,List.copyOf(lines));
        }catch(StripeException e){throw readFailure(subscriptionId,e);}
    }
    public PaymentMethod readPaymentMethod(String id){outsideTransaction();try{return PaymentMethod.retrieve(id);}catch(StripeException e){throw readFailure(id,e);}}
    public Customer readCustomer(String id){outsideTransaction();try{return Customer.retrieve(id);}catch(StripeException e){throw readFailure(id,e);}}
    public Charge readCharge(String id){outsideTransaction();try{return Charge.retrieve(id);}catch(StripeException e){throw readFailure(id,e);}}
    public Dispute readDispute(String id){outsideTransaction();try{return Dispute.retrieve(id);}catch(StripeException e){throw readFailure(id,e);}}
    public Session findCheckout(String customerId,String orderNo){
        outsideTransaction();try{
            for(var session:Session.list(Map.of("customer",customerId,"limit",100L)).autoPagingIterable())
                if(session.getMetadata()!=null&&orderNo.equals(session.getMetadata().get("order_no")))return session;
            return null;
        }catch(StripeException e){throw readFailure(orderNo,e);}
    }
    public Session readCheckout(String id){outsideTransaction();try{return Session.retrieve(id,com.stripe.param.checkout.SessionRetrieveParams.builder().addExpand("line_items.data.price").build(),null);}catch(StripeException e){throw readFailure(id,e);}}
    public List<Invoice> listCustomerInvoices(String customerId){
        outsideTransaction();try{List<Invoice> invoices=new ArrayList<>();for(var invoice:Invoice.list(Map.of("customer",customerId,"limit",100L)).autoPagingIterable())invoices.add(invoice);return invoices;}
        catch(StripeException e){throw readFailure(customerId,e);}
    }
    public List<Invoice> listInvoices(String subscriptionId){
        outsideTransaction();try{
            List<Invoice> invoices=new ArrayList<>();
            for(var invoice:Invoice.list(Map.of("subscription",subscriptionId,"limit",100L)).autoPagingIterable())invoices.add(invoice);
            return invoices;
        }catch(StripeException e){throw readFailure(subscriptionId,e);}
    }
    public Invoice invoiceForPayment(String paymentIntentId,String chargeId){
        outsideTransaction();try{
            Charge charge=chargeId==null?null:Charge.retrieve(chargeId);
            if(charge!=null&&charge.getInvoice()!=null)return Invoice.retrieve(charge.getInvoice());
            if(paymentIntentId!=null){
                PaymentIntent pi=PaymentIntent.retrieve(paymentIntentId);
                if(pi.getLatestCharge()!=null){charge=Charge.retrieve(pi.getLatestCharge());if(charge.getInvoice()!=null)return Invoice.retrieve(charge.getInvoice());}
            }
            return null;
        }catch(StripeException e){throw readFailure(chargeId==null?paymentIntentId:chargeId,e);}
    }
    public WriteResult execute(SubscriptionRecoveryActionEntity action){
        outsideTransaction();
        if(testClockSupport!=null)testClockSupport.safeRuntime();
        if(!"DISPATCHING".equals(action.getStatus())||action.getProviderIdempotencyKey()==null||action.getPayloadJson()==null)
            throw new IllegalStateException("Recovery writes require a claimed persistent action");
        Map<String,Object> params=thaw(action.getPayloadJson());
        RequestOptions options=RequestOptions.builder().setIdempotencyKey(action.getProviderIdempotencyKey()).setMaxNetworkRetries(0).build();
        String target=action.getTargetId();
        try {
            StripeObject object=switch(action.getActionType()){
                case "DISABLE_ADVANCE", "RESTORE_ADVANCE" -> Invoice.retrieve(target).update(params,options);
                case "PAUSE_COLLECTION", "SHIFT_RENEWAL", "RELEASE_PAUSE", "SET_CANCEL_PREFERENCE" -> Subscription.retrieve(target).update(params,options);
                case "RETRY_PAY" -> Invoice.retrieve(target).pay(params,options);
                case "FINALIZE_INVOICE" -> Invoice.retrieve(target).finalizeInvoice(params,options);
                case "VOID_INVOICE" -> Invoice.retrieve(target).voidInvoice(params,options);
                case "CANCEL_SUBSCRIPTION" -> Subscription.retrieve(target).cancel(params,options);
                case "CANCEL_SCHEDULE" -> SubscriptionSchedule.retrieve(target).cancel(params,options);
                case "RELEASE_SCHEDULE" -> SubscriptionSchedule.retrieve(target).release(params,options);
                case "CREATE_CHECKOUT" -> Session.create(params,options);
                case "CREATE_PORTAL" -> com.stripe.model.billingportal.Session.create(params,options);
                case "EXPIRE_CHECKOUT" -> Session.retrieve(target).expire(params,options);
                case "UPDATE_METHOD" -> {
                    String resource=Objects.toString(params.remove("resource"),"");
                    String method=Objects.toString(params.get("default_payment_method"),"");
                    if("schedule".equals(resource)){
                        if(method.isBlank())throw new IllegalArgumentException("Schedule payment method is required");
                        var scheduleParams=SubscriptionScheduleUpdateParams.builder()
                                .setDefaultSettings(SubscriptionScheduleUpdateParams.DefaultSettings.builder()
                                        .setDefaultPaymentMethod(method).build())
                                .build();
                        yield SubscriptionSchedule.retrieve(target).update(scheduleParams,options);
                    }
                    yield switch(resource){case "subscription"->Subscription.retrieve(target).update(params,options);
                        case "invoice"->Invoice.retrieve(target).update(params,options);
                        default->throw new IllegalArgumentException("Invalid payment-method target");};
                }
                default -> throw new IllegalArgumentException("Unsupported Stripe recovery action: "+action.getActionType());
            };
            return new WriteResult("SUCCEEDED",object.getLastResponse()==null?null:object.getLastResponse().requestId(),null,object);
        }catch(CardException e){
            var nativeError=e.getStripeError();var proof=nativeError==null?null:nativeError.getPaymentIntent();
            if(proof==null)proof=new PaymentIntent();
            // Preserve the real HTTP error response for forward-compatible advice fields without creating an error->PI cycle.
            var receiptError=new com.stripe.model.StripeError();receiptError.setDeclineCode(nativeError!=null&&nativeError.getDeclineCode()!=null?nativeError.getDeclineCode():e.getDeclineCode());
            if(nativeError!=null)receiptError.setLastResponse(nativeError.getLastResponse());
            proof.setLastPaymentError(receiptError);if(proof.getLatestCharge()==null&&e.getCharge()!=null)proof.setLatestCharge(e.getCharge());
            return new WriteResult("DECLINED",e.getRequestId(),e.getDeclineCode(),proof);
        }catch(InvalidRequestException e){
            return new WriteResult("FAILED",e.getRequestId(),Objects.toString(e.getCode(),"invalid_request"),null);
        }catch(StripeException e){
            return new WriteResult("UNKNOWN",e.getRequestId(),e.getClass().getSimpleName(),null);
        }
    }
    private static void outsideTransaction(){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Recovery network I/O cannot run inside a database transaction");
    }
    private static IllegalStateException readFailure(String id,StripeException e){
        return new com.studyagent.service.domain.billing.PaymentRecoveryReadException(
                id,e.getStatusCode(),e.getCode(),e.getRequestId(),e);
    }
}
