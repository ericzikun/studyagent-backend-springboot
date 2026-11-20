package com.studyagent.infra.service.billing;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stripe.Stripe;
import com.stripe.exception.*;
import com.stripe.model.*;
import com.stripe.model.testhelpers.TestClock;
import com.studyagent.common.datetime.DateTimeFormats;
import com.studyagent.infra.entity.*;
import com.studyagent.infra.mapper.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.Callable;
import static com.studyagent.infra.service.billing.RecoveryTestClockStripe.check;

/** Isolated TEST operator. It supplies evidence, never rewrites recovery rules or due dates. */
@Component
@ConditionalOnProperty(name="billing.payment-recovery.test-clock.enabled",havingValue="true")
public class RecoveryTestClockSupport {
    private final RecoveryTestClockProperties properties;
    private final RecoveryTestClockStore store;
    private final RecoveryTestClockStripe stripe;
    private final UserSubscriptionMapper users;
    private final SubscriptionPaymentRecoveryMapper roots;
    private final TransactionTemplate transactions;
    @Autowired private ObjectProvider<SubscriptionPaymentRecoveryService> recovery;
    @Autowired(required=false) private SubscriptionRecoveryActionMapper actions;
    public RecoveryTestClockSupport(RecoveryTestClockProperties properties,Environment environment,
            @Value("${stripe.secret-key:}")String key,RecoveryTestClockStripe stripe,UserSubscriptionMapper users,
            SubscriptionPaymentRecoveryMapper roots,PlatformTransactionManager transactions)throws Exception{
        properties.validate(environment,key);this.properties=properties;store=new RecoveryTestClockStore(Path.of(properties.getDirectory()));
        this.stripe=stripe;this.users=users;this.roots=roots;this.transactions=new TransactionTemplate(transactions);
    }
    public <T>T locked(Callable<T> task){return store.locked(()->{safeRuntime();return task.call();});}
    void safeRuntime(){check(Stripe.apiKey==null||Stripe.apiKey.startsWith("sk_test_")||Stripe.apiKey.startsWith("rk_test_"),"RUNTIME_KEY_MUST_REMAIN_TEST");}
    public Map<String,Object> register(String runId,String userId,String customerId,String clockId){return locked(()->{
        RecoveryTestClockStore.runId(runId);allowed(userId);
        var prior=store.read(runId);
        if(prior!=null)check(Objects.equals(userId,prior.userId)&&Objects.equals(customerId,prior.customerId)&&Objects.equals(clockId,prior.clockId),"RUN_BINDING_CONFLICT");
        var run=prior==null?new RecoveryTestClockStore.Run():prior;run.runId=runId;run.userId=userId;run.customerId=customerId;run.clockId=clockId;
        scope(run);RecoveryTestClockStripe.ready(stripe.clock(clockId));run.observedBusinessTime=Instant.ofEpochSecond(stripe.clock(clockId).getFrozenTime());store.save(run); // Binding intent precedes the local user-row transaction.
        transactions.executeWithoutResult(tx->{
            users.insertFreeIfAbsent(userId,DateTimeFormats.now());var user=users.selectByUserForUpdate(userId);check(user!=null,"USER_ROW_REQUIRED");
            var owners=users.selectList(new LambdaQueryWrapper<UserSubscriptionEntity>().eq(UserSubscriptionEntity::getStripeCustomerId,customerId));
            check(owners.stream().allMatch(u->userId.equals(u.getClerkUserId())),"CUSTOMER_ALREADY_BOUND");
            check(user.getStripeCustomerId()==null||customerId.equals(user.getStripeCustomerId()),"EXISTING_CUSTOMER_CANNOT_BE_REPLACED");
            if(user.getStripeCustomerId()==null){check(user.getStripeSubscriptionId()==null&&user.getPaymentRecoveryId()==null,"FRESH_ACCOUNT_REQUIRED");user.setStripeCustomerId(customerId);users.updateById(user);}
        });
        return summary(run);
    });}
    public Map<String,Object> nextWindow(String runId,String expectedInvoiceId){return locked(()->{
        var run=required(runId);scope(run);
        check(expectedInvoiceId!=null&&!expectedInvoiceId.isBlank(),"PREVIOUS_INVOICE_REQUIRED");
        // The prior invoice is the transition token: duplicate requests never rotate twice.
        if(run.history.stream().anyMatch(w->expectedInvoiceId.equals(w.invoiceId)))return summary(run);
        check(run.proof!=null&&expectedInvoiceId.equals(run.invoiceId),"PREVIOUS_WINDOW_REQUIRED");
        new RecoveryOwnedClockEvidence.Registry().register(run.proof);
        check(run.operations.values().stream().allMatch(op->Set.of("SUCCEEDED","DECLINED","FAILED").contains(op.state)),"PREVIOUS_OPERATIONS_MUST_BE_SETTLED");
        check(run.history.size()<100,"WINDOW_LIMIT_REACHED");
        var root=roots.selectByInvoice(expectedInvoiceId);var user=users.selectByUser(run.userId);
        check(root!=null&&root.getOwnerRecoveryId()==null&&run.userId.equals(root.getClerkUserId())&&run.subscriptionId.equals(root.getStripeSubscriptionId())
                &&"PAID".equals(root.getStatus())&&"GRANTED".equals(root.getGrantState())&&"CONFIRMED".equals(root.getAnchorSyncState())
                &&"RELEASED".equals(root.getProtectionState())&&Set.of("NONE","RESOLVED").contains(Objects.toString(root.getReviewState(),"NONE")),"PREVIOUS_RECOVERY_MUST_BE_SETTLED");
        check(user!=null&&run.customerId.equals(user.getStripeCustomerId())&&run.subscriptionId.equals(user.getStripeSubscriptionId())&&user.getPaymentRecoveryId()==null,"RELEASED_LOCAL_SUBSCRIPTION_REQUIRED");
        var notifications=Set.of("CAPTURE_FAILURE","NOTIFY_INTERNAL_FAILURE","NOTIFY_REVIEW","SEND_EMAIL");
        check(actions!=null&&actions.selectByRecovery(root.getId()).stream().noneMatch(a->!notifications.contains(Objects.toString(a.getActionType(),""))
                &&Set.of("READY","DISPATCHING","UNKNOWN","SUBMITTED","REVIEW").contains(Objects.toString(a.getStatus(),""))),"PREVIOUS_ACTIONS_MUST_BE_SETTLED");
        var clock=stripe.clock(run.clockId);RecoveryTestClockStripe.ready(clock);var sub=stripe.subscription(run.subscriptionId);subscriptionScope(run,sub);
        check("active".equals(sub.getStatus())&&sub.getPauseCollection()==null&&!Boolean.TRUE.equals(sub.getCancelAtPeriodEnd())&&sub.getCancelAt()==null
                &&sub.getCurrentPeriodEnd()!=null&&sub.getCurrentPeriodEnd()>clock.getFrozenTime(),"NEXT_PAID_BOUNDARY_REQUIRED");
        for(var invoiceId:List.of(expectedInvoiceId,Objects.toString(sub.getLatestInvoice(),""))){
            var invoice=stripe.invoice(invoiceId);invoiceScope(run,invoice);check("paid".equals(invoice.getStatus()),"PREVIOUS_INVOICES_MUST_BE_PAID");
        }
        var previous=new RecoveryTestClockStore.Window();previous.number=run.window;previous.invoiceId=run.invoiceId;
        previous.proof=run.proof;previous.before=run.before;previous.operations=run.operations;run.history.add(previous);
        run.window++;run.invoiceId=null;run.proof=null;run.before=null;run.operations=new LinkedHashMap<>();
        run.observedBusinessTime=Instant.ofEpochSecond(clock.getFrozenTime());store.save(run);return summary(run);
    });}
    public Map<String,Object> pause(String runId,String subscriptionId){return pause(runId,subscriptionId,null);}
    public Map<String,Object> pause(String runId,String subscriptionId,Integer window){return locked(()->{
        var run=required(runId);requireWindow(run,window);scope(run);var user=users.selectByUser(run.userId);var sub=stripe.subscription(subscriptionId);
        subscriptionScope(run,sub);check(user!=null&&subscriptionId.equals(user.getStripeSubscriptionId())&&run.customerId.equals(user.getStripeCustomerId()),"LOCAL_SUBSCRIPTION_BINDING_REQUIRED");
        check(run.proof==null,"FIRST_FAILURE_ALREADY_RECORDED");check(run.subscriptionId==null||run.subscriptionId.equals(subscriptionId),"SUBSCRIPTION_BINDING_CONFLICT");
        run.subscriptionId=subscriptionId;store.save(run);
        var op=operation(run,"pause","PAUSE",subscriptionId,Map.of("pause_collection",Map.of("behavior","keep_as_draft")));
        execute(run,op);sub=stripe.subscription(subscriptionId);
        if(sub.getPauseCollection()!=null&&"keep_as_draft".equals(sub.getPauseCollection().getBehavior())){op.state="SUCCEEDED";store.save(run);}
        return summary(run);
    });}
    public Map<String,Object> firstPay(String runId){return firstPay(runId,null);}
    public Map<String,Object> firstPay(String runId,Integer window){return locked(()->{
        var run=required(runId);requireWindow(run,window);scope(run);check(run.subscriptionId!=null,"PAUSE_SUBSCRIPTION_FIRST");
        var sub=stripe.subscription(run.subscriptionId);subscriptionScope(run,sub);
        check(sub.getPauseCollection()!=null&&"keep_as_draft".equals(sub.getPauseCollection().getBehavior()),"SETUP_PAUSE_REQUIRED");
        var user=users.selectByUser(run.userId);check(user!=null&&run.subscriptionId.equals(user.getStripeSubscriptionId())&&run.customerId.equals(user.getStripeCustomerId()),"LOCAL_SUBSCRIPTION_BINDING_REQUIRED");
        if(run.proof!=null)return summary(run);
        if(run.invoiceId==null){run.invoiceId=sub.getLatestInvoice();check(run.invoiceId!=null,"INVOICE_REQUIRED");store.save(run);}
        check(recovery!=null&&recovery.getIfAvailable()!=null,"APPLICATION_ADMISSION_CHECK_REQUIRED");
        recovery.getObject().requireTestClockAdmission(run.userId,run.invoiceId);
        var invoice=stripe.invoice(run.invoiceId);invoiceScope(run,invoice);
        if("draft".equals(invoice.getStatus())){
            var finalize=operation(run,"finalize","FINALIZE",invoice.getId(),Map.of("auto_advance",false));execute(run,finalize);
            invoice=stripe.invoice(run.invoiceId);if("open".equals(invoice.getStatus())&&Boolean.FALSE.equals(invoice.getAutoAdvance())){finalize.state="SUCCEEDED";store.save(run);}
            else return summary(run);
        }
        if(run.before==null){run.before=stripe.before(run);store.save(run);}
        var pay=operation(run,"first-pay","PAY",run.invoiceId,Map.of("off_session",true));execute(run,pay);
        invoice=stripe.invoice(run.invoiceId);
        if("paid".equals(invoice.getStatus())){pay.state="SUCCEEDED";pay.errorCode="EXPECTED_DECLINE_BUT_PAID";store.save(run);return summary(run);}
        if("FAILED".equals(pay.state))return summary(run);
        var frame=stripe.after(run,pay);
        if(frame!=null){new RecoveryOwnedClockEvidence.Registry().register(frame);run.proof=frame;pay.state="DECLINED";store.save(run);}
        return summary(run);
    });}
    public Map<String,Object> advance(String runId,String checkpoint){return advance(runId,checkpoint,null);}
    public Map<String,Object> advance(String runId,String checkpoint,Integer window){return locked(()->{
        var run=required(runId);requireWindow(run,window);scope(run);check(run.subscriptionId!=null,"PAUSE_SUBSCRIPTION_FIRST");
        var sub=stripe.subscription(run.subscriptionId);subscriptionScope(run,sub);var clock=stripe.clock(run.clockId);
        var prior=run.operations.get("advance-"+checkpoint);
        if(prior!=null){observeAdvance(run,prior);return summary(run);}
        RecoveryTestClockStripe.ready(clock);
        for(var pending:run.operations.values())if("ADVANCE".equals(pending.type)&&!"SUCCEEDED".equals(pending.state)){
            observeAdvance(run,pending);check("SUCCEEDED".equals(pending.state),"PREVIOUS_ADVANCE_REQUIRES_READBACK");
        }
        long target;
        if("NEXT_BOUNDARY".equals(checkpoint)){
            check(run.proof==null&&sub.getPauseCollection()!=null&&"keep_as_draft".equals(sub.getPauseCollection().getBehavior()),"UNATTEMPTED_SETUP_BOUNDARY_REQUIRED");
            check(sub.getCurrentPeriodEnd()!=null,"SUBSCRIPTION_BOUNDARY_REQUIRED");target=sub.getCurrentPeriodEnd();
        }else{
            check(run.proof!=null,"VERIFIED_FIRST_FAILURE_REQUIRED");var root=roots.selectByInvoice(run.invoiceId);
            check(root!=null&&run.userId.equals(root.getClerkUserId())&&run.subscriptionId.equals(root.getStripeSubscriptionId())
                    &&root.getOwnerRecoveryId()==null&&DateTimeFormats.fromInstant(run.proof.beforeClock().frozenTime()).equals(root.getFirstFailedAt()),"APPLICATION_TAKEOVER_REQUIRED");
            var user=users.selectByUser(run.userId);check(user!=null&&(run.subscriptionId.equals(user.getStripeSubscriptionId())||user.getStripeSubscriptionId()==null),"NEW_SUBSCRIPTION_MUST_NOT_BE_ADVANCED");
            target=switch(checkpoint){
                case "D7"->run.proof.beforeClock().frozenTime().plus(Duration.ofDays(7)).getEpochSecond();
                case "D14"->run.proof.beforeClock().frozenTime().plus(Duration.ofDays(14)).getEpochSecond();
                case "D21"->run.proof.beforeClock().frozenTime().plus(Duration.ofDays(21)).getEpochSecond();
                case "D28"->run.proof.beforeClock().frozenTime().plus(Duration.ofDays(28)).getEpochSecond();
                case "D30_BEFORE"->run.proof.beforeClock().frozenTime().plus(Duration.ofDays(30)).minusSeconds(1).getEpochSecond();
                case "D30"->run.proof.beforeClock().frozenTime().plus(Duration.ofDays(30)).getEpochSecond();
                case "MONTH_1","MONTH_2","MONTH_1_AFTER","MONTH_2_AFTER","PAID_END"->{
                    check("PAID".equals(root.getStatus())&&"GRANTED".equals(root.getGrantState())&&root.getEntitlementStartAt()!=null&&root.getEntitlementEndAt()!=null,"CONFIRMED_PAID_CYCLE_REQUIRED");
                    if("PAID_END".equals(checkpoint))yield DateTimeFormats.toEpochSecond(root.getEntitlementEndAt());
                    check("year".equals(root.getBillingInterval()),"ANNUAL_PAID_CYCLE_REQUIRED");
                    long month=root.getEntitlementStartAt().atZone(DateTimeFormats.APP_ZONE).toInstant().atZone(ZoneOffset.UTC)
                            .plusMonths(checkpoint.startsWith("MONTH_1")?1:2).toEpochSecond()+(checkpoint.endsWith("_AFTER")?1:0);
                    check(month<DateTimeFormats.toEpochSecond(root.getEntitlementEndAt()),"CHECKPOINT_MUST_BE_INSIDE_PAID_CYCLE");yield month;
                }
                default->throw new IllegalArgumentException("Unsupported TEST checkpoint");
            };
        }
        check(target>clock.getFrozenTime(),"CHECKPOINT_ALREADY_PASSED");
        var operation=operation(run,"advance-"+checkpoint,"ADVANCE",run.clockId,Map.of("frozen_time",target));execute(run,operation);
        long end=System.nanoTime()+Duration.ofSeconds(90).toNanos();
        do{observeAdvance(run,operation);if("SUCCEEDED".equals(operation.state)||"FAILED".equals(operation.state))break;Thread.sleep(250);}while(System.nanoTime()<end);
        return summary(run);
    });}
    public Map<String,Object> status(String runId){return locked(()->summary(required(runId)));}
    /** Atomic local files only: safe inside quota/user transactions; no network or operator lock. */
    public Instant localTime(String userId,String subscriptionId,String customerId){
        if(userId==null)return null;
        try{
            var bindings=store.bindings().stream().filter(r->userId.equals(r.userId)&&Objects.equals(subscriptionId,r.subscriptionId)&&Objects.equals(customerId,r.customerId)).toList();
            // Revoking operator permission does not revoke facts already captured for this exact owner.
            return bindings.size()==1?bindings.get(0).observedBusinessTime:null;
        }catch(IllegalStateException|IllegalArgumentException unavailable){
            // Read paths must stay available. The access resolver holds the account when time is unknown.
            return null;
        }
    }
    void observeReadyClock(Subscription sub,TestClock clock){locked(()->{
        RecoveryTestClockStripe.ready(clock);String name=clock.getName();
        if(name==null||!name.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{7,79}"))return null;
        var run=store.read(name);if(run==null||run.subscriptionId==null)return null;
        check(run.clockId.equals(clock.getId())&&run.customerId.equals(sub.getCustomer())&&Objects.equals(run.subscriptionId,sub.getId()),"OBSERVED_CLOCK_SCOPE_REQUIRED");
        var observed=Instant.ofEpochSecond(clock.getFrozenTime());
        if(!observed.equals(run.observedBusinessTime)){run.observedBusinessTime=observed;store.save(run);}return null;
    });}
    SubscriptionRecoveryStripeGateway.FirstFailure firstFailure(SubscriptionRecoveryStripeGateway.Snapshot snapshot,Event event){return locked(()->{
        var customer=stripe.customer(snapshot.invoice().getCustomer());var metadata=customer.getMetadata();
        check(metadata!=null&&metadata.get("recovery_test_run")!=null,"REGISTERED_RUN_REQUIRED");var run=required(metadata.get("recovery_test_run"));scope(run);
        var frame=run.proof!=null&&Objects.equals(run.invoiceId,snapshot.invoice().getId())?run.proof:
                run.history.stream().filter(w->Objects.equals(w.invoiceId,snapshot.invoice().getId())).map(w->w.proof).findFirst().orElse(null);
        String invoiceId=snapshot.invoice().getId();
        check(frame!=null&&run.subscriptionId.equals(snapshot.invoice().getSubscription())
                &&run.customerId.equals(snapshot.invoice().getCustomer())&&snapshot.subscription()!=null&&run.clockId.equals(snapshot.subscription().getTestClock()),"VERIFIED_FIRST_FAILURE_REQUIRED");
        var proof=new RecoveryOwnedClockEvidence.Registry().register(frame);var nativeEvent=proof.frame().failureEvents().get(0);
        var currentClock=stripe.clock(run.clockId);RecoveryTestClockStripe.ready(currentClock);
        check(Boolean.FALSE.equals(snapshot.invoice().getLivemode())&&Boolean.FALSE.equals(snapshot.subscription().getLivemode())
                &&snapshot.paymentIntent()!=null&&Boolean.FALSE.equals(snapshot.paymentIntent().getLivemode())
                &&run.customerId.equals(snapshot.paymentIntent().getCustomer())&&snapshot.invoice().getPaymentIntent().equals(snapshot.paymentIntent().getId())
                &&snapshot.charge()!=null&&Boolean.FALSE.equals(snapshot.charge().getLivemode())&&run.customerId.equals(snapshot.charge().getCustomer())
                &&invoiceId.equals(snapshot.charge().getInvoice())&&snapshot.paymentIntent().getId().equals(snapshot.charge().getPaymentIntent())
                &&snapshot.charge().getId().equals(snapshot.paymentIntent().getLatestCharge()),"NATIVE_PAYMENT_ASSOCIATIONS_REQUIRED");
        check(event!=null&&Boolean.FALSE.equals(event.getLivemode())&&nativeEvent.eventId().equals(event.getId())&&nativeEvent.type().equals(event.getType())
                &&Objects.equals(nativeEvent.created(),event.getCreated()),"NATIVE_FIRST_EVENT_REQUIRED");
        var raw=RecoveryTestClockStripe.raw(event);String eventSub=RecoveryTestClockStripe.id(raw,"subscription");
        if(eventSub==null&&raw.has("parent")&&raw.get("parent").isJsonObject()){
            var parent=raw.getAsJsonObject("parent");if("subscription_details".equals(RecoveryTestClockStripe.id(parent,"type"))&&parent.has("subscription_details"))eventSub=RecoveryTestClockStripe.id(parent.getAsJsonObject("subscription_details"),"subscription");
        }
        check(Set.of("2023-10-16","2025-12-15.clover").contains(Objects.toString(event.getApiVersion(),""))&&invoiceId.equals(RecoveryTestClockStripe.id(raw,"id"))
                &&run.customerId.equals(RecoveryTestClockStripe.id(raw,"customer"))&&run.subscriptionId.equals(eventSub),"EVENT_SCOPE_REQUIRED");
        var envelope=com.google.gson.JsonParser.parseString(event.toJson()).getAsJsonObject();
        check(envelope.has("request")&&envelope.get("request").isJsonObject()
                &&proof.frame().operationRequestId().equals(RecoveryTestClockStripe.id(envelope.getAsJsonObject("request"),"id")),"NATIVE_OPERATION_REQUEST_REQUIRED");
        check(snapshot.businessNow()!=null&&!snapshot.businessNow().isBefore(proof.occurredAt())
                &&snapshot.businessNow().equals(Instant.ofEpochSecond(currentClock.getFrozenTime())),"READY_BUSINESS_OBSERVATION_REQUIRED");
        return new SubscriptionRecoveryStripeGateway.FirstFailure(proof.occurredAt(),"registered_test_clock_controlled_operation",nativeEvent.eventId(),proof.frame().charges().get(0).chargeId(),snapshot.businessNow());
    });}
    private RecoveryTestClockStore.Run required(String id){var run=store.read(id);check(run!=null,"REGISTERED_RUN_REQUIRED");allowed(run.userId);return run;}
    private static void requireWindow(RecoveryTestClockStore.Run run,Integer expected){check(run.window==(expected==null?1:expected),"WINDOW_MISMATCH");}
    private void allowed(String user){check(properties.getAllowedUserIds().contains(user),"USER_NOT_ALLOWED");}
    private void scope(RecoveryTestClockStore.Run run)throws Exception{
        allowed(run.userId);var clock=stripe.clock(run.clockId);var customer=stripe.customer(run.customerId);
        check(clock!=null&&Boolean.FALSE.equals(clock.getLivemode())&&run.runId.equals(clock.getName()),"OWNED_CLOCK_REQUIRED");
        check(customer!=null&&Boolean.FALSE.equals(customer.getLivemode())&&!Boolean.TRUE.equals(customer.getDeleted())&&run.clockId.equals(customer.getTestClock())
                &&customer.getMetadata()!=null&&run.runId.equals(customer.getMetadata().get("recovery_test_run"))&&run.userId.equals(customer.getMetadata().get("clerk_user_id")),"OWNED_CUSTOMER_REQUIRED");
    }
    private static void subscriptionScope(RecoveryTestClockStore.Run run,Subscription sub){check(sub!=null&&Boolean.FALSE.equals(sub.getLivemode())&&run.customerId.equals(sub.getCustomer())&&run.clockId.equals(sub.getTestClock()),"OWNED_SUBSCRIPTION_REQUIRED");}
    private static void invoiceScope(RecoveryTestClockStore.Run run,Invoice in){check(in!=null&&Boolean.FALSE.equals(in.getLivemode())&&run.customerId.equals(in.getCustomer())&&run.subscriptionId.equals(in.getSubscription()),"OWNED_INVOICE_REQUIRED");}
    private RecoveryTestClockStore.Operation operation(RecoveryTestClockStore.Run run,String name,String type,String target,Map<String,Object> params){
        var prior=run.operations.get(name);
        if(prior!=null){check(type.equals(prior.type)&&target.equals(prior.target)&&params.equals(prior.parameters),"FROZEN_OPERATION_CONFLICT");return prior;}
        var operation=new RecoveryTestClockStore.Operation();operation.type=type;operation.target=target;operation.parameters=new LinkedHashMap<>(params);operation.key="test-clock:"+run.runId+":"+(run.window==1?"":"window-"+run.window+":")+name;run.operations.put(name,operation);store.save(run);return operation;
    }
    private void execute(RecoveryTestClockStore.Run run,RecoveryTestClockStore.Operation operation){
        if(!"READY".equals(operation.state))return; // UNKNOWN/DISPATCHING are read back, never resent.
        check(!TransactionSynchronizationManager.isActualTransactionActive(),"REMOTE_IO_OUTSIDE_TRANSACTION_REQUIRED");
        operation.state="DISPATCHING";operation.submittedAt=Instant.now();store.save(run);
        try{var object=stripe.write(operation.type,operation.target,operation.parameters,operation.key);operation.requestId=object.getLastResponse()==null?null:object.getLastResponse().requestId();operation.state="ADVANCE".equals(operation.type)?"SUBMITTED":"SUCCEEDED";}
        catch(CardException e){operation.state="DECLINED";operation.requestId=e.getRequestId();operation.errorCode=e.getDeclineCode();}
        catch(InvalidRequestException e){operation.state="FAILED";operation.requestId=e.getRequestId();operation.errorCode=Objects.toString(e.getCode(),"invalid_request");}
        catch(Exception e){operation.state="UNKNOWN";operation.errorCode=e.getClass().getSimpleName();}
        store.save(run);
    }
    private void observeAdvance(RecoveryTestClockStore.Run run,RecoveryTestClockStore.Operation operation)throws Exception{
        if(Set.of("SUCCEEDED","FAILED").contains(operation.state))return;
        var clock=stripe.clock(run.clockId);check(Boolean.FALSE.equals(clock.getLivemode())&&run.runId.equals(clock.getName()),"OWNED_CLOCK_REQUIRED");
        if("ready".equals(clock.getStatus())&&((Number)operation.parameters.get("frozen_time")).longValue()==clock.getFrozenTime()){operation.state="SUCCEEDED";run.observedBusinessTime=Instant.ofEpochSecond(clock.getFrozenTime());store.save(run);}
    }
    private Map<String,Object> summary(RecoveryTestClockStore.Run run){
        var view=new LinkedHashMap<String,Object>();view.put("runId",run.runId);view.put("userId",run.userId);view.put("customerId",run.customerId);view.put("clockId",run.clockId);view.put("subscriptionId",run.subscriptionId);view.put("invoiceId",run.invoiceId);
        view.put("window",run.window);view.put("history",run.history.stream().map(w->Map.of("window",w.number,"invoiceId",w.invoiceId,"firstFailureBusinessAt",w.proof.beforeClock().frozenTime())).toList());
        view.put("firstFailureBusinessAt",run.proof==null?null:run.proof.beforeClock().frozenTime());view.put("observedBusinessTime",run.observedBusinessTime);view.put("operations",run.operations);view.put("testOnly",true);
        var root=run.invoiceId==null?null:roots.selectByInvoice(run.invoiceId);
        if(root!=null){
            check(run.userId.equals(root.getClerkUserId())&&run.subscriptionId.equals(root.getStripeSubscriptionId()),"APPLICATION_SUMMARY_SCOPE_REQUIRED");
            var state=new LinkedHashMap<String,Object>();state.put("id",root.getId());state.put("status",root.getStatus());state.put("firstFailedAt",root.getFirstFailedAt());state.put("deadlineAt",root.getDeadlineAt());state.put("protectionState",root.getProtectionState());state.put("grantState",root.getGrantState());state.put("anchorSyncState",root.getAnchorSyncState());state.put("reviewState",root.getReviewState());state.put("entitlementStartAt",root.getEntitlementStartAt());state.put("entitlementEndAt",root.getEntitlementEndAt());
            if(actions!=null)state.put("actions",actions.selectByRecovery(root.getId()).stream().map(action->{
                var row=new LinkedHashMap<String,Object>();row.put("type",action.getActionType());row.put("retrySlot",action.getRetrySlot());row.put("scheduledAt",action.getScheduledAt());row.put("status",action.getStatus());row.put("transportAttempts",action.getTransportAttempts());row.put("requestId",action.getProviderRequestId());row.put("skipReason",action.getSkipReason());return row;
            }).toList());view.put("recovery",state);
        }
        return view;
    }
}
