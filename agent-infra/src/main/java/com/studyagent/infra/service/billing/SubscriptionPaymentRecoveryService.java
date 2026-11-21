package com.studyagent.infra.service.billing;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.google.gson.*;
import com.stripe.model.*;
import com.stripe.model.checkout.Session;
import com.stripe.net.ApiResource;
import com.stripe.param.checkout.SessionCreateParams;
import com.studyagent.common.datetime.DateTimeFormats;
import com.studyagent.infra.entity.*;
import com.studyagent.infra.mapper.*;
import com.studyagent.service.domain.billing.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.function.BiFunction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Coordinates durable recovery facts. Network reads precede short user/recovery/action/quota transactions. */
@Service
@RequiredArgsConstructor
public class SubscriptionPaymentRecoveryService {
    @org.springframework.beans.factory.annotation.Autowired private SubscriptionContractMutationCoordinator contractMutations;
    private final UserSubscriptionMapper users;
    private final SubscriptionPaymentRecoveryMapper recoveries;
    private final SubscriptionRecoveryActionMapper actions;
    private final SubscriptionRecoveryReplacementMapper replacements;
    private final SubscriptionPlanMapper plans;
    private final RechargeOrderMapper orders;
    private final SubscriptionRecoveryStripeGateway stripe;
    private final PaymentRecoveryProperties config;
    private final PaymentRecoveryAccessService access;
    private final PlatformTransactionManager transactionManager;
    @Autowired private ObjectProvider<BillingQuotaGateway> quotaProvider;
    @Autowired private ZonePricingSelectionService zonePricing;
    @Autowired private StudyPassMapper passes;
    @Autowired private com.studyagent.common.analytics.AnalyticsService analytics;
    @Autowired private ObjectProvider<BillingRobotNotifyGateway> internalNotifications;
    @Autowired private com.studyagent.infra.notification.BillingPaymentRecoveryEmailSender emailSender;
    @Value("${notification.email.frontend-url:https://verla.io}") private String frontendUrl;
    private static final Gson JSON=new Gson();
    private final ThreadLocal<java.util.function.Consumer<StripeObject>> immediateResponse=new ThreadLocal<>();
    private static final Set<String> CLOSED=Set.of("EXPIRED","CANCELED","SWITCHED");
    private static final Set<String> INVOICE_EVENTS=Set.of("invoice.payment_failed","invoice.payment_action_required","invoice.paid","invoice.payment_succeeded","invoice_payment.paid","invoice.finalized","invoice.voided","invoice.marked_uncollectible");
    public record ReplacementReservation(long recoveryId,long replacementId,long actionId,String orderNo,Instant expiresAt){}
    /** Read-only preflight for the isolated TEST operator; ordinary admission remains unchanged. */
    public String requireTestClockAdmission(String userId,String invoiceId){
        var snapshot=stripe.readInvoice(invoiceId);var user=users.selectByUser(userId);
        if(user==null||snapshot.subscription()==null||!Objects.equals(user.getStripeSubscriptionId(),snapshot.subscription().getId())
                ||!Objects.equals(user.getStripeCustomerId(),snapshot.invoice().getCustomer()))throw incomplete(invoiceId);
        Contract contract=contract(snapshot,admissionSource(snapshot,user),trialSignal(snapshot,user));
        if(contract==null||!config.admits(userId,RecoveryCaseKey.parse(contract.caseKey()),epoch(snapshot.invoice().getCreated()),Instant.now()))
            throw new IllegalStateException("RECOVERY_TEST_CLOCK_CASE_NOT_ADMITTED");
        return contract.caseKey();
    }
    private record CancellationTarget(String subscriptionId,String status){}
    private record SourceProvenance(String source,Map<String,Object> evidence){}
    private record SourceInvoice(Invoice invoice,InvoiceLineItem line,SubscriptionPlanEntity plan){}
    private record Contract(SubscriptionPlanEntity plan,String source,String priceId,String interval,int count,String caseKey,String hash,String json){}

    public boolean tryProcessEvent(Event event){
        JsonObject object=eventObject(event);
        String type=event.getType();
        String passTarget=type.startsWith("checkout.session.")||type.startsWith("customer.subscription.")?string(object,"id")
                :type.startsWith("subscription_schedule.")?Objects.toString(string(object,"subscription"),string(object,"released_subscription")):null;
        if(passTarget!=null && routeSupersededStudyPass(passTarget,null))return true;
        if(INVOICE_EVENTS.contains(type)){
            String invoiceId="invoice_payment.paid".equals(type)?string(object,"invoice"):string(object,"id");
            if(invoiceId==null)throw new IllegalStateException("RECOVERY_ADMISSION_INCOMPLETE_EVIDENCE:"+event.getId());
            var owned=recoveries.selectByInvoice(invoiceId);
            if(owned!=null){applyInvoice(owned,stripe.readInvoice(invoiceId),event);return true;}
            String subscriptionId=string(object,"subscription");
            // Newer webhook envelopes omit the legacy root subscription field. Resolve the native
            // header before choosing a handler, including before a SHIFT invoice is registered.
            if(subscriptionId==null && ("invoice_payment.paid".equals(type)||!object.has("subscription")||object.has("parent"))){var header=stripe.readInvoiceHeader(invoiceId);if(header==null)throw incomplete(invoiceId);subscriptionId=header.getSubscription();}
            if(subscriptionId!=null && routeSupersededStudyPass(subscriptionId,Set.of("invoice.paid","invoice.payment_succeeded","invoice_payment.paid").contains(type)?invoiceId:null))return true;
            if(routeReplacementInvoice(subscriptionId,invoiceId,object,event))return true;
            var user=subscriptionId==null?null:users.selectByStripeSubscriptionId(subscriptionId);
            var current=user==null||user.getPaymentRecoveryId()==null?null:recoveries.selectById(user.getPaymentRecoveryId());
            SubscriptionPaymentRecoveryEntity previousPaid=null;
            SubscriptionRecoveryStripeGateway.Snapshot nextCycle=null;
            if(current!=null&&current.getAccountClosedAt()==null&&current.getOwnerRecoveryId()==null){
                var snapshot=stripe.readInvoice(invoiceId);
                if("PAID".equals(current.getStatus())){
                    if(isTechnicalInvoice(current,invoiceId))return true;
                    if(hasPendingShift(current)||awaitsShiftInvoiceRegistration(current,invoiceId))throw new IllegalStateException("RECOVERY_TECHNICAL_INVOICE_PENDING:"+invoiceId);
                    Instant start=firstPeriodStart(snapshot);
                    if(start==null||current.getEntitlementEndAt()==null||start.isBefore(instant(current.getEntitlementEndAt())))
                        return adoptChild(current,snapshot,event);
                    if(!genuineNextCycle(current,snapshot))return adoptChild(current,snapshot,event);
                    previousPaid=current;nextCycle=snapshot;
                    if(isPaid(snapshot))return leaveToLegacy(previousPaid,nextCycle);
                }else return adoptChild(current,snapshot,event);
            }
            if(!config.isAcceptNew()||!Set.of("invoice.payment_failed","invoice.payment_action_required").contains(type))return leaveToLegacy(previousPaid,nextCycle);
            if(config.getAcceptInvoicesCreatedAfter()==null || config.getAllowedUserIds().isEmpty() || config.getVerifiedCohorts().isEmpty()
                    || user!=null && !config.getAllowedUserIds().contains(user.getClerkUserId()))return leaveToLegacy(previousPaid,nextCycle);
            if(event.getCreated()==null)throw incomplete(invoiceId);
            if(epoch(event.getCreated()).isBefore(config.getAcceptInvoicesCreatedAfter()))return leaveToLegacy(previousPaid,nextCycle);
            Long invoiceCreated=nextCycle==null?object.has("created") && !object.get("created").isJsonNull()?object.get("created").getAsLong():null:nextCycle.invoice().getCreated();
            if(invoiceCreated==null){var header=stripe.readInvoiceHeader(invoiceId);if(header==null || header.getCreated()==null)throw incomplete(invoiceId);invoiceCreated=header.getCreated();}
            if(epoch(invoiceCreated).isBefore(config.getAcceptInvoicesCreatedAfter()))return leaveToLegacy(previousPaid,nextCycle);
            var snapshot=nextCycle==null?stripe.readInvoice(invoiceId):nextCycle;
            if(snapshot.subscription()==null){if(snapshot.invoice().getSubscription()!=null)throw incomplete(snapshot.invoice().getId());return false;}
            if(isPaid(snapshot))return false;
            if(user==null)user=users.selectByStripeSubscriptionId(snapshot.subscription().getId());
            if(user==null||!config.getAllowedUserIds().contains(user.getClerkUserId()))return leaveToLegacy(previousPaid,snapshot);
            Contract contract=contract(snapshot,admissionSource(snapshot,user),trialSignal(snapshot,user));
            if(contract==null||!config.admits(user.getClerkUserId(),RecoveryCaseKey.parse(contract.caseKey()),
                    epoch(snapshot.invoice().getCreated()),epoch(event.getCreated())))return leaveToLegacy(previousPaid,snapshot);
            var firstFailure=stripe.readFirstFailure(snapshot,event);
            if(firstFailure==null || firstFailure.occurredAt()==null)throw new IllegalStateException("RECOVERY_FIRST_FAILURE_HISTORY_UNKNOWN:"+snapshot.invoice().getId());
            takeOver(user,snapshot,event,contract,firstFailure);return true;
        }
        if(type.startsWith("checkout.session.")){
            String orderNo=metadata(object,"order_no");
            var replacement=orderNo==null?replacements.selectBySession(string(object,"id")):replacements.selectByOrder(orderNo);
            if(replacement!=null){reconcileReplacement(replacement,event);return true;}
            if("subscription_replacement".equals(metadata(object,"purchase_type")))throw incomplete(string(object,"id"));
            return false;
        }
        if(type.startsWith("customer.subscription.")){
            String orderNo=metadata(object,"order_no");
            if("subscription_replacement".equals(metadata(object,"purchase_type"))){
                var replacement=orderNo==null?null:replacements.selectByOrder(orderNo);
                if(replacement==null)throw incomplete(string(object,"id"));
                if(!"PAID".equals(replacement.getState()))return true;
                return false;
            }
            var root=latestRoot(string(object,"id"));
            if(root==null)return false;
            if("PAID".equals(root.getStatus())){
                var user=users.selectByUser(root.getClerkUserId());
                if(user==null)throw incomplete(root.getStripeInvoiceId());
                if(!currentRecovery(user,root)){
                    // Only the same subscription with a released pointer may resume legacy synchronization.
                    return user.getPaymentRecoveryId()!=null || !Objects.equals(user.getStripeSubscriptionId(),root.getStripeSubscriptionId());
                }
            }
            if("customer.subscription.trial_will_end".equals(type)&&"PAID".equals(root.getStatus()))return true;
            applyInvoice(root,stripe.readInvoice(root.getStripeInvoiceId()),event);return true;
        }
        if(type.startsWith("subscription_schedule.")){
            var root=latestRoot(string(object,"subscription"));
            if(root==null)root=latestRoot(string(object,"released_subscription"));
            if(root==null){var targets=actions.selectByTarget(string(object,"id"));if(!targets.isEmpty())root=recoveries.selectById(targets.get(0).getRecoveryId());}
            if(root==null)return false;reconcile(root.getId());return true;
        }
        if(type.startsWith("charge.")||type.startsWith("credit_note."))return processFinancialEvent(event,object);
        if("customer.updated".equals(type)){
            var targets=recoveries.selectOpenByCustomer(string(object,"id"));
            for(var root:targets)requestReconcile(root.getId());return !targets.isEmpty();
        }
        return false;
    }
    private boolean routeSupersededStudyPass(String targetId,String paidInvoiceId) {
        var targets=actions.selectStudyPassTargets(targetId);if(targets.isEmpty())return false;
        if(targets.stream().map(SubscriptionRecoveryActionEntity::getRecoveryId).distinct().count()!=1)throw incomplete(targetId);
        var action=targets.get(0);var root=recoveries.selectById(action.getRecoveryId());if(root==null)throw incomplete(targetId);
        // This adapter only reads Stripe and persists target-specific child intents; it never writes Stripe directly.
        executeApplicationAction(action,root);
        if(paidInvoiceId!=null){var payment=stripe.readInvoice(paidInvoiceId);locked(root.getId(),(u,r)->{recordSupersededPassReceipt(r,action,null,payment);recoveries.updateById(r);return null;});}
        return true;
    }
    private boolean genuineNextCycle(SubscriptionPaymentRecoveryEntity previous,SubscriptionRecoveryStripeGateway.Snapshot s) {
        Instant end=instant(previous.getEntitlementEndAt());Instant start=firstPeriodStart(s);Instant created=epoch(s.invoice().getCreated());
        return end!=null && start!=null && created!=null && !start.isBefore(end) && !created.isBefore(end)
                && "subscription_cycle".equals(s.invoice().getBillingReason()) && Objects.equals(previous.getStripeSubscriptionId(),s.invoice().getSubscription())
                && Objects.equals(previous.getStripeCustomerId(),s.invoice().getCustomer());
    }
    private boolean leaveToLegacy(SubscriptionPaymentRecoveryEntity previous,SubscriptionRecoveryStripeGateway.Snapshot snapshot) {
        if(previous!=null){
            if(snapshot==null || !genuineNextCycle(previous,snapshot))throw incomplete(previous.getStripeInvoiceId());
            locked(previous.getId(),(u,r)->{
                if(!"PAID".equals(r.getStatus()) || !"GRANTED".equals(r.getGrantState()) || hasPendingShift(r))throw incomplete(snapshot.invoice().getId());
                if(Objects.equals(u.getPaymentRecoveryId(),r.getId()) && Objects.equals(u.getStripeSubscriptionId(),r.getStripeSubscriptionId())){
                    u.setPaymentRecoveryId(null);saveRecoveryUser(u);r.setNextReconcileAt(null);recoveries.updateById(r);
                }
                return null;
            });
        }
        return false;
    }
    private void takeOver(UserSubscriptionEntity before,SubscriptionRecoveryStripeGateway.Snapshot snapshot,Event event,Contract contract,SubscriptionRecoveryStripeGateway.FirstFailure proof){
        var failedCharge=failureCharge(snapshot,event);
        new TransactionTemplate(transactionManager).executeWithoutResult(t->{
            var user=users.selectByUserForUpdate(before.getClerkUserId());
            if(user==null||!Objects.equals(user.getStripeSubscriptionId(),snapshot.subscription().getId())||!Objects.equals(user.getVersion(),before.getVersion()))throw incomplete(snapshot.invoice().getId());
            if(contractMutations!=null && contractMutations.inFlight(snapshot.subscription().getId(),true))throw new IllegalStateException("RECOVERY_CONTRACT_MUTATION_PENDING:"+snapshot.subscription().getId());
            if(recoveries.selectByInvoice(snapshot.invoice().getId())!=null)return;
            var existing=user.getPaymentRecoveryId()==null?null:recoveries.selectForUpdate(user.getPaymentRecoveryId());
            if(existing!=null&&!"PAID".equals(existing.getStatus())&&existing.getAccountClosedAt()==null){insertChild(existing,snapshot,event);return;}
            Instant failed=proof.occurredAt();
            var firstEvidence=new LinkedHashMap<String,Object>();firstEvidence.put("event_id",proof.eventId());firstEvidence.put("charge_id",proof.chargeId());
            firstEvidence.put("observed_event_id",event.getId());firstEvidence.put("observed_event_created",event.getCreated());firstEvidence.put("observed_charge_id",snapshot.charge()==null?null:snapshot.charge().getId());
            firstEvidence.put("selected_at",failed.getEpochSecond());firstEvidence.put("selected_source",proof.source());
            var saved=JsonParser.parseString(contract.json()).getAsJsonObject();saved.add("first_failure_evidence",JSON.toJsonTree(firstEvidence));
            var root=new SubscriptionPaymentRecoveryEntity();fillInvoice(root,snapshot);
            root.setClerkUserId(user.getClerkUserId());root.setPlanCode(contract.plan().getPlanCode());root.setDisplayTier(contract.plan().getTier());
            root.setBillingInterval(contract.interval());root.setIntervalCount(contract.count());root.setContractSnapshot(JSON.toJson(saved));
            root.setFirstFailedAt(local(failed));root.setDeadlineAt(local(SubscriptionRecoveryPolicy.deadline(failed)));
            root.setStatus("open".equals(snapshot.invoice().getStatus())&&automatic(snapshot)&&!"invoice.payment_action_required".equals(event.getType())?"OPEN_AUTO":"OPEN_MANUAL");root.setProtectionState("PENDING");root.setGrantState("NONE");root.setAnchorSyncState("NONE");
            root.setReviewState("NONE");root.setRefundState("NONE");root.setDisputeState("NONE");root.setRefundedAmountMinor(0L);root.setVersion(0);
            setFailure(root,snapshot,event);root.setCreatedAt(DateTimeFormats.now());root.setUpdatedAt(DateTimeFormats.now());root.setNextReconcileAt(DateTimeFormats.now());
            recoveries.insert(root);
            user.setPaymentRecoveryId(root.getId());user.setGraceEndAt(null);mergeUserStripeFacts(user,root,snapshot);saveRecoveryUser(user);
            if(existing!=null && "PAID".equals(existing.getStatus())){existing.setNextReconcileAt(null);recoveries.updateById(existing);}
            protect(root,snapshot);
            if("OPEN_AUTO".equals(root.getStatus()))for(int slot=1;slot<=4;slot++){
                Instant scheduled=SubscriptionRecoveryPolicy.retryAt(failed,slot);
                var retry=addAction(root,"RETRY_PAY","recovery:"+root.getStripeInvoiceId()+":retry:"+slot,root.getStripeInvoiceId(),SubscriptionRecoveryStripeGateway.retryParams(),slot,scheduled);
                if(!scheduled.isAfter(proof.businessObservedAt()==null?snapshot.businessNow():proof.businessObservedAt())){retry.setStatus("SKIPPED");retry.setSkipReason("SKIPPED_LATE_ADMISSION");actions.updateById(retry);}
            }
            failureActions(root,snapshot,event,null,failedCharge);
        });
    }
    private Contract contract(SubscriptionRecoveryStripeGateway.Snapshot snapshot,SourceProvenance provenance){
        return contract(snapshot,provenance,trialSignal(snapshot,null));
    }
    private Contract contract(SubscriptionRecoveryStripeGateway.Snapshot snapshot,SourceProvenance provenance,boolean hasTrialSignal){
        if(snapshot.subscription()==null || snapshot.lines()==null||snapshot.lines().isEmpty())throw incomplete(snapshot.invoice().getId());
        var lines=snapshot.lines().stream().filter(l->!Boolean.TRUE.equals(l.getProration())).toList();
        if(lines.size()!=1||!"subscription".equals(lines.get(0).getType())||lines.get(0).getPrice()==null||lines.get(0).getPrice().getRecurring()==null){return null;}
        var line=lines.get(0);var price=line.getPrice();
        if(line.getSubscription()!=null&&!snapshot.subscription().getId().equals(line.getSubscription()))throw incomplete(line.getId());
        var plan=planForPrice(price.getId());if(plan==null||!Set.of("basic","plus","pro").contains(plan.getTier())||IntroTrialPlans.isIntroTrialPlanCode(plan.getPlanCode())){return null;}
        String source=provenance==null?"formal":provenance.source();
        if(provenance==null&&snapshot.schedule()!=null&&snapshot.schedule().getCurrentPhase()!=null&&line.getPeriod()!=null
                &&Objects.equals(snapshot.schedule().getCurrentPhase().getStartDate(),line.getPeriod().getStart())){
            for(var phase:snapshot.schedule().getPhases()==null?List.<SubscriptionSchedule.Phase>of():snapshot.schedule().getPhases()){
                if(Objects.equals(phase.getEndDate(),line.getPeriod().getStart())&&phase.getItems()!=null&&phase.getItems().size()==1){
                    var intro=planForPrice(phase.getItems().get(0).getPrice());
                    if(intro!=null&&(IntroTrialPlans.isBasicIntroTrialPlanCode(intro.getPlanCode())||IntroTrialPlans.isProSubscriptionTrialPlanCode(intro.getPlanCode())))source=intro.getPlanCode();
                }
            }
        }
        if(provenance==null && "formal".equals(source) && hasTrialSignal){
            boolean laterPhase=snapshot.schedule()!=null && snapshot.schedule().getCurrentPhase()!=null && line.getPeriod()!=null
                    && snapshot.schedule().getCurrentPhase().getStartDate()!=null && line.getPeriod().getStart()!=null
                    && line.getPeriod().getStart()>snapshot.schedule().getCurrentPhase().getStartDate();
            if(!laterPhase)throw new IllegalStateException("RECOVERY_TRIAL_SOURCE_UNKNOWN:"+snapshot.invoice().getId());
        }
        if(!"subscription_cycle".equals(snapshot.invoice().getBillingReason())&&"formal".equals(source)){return null;}
        var classified=RecoveryContractClassifier.classify(snapshot.subscription(),snapshot.schedule(),true,price.getId(),line.getQuantity());
        if("UNKNOWN".equals(classified.scheduleClass()))throw new IllegalStateException("RECOVERY_ADMISSION_UNKNOWN_SCHEDULE:"+snapshot.subscription().getId());
        if(!Set.of("NO_SCHEDULE","EQUIVALENT_SCHEDULE").contains(classified.scheduleClass())){return null;}
        int count=Math.toIntExact(price.getRecurring().getIntervalCount());String interval=price.getRecurring().getInterval();
        RecoveryCaseKey key;
        try{key=new RecoveryCaseKey(source,plan.getPlanCode(),interval,count,classified.scheduleClass(),classified.cancelMode());}
        catch(IllegalArgumentException e){return null;}
        Map<String,Object> admission=new LinkedHashMap<>();admission.put("sourceKey",source);admission.put("targetPlanCode",plan.getPlanCode());admission.put("interval",interval);admission.put("intervalCount",count);
        admission.put("caseKey",key.value());admission.put("silentCleanupVerified",config.getVerifiedCohorts().contains(key.value()));admission.put("scheduleClass",classified.scheduleClass());admission.put("cancelMode",classified.cancelMode());admission.put("contractHash",classified.contractHash());
        admission.put("scheduleContract",JsonParser.parseString(classified.contractJson()));admission.put("checkedAt",Instant.now().toString());
        Map<String,Object> saved=new LinkedHashMap<>();saved.put("recovery_admission",admission);saved.put("price_id",price.getId());saved.put("subscription_item_id",line.getSubscriptionItem());
        if(provenance!=null)saved.put("source_provenance",provenance.evidence());
        saved.put("line_id",line.getId());saved.put("period_start",line.getPeriod()==null?null:line.getPeriod().getStart());saved.put("period_end",line.getPeriod()==null?null:line.getPeriod().getEnd());saved.put("quantity",line.getQuantity());
        saved.put("invoice_amount_due",snapshot.invoice().getAmountDue());saved.put("invoice_total",snapshot.invoice().getTotal());saved.put("currency",snapshot.invoice().getCurrency());
        if(zonePricing!=null){var binding=zonePricing.registryBinding(price.getId());if(binding.isPresent()){
            if(!Objects.equals(binding.get().skuCode(),plan.getPlanCode()) || !"subscription_plan".equals(binding.get().skuKind()))throw incomplete(snapshot.invoice().getId());
            saved.put("pricing_zone",binding.get().zoneCode());saved.put("price_version",binding.get().priceVersion());saved.put("pricing_binding_source","original_price_registry");
        }}
        var details=snapshot.invoice().getSubscriptionDetails();String country=details!=null && details.getMetadata()!=null?details.getMetadata().get("pricing_country"):null;
        if(country==null && snapshot.invoice().getMetadata()!=null)country=snapshot.invoice().getMetadata().get("pricing_country");saved.put("pricing_country",country);
        saved.put("unit_amount",price.getUnitAmount());saved.put("discounts",line.getDiscountAmounts());saved.put("taxes",line.getTaxAmounts());
        return new Contract(plan,source,price.getId(),interval,count,key.value(),classified.contractHash(),JSON.toJson(saved));
    }
    private static boolean trialSignal(SubscriptionRecoveryStripeGateway.Snapshot snapshot,UserSubscriptionEntity user) {
        var metadata=snapshot.subscription()==null?null:snapshot.subscription().getMetadata();
        return metadata!=null && (IntroTrialPlans.PURCHASE_TYPE_INTRO_TRIAL.equals(metadata.get("purchase_type")) || IntroTrialPlans.isIntroTrialPlanCode(metadata.get("plan_code")))
                || user!=null && (IntroTrialPlans.isIntroTrialPlanCode(user.getPlanCode()) || IntroTrialPlans.PHASE_INTRO.equals(user.getSubscriptionPhase()));
    }
    private SourceProvenance admittedSource(SubscriptionPaymentRecoveryEntity root) {
        if(root.getContractSnapshot()==null)return null;
        var original=JsonParser.parseString(root.getContractSnapshot()).getAsJsonObject();String source=string(original.getAsJsonObject("recovery_admission"),"sourceKey");
        return source==null?null:new SourceProvenance(source,Map.of("kind","previously_admitted_source","source",source));
    }
    private SourceProvenance admissionSource(SubscriptionRecoveryStripeGateway.Snapshot snapshot,UserSubscriptionEntity user) {
        if(snapshot.subscription().getSchedule()!=null || !trialSignal(snapshot,user) || snapshot.lines()==null || snapshot.lines().size()!=1)return null;
        var line=snapshot.lines().get(0);var price=line.getPrice();
        if(price==null || price.getRecurring()==null || !Set.of("month","year").contains(price.getRecurring().getInterval()))return null;
        if(line.getPeriod()==null || line.getPeriod().getStart()==null)throw incomplete(snapshot.invoice().getId());
        Long boundary=null;String source=null;SubscriptionSchedule original=null;
        for(var schedule:stripe.listSubscriptionSchedules(snapshot.invoice().getCustomer(),snapshot.subscription().getId())){
            if(!Objects.equals(snapshot.invoice().getCustomer(),schedule.getCustomer()) || schedule.getPhases()==null)continue;
            var phases=schedule.getPhases();
            for(int index=1;index<phases.size();index++){
                var prior=phases.get(index-1);var next=phases.get(index);
                if(prior.getEndDate()==null || !Objects.equals(prior.getEndDate(),next.getStartDate()) || prior.getItems()==null || prior.getItems().size()!=1 || next.getItems()==null || next.getItems().size()!=1)continue;
                var intro=planForPrice(prior.getItems().get(0).getPrice());var formal=planForPrice(next.getItems().get(0).getPrice());
                if(intro==null || !IntroTrialPlans.isIntroTrialPlanCode(intro.getPlanCode()) || IntroTrialPlans.isOneTimeProTrialPlanCode(intro.getPlanCode()) || formal==null || IntroTrialPlans.isIntroTrialPlanCode(formal.getPlanCode()))continue;
                long start=next.getStartDate();Long ended="released".equals(schedule.getStatus())?schedule.getReleasedAt():"completed".equals(schedule.getStatus())?schedule.getCompletedAt():"canceled".equals(schedule.getStatus())?schedule.getCanceledAt():null;
                if(ended==null || ended<start)continue;
                if(boundary!=null && (!Objects.equals(boundary,start) || !Objects.equals(source,intro.getPlanCode())))throw new IllegalStateException("RECOVERY_TRIAL_SOURCE_AMBIGUOUS:"+snapshot.invoice().getId());
                boundary=start;source=intro.getPlanCode();original=schedule;
            }
        }
        if(boundary==null)return invoiceHistorySource(snapshot);
        if(line.getPeriod().getStart()<boundary)throw new IllegalStateException("RECOVERY_TRIAL_SOURCE_UNKNOWN:"+snapshot.invoice().getId());
        String actualSource=Objects.equals(line.getPeriod().getStart(),boundary)?source:"formal";
        var evidence=new LinkedHashMap<String,Object>();evidence.put("kind","historical_schedule");evidence.put("schedule",JsonParser.parseString(original.toJson()));evidence.put("first_formal_period_start",boundary);evidence.put("original_trial_source",source);evidence.put("source",actualSource);
        return new SourceProvenance(actualSource,evidence);
    }
    private SourceProvenance invoiceHistorySource(SubscriptionRecoveryStripeGateway.Snapshot current){
        String unknown="RECOVERY_TRIAL_SOURCE_UNKNOWN:"+current.invoice().getId();
        var history=new ArrayList<>(stripe.listInvoices(current.subscription().getId()));
        if(history.stream().noneMatch(i->Objects.equals(i.getId(),current.invoice().getId())))history.add(current.invoice());
        var facts=new ArrayList<SourceInvoice>();
        for(var listed:history){
            if("draft".equals(listed.getStatus()))continue;
            Invoice invoice=listed;List<InvoiceLineItem> lines;
            if(Objects.equals(listed.getId(),current.invoice().getId()))lines=current.lines();
            else if(listed.getLines()!=null && Boolean.FALSE.equals(listed.getLines().getHasMore()))lines=listed.getLines().getData();
            else{var complete=stripe.readInvoice(listed.getId());if(complete==null)throw new IllegalStateException(unknown);invoice=complete.invoice();lines=complete.lines();}
            if(!Objects.equals(current.invoice().getCustomer(),invoice.getCustomer()) || !Objects.equals(current.subscription().getId(),invoice.getSubscription()))throw new IllegalStateException(unknown);
            if(lines==null || invoice.getCurrency()==null)throw new IllegalStateException(unknown);
            if(lines.stream().noneMatch(l->"subscription".equals(l.getType())))continue;
            if(lines.size()!=1)throw new IllegalStateException(unknown);
            var line=lines.get(0);
            if(Boolean.TRUE.equals(line.getProration()) || line.getPrice()==null || line.getPrice().getRecurring()==null || line.getPeriod()==null || line.getPeriod().getStart()==null || line.getPeriod().getEnd()==null
                    || line.getPeriod().getStart()>=line.getPeriod().getEnd() || line.getQuantity()==null || line.getQuantity()<=0
                    || line.getSubscription()!=null && !Objects.equals(invoice.getSubscription(),line.getSubscription()))throw new IllegalStateException(unknown);
            var plan=planForPrice(line.getPrice().getId());if(plan==null)throw new IllegalStateException(unknown);facts.add(new SourceInvoice(invoice,line,plan));
        }
        var formals=facts.stream().filter(f->!IntroTrialPlans.isIntroTrialPlanCode(f.plan().getPlanCode()) && Set.of("basic","plus","pro").contains(f.plan().getTier())
                && Set.of("month","year").contains(f.line().getPrice().getRecurring().getInterval())).toList();
        Long boundary=formals.stream().map(f->f.line().getPeriod().getStart()).min(Long::compareTo).orElse(null);
        if(boundary==null || boundary>current.businessNow().getEpochSecond() || current.lines().get(0).getPeriod().getStart()<boundary)throw new IllegalStateException(unknown);
        if(formals.stream().filter(f->Objects.equals(boundary,f.line().getPeriod().getStart())).map(f->f.plan().getPlanCode()).distinct().count()!=1)throw new IllegalStateException("RECOVERY_TRIAL_SOURCE_AMBIGUOUS:"+current.invoice().getId());
        SourceInvoice prior=null,first=null;String source=null;
        for(var formal:formals){
            if(!Objects.equals(boundary,formal.line().getPeriod().getStart()))continue;
            for(var trial:facts){
                var recurring=trial.line().getPrice().getRecurring();
                if(!IntroTrialPlans.isIntroTrialPlanCode(trial.plan().getPlanCode()) || IntroTrialPlans.isOneTimeProTrialPlanCode(trial.plan().getPlanCode())
                        || !"paid".equals(trial.invoice().getStatus()) || trial.invoice().getAmountPaid()==null || trial.invoice().getAmountPaid()<=0 || !Boolean.FALSE.equals(trial.invoice().getPaidOutOfBand())
                        || !"week".equals(recurring.getInterval()) || !Long.valueOf(1).equals(recurring.getIntervalCount())
                        || !Objects.equals(boundary,trial.line().getPeriod().getEnd()) || !Objects.equals(trial.line().getQuantity(),formal.line().getQuantity())
                        || !Objects.equals(trial.plan().getTier(),formal.plan().getTier()) || !Objects.equals(trial.invoice().getCurrency(),formal.invoice().getCurrency()))continue;
                String original=current.subscription().getMetadata()==null?null:current.subscription().getMetadata().get("plan_code");
                if(IntroTrialPlans.isIntroTrialPlanCode(original) && !Objects.equals(original,trial.plan().getPlanCode()))throw new IllegalStateException("RECOVERY_TRIAL_SOURCE_AMBIGUOUS:"+current.invoice().getId());
                if(source!=null && (!Objects.equals(source,trial.plan().getPlanCode()) || !Objects.equals(first.plan().getPlanCode(),formal.plan().getPlanCode())))throw new IllegalStateException("RECOVERY_TRIAL_SOURCE_AMBIGUOUS:"+current.invoice().getId());
                prior=trial;first=formal;source=trial.plan().getPlanCode();
            }
        }
        if(source==null)throw new IllegalStateException(unknown);
        boolean firstPeriod=Objects.equals(boundary,current.lines().get(0).getPeriod().getStart());
        var target=planForPrice(current.lines().get(0).getPrice().getId());
        if(firstPeriod && (target==null || !Objects.equals(target.getPlanCode(),first.plan().getPlanCode())))throw new IllegalStateException("RECOVERY_TRIAL_SOURCE_AMBIGUOUS:"+current.invoice().getId());
        String actual=firstPeriod?source:"formal";
        var evidence=new LinkedHashMap<String,Object>();evidence.put("kind","historical_invoice_pair");evidence.put("trial_invoice",sourceInvoiceProof(prior));evidence.put("first_formal_invoice",sourceInvoiceProof(first));
        evidence.put("first_formal_period_start",boundary);evidence.put("original_trial_source",source);evidence.put("source",actual);return new SourceProvenance(actual,evidence);
    }
    private static Map<String,Object> sourceInvoiceProof(SourceInvoice fact){
        var proof=new LinkedHashMap<String,Object>();proof.put("invoice_id",fact.invoice().getId());proof.put("customer_id",fact.invoice().getCustomer());proof.put("subscription_id",fact.invoice().getSubscription());
        proof.put("price_id",fact.line().getPrice().getId());proof.put("plan_code",fact.plan().getPlanCode());proof.put("period_start",fact.line().getPeriod().getStart());proof.put("period_end",fact.line().getPeriod().getEnd());
        proof.put("billing_reason",fact.invoice().getBillingReason());proof.put("status",fact.invoice().getStatus());proof.put("amount_paid",fact.invoice().getAmountPaid());proof.put("currency",fact.invoice().getCurrency());
        proof.put("quantity",fact.line().getQuantity());proof.put("subscription_item_id",fact.line().getSubscriptionItem());proof.put("paid_out_of_band",fact.invoice().getPaidOutOfBand());
        if(fact.line().getPrice()!=null&&fact.line().getPrice().getRecurring()!=null){proof.put("recurring_interval",fact.line().getPrice().getRecurring().getInterval());proof.put("recurring_interval_count",fact.line().getPrice().getRecurring().getIntervalCount());}
        return proof;
    }
    private boolean routeReplacementInvoice(String subscriptionId,String invoiceId,JsonObject object,Event event){
        var replacement=subscriptionId==null?null:replacements.selectByNewSubscription(subscriptionId);
        String orderNo=metadata(object,"order_no");
        if(orderNo==null&&object.has("subscription_details")&&object.get("subscription_details").isJsonObject())orderNo=metadata(object.getAsJsonObject("subscription_details"),"order_no");
        if(replacement==null&&orderNo!=null)replacement=replacements.selectByOrder(orderNo);
        if(replacement==null&&subscriptionId!=null){
            boolean pending=false;
            for(var r:recoveries.selectOpenByCustomer(string(object,"customer")))if(replacements.selectActive(r.getStripeSubscriptionId())!=null){pending=true;break;}
            if(pending){var sub=stripe.readSubscription(subscriptionId);if(sub.getMetadata()!=null&&"subscription_replacement".equals(sub.getMetadata().get("purchase_type"))){
                replacement=replacements.selectByOrder(sub.getMetadata().get("order_no"));if(replacement==null)throw incomplete(invoiceId);
            }}
        }
        if(replacement==null){if("subscription_replacement".equals(metadata(object,"purchase_type")))throw incomplete(invoiceId);return false;}
        if("PAID".equals(replacement.getState())){
            var order=orders.selectOne(new LambdaQueryWrapper<RechargeOrderEntity>().eq(RechargeOrderEntity::getOrderNo,replacement.getOrderNo()));
            return order!=null&&Objects.equals(invoiceId,order.getStripeInvoiceId());
        }
        reconcileReplacement(replacement,event);return true;
    }
    private boolean windowHasInFlight(SubscriptionRecoveryStripeGateway.Snapshot primary,List<Invoice> invoices){
        if(paymentInFlight(primary))return true;
        for(var invoice:invoices)if(!Objects.equals(invoice.getId(),primary.invoice().getId())&&!Set.of("paid","void").contains(invoice.getStatus())&&invoice.getPaymentIntent()!=null)
            if(paymentInFlight(stripe.readInvoice(invoice.getId())))return true;
        return false;
    }
    private SubscriptionPlanEntity planForPrice(String price){
        if(price==null)return null;
        if(zonePricing!=null){var binding=zonePricing.registryBinding(price);if(binding.isPresent()&&"subscription_plan".equals(binding.get().skuKind()))
            return plans.selectOne(new LambdaQueryWrapper<SubscriptionPlanEntity>().eq(SubscriptionPlanEntity::getPlanCode,binding.get().skuCode()));}
        var matches=plans.selectList(new LambdaQueryWrapper<SubscriptionPlanEntity>().eq(SubscriptionPlanEntity::getStripePriceId,price));
        return matches.size()==1?matches.get(0):null;
    }
    private boolean adoptChild(SubscriptionPaymentRecoveryEntity root,SubscriptionRecoveryStripeGateway.Snapshot snapshot,Event event){
        if(!belongsToWindow(root,snapshot))return false;
        if(snapshot.subscription()==null||!root.getStripeSubscriptionId().equals(snapshot.subscription().getId()))throw incomplete(snapshot.invoice().getId());
        locked(root.getId(),(u,r)->{insertChild(r,snapshot,event);return null;});
        var child=recoveries.selectByInvoice(snapshot.invoice().getId());if(child==null)throw incomplete(snapshot.invoice().getId());
        applyInvoice(child,snapshot,event);return true;
    }
    private boolean belongsToWindow(SubscriptionPaymentRecoveryEntity root,SubscriptionRecoveryStripeGateway.Snapshot snapshot){
        if(!Objects.equals(root.getStripeSubscriptionId(),snapshot.invoice().getSubscription()) || !Objects.equals(root.getStripeCustomerId(),snapshot.invoice().getCustomer()))throw incomplete(snapshot.invoice().getId());
        if("PAID".equals(root.getStatus()) && genuineNextCycle(root,snapshot))return false;
        if(!isPaid(snapshot))return true; // The old subscription's unpaid targets still require protection/closure.
        if(root.getFirstFailedAt()==null)throw incomplete(root.getStripeInvoiceId());
        Instant paid=snapshot.invoice().getStatusTransitions()==null?null:epoch(snapshot.invoice().getStatusTransitions().getPaidAt());
        if(paid!=null)return !paid.isBefore(instant(root.getFirstFailedAt()));
        Instant created=epoch(snapshot.invoice().getCreated());
        if(created==null || created.isBefore(instant(root.getFirstFailedAt())))throw new IllegalStateException("RECOVERY_PAYMENT_WINDOW_UNKNOWN:"+snapshot.invoice().getId());
        return true;
    }
    private void insertChild(SubscriptionPaymentRecoveryEntity root,SubscriptionRecoveryStripeGateway.Snapshot snapshot,Event event){
        if(recoveries.selectByInvoice(snapshot.invoice().getId())!=null)return;
        var child=new SubscriptionPaymentRecoveryEntity();fillInvoice(child,snapshot);
        child.setOwnerRecoveryId(root.getId());child.setClerkUserId(root.getClerkUserId());child.setPlanCode(root.getPlanCode());child.setDisplayTier(root.getDisplayTier());
        child.setBillingInterval(root.getBillingInterval());child.setIntervalCount(root.getIntervalCount());child.setContractSnapshot(root.getContractSnapshot());
        child.setFirstFailedAt(root.getFirstFailedAt());child.setDeadlineAt(root.getDeadlineAt());child.setStatus(root.getStatus());child.setProtectionState("PENDING");
        child.setGrantState("NONE");child.setAnchorSyncState("NONE");child.setReviewState("NONE");child.setRefundState("NONE");child.setDisputeState("NONE");child.setVersion(0);
        recoveries.insert(child);addAction(root,"DISABLE_ADVANCE","recovery:"+child.getStripeInvoiceId()+":disable-advance",child.getStripeInvoiceId(),Map.of("auto_advance",false),null,Instant.now());
    }
    private void protect(SubscriptionPaymentRecoveryEntity root,SubscriptionRecoveryStripeGateway.Snapshot snapshot){
        addAction(root,"DISABLE_ADVANCE","recovery:"+root.getStripeInvoiceId()+":disable-advance",root.getStripeInvoiceId(),Map.of("auto_advance",false),null,Instant.now());
        addAction(root,"PAUSE_COLLECTION","recovery:"+root.getId()+":pause:"+root.getStripeSubscriptionId(),root.getStripeSubscriptionId(),Map.of("pause_collection",Map.of("behavior","keep_as_draft")),null,Instant.now());
    }
    private void setFailure(SubscriptionPaymentRecoveryEntity root,SubscriptionRecoveryStripeGateway.Snapshot snapshot,Event event){
        var error=snapshot.paymentIntent()==null?null:snapshot.paymentIntent().getLastPaymentError();
        String decline=error==null?null:error.getDeclineCode();String advice=paymentErrorField(snapshot,"advice_code");String networkCode=paymentErrorField(snapshot,"network_advice_code");
        advice=SubscriptionRecoveryPolicy.normalizeAdvice(advice,null,networkCode);
        root.setLatestDeclineCode(decline);root.setLatestAdviceCode(advice);
        Map<String,Object> evidence=new LinkedHashMap<>();evidence.put("event_id",event.getId());evidence.put("event_created",event.getCreated());
        evidence.put("charge_id",snapshot.charge()==null?null:snapshot.charge().getId());evidence.put("payment_intent_id",snapshot.invoice().getPaymentIntent());
        evidence.put("decline_code",decline);evidence.put("advice_code",advice);evidence.put("network_advice_code",networkCode);
        root.setFailureEvidence(JSON.toJson(evidence));root.setLastStripeEventId(event.getId());root.setLastStripeEventCreatedAt(event.getCreated());
    }
    private static boolean automatic(SubscriptionRecoveryStripeGateway.Snapshot snapshot){
        var error=snapshot.paymentIntent()==null?null:snapshot.paymentIntent().getLastPaymentError();
        return error!=null&&SubscriptionRecoveryPolicy.allowsAutomaticRetry(error.getDeclineCode(),
                SubscriptionRecoveryPolicy.normalizeAdvice(paymentErrorField(snapshot,"advice_code"),null,paymentErrorField(snapshot,"network_advice_code")));
    }
    // SDK 24 keeps raw JSON on the top-level response, not on nested StripeError objects.
    private static String paymentErrorField(SubscriptionRecoveryStripeGateway.Snapshot snapshot,String key){
        var intent=snapshot.paymentIntent();if(intent==null)return null;
        var parent=intent.getRawJsonObject();var nativeError=jsonObject(parent,"last_payment_error");String value=string(nativeError,key);if(value!=null)return value;
        var error=intent.getLastPaymentError();var errorRaw=error==null?null:error.getRawJsonObject();
        var wrapped=jsonObject(errorRaw,"error");if(wrapped==null)wrapped=errorRaw;
        value=string(wrapped,key);if(value!=null)return value;
        return string(jsonObject(jsonObject(wrapped,"payment_intent"),"last_payment_error"),key);
    }
    private static JsonObject jsonObject(JsonObject parent,String key){
        return parent!=null&&parent.has(key)&&parent.get(key).isJsonObject()?parent.getAsJsonObject(key):null;
    }
    // Event identity and current collection state are separate. Never bind an old callback to PI.latest_charge.
    private Charge failureCharge(SubscriptionRecoveryStripeGateway.Snapshot snapshot,Event event){
        if(!Set.of("invoice.payment_failed","invoice.payment_action_required").contains(event.getType()))return null;
        String id=string(eventObject(event),"charge");if(id==null)return null;
        Charge charge=snapshot.charge()!=null && Objects.equals(id,snapshot.charge().getId())?snapshot.charge():stripe.readCharge(id);
        if(charge==null || !Objects.equals(id,charge.getId()) || !Objects.equals(snapshot.invoice().getCustomer(),charge.getCustomer())
                || !Objects.equals(snapshot.invoice().getId(),charge.getInvoice()) || snapshot.invoice().getPaymentIntent()==null
                || !Objects.equals(snapshot.invoice().getPaymentIntent(),charge.getPaymentIntent()))throw incomplete(id);
        return charge;
    }
    private void failureActions(SubscriptionPaymentRecoveryEntity root,SubscriptionRecoveryStripeGateway.Snapshot snapshot,Event event,SubscriptionRecoveryActionEntity confirmedAttempt,Charge failedCharge){
        String chargeId=failedCharge==null?null:failedCharge.getId();
        var history=actions.selectByRecovery(root.getId());
        if(confirmedAttempt==null && chargeId!=null)for(var candidate:history){
            if(!"RETRY_PAY".equals(candidate.getActionType()) || candidate.getResultJson()==null)continue;
            var proof=SubscriptionRecoveryStripeGateway.thaw(candidate.getResultJson());
            if("DECLINED".equals(proof.get("provider_state")) && Objects.equals(chargeId,proof.get("charge_id"))){confirmedAttempt=candidate;break;}
        }
        String attemptId=confirmedAttempt==null?null:String.valueOf(confirmedAttempt.getId());
        String evidence=null;
        for(var prior:history){
            if(!Set.of("CAPTURE_FAILURE","NOTIFY_INTERNAL_FAILURE").contains(prior.getActionType()))continue;
            var payload=SubscriptionRecoveryStripeGateway.thaw(prior.getPayloadJson());var aliases=prior.getResultJson()==null?Map.<String,Object>of():SubscriptionRecoveryStripeGateway.thaw(prior.getResultJson());
            if(chargeId!=null && (Objects.equals(chargeId,payload.get("charge_id")) || aliasMatches(aliases,"failure_charge_ids",chargeId))
                    || attemptId!=null && aliasMatches(aliases,"failure_action_ids",attemptId) || aliasMatches(aliases,"failure_event_ids",event.getId())){
                evidence=Objects.toString(payload.get("recovery_failure_id"),null);if(evidence!=null)break;
            }
        }
        if(evidence==null)evidence=hash(chargeId!=null?chargeId:attemptId!=null?"action:"+attemptId:event.getId());
        String attribution=confirmedAttempt==null?"UNKNOWN":"automatic";
        Map<String,Object> data=new LinkedHashMap<>();data.put("recovery_failure_id",evidence);data.put("recovery_id",root.getId());data.put("invoice_id",snapshot.invoice().getId());
        data.put("charge_id",chargeId);data.put("action_id",attemptId);data.put("retry_slot",confirmedAttempt==null?null:confirmedAttempt.getRetrySlot());data.put("amount_minor",snapshot.invoice().getAmountDue());data.put("currency",snapshot.invoice().getCurrency());
        boolean currentAttempt=confirmedAttempt!=null || chargeId!=null && snapshot.charge()!=null && Objects.equals(chargeId,snapshot.charge().getId());
        data.put("decline_code",currentAttempt?root.getLatestDeclineCode():failedCharge==null?"unknown":Objects.toString(failedCharge.getFailureCode(),"unknown"));data.put("advice_code",currentAttempt?root.getLatestAdviceCode():null);data.put("event_type",event.getType());data.put("attribution",attribution);
        data.put("requires_action","invoice.payment_action_required".equals(event.getType()));data.put("actual_attempt",!"invoice.payment_action_required".equals(event.getType()) && (chargeId!=null || confirmedAttempt!=null));
        for(var channel:Map.of("CAPTURE_FAILURE","posthog","NOTIFY_INTERNAL_FAILURE","feishu").entrySet()){
            var action=addAction(root,channel.getKey(),"recovery:failure:"+evidence+":"+channel.getValue(),root.getStripeInvoiceId(),data,null,Instant.now());
            var merged=action.getResultJson()==null?new LinkedHashMap<String,Object>():new LinkedHashMap<>(SubscriptionRecoveryStripeGateway.thaw(action.getResultJson()));
            mergeAlias(merged,"failure_charge_ids",chargeId);mergeAlias(merged,"failure_action_ids",attemptId);mergeAlias(merged,"failure_event_ids",event.getId());
            if(confirmedAttempt!=null)merged.put("failure_attribution","automatic");
            var patch=new LinkedHashMap<String,Object>();for(String field:List.of("failure_charge_ids","failure_action_ids","failure_event_ids","failure_attribution"))if(merged.containsKey(field))patch.put(field,merged.get(field));
            action.setResultJson(JSON.toJson(merged));if(action.getId()!=null)actions.mergeFailureEvidence(action.getId(),JSON.toJson(patch));
        }
        if("OPEN_MANUAL".equals(root.getStatus())&&!"insufficient_funds".equals(root.getLatestDeclineCode()))
            addAction(root,"SEND_EMAIL","recovery:"+root.getStripeInvoiceId()+":manual-email",root.getStripeInvoiceId(),Map.of("recovery_id",root.getId(),"url",frontendUrl+"/?payment_recovery_id="+root.getId()),null,Instant.now());
    }
    private static boolean aliasMatches(Map<String,Object> aliases,String key,String value){
        return value!=null && aliases.get(key) instanceof List<?> values && values.contains(value);
    }
    private static void mergeAlias(Map<String,Object> aliases,String key,String value){
        if(value==null)return;var values=new LinkedHashSet<String>();
        if(aliases.get(key) instanceof List<?> existing)for(var entry:existing)values.add(String.valueOf(entry));
        values.add(value);aliases.put(key,new ArrayList<>(values));
    }
    private SubscriptionRecoveryActionEntity addAction(SubscriptionPaymentRecoveryEntity root,String type,String key,String target,Map<String,Object> params,Integer slot,Instant at){
        var existing=actions.selectByKey(key);if(existing!=null)return existing;
        if("RETRY_PAY".equals(type)&&root.getOwnerRecoveryId()!=null)throw new IllegalStateException("SKIPPED_NON_PRIMARY");
        var a=new SubscriptionRecoveryActionEntity();a.setRecoveryId(root.getId());a.setActionType(type);a.setActionKey(key);a.setTargetId(target);a.setRetrySlot(slot);
        a.setScheduledAt(local(at));a.setNextRunAt(local(at));a.setStatus("READY");a.setPayloadJson(SubscriptionRecoveryStripeGateway.freeze(params));a.setProviderIdempotencyKey(key);
        a.setTransportAttempts(0);a.setVersion(0);actions.insert(a);return a;
    }
    // Only full mirrors read under the user lock reach this method. Keep NULL semantics local to recovery.
    private void saveRecoveryUser(UserSubscriptionEntity user){
        var update=new LambdaUpdateWrapper<UserSubscriptionEntity>().eq(UserSubscriptionEntity::getId,user.getId())
                .set(user.getPaymentRecoveryId()==null,UserSubscriptionEntity::getPaymentRecoveryId,null)
                .set(user.getGraceEndAt()==null,UserSubscriptionEntity::getGraceEndAt,null)
                .set(user.getStripeSubscriptionId()==null,UserSubscriptionEntity::getStripeSubscriptionId,null)
                .set(user.getStripeScheduleId()==null,UserSubscriptionEntity::getStripeScheduleId,null);
        if(users.update(user,update)!=1)throw incomplete(user.getStripeSubscriptionId());
    }
    private <T>T locked(long id,BiFunction<UserSubscriptionEntity,SubscriptionPaymentRecoveryEntity,T> body){
        var before=recoveries.selectById(id);if(before==null)throw new BillingDomainException("BILLING_RECORD_NOT_FOUND","Recovery not found");
        long rootId=before.getOwnerRecoveryId()==null?id:before.getOwnerRecoveryId();
        return new TransactionTemplate(transactionManager).execute(t->{var user=users.selectByUserForUpdate(before.getClerkUserId());var root=recoveries.selectForUpdate(rootId);
            if(user==null||root==null)throw incomplete(before.getStripeInvoiceId());return body.apply(user,root);});
    }
    private static void fillInvoice(SubscriptionPaymentRecoveryEntity row,SubscriptionRecoveryStripeGateway.Snapshot s){
        if("paid".equals(row.getStripeInvoiceStatus()) && !"paid".equals(s.invoice().getStatus()))return;
        row.setStripeLivemode(s.invoice().getLivemode());row.setStripeCustomerId(s.invoice().getCustomer());row.setStripeSubscriptionId(s.invoice().getSubscription());row.setStripeInvoiceId(s.invoice().getId());
        row.setStripeInvoiceStatus(s.invoice().getStatus());row.setCurrency(s.invoice().getCurrency());row.setAmountDueMinor(s.invoice().getAmountDue());row.setAmountPaidMinor(s.invoice().getAmountPaid());row.setAmountRemainingMinor(s.invoice().getAmountRemaining());
    }
    private static JsonObject eventObject(Event e){var json=JsonParser.parseString(e.toJson()).getAsJsonObject();return json.getAsJsonObject("data").getAsJsonObject("object");}
    private static String string(JsonObject o,String key){if(o==null||!o.has(key)||o.get(key).isJsonNull())return null;var value=o.get(key);return value.isJsonPrimitive()?value.getAsString():value.isJsonObject()?string(value.getAsJsonObject(),"id"):null;}
    private static String metadata(JsonObject o,String key){return o!=null&&o.has("metadata")&&o.get("metadata").isJsonObject()?string(o.getAsJsonObject("metadata"),key):null;}
    private static String raw(StripeObject o,String key){return o==null?null:string(o.getRawJsonObject(),key);}
    private static boolean isPaid(SubscriptionRecoveryStripeGateway.Snapshot s){return "paid".equals(s.invoice().getStatus())&&s.invoice().getAmountRemaining()!=null&&s.invoice().getAmountRemaining()==0;}
    private static Instant firstPeriodStart(SubscriptionRecoveryStripeGateway.Snapshot s){return s.lines().stream().filter(l->!Boolean.TRUE.equals(l.getProration())&&l.getPeriod()!=null).map(l->epoch(l.getPeriod().getStart())).filter(Objects::nonNull).min(Instant::compareTo).orElse(null);}
    private static LocalDateTime local(Instant at){return DateTimeFormats.fromInstant(at);}
    private static Instant instant(LocalDateTime at){return at==null?null:at.atZone(DateTimeFormats.APP_ZONE).toInstant();}
    private static Instant epoch(Long value){return value==null?null:Instant.ofEpochSecond(value);}
    private static IllegalStateException incomplete(String id){return new IllegalStateException("RECOVERY_ADMISSION_INCOMPLETE_EVIDENCE:"+Objects.toString(id,"unknown"));}
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}

    private void applyInvoice(SubscriptionPaymentRecoveryEntity owned,SubscriptionRecoveryStripeGateway.Snapshot snapshot,Event event){
        if(!Objects.equals(owned.getStripeSubscriptionId(),snapshot.invoice().getSubscription())||!Objects.equals(owned.getStripeCustomerId(),snapshot.invoice().getCustomer()))throw incomplete(snapshot.invoice().getId());
        var failedCharge=failureCharge(snapshot,event);
        List<Invoice> invoices;boolean scopeUnknown=false;
        try{invoices=stripe.listInvoices(owned.getStripeSubscriptionId());}
        catch(RuntimeException readFailure){if(!isPaid(snapshot))throw readFailure;invoices=List.of(snapshot.invoice());scopeUnknown=true;}
        // An unrelated receipt or in-flight lookup must not undo this confirmed receipt.
        boolean windowInFlight=!isPaid(snapshot) && windowHasInFlight(snapshot,invoices);
        List<SubscriptionRecoveryStripeGateway.Snapshot> paidChildren;
        try{paidChildren=readWindowPayments(owned,snapshot,invoices);}
        catch(RuntimeException readFailure){if(!isPaid(snapshot))throw readFailure;paidChildren=List.of();scopeUnknown=true;}
        final var knownInvoices=invoices;final boolean incompleteScope=scopeUnknown;final var knownPayments=paidChildren;
        locked(owned.getId(),(user,root)->{
            var row=owned.getOwnerRecoveryId()==null?root:recoveries.selectForUpdate(owned.getId());
            fillInvoice(row,snapshot);row.setUpdatedAt(DateTimeFormats.now());
            if(mergeUserStripeFacts(user,root,snapshot))saveRecoveryUser(user);
            if(isPaid(snapshot)){
                applyPaid(user,root,row,snapshot,!incompleteScope);
                if(incompleteScope){root.setAnchorSyncState("REVIEW");review(root,"OLD_INVOICE_SCOPE_READ_UNKNOWN");}
            }
            for(var childSnapshot:knownPayments){
                var child=Objects.equals(root.getStripeInvoiceId(),childSnapshot.invoice().getId())?root:recoveries.selectByInvoice(childSnapshot.invoice().getId());
                if(child==null){insertChild(root,childSnapshot,event);child=recoveries.selectByInvoice(childSnapshot.invoice().getId());}
                if(child==null)throw incomplete(childSnapshot.invoice().getId());
                if(child!=root && !Objects.equals(child.getOwnerRecoveryId(),root.getId()))throw incomplete(childSnapshot.invoice().getId());
                fillInvoice(child,childSnapshot);applyPaid(user,root,child,childSnapshot);if(child!=root)recoveries.updateById(child);
            }
            if(!isPaid(snapshot)&&!"PAID".equals(root.getStatus())&&root.getAccountClosedAt()==null){
                if(snapshot.invoice().getAmountPaid()!=null&&snapshot.invoice().getAmountPaid()>0){
                    skipRetries(root,"PARTIAL_PAYMENT");review(root,"PARTIAL_PAYMENT");
                    if("canceled".equals(snapshot.subscription().getStatus()))startClosing(root,"REMOTE_CANCELED");
                    else if(!snapshot.businessNow().isBefore(instant(root.getDeadlineAt())))startClosing(root,"DEADLINE");
                }else if("void".equals(snapshot.invoice().getStatus())||"canceled".equals(snapshot.subscription().getStatus()))
                    startClosing(root,"void".equals(snapshot.invoice().getStatus())?"REMOTE_VOID":"REMOTE_CANCELED");
                else if(!snapshot.businessNow().isBefore(instant(root.getDeadlineAt())))startClosing(root,"DEADLINE");
                else if(Set.of("invoice.payment_failed","invoice.payment_action_required").contains(event.getType())){
                    setFailure(row,snapshot,event);
                    if(row.getOwnerRecoveryId()==null){
                        if("OPEN_AUTO".equals(root.getStatus())&&(!automatic(snapshot)||"invoice.payment_action_required".equals(event.getType()))){root.setStatus("OPEN_MANUAL");skipRetries(root,"MANUAL_ONLY");}
                        failureActions(root,snapshot,event,null,failedCharge);
                    }
                }else if("uncollectible".equals(snapshot.invoice().getStatus())){
                    if(row.getOwnerRecoveryId()==null)root.setStatus("OPEN_MANUAL");skipRetries(root,"UNCOLLECTIBLE");
                }
                if(row.getOwnerRecoveryId()==null&&"OPEN_AUTO".equals(root.getStatus())&&!automatic(snapshot)){root.setStatus("OPEN_MANUAL");skipRetries(root,"MANUAL_ONLY");}
            }
            if(row!=root)recoveries.updateById(row);
            if(root.getAccountClosedAt()==null){
                if("CLOSING".equals(root.getStatus())){
                    if(windowInFlight){review(root,"WINDOW_PAYMENT_IN_FLIGHT_AT_CLOSE");root.setNextReconcileAt(DateTimeFormats.now().plusMinutes(1));}
                    else closeIntents(root,snapshot,knownInvoices);
                }
                else if(!"PAID".equals(root.getStatus())){
                    boolean protectedInvoice=Boolean.FALSE.equals(snapshot.invoice().getAutoAdvance());
                    boolean paused=snapshot.subscription().getPauseCollection()!=null&&"keep_as_draft".equals(snapshot.subscription().getPauseCollection().getBehavior());
                    root.setProtectionState(protectedInvoice&&paused?"ACTIVE":"PENDING");
                    for(Invoice invoice:knownInvoices)if(Set.of("draft","open","uncollectible").contains(invoice.getStatus())&&Boolean.TRUE.equals(invoice.getAutoAdvance()))
                        addAction(root,"DISABLE_ADVANCE","recovery:"+invoice.getId()+":disable-advance",invoice.getId(),Map.of("auto_advance",false),null,Instant.now());
                    if(!protectedInvoice||!paused)repairProtection(root,snapshot);
                }
                root.setNextReconcileAt(DateTimeFormats.now().plusMinutes(1));
            }
            recoveries.updateById(root);return null;
        });
    }
    private boolean mergeUserStripeFacts(UserSubscriptionEntity user,SubscriptionPaymentRecoveryEntity root,SubscriptionRecoveryStripeGateway.Snapshot snapshot) {
        if(!currentRecovery(user,root) || snapshot.subscription()==null)return false;
        var remote=snapshot.subscription();
        // A canceled Stripe subscription cannot be reactivated under the same ID.
        if("canceled".equals(user.getStatus()) && !"canceled".equals(remote.getStatus()))return false;
        boolean changed=false;
        if(remote.getStatus()!=null && !Objects.equals(user.getStatus(),remote.getStatus())){user.setStatus(remote.getStatus());changed=true;}
        if(remote.getCancelAtPeriodEnd()!=null && !Objects.equals(user.getCancelAtPeriodEnd(),remote.getCancelAtPeriodEnd())){user.setCancelAtPeriodEnd(remote.getCancelAtPeriodEnd());changed=true;}
        String schedule=remote.getSchedule();
        boolean released=schedule!=null && actions.selectByRecovery(root.getId()).stream().anyMatch(a->"RELEASE_SCHEDULE".equals(a.getActionType()) && "SUCCEEDED".equals(a.getStatus()) && Objects.equals(a.getTargetId(),schedule));
        if(!released && !Objects.equals(user.getStripeScheduleId(),schedule)){user.setStripeScheduleId(schedule);changed=true;}
        return changed;
    }
    private List<SubscriptionRecoveryStripeGateway.Snapshot> readWindowPayments(SubscriptionPaymentRecoveryEntity owned,SubscriptionRecoveryStripeGateway.Snapshot current,List<Invoice> invoices) {
        var root=owned.getOwnerRecoveryId()==null?owned:recoveries.selectById(owned.getOwnerRecoveryId());
        if(root==null || root.getAccountClosedAt()!=null)return List.of();
        if("PAID".equals(root.getStatus())){
            if(root.getEntitlementInvoiceId()==null || Objects.equals(root.getEntitlementInvoiceId(),current.invoice().getId()))return List.of();
            return List.of(stripe.readInvoice(root.getEntitlementInvoiceId()));
        }
        List<SubscriptionRecoveryStripeGateway.Snapshot> result=new ArrayList<>();
        for(var invoice:invoices){
            if(Objects.equals(invoice.getId(),current.invoice().getId()) || !"paid".equals(invoice.getStatus()) || invoice.getAmountPaid()==null || invoice.getAmountPaid()<=0)continue;
            Instant paid=invoice.getStatusTransitions()==null?null:epoch(invoice.getStatusTransitions().getPaidAt());
            var known=recoveries.selectByInvoice(invoice.getId());
            // Settled receipts from earlier windows on this subscription are not payments for this window.
            if(known!=null&&!Objects.equals(root.getId(),known.getId())&&!Objects.equals(root.getId(),known.getOwnerRecoveryId())
                    &&paid!=null&&root.getFirstFailedAt()!=null&&paid.isBefore(instant(root.getFirstFailedAt())))continue;
            if(known==null && (paid==null || root.getFirstFailedAt()==null || paid.isBefore(instant(root.getFirstFailedAt()))))continue;
            var candidate=stripe.readInvoice(invoice.getId());
            if(!Objects.equals(root.getStripeCustomerId(),candidate.invoice().getCustomer()) || !Objects.equals(root.getStripeSubscriptionId(),candidate.invoice().getSubscription()))throw incomplete(invoice.getId());
            result.add(candidate);
        }
        return result;
    }
    private void applyPaid(UserSubscriptionEntity user,SubscriptionPaymentRecoveryEntity root,SubscriptionPaymentRecoveryEntity row,SubscriptionRecoveryStripeGateway.Snapshot snapshot){
        applyPaid(user,root,row,snapshot,true);
    }
    private void applyPaid(UserSubscriptionEntity user,SubscriptionPaymentRecoveryEntity root,SubscriptionPaymentRecoveryEntity row,SubscriptionRecoveryStripeGateway.Snapshot snapshot,boolean renewalScopeKnown){
        Instant paidEvidence=snapshot.invoice().getStatusTransitions()==null?null:epoch(snapshot.invoice().getStatusTransitions().getPaidAt());
        if(paidEvidence!=null && root.getFirstFailedAt()!=null && paidEvidence.isBefore(instant(root.getFirstFailedAt()))){
            review(root,"PAYMENT_OUTSIDE_RECOVERY_WINDOW");if(row!=root)row.setGrantState("HELD");return;
        }
        skipRetries(root,"PAID");
        Long paidEpoch=snapshot.invoice().getStatusTransitions()==null?null:snapshot.invoice().getStatusTransitions().getPaidAt();
        row.setPaidAt(paidEpoch==null?null:local(epoch(paidEpoch)));
        Map<String,Object> proof=new LinkedHashMap<>();proof.put("invoice_id",row.getStripeInvoiceId());proof.put("payment_intent_id",snapshot.invoice().getPaymentIntent());
        proof.put("charge_id",snapshot.charge()==null?null:snapshot.charge().getId());proof.put("paid_at",paidEpoch);proof.put("paid_at_source","invoice.status_transitions.paid_at");proof.put("amount_paid",snapshot.invoice().getAmountPaid());
        row.setPaymentEvidence(JSON.toJson(proof));
        boolean cash=verifiedCash(snapshot);
        if(snapshot.charge()!=null && snapshot.charge().getAmountRefunded()!=null){
            long refunded=Math.max(row.getRefundedAmountMinor()==null?0:row.getRefundedAmountMinor(),snapshot.charge().getAmountRefunded());row.setRefundedAmountMinor(refunded);
            row.setRefundState(refunded<=0?"NONE":snapshot.charge().getAmount()!=null && refunded>=snapshot.charge().getAmount()?"FULL":"PARTIAL");
        }
        if(snapshot.charge()!=null && Boolean.TRUE.equals(snapshot.charge().getDisputed())
                && (row.getDisputeState()==null || "NONE".equals(row.getDisputeState())))row.setDisputeState("OPEN");
        recordPaidOrder(root,row,snapshot);
        var replacement=replacements.selectByRecovery(root.getId()).stream().filter(x->"PAID".equals(x.getState())).findFirst().orElse(null);
        boolean current=Objects.equals(user.getStripeSubscriptionId(),root.getStripeSubscriptionId())&&Objects.equals(user.getPaymentRecoveryId(),root.getId())&&root.getAccountClosedAt()==null;
        if(replacement!=null||!current){review(root,"OLD_PAYMENT_AFTER_SWITCH_OR_CLOSE");row.setGrantState("HELD");return;}
        if(root.getEntitlementInvoiceId()!=null&&!root.getEntitlementInvoiceId().equals(row.getStripeInvoiceId())){review(root,"MULTIPLE_OLD_INVOICES_PAID");if(row!=root)row.setGrantState("HELD");return;}
        if(!cash){review(root,"CASH_PAYMENT_UNVERIFIED");root.setGrantState("HELD");return;}
        if(!equivalentPaidLine(root,snapshot)){review(root,"PAID_CONTRACT_UNVERIFIED");root.setGrantState("HELD");return;}
        root.setEntitlementInvoiceId(row.getStripeInvoiceId());
        if("PAYMENT_REFUNDED".equals(root.getCloseReason())&&"FULL".equals(row.getRefundState())){root.setStatus("CLOSING");root.setGrantState("HELD");return;}
        root.setStatus("PAID");
        var openReplacement=replacements.selectActive(root.getStripeSubscriptionId());
        if(openReplacement!=null&&openReplacement.getCheckoutSessionId()!=null&&"OPEN".equals(openReplacement.getState()))
            addAction(root,"EXPIRE_CHECKOUT","recovery:"+root.getId()+":expire:"+openReplacement.getCheckoutSessionId(),openReplacement.getCheckoutSessionId(),Map.of(),null,Instant.now());
        if(paidEpoch==null){root.setGrantState("HELD");review(root,"PAID_TIME_UNVERIFIED");return;}
        if("OPEN".equals(row.getDisputeState())||"LOST".equals(row.getDisputeState())||"FULL".equals(row.getRefundState())){root.setGrantState("HELD");review(root,"PAID_FINANCIAL_HOLD");return;}
        if("GRANTED".equals(root.getGrantState())){planRenewal(root,snapshot,renewalScopeKnown);return;}
        if(root.getEntitlementStartAt()!=null && root.getEntitlementEndAt()!=null){root.setGrantState("GRANTED");planRenewal(root,snapshot,renewalScopeKnown);return;}
        Instant paid=epoch(paidEpoch);Instant end=SubscriptionRecoveryPolicy.paidPeriodEnd(paid,root.getBillingInterval(),root.getIntervalCount());
        Instant quotaEnd=SubscriptionRecoveryPolicy.quotaPeriodEnd(paid,root.getBillingInterval(),root.getIntervalCount());
        root.setEntitlementStartAt(local(paid));root.setEntitlementEndAt(local(end));root.setQuotaPeriodEndAt(local(quotaEnd));
        user.setTier(root.getDisplayTier());user.setPlanCode(root.getPlanCode());if(snapshot.subscription()!=null)user.setStatus(snapshot.subscription().getStatus());
        user.setCurrentPeriodStart(local(paid));user.setCurrentPeriodEnd(local(end));user.setQuotaPeriodStart(local(paid));user.setQuotaPeriodEnd(local(quotaEnd));user.setGraceEndAt(null);
        user.setCurrentPeriodTimeZone(DateTimeFormats.APP_ZONE.getId());user.setQuotaPeriodTimeZone(DateTimeFormats.APP_ZONE.getId());
        user.setSubscriptionPhase(IntroTrialPlans.PHASE_STANDARD);
        var admission=JsonParser.parseString(root.getContractSnapshot()).getAsJsonObject().getAsJsonObject("recovery_admission");
        if(!"formal".equals(string(admission,"sourceKey"))&&user.getIntroTrialConvertedAt()==null)user.setIntroTrialConvertedAt(local(paid));
        saveRecoveryUser(user);
        endStudyPasses(root);
        var quota=quotaProvider==null?null:quotaProvider.getIfAvailable();if(quota==null)throw new IllegalStateException("BillingQuotaGateway unavailable");
        quota.resetFromPaidInvoiceAt(root.getClerkUserId(),root.getStripeSubscriptionId(),root.getPlanCode(),paid,quotaEnd,row.getStripeInvoiceId(),requiredBusinessTime(snapshot));
        quota.resumeEligibleAddonsAt(root.getClerkUserId(),root.getStripeSubscriptionId(),"recovery:"+row.getStripeInvoiceId()+":addons",requiredBusinessTime(snapshot));
        root.setGrantState("GRANTED");root.setAnchorSyncState("PENDING");
        planRenewal(root,snapshot,renewalScopeKnown);
    }
    /** Called under the user/root locks. Persist missing intents, never regrant or replace a frozen intent. */
    private void planRenewal(SubscriptionPaymentRecoveryEntity root,SubscriptionRecoveryStripeGateway.Snapshot snapshot,boolean renewalScopeKnown){
        if(root.getEntitlementEndAt()==null || root.getEntitlementInvoiceId()==null || "CONFIRMED".equals(root.getAnchorSyncState())
                || actions.selectByRecovery(root.getId()).stream().anyMatch(a->Set.of("RELEASE_SCHEDULE","SHIFT_RENEWAL").contains(a.getActionType())))return;
        if("IN_PROGRESS".equals(root.getReviewState()) || root.getReviewReason()!=null
                && !Set.of("CONTRACT_CHANGED_AFTER_ADMISSION","RENEWAL_CONTRACT_READ_UNKNOWN","OLD_INVOICE_SCOPE_READ_UNKNOWN").contains(root.getReviewReason()))return;
        if(snapshot.subscription()==null || "canceled".equals(snapshot.subscription().getStatus())){
            root.setAnchorSyncState("REVIEW");review(root,"REMOTE_CANCELED_PAID_CYCLE_PRESERVED");return;
        }
        if(!requiredBusinessTime(snapshot).isBefore(instant(root.getEntitlementEndAt()))){
            root.setAnchorSyncState("REVIEW");review(root,"PAID_PERIOD_ELAPSED_BEFORE_RENEWAL_SYNC");return;
        }
        if(!renewalScopeKnown){root.setAnchorSyncState("REVIEW");review(root,"OLD_INVOICE_SCOPE_READ_UNKNOWN");return;}
        Contract latest;
        try{latest=contract(snapshot,admittedSource(root));}
        catch(RuntimeException metadataUnknown){root.setAnchorSyncState("REVIEW");review(root,"RENEWAL_CONTRACT_READ_UNKNOWN");return;}
        if(latest==null||!sameContract(root,latest)){root.setAnchorSyncState("REVIEW");review(root,"CONTRACT_CHANGED_AFTER_ADMISSION");return;}
        resolveRenewalContractReview(root);
        if(snapshot.subscription().getSchedule()!=null)
            addAction(root,"RELEASE_SCHEDULE","recovery:"+root.getId()+":release:"+snapshot.subscription().getSchedule(),snapshot.subscription().getSchedule(),Map.of("preserve_cancel_date",true),null,Instant.now());
        Instant end=instant(root.getEntitlementEndAt());
        addAction(root,"SHIFT_RENEWAL","recovery:"+root.getEntitlementInvoiceId()+":anchor:"+end.getEpochSecond(),root.getStripeSubscriptionId(),SubscriptionRecoveryStripeGateway.shiftParams(root.getEntitlementInvoiceId(),end),null,Instant.now());
        root.setAnchorSyncState("PENDING");
    }
    private void resolveRenewalContractReview(SubscriptionPaymentRecoveryEntity root){
        if(root.getReviewReason()==null)return;
        // updateById skips null fields; clear only this resolved technical review explicitly in the same transaction.
        int changed=recoveries.update(null,new LambdaUpdateWrapper<SubscriptionPaymentRecoveryEntity>()
                .eq(SubscriptionPaymentRecoveryEntity::getId,root.getId())
                .eq(SubscriptionPaymentRecoveryEntity::getVersion,root.getVersion())
                .eq(SubscriptionPaymentRecoveryEntity::getReviewReason,root.getReviewReason())
                .set(SubscriptionPaymentRecoveryEntity::getReviewState,"NONE")
                .set(SubscriptionPaymentRecoveryEntity::getReviewReason,null)
                .set(SubscriptionPaymentRecoveryEntity::getReviewEnteredAt,null)
                .set(SubscriptionPaymentRecoveryEntity::getReviewDueAt,null)
                .set(SubscriptionPaymentRecoveryEntity::getReviewNextFollowupAt,null));
        if(changed!=1)throw new IllegalStateException("RECOVERY_REVIEW_RESOLUTION_CONFLICT");
        root.setReviewState("NONE");root.setReviewReason(null);root.setReviewEnteredAt(null);root.setReviewDueAt(null);root.setReviewNextFollowupAt(null);
    }

    private static Instant requiredBusinessTime(SubscriptionRecoveryStripeGateway.Snapshot snapshot) {
        if (snapshot.businessNow() == null) throw new IllegalStateException("RECOVERY_BUSINESS_TIME_MISSING");
        return snapshot.businessNow();
    }
    private static boolean verifiedCash(SubscriptionRecoveryStripeGateway.Snapshot s) {
        var invoice=s.invoice();var pi=s.paymentIntent();var charge=s.charge();
        return invoice.getAmountPaid()!=null && invoice.getAmountPaid()>0 && !Boolean.TRUE.equals(invoice.getPaidOutOfBand())
                && (invoice.getStartingBalance()==null || invoice.getStartingBalance()>=0)
                && pi!=null && "succeeded".equals(pi.getStatus()) && Objects.equals(invoice.getPaymentIntent(),pi.getId())
                && Objects.equals(invoice.getCustomer(),pi.getCustomer()) && Objects.equals(invoice.getCurrency(),pi.getCurrency())
                && Objects.equals(invoice.getAmountPaid(),pi.getAmountReceived())
                && charge!=null && Objects.equals(pi.getLatestCharge(),charge.getId()) && "succeeded".equals(charge.getStatus())
                && Boolean.TRUE.equals(charge.getPaid()) && Boolean.TRUE.equals(charge.getCaptured())
                && Objects.equals(invoice.getCustomer(),charge.getCustomer()) && Objects.equals(invoice.getCurrency(),charge.getCurrency())
                && Objects.equals(invoice.getId(),charge.getInvoice()) && Objects.equals(pi.getId(),charge.getPaymentIntent())
                && Objects.equals(invoice.getAmountPaid(),charge.getAmountCaptured());
    }
    private boolean equivalentPaidLine(SubscriptionPaymentRecoveryEntity root,SubscriptionRecoveryStripeGateway.Snapshot s){
        var saved=JsonParser.parseString(root.getContractSnapshot()).getAsJsonObject();
        var lines=s.lines().stream().filter(l->!Boolean.TRUE.equals(l.getProration())).toList();
        if(lines.size()!=1)return false;var line=lines.get(0);var price=line.getPrice();
        return "subscription".equals(line.getType())&&price!=null&&price.getRecurring()!=null&&line.getPeriod()!=null
                &&saved.has("quantity")&&!saved.get("quantity").isJsonNull()&&line.getQuantity()!=null&&saved.get("quantity").getAsLong()==line.getQuantity()
                &&Objects.equals(root.getBillingInterval(),price.getRecurring().getInterval())&&root.getIntervalCount()!=null&&Objects.equals(root.getIntervalCount().longValue(),price.getRecurring().getIntervalCount())
                &&Objects.equals(string(saved,"price_id"),price.getId())&&Objects.equals(root.getCurrency(),s.invoice().getCurrency())&&Objects.equals(root.getCurrency(),line.getCurrency())
                &&(line.getSubscription()==null||Objects.equals(root.getStripeSubscriptionId(),line.getSubscription()))
                &&(string(saved,"subscription_item_id")==null||Objects.equals(string(saved,"subscription_item_id"),line.getSubscriptionItem()));
    }
    private void recordPaidOrder(SubscriptionPaymentRecoveryEntity root,SubscriptionPaymentRecoveryEntity row,SubscriptionRecoveryStripeGateway.Snapshot snapshot){
        var existing=orders.selectOne(new LambdaQueryWrapper<RechargeOrderEntity>().eq(RechargeOrderEntity::getStripeInvoiceId,row.getStripeInvoiceId()).last("LIMIT 1"));
        if(existing==null){existing=new RechargeOrderEntity();existing.setOrderNo("recovery_"+row.getStripeInvoiceId());existing.setOrderType("subscription");existing.setClerkUserId(root.getClerkUserId());existing.setPlanCode(root.getPlanCode());
            existing.setFeatureCode("subscription");existing.setPackageCode(root.getPlanCode());existing.setQuotaAmount(0L);
            existing.setStripeInvoiceId(row.getStripeInvoiceId());existing.setStripeSubscriptionId(row.getStripeSubscriptionId());existing.setStripePaymentIntentId(snapshot.invoice().getPaymentIntent());
            existing.setCurrency(row.getCurrency());existing.setPriceCents(Math.toIntExact(row.getAmountPaidMinor()));existing.setStatus("FULL".equals(row.getRefundState())?"refunded":"PARTIAL".equals(row.getRefundState())?"partially_refunded":"OPEN".equals(row.getDisputeState())||"LOST".equals(row.getDisputeState())?"disputed":"completed");existing.setPaidAt(row.getPaidAt());
            existing.setBizContext(JSON.toJson(Map.of("payment_recovery_id",root.getId(),"original_invoice_id",row.getStripeInvoiceId())));orders.insert(existing);
        }else if(!Set.of("completed","refunded","partially_refunded","disputed").contains(existing.getStatus())){existing.setStatus("FULL".equals(row.getRefundState())?"refunded":"PARTIAL".equals(row.getRefundState())?"partially_refunded":"OPEN".equals(row.getDisputeState())||"LOST".equals(row.getDisputeState())?"disputed":"completed");existing.setPaidAt(row.getPaidAt());orders.updateById(existing);}
    }
    private void endStudyPasses(SubscriptionPaymentRecoveryEntity root){
        if(passes==null)return;
        var active=passes.selectList(new LambdaQueryWrapper<StudyPassEntity>().eq(StudyPassEntity::getClerkUserId,root.getClerkUserId()).in(StudyPassEntity::getStatus,List.of("active","pending")).last("FOR UPDATE"));
        for(var pass:active){Map<String,Object> payload=new LinkedHashMap<>();payload.put("original_status",pass.getStatus());payload.put("original_expires_at",pass.getExpiresAt()==null?null:DateTimeFormats.formatApi(pass.getExpiresAt()));
            pass.setStatus("superseded");passes.updateById(pass);payload.put("study_pass_id",pass.getId());payload.put("subscription_id",pass.getStripeSubscriptionId());payload.put("checkout_id",pass.getStripeCheckoutSessionId());
            addAction(root,"END_STUDY_PASS","recovery:"+root.getId()+":end-pass:"+pass.getId(),String.valueOf(pass.getId()),payload,null,Instant.now());}
    }
    private void skipRetries(SubscriptionPaymentRecoveryEntity root,String reason){
        for(var action:actions.selectByRecovery(root.getId()))if("RETRY_PAY".equals(action.getActionType())&&"READY".equals(action.getStatus())){action.setStatus("SKIPPED");action.setSkipReason(reason);actions.updateById(action);}
    }
    private void review(SubscriptionPaymentRecoveryEntity root,String reason){
        if(root.getReviewEnteredAt()==null){root.setReviewEnteredAt(DateTimeFormats.now());root.setReviewDueAt(reviewDue(root.getReviewEnteredAt()));}
        if(!"IN_PROGRESS".equals(root.getReviewState()))root.setReviewState("OPEN");root.setReviewReason(reason);
        if(root.getReviewNextFollowupAt()==null)root.setReviewNextFollowupAt(root.getReviewDueAt());
        if(root.getReviewAssignee()==null&&!config.getReviewAssignee().isBlank())root.setReviewAssignee(config.getReviewAssignee());
    }
    static LocalDateTime twoWorkingDays(LocalDateTime entered){
        LocalDateTime due=entered;int count=0;while(count<2){due=due.plusDays(1);if(due.getDayOfWeek()!=DayOfWeek.SATURDAY&&due.getDayOfWeek()!=DayOfWeek.SUNDAY)count++;}return due;
    }
    private void startClosing(SubscriptionPaymentRecoveryEntity root,String reason){
        if("PAID".equals(root.getStatus())&&root.getEntitlementInvoiceId()!=null)return;
        if(root.getCloseReason()==null)root.setCloseReason(reason);root.setStatus("CLOSING");skipRetries(root,"CLOSING");
    }
    private void closeIntents(SubscriptionPaymentRecoveryEntity root,SubscriptionRecoveryStripeGateway.Snapshot snapshot,List<Invoice> invoices){
        var pendingReplacement=replacements.selectActive(root.getStripeSubscriptionId());
        if(pendingReplacement!=null){
            if("OPEN".equals(pendingReplacement.getState())&&pendingReplacement.getCheckoutSessionId()!=null)
                addAction(root,"EXPIRE_CHECKOUT","recovery:"+root.getId()+":expire:"+pendingReplacement.getCheckoutSessionId(),pendingReplacement.getCheckoutSessionId(),Map.of(),null,Instant.now());
            else review(root,"REPLACEMENT_RESULT_UNKNOWN_AT_CLOSE");
            return;
        }
        if(paymentInFlight(snapshot)||hasUncertainPayment(root,null)){review(root,"PAYMENT_IN_FLIGHT_AT_CLOSE");return;}
        boolean unprotected=false;
        for(var invoice:invoices)if(Set.of("draft","open","uncollectible").contains(invoice.getStatus())&&Boolean.TRUE.equals(invoice.getAutoAdvance())){
            addAction(root,"DISABLE_ADVANCE","recovery:"+invoice.getId()+":disable-advance",invoice.getId(),Map.of("auto_advance",false),null,Instant.now());unprotected=true;
        }
        if(unprotected)return;
        if(!"canceled".equals(snapshot.subscription().getStatus())){
            String schedule=snapshot.subscription().getSchedule();String type=schedule==null?"CANCEL_SUBSCRIPTION":"CANCEL_SCHEDULE";
            addAction(root,type,"recovery:"+root.getId()+":cancel:"+(schedule==null?root.getStripeSubscriptionId():schedule),schedule==null?root.getStripeSubscriptionId():schedule,SubscriptionRecoveryStripeGateway.cancelParams(),null,Instant.now());
        }
        boolean allClosed=true;
        for(var invoice:invoices){
            if(Set.of("paid","void").contains(invoice.getStatus()))continue;
            if(invoice.getAmountPaid()!=null&&invoice.getAmountPaid()>0){review(root,"PARTIAL_PAYMENT_FINANCIAL_REVIEW");continue;}
            if("draft".equals(invoice.getStatus())){
                if(Boolean.TRUE.equals(invoice.getAutoAdvance())){allClosed=false;addAction(root,"DISABLE_ADVANCE","recovery:"+invoice.getId()+":disable-advance",invoice.getId(),Map.of("auto_advance",false),null,Instant.now());}
                if(silentDraftCleanupVerified(root)){
                    var finalize=addAction(root,"FINALIZE_INVOICE","recovery:"+root.getId()+":FINALIZE_INVOICE:"+invoice.getId(),invoice.getId(),SubscriptionRecoveryStripeGateway.finalizeParams(),null,Instant.now());
                    // A target rejected before dispatch remains a protected draft for independent
                    // review (§8.2); it must not hold an otherwise closed unpaid account forever.
                    boolean deferred="SKIPPED".equals(finalize.getStatus())&&"SKIPPED_UNVERIFIED_TARGET".equals(finalize.getSkipReason())
                            &&Boolean.FALSE.equals(invoice.getAutoAdvance())&&Long.valueOf(0).equals(invoice.getAmountPaid());
                    if(deferred)review(root,"UNVERIFIED_OR_MIXED_INVOICE_CLOSE_TARGET");else allClosed=false;
                }else review(root,"SILENT_DRAFT_CLEANUP_UNVERIFIED");
            }else if(Set.of("open","uncollectible").contains(invoice.getStatus())){
                allClosed=false;addAction(root,"VOID_INVOICE","recovery:"+root.getId()+":VOID_INVOICE:"+invoice.getId(),invoice.getId(),Map.of(),null,Instant.now());
            }else{allClosed=false;review(root,"UNKNOWN_INVOICE_CLOSE_STATUS");}
        }
        if(allClosed&&"canceled".equals(snapshot.subscription().getStatus())){
            root.setAccountClosedAt(DateTimeFormats.now());root.setStatus("REPLACEMENT_PAID".equals(root.getCloseReason())?"SWITCHED":"DEADLINE".equals(root.getCloseReason())?"EXPIRED":"CANCELED");root.setNextReconcileAt(null);
            var user=users.selectByUserForUpdate(root.getClerkUserId());
            if(Objects.equals(user.getStripeSubscriptionId(),root.getStripeSubscriptionId())&&Objects.equals(user.getPaymentRecoveryId(),root.getId())){
                var quota=quotaProvider==null?null:quotaProvider.getIfAvailable();if(quota==null)throw new IllegalStateException("BillingQuotaGateway unavailable");
                quota.clearPlanQuotaAt(user.getClerkUserId(),root.getStripeSubscriptionId(),root.getPlanCode(),"recovery:"+root.getId()+":closed",requiredBusinessTime(snapshot));
                user.setTier("free");user.setPlanCode("free");user.setStatus("canceled");user.setStripeSubscriptionId(null);user.setGraceEndAt(null);saveRecoveryUser(user);
            }
        }
    }
    private boolean silentDraftCleanupVerified(SubscriptionPaymentRecoveryEntity root) {
        if(root.getContractSnapshot()==null)return false;
        var saved=JsonParser.parseString(root.getContractSnapshot()).getAsJsonObject();var admission=saved.getAsJsonObject("recovery_admission");
        return admission!=null && admission.has("silentCleanupVerified") && admission.get("silentCleanupVerified").getAsBoolean();
    }
    private static boolean freshProtection(SubscriptionRecoveryStripeGateway.Snapshot snapshot) {
        return Boolean.FALSE.equals(snapshot.invoice().getAutoAdvance()) && snapshot.subscription().getPauseCollection()!=null
                && "keep_as_draft".equals(snapshot.subscription().getPauseCollection().getBehavior());
    }
    private static boolean reminderProviderEligible(SubscriptionRecoveryStripeGateway.Snapshot snapshot){
        var invoice=snapshot.invoice();var error=snapshot.paymentIntent()==null?null:snapshot.paymentIntent().getLastPaymentError();
        return Set.of("open","uncollectible").contains(invoice.getStatus()) && Long.valueOf(0).equals(invoice.getAmountPaid())
                && invoice.getAmountRemaining()!=null && invoice.getAmountRemaining()>0 && !"canceled".equals(snapshot.subscription().getStatus())
                && (error==null || !"insufficient_funds".equals(error.getDeclineCode()));
    }
    private static boolean paymentInFlight(SubscriptionRecoveryStripeGateway.Snapshot snapshot){
        var intent=snapshot.paymentIntent();var charge=snapshot.charge();
        if(intent!=null&&Set.of("processing","requires_capture").contains(intent.getStatus()))return true;
        if(isPaid(snapshot)||!Set.of("open","uncollectible").contains(snapshot.invoice().getStatus()))return false;
        // The invoice is read before its PI/Charge. A newer cash receipt must settle before another payment or close.
        long recorded=snapshot.invoice().getAmountPaid()==null?0:snapshot.invoice().getAmountPaid();
        return intent!=null&&(("succeeded".equals(intent.getStatus())&&recorded==0)
                ||intent.getAmountReceived()!=null&&intent.getAmountReceived()>recorded)
                ||charge!=null&&(charge.getAmountCaptured()!=null&&charge.getAmountCaptured()>recorded
                ||recorded==0&&Boolean.TRUE.equals(charge.getPaid()));
    }
    private void repairProtection(SubscriptionPaymentRecoveryEntity root,SubscriptionRecoveryStripeGateway.Snapshot snapshot){
        int version=root.getVersion()==null?0:root.getVersion();
        if(Boolean.TRUE.equals(snapshot.invoice().getAutoAdvance()))addAction(root,"DISABLE_ADVANCE","recovery:"+root.getStripeInvoiceId()+":disable-advance:repair:"+version,root.getStripeInvoiceId(),Map.of("auto_advance",false),null,Instant.now());
        if(snapshot.subscription().getPauseCollection()==null||!"keep_as_draft".equals(snapshot.subscription().getPauseCollection().getBehavior()))
            addAction(root,"PAUSE_COLLECTION","recovery:"+root.getId()+":pause:"+root.getStripeSubscriptionId()+":repair:"+version,root.getStripeSubscriptionId(),Map.of("pause_collection",Map.of("behavior","keep_as_draft")),null,Instant.now());
    }

    public void runDueAction(long actionId){
        var candidate=actions.selectById(actionId);if(candidate==null)return;
        var record=recoveries.selectById(candidate.getRecoveryId());if(record==null)return;
        if("VERIFY_COLLECTION".equals(candidate.getActionType())){
            var owner=record.getOwnerRecoveryId()==null?record:recoveries.selectById(record.getOwnerRecoveryId());if(owner!=null)runCollectionCheck(candidate,owner);return;
        }
        if("UNKNOWN".equals(candidate.getStatus())||("DISPATCHING".equals(candidate.getStatus())&&candidate.getLeaseUntil()!=null&&!candidate.getLeaseUntil().isAfter(DateTimeFormats.now()))){reconcileUnknown(candidate,record);return;}
        if(!"READY".equals(candidate.getStatus()))return;
        var root=record.getOwnerRecoveryId()==null?record:recoveries.selectById(record.getOwnerRecoveryId());if(root==null)return;
        var snapshot=stripe.readInvoice(root.getStripeInvoiceId());
        var invoices=stripe.listInvoices(root.getStripeSubscriptionId());
        if(Set.of("RETRY_PAY","SHIFT_RENEWAL","RELEASE_SCHEDULE").contains(candidate.getActionType()))synchronizeConfirmedMethod(root,snapshot);
        SubscriptionRecoveryStripeGateway.Snapshot targetSnapshot=Set.of("VOID_INVOICE","FINALIZE_INVOICE","DISABLE_ADVANCE","RESTORE_ADVANCE").contains(candidate.getActionType())
                &&!candidate.getTargetId().equals(root.getStripeInvoiceId())?stripe.readInvoice(candidate.getTargetId()):snapshot;
        Contract currentContract=null;
        boolean incompleteContract=false;
        if(Set.of("SHIFT_RENEWAL","RELEASE_SCHEDULE","RETRY_PAY").contains(candidate.getActionType())){
            try{currentContract=contract(snapshot,admittedSource(root));}
            catch(IllegalStateException e){
                if(!Set.of("SHIFT_RENEWAL","RELEASE_SCHEDULE").contains(candidate.getActionType())
                        ||e.getMessage()==null||!e.getMessage().startsWith("RECOVERY_ADMISSION_UNKNOWN_SCHEDULE:"))throw e;
                incompleteContract=true;
            }
        }
        final Contract checked=currentContract;
        final boolean contractRecheckIncomplete=incompleteContract;
        var cancellation=cancellationTarget(candidate,snapshot);
        var customerBefore=cancellation!=null && !"canceled".equals(cancellation.status())?stripe.listCustomerInvoices(root.getStripeCustomerId()):List.<Invoice>of();
        var methodCustomer="UPDATE_METHOD".equals(candidate.getActionType())?stripe.readCustomer(root.getStripeCustomerId()):null;
        boolean windowPayment=Set.of("RETRY_PAY","CREATE_CHECKOUT","CANCEL_SUBSCRIPTION","CANCEL_SCHEDULE").contains(candidate.getActionType())&&windowHasInFlight(snapshot,invoices);
        String preparedEmail=null;
        if("SEND_EMAIL".equals(candidate.getActionType())&&config.isEmailEnabled()
                &&com.studyagent.infra.notification.BillingPaymentRecoveryEmailSender.eligible(root)
                &&reminderProviderEligible(snapshot) && !paymentInFlight(snapshot)
                &&!SubscriptionRecoveryStripeGateway.thaw(candidate.getPayloadJson()).containsKey("to"))
            preparedEmail=emailSender.prepare(root,frontendUrl+"/?payment_recovery_id="+root.getId());
        final String frozenEmail=preparedEmail;
        var claimed=locked(root.getId(),(user,r)->{
            var action=actions.selectForUpdate(actionId);if(action==null||!"READY".equals(action.getStatus()))return null;
            Instant business=snapshot.businessNow();
            if(contractMutations!=null && Set.of("SHIFT_RENEWAL","RELEASE_SCHEDULE","SET_CANCEL_PREFERENCE","UPDATE_METHOD","CANCEL_SUBSCRIPTION","CANCEL_SCHEDULE").contains(action.getActionType())
                    && (action.getActionKey()==null || !action.getActionKey().contains(":end-pass:")) && contractMutations.inFlight(r.getStripeSubscriptionId(),true)){
                if("CLOSING".equals(r.getStatus())){repairProtection(r,snapshot);r.setProtectionState("PENDING");recoveries.updateById(r);}return null;
            }
            if(action.getNextRunAt()!=null&&instant(action.getNextRunAt()).isAfter("RETRY_PAY".equals(action.getActionType())?business:Instant.now()))return null;
            String skip=null;
            if("RETRY_PAY".equals(action.getActionType())){
                if(record.getOwnerRecoveryId()!=null)skip="SKIPPED_NON_PRIMARY";
                else if(!"OPEN_AUTO".equals(r.getStatus())||!Objects.equals(user.getPaymentRecoveryId(),r.getId())||!Objects.equals(user.getStripeSubscriptionId(),r.getStripeSubscriptionId())
                        ||!"open".equals(snapshot.invoice().getStatus())||snapshot.invoice().getAmountPaid()==null||snapshot.invoice().getAmountPaid()!=0||!automatic(snapshot))skip="SKIPPED_TERMINAL";
                else if(!config.isAutomaticPayEnabled())skip="SKIPPED_DISABLED";
                else{
                    var history=replacements.selectByRecovery(r.getId());
                    boolean covered=history.stream().anyMatch(x->SubscriptionRecoveryPolicy.pauseCovers(instant(action.getScheduledAt()),instant(x.getPausedAt()),instant(x.getClosedAt())));
                    if(covered)skip="SKIPPED_REPLACEMENT";
                    else if(!SubscriptionRecoveryPolicy.withinDispatchWindow(instant(action.getScheduledAt()),business,instant(r.getDeadlineAt()),config.getDispatchToleranceSeconds()))skip="SKIPPED_LATE";
                    else if(!freshProtection(snapshot)){r.setProtectionState("PENDING");repairProtection(r,snapshot);recoveries.updateById(r);return null;}
                    else if(!"ACTIVE".equals(r.getProtectionState())||windowPayment||replacements.selectActive(r.getStripeSubscriptionId())!=null||hasUncertainPayment(r,action.getId())||methodSyncPending(r))return null;
                    else if(!sameContract(r,checked)){review(r,"CONTRACT_CHANGED_BEFORE_RETRY");r.setStatus("OPEN_MANUAL");recoveries.updateById(r);skip="SKIPPED_CONTRACT_CHANGED";}
                }
            }else if("RESTORE_ADVANCE".equals(action.getActionType())){
                if(!collectionRepairPermitted(user,r,action,targetSnapshot))skip="SKIPPED_NEW_RECOVERY_OR_UNVERIFIED_SCOPE";
                else if(Boolean.TRUE.equals(targetSnapshot.invoice().getAutoAdvance())){action.setStatus("SUCCEEDED");actions.updateById(action);return null;}
            }else if("PAUSE_COLLECTION".equals(action.getActionType())){
                if(r.getAccountClosedAt()!=null || "PAID".equals(r.getStatus()) && (!currentRecovery(user,r) || obsoletePause(r,action,snapshot)))skip="SKIPPED_SUPERSEDED";
            }else if("UPDATE_METHOD".equals(action.getActionType())){
                var params=SubscriptionRecoveryStripeGateway.thaw(action.getPayloadJson());String method=Objects.toString(params.get("default_payment_method"),"");
                String resource=Objects.toString(params.get("resource"),"");
                if(!Objects.equals(user.getPaymentRecoveryId(),r.getId()) || !Objects.equals(user.getStripeSubscriptionId(),r.getStripeSubscriptionId()) || r.getAccountClosedAt()!=null
                        || !Set.of("OPEN_AUTO","OPEN_MANUAL","PAID").contains(r.getStatus()))skip="SKIPPED_TERMINAL";
                else if(methodCustomer==null || !Objects.equals(r.getStripeCustomerId(),methodCustomer.getId()) || methodCustomer.getInvoiceSettings()==null
                        || !Objects.equals(method,methodCustomer.getInvoiceSettings().getDefaultPaymentMethod()))skip="SKIPPED_SUPERSEDED";
                else if(actions.selectByRecovery(r.getId()).stream().anyMatch(a->!Objects.equals(a.getId(),action.getId()) && "UPDATE_METHOD".equals(a.getActionType()) && Set.of("DISPATCHING","UNKNOWN","REVIEW").contains(a.getStatus())))return null;
                else if("schedule".equals(resource)){
                    var schedule=snapshot.schedule();
                    if(schedule==null || !Objects.equals(action.getTargetId(),schedule.getId()))skip="SKIPPED_SUPERSEDED";
                    else if(schedulePhaseMethodConflict(snapshot,method)){review(r,"PAYMENT_METHOD_SCHEDULE_SYNC_REQUIRES_REVIEW");recoveries.updateById(r);skip="SKIPPED_CONTRACT_CHANGED";}
                }else if(snapshot.subscription().getSchedule()!=null){
                    var schedule=snapshot.schedule();
                    String scheduleMethod=schedule==null||schedule.getDefaultSettings()==null?null:schedule.getDefaultSettings().getDefaultPaymentMethod();
                    if(schedulePhaseMethodConflict(snapshot,method)){review(r,"PAYMENT_METHOD_SCHEDULE_SYNC_REQUIRES_REVIEW");recoveries.updateById(r);skip="SKIPPED_CONTRACT_CHANGED";}
                    else if(!Objects.equals(method,scheduleMethod))return null;
                }
                else if("invoice".equals(params.get("resource")) && !"open".equals(targetSnapshot.invoice().getStatus()))skip="SKIPPED_TERMINAL";
            }else if("SET_CANCEL_PREFERENCE".equals(action.getActionType())){
                var saved=r.getContractSnapshot()==null?new JsonObject():JsonParser.parseString(r.getContractSnapshot()).getAsJsonObject();
                var intent=saved.getAsJsonObject("cancellation_intent");
                if(intent==null || !Objects.equals(action.getActionKey(),string(intent,"action_key")) || !Objects.equals(user.getPaymentRecoveryId(),r.getId())
                        || !Objects.equals(user.getStripeSubscriptionId(),r.getStripeSubscriptionId()))skip="SKIPPED_SUPERSEDED";
                else if(actions.selectByRecovery(r.getId()).stream().anyMatch(a->!Objects.equals(a.getId(),action.getId()) && "SET_CANCEL_PREFERENCE".equals(a.getActionType()) && Set.of("DISPATCHING","UNKNOWN","REVIEW").contains(a.getStatus())))return null;
                else if(snapshot.subscription().getSchedule()!=null || "canceled".equals(snapshot.subscription().getStatus())){review(r,"CANCELLATION_PREFERENCE_REQUIRES_MANUAL_SYNC");recoveries.updateById(r);skip="SKIPPED_REMOTE_CONTRACT";}
            }else if("CREATE_CHECKOUT".equals(action.getActionType())){
                boolean eligible=Set.of("OPEN_AUTO","OPEN_MANUAL").contains(r.getStatus())&&Objects.equals(user.getPaymentRecoveryId(),r.getId())&&Objects.equals(user.getStripeSubscriptionId(),r.getStripeSubscriptionId())
                        &&"ACTIVE".equals(r.getProtectionState())&&snapshot.businessNow().isBefore(instant(r.getDeadlineAt()))&&!isPaid(snapshot)&&snapshot.invoice().getAmountPaid()!=null&&snapshot.invoice().getAmountPaid()==0;
                if(!eligible){
                    skip="SKIPPED_TERMINAL";
                    String orderNo=metadata(JsonParser.parseString(action.getPayloadJson()).getAsJsonObject(),"order_no");var replacement=replacements.selectByOrder(orderNo);
                    if(replacement!=null&&replacement.getCheckoutSessionId()==null&&action.getFirstDispatchedAt()==null){replacement.setState("CANCELED");replacement.setActiveSubscriptionKey(null);replacement.setClosedAt(DateTimeFormats.now());replacements.updateById(replacement);}
                }else if(!freshProtection(snapshot)){r.setProtectionState("PENDING");repairProtection(r,snapshot);recoveries.updateById(r);return null;}
                else if(windowPayment||hasUncertainPayment(r,null))return null;
            }else if(Set.of("SHIFT_RENEWAL","RELEASE_SCHEDULE").contains(action.getActionType())){
                if(actions.selectByRecovery(r.getId()).stream().anyMatch(a->"SET_CANCEL_PREFERENCE".equals(a.getActionType()) && Set.of("READY","DISPATCHING","UNKNOWN","REVIEW").contains(a.getStatus())))return null;
                if(!currentRecovery(user,r))skip="SKIPPED_SUPERSEDED";
                else if(r.getEntitlementEndAt()!=null && !snapshot.businessNow().isBefore(instant(r.getEntitlementEndAt()))){r.setAnchorSyncState("REVIEW");review(r,"PAID_PERIOD_ELAPSED_BEFORE_RENEWAL_SYNC");recoveries.updateById(r);skip="SKIPPED_ELAPSED_PAID_PERIOD";}
                else if(methodSyncPending(r))return null;
                else if(contractRecheckIncomplete){
                    r.setAnchorSyncState("REVIEW");review(r,"RENEWAL_CONTRACT_RECHECK_INCOMPLETE");recoveries.updateById(r);
                    action.setStatus("REVIEW");action.setLastError("RECOVERY_ADMISSION_UNKNOWN_SCHEDULE");actions.updateById(action);return null;
                }
                else if(!"PAID".equals(r.getStatus())||!"GRANTED".equals(r.getGrantState())||"canceled".equals(snapshot.subscription().getStatus())||!sameContract(r,checked)){
                    r.setAnchorSyncState("REVIEW");review(r,"RENEWAL_CONTRACT_OR_CANCEL_REVIEW");recoveries.updateById(r);skip="SKIPPED_CONTRACT_CHANGED";
                }else if("SHIFT_RENEWAL".equals(action.getActionType())){
                    if(snapshot.subscription().getSchedule()!=null)return null;
                    if(!freshProtection(snapshot)){repairProtection(r,snapshot);r.setProtectionState("PENDING");recoveries.updateById(r);return null;}
                    boolean unprotected=invoices.stream().anyMatch(i->Set.of("draft","open","uncollectible").contains(i.getStatus())&&Boolean.TRUE.equals(i.getAutoAdvance()));
                    if(unprotected){for(var invoice:invoices)if(Boolean.TRUE.equals(invoice.getAutoAdvance())&&Set.of("draft","open","uncollectible").contains(invoice.getStatus()))
                        addAction(r,"DISABLE_ADVANCE","recovery:"+invoice.getId()+":disable-advance",invoice.getId(),Map.of("auto_advance",false),null,Instant.now());return null;}
                }
            }else if("DISABLE_ADVANCE".equals(action.getActionType())){
                // A protection intent frozen while the invoice was still collectible must not be
                // dispatched after finalize/void completed it: Stripe rejects updates on a void
                // invoice, which would open a human review for a no-op.
                if(Set.of("paid","void").contains(targetSnapshot.invoice().getStatus()))skip="SKIPPED_TERMINAL";
            }else if(Set.of("VOID_INVOICE","FINALIZE_INVOICE").contains(action.getActionType())){
                if("PAID".equals(r.getStatus()) && genuineNextCycle(r,targetSnapshot))skip="SKIPPED_NEW_BILLING_CYCLE";
                else if(!Objects.equals(targetSnapshot.invoice().getSubscription(),r.getStripeSubscriptionId()) || !Objects.equals(targetSnapshot.invoice().getCustomer(),r.getStripeCustomerId()))skip="SKIPPED_WRONG_SUBSCRIPTION";
                else if(targetSnapshot.invoice().getAmountPaid()==null || targetSnapshot.invoice().getAmountPaid()>0 || paymentInFlight(targetSnapshot)){review(r,"PAYMENT_DURING_INVOICE_CLOSE");recoveries.updateById(r);return null;}
                else if(Set.of("paid","void").contains(targetSnapshot.invoice().getStatus()))skip="SKIPPED_TERMINAL";
                else if(!equivalentPaidLine(r,targetSnapshot) && !RecoveryInvoiceCloseScope.paidTrialRollover(r,targetSnapshot)
                        && !(isTechnicalInvoice(r,targetSnapshot.invoice().getId()) && Long.valueOf(0).equals(targetSnapshot.invoice().getAmountDue()) && "subscription_update".equals(targetSnapshot.invoice().getBillingReason()))){
                    review(r,"UNVERIFIED_OR_MIXED_INVOICE_CLOSE_TARGET");if("PAID".equals(r.getStatus()))r.setAnchorSyncState("REVIEW");recoveries.updateById(r);skip="SKIPPED_UNVERIFIED_TARGET";
                }else if("FINALIZE_INVOICE".equals(action.getActionType()) && (!silentDraftCleanupVerified(r) || !Boolean.FALSE.equals(SubscriptionRecoveryStripeGateway.thaw(action.getPayloadJson()).get("auto_advance")))){
                    review(r,"SILENT_DRAFT_CLEANUP_UNVERIFIED");recoveries.updateById(r);skip="SKIPPED_UNVERIFIED_SILENCE";
                }
            }else if(Set.of("CANCEL_SUBSCRIPTION","CANCEL_SCHEDULE").contains(action.getActionType())){
                boolean passCleanup=action.getActionKey()!=null&&action.getActionKey().contains(":end-pass:");
                if(!passCleanup&&(!"CLOSING".equals(r.getStatus())||r.getCloseReason()==null))skip="SKIPPED_TERMINAL";
                else if(!passCleanup&&(windowPayment||hasUncertainPayment(r,null)))return null;
                else if(cancellation!=null && "canceled".equals(cancellation.status())){action.setStatus("SUCCEEDED");actions.updateById(action);return null;}
            }
            if("DISABLE_ADVANCE".equals(action.getActionType()) && "PAID".equals(r.getStatus()) && genuineNextCycle(r,targetSnapshot))skip="SKIPPED_NEW_BILLING_CYCLE";
            if("SEND_EMAIL".equals(action.getActionType())){
                if(!config.isEmailEnabled())skip="SKIPPED_DISABLED";
                else if(!com.studyagent.infra.notification.BillingPaymentRecoveryEmailSender.eligible(r) || !reminderProviderEligible(snapshot) || !currentRecovery(user,r))skip="SKIPPED_TERMINAL";
                else if(paymentInFlight(snapshot) || hasUncertainPayment(r,null) || replacements.selectActive(r.getStripeSubscriptionId())!=null)return null;
                else if(!"ACTIVE".equals(r.getProtectionState()))return null;
                else if(frozenEmail!=null){action.setPayloadJson(frozenEmail);actions.updateById(action);}
            }
            if(skip!=null){action.setStatus("SKIPPED");action.setSkipReason(skip);actions.updateById(action);return null;}
            String token=UUID.randomUUID().toString();int version=action.getVersion()==null?0:action.getVersion();LocalDateTime real=DateTimeFormats.now();
            // Due comparison uses business time for retry slots, provider leases always use real elapsed time.
            LocalDateTime due="RETRY_PAY".equals(action.getActionType())?local(business):real;
            if(actions.claim(actionId,version,token,real.plusSeconds(config.getLeaseSeconds()),due)!=1)return null;
            action.setVersion(version+1);action.setLeaseToken(token);action.setStatus("DISPATCHING");action.setLeaseUntil(real.plusSeconds(config.getLeaseSeconds()));
            Map<String,Object> pre=action.getResultJson()==null?new LinkedHashMap<>():new LinkedHashMap<>(SubscriptionRecoveryStripeGateway.thaw(action.getResultJson()));pre.put("before_charge_id",snapshot.charge()==null?null:snapshot.charge().getId());pre.put("before_latest_invoice",snapshot.subscription().getLatestInvoice());pre.put("before_invoice_ids",invoices.stream().map(Invoice::getId).toList());
            if(cancellation!=null){
                pre.put("cancel_target_subscription_id",cancellation.subscriptionId());
                pre.put("before_other_collection",customerBefore.stream().filter(i->i.getId()!=null && Objects.equals(r.getStripeCustomerId(),i.getCustomer()) && user.getStripeSubscriptionId()!=null
                        && Objects.equals(user.getStripeSubscriptionId(),i.getSubscription()) && !Objects.equals(cancellation.subscriptionId(),i.getSubscription()) && Boolean.TRUE.equals(i.getAutoAdvance()) && Set.of("draft","open").contains(i.getStatus()))
                        .map(i->Map.of("invoice_id",i.getId(),"subscription_id",i.getSubscription(),"auto_advance",true)).toList());
            }
            if(actions.markDispatched(actionId,version+1,token,real,JSON.toJson(pre))!=1)throw new IllegalStateException("Recovery action lost lease before dispatch");
            action.setFirstDispatchedAt(real);action.setResultJson(JSON.toJson(pre));
            if(cancellation!=null)queueCollectionCheck(r,action);
            return action;
        });
        if(claimed==null)return;
        SubscriptionRecoveryStripeGateway.WriteResult result;
        try{
            if(Set.of("SEND_EMAIL","CAPTURE_FAILURE","NOTIFY_INTERNAL_FAILURE","NOTIFY_REVIEW","END_STUDY_PASS").contains(claimed.getActionType()))result=executeApplicationAction(claimed,root);
            else result=stripe.execute(claimed);
        }catch(RuntimeException e){result=new SubscriptionRecoveryStripeGateway.WriteResult("UNKNOWN",null,e.getClass().getSimpleName(),null);}
        completeAction(claimed,root,result,snapshot);
        if(immediateResponse.get()!=null&&result.object()!=null)immediateResponse.get().accept(result.object());
        if(!Set.of("CREATE_PORTAL","CAPTURE_FAILURE","NOTIFY_INTERNAL_FAILURE","NOTIFY_REVIEW").contains(claimed.getActionType()))reconcile(root.getId());
    }
    private CancellationTarget cancellationTarget(SubscriptionRecoveryActionEntity action,SubscriptionRecoveryStripeGateway.Snapshot snapshot) {
        if("CANCEL_SUBSCRIPTION".equals(action.getActionType())){
            var sub=Objects.equals(action.getTargetId(),snapshot.subscription().getId())?snapshot.subscription():stripe.readSubscription(action.getTargetId());
            if(sub==null || !Objects.equals(sub.getCustomer(),snapshot.invoice().getCustomer()))throw incomplete(action.getTargetId());
            return new CancellationTarget(sub.getId(),sub.getStatus());
        }
        if("CANCEL_SCHEDULE".equals(action.getActionType())){
            var schedule=snapshot.schedule()!=null && Objects.equals(action.getTargetId(),snapshot.schedule().getId())?snapshot.schedule():stripe.readSchedule(action.getTargetId());
            if(schedule==null)throw incomplete(action.getTargetId());return new CancellationTarget(schedule.getSubscription(),schedule.getStatus());
        }
        return null;
    }
    private void queueCollectionCheck(SubscriptionPaymentRecoveryEntity root,SubscriptionRecoveryActionEntity cancellation) {
        if(cancellation==null || cancellation.getResultJson()==null)return;
        var proof=SubscriptionRecoveryStripeGateway.thaw(cancellation.getResultJson());
        if(!(proof.get("before_other_collection") instanceof List<?> captures) || captures.isEmpty())return;
        addAction(root,"VERIFY_COLLECTION","recovery:"+cancellation.getId()+":verify-other-collection",root.getStripeCustomerId(),Map.of("cancel_action_id",cancellation.getId()),null,Instant.now());
    }
    private void runCollectionCheck(SubscriptionRecoveryActionEntity candidate,SubscriptionPaymentRecoveryEntity root) {
        var claimed=locked(root.getId(),(u,r)->{
            var action=actions.selectForUpdate(candidate.getId());if(action==null || !Set.of("READY","UNKNOWN","DISPATCHING").contains(action.getStatus()))return null;
            var now=DateTimeFormats.now();int version=action.getVersion()==null?0:action.getVersion();String token=UUID.randomUUID().toString();
            if(actions.claimCollectionCheck(action.getId(),version,token,now.plusSeconds(config.getLeaseSeconds()),now)!=1)return null;
            action.setStatus("DISPATCHING");action.setVersion(version+1);action.setLeaseToken(token);action.setLeaseUntil(now.plusSeconds(config.getLeaseSeconds()));
            if(action.getFirstDispatchedAt()==null)action.setFirstDispatchedAt(now);
            if(actions.markDispatched(action.getId(),action.getVersion(),token,now,"{}")!=1)throw new IllegalStateException("Collection check lost lease");return action;
        });
        if(claimed==null)return;
        String outcome="UNKNOWN",error=null,followupOutcome="WAITING_FOR_CANCEL_RESULT";boolean scopeRead=false;
        try{
            var params=SubscriptionRecoveryStripeGateway.thaw(claimed.getPayloadJson());
            if(!(params.get("cancel_action_id") instanceof Number id))throw incomplete(claimed.getActionKey());
            var source=actions.selectById(id.longValue());
            if(source==null || !Objects.equals(root.getId(),source.getRecoveryId()) || !Set.of("CANCEL_SUBSCRIPTION","CANCEL_SCHEDULE").contains(source.getActionType()))throw incomplete(claimed.getActionKey());
            if("SUCCEEDED".equals(source.getStatus())){
                if(source.getResultJson()==null || !(SubscriptionRecoveryStripeGateway.thaw(source.getResultJson()).get("before_other_collection") instanceof List<?> captures) || captures.isEmpty())throw incomplete(claimed.getActionKey());
                registerCollectionRepairs(root,source);scopeRead=true;followupOutcome="READ_AND_REGISTERED";outcome="SUCCEEDED";
            }
            else if("FAILED".equals(source.getStatus())){followupOutcome="CANCEL_FAILED_NO_OWN_WRITE";outcome="SUCCEEDED";}
            else if("REVIEW".equals(source.getStatus())){
                locked(root.getId(),(u,r)->{review(r,"OTHER_SUBSCRIPTION_COLLECTION_CANCEL_RESULT_UNVERIFIED");recoveries.updateById(r);return null;});followupOutcome="MANUAL_CANCEL_RESULT_REVIEW";outcome="SUCCEEDED";
            }
        }catch(RuntimeException unavailable){error=unavailable.getClass().getSimpleName();}
        final String status=outcome,readError=error,followup=followupOutcome;final boolean readComplete=scopeRead;
        locked(root.getId(),(u,r)->{
            var action=actions.selectForUpdate(claimed.getId());
            if(action==null || !"DISPATCHING".equals(action.getStatus()) || !Objects.equals(action.getVersion(),claimed.getVersion()) || !Objects.equals(action.getLeaseToken(),claimed.getLeaseToken()))return null;
            var result=new LinkedHashMap<String,Object>();result.put("collection_scope_read_complete",readComplete);result.put("followup_outcome",followup);result.put("error",readError);
            if(actions.complete(action.getId(),action.getVersion(),action.getLeaseToken(),status,JSON.toJson(result),DateTimeFormats.now())==1
                    && "UNKNOWN".equals(status) && !SubscriptionRecoveryPolicy.canReplayUnknown(instant(action.getFirstDispatchedAt()),Instant.now())){
                review(r,"OTHER_SUBSCRIPTION_COLLECTION_READ_UNAVAILABLE");recoveries.updateById(r);
            }
            return null;
        });
    }
    private void registerCollectionRepairs(SubscriptionPaymentRecoveryEntity root,SubscriptionRecoveryActionEntity cancellation) {
        if(cancellation.getResultJson()==null)return;
        var before=SubscriptionRecoveryStripeGateway.thaw(cancellation.getResultJson());
        if(!(before.get("before_other_collection") instanceof List<?> captures) || captures.isEmpty())return;
        var after=stripe.listCustomerInvoices(root.getStripeCustomerId());
        locked(root.getId(),(user,r)->{
            var source=actions.selectForUpdate(cancellation.getId());if(source==null || !"SUCCEEDED".equals(source.getStatus()))return null;
            var proof=SubscriptionRecoveryStripeGateway.thaw(source.getResultJson());
            for(var capture:captures){
                if(!(capture instanceof Map<?,?> prior))continue;String id=Objects.toString(prior.get("invoice_id"),null),sub=Objects.toString(prior.get("subscription_id"),null);
                if(!Objects.equals(user.getStripeSubscriptionId(),sub) || Objects.equals(sub,proof.get("cancel_target_subscription_id")))continue;
                var invoice=after.stream().filter(i->Objects.equals(id,i.getId()) && Objects.equals(sub,i.getSubscription()) && Objects.equals(r.getStripeCustomerId(),i.getCustomer())).findFirst().orElse(null);
                if(invoice==null || !Boolean.FALSE.equals(invoice.getAutoAdvance()) || !Set.of("draft","open").contains(invoice.getStatus()))continue;
                if(!"SUCCEEDED".equals(proof.get("provider_state"))){
                    review(r,"OTHER_SUBSCRIPTION_COLLECTION_REQUIRES_REVIEW");recoveries.updateById(r);continue;
                }
                String key="recovery:"+source.getId()+":restore-advance:"+id;
                var repair=addAction(r,"RESTORE_ADVANCE",key,id,Map.of("auto_advance",true,"metadata",Map.of("payment_recovery_restore_action",key)),null,Instant.now());
                if("READY".equals(repair.getStatus()) && repair.getResultJson()==null){repair.setResultJson(JSON.toJson(Map.of("cause_action_id",source.getId(),"expected_subscription_id",sub)));actions.updateById(repair);}
            }
            return null;
        });
    }
    private boolean collectionRepairPermitted(UserSubscriptionEntity user,SubscriptionPaymentRecoveryEntity root,SubscriptionRecoveryActionEntity action,SubscriptionRecoveryStripeGateway.Snapshot target) {
        if(action.getResultJson()==null)return false;var binding=SubscriptionRecoveryStripeGateway.thaw(action.getResultJson());String expected=Objects.toString(binding.get("expected_subscription_id"),null);
        if(!(binding.get("cause_action_id") instanceof Number sourceId) || expected==null || !Objects.equals(user.getStripeSubscriptionId(),expected)
                || !Objects.equals(target.invoice().getSubscription(),expected) || !Objects.equals(target.invoice().getCustomer(),root.getStripeCustomerId())
                || target.subscription()==null || !Objects.equals(target.subscription().getId(),expected) || target.subscription().getPauseCollection()!=null
                || !Set.of("active","trialing","past_due").contains(Objects.toString(target.subscription().getStatus(),"")) || !Set.of("draft","open").contains(target.invoice().getStatus()))return false;
        var current=access.resolveForUpdate(user.getClerkUserId(),expected);if(current==null || current.locked())return false;
        var source=actions.selectForUpdate(sourceId.longValue());
        if(source==null || !Objects.equals(root.getId(),source.getRecoveryId()) || !"SUCCEEDED".equals(source.getStatus()) || !Set.of("CANCEL_SUBSCRIPTION","CANCEL_SCHEDULE").contains(source.getActionType()))return false;
        var proof=SubscriptionRecoveryStripeGateway.thaw(source.getResultJson());if(!"SUCCEEDED".equals(proof.get("provider_state")) || Objects.equals(expected,proof.get("cancel_target_subscription_id")))return false;
        return proof.get("before_other_collection") instanceof List<?> list && list.stream().anyMatch(v->v instanceof Map<?,?> item && Boolean.TRUE.equals(item.get("auto_advance")) && Objects.equals(action.getTargetId(),item.get("invoice_id")) && Objects.equals(expected,item.get("subscription_id")));
    }
    private boolean hasUncertainPayment(SubscriptionPaymentRecoveryEntity root,Long exclude){
        return actions.selectByRecovery(root.getId()).stream().anyMatch(a->!Objects.equals(exclude,a.getId())&&"RETRY_PAY".equals(a.getActionType())&&Set.of("DISPATCHING","UNKNOWN","REVIEW").contains(a.getStatus()));
    }
    private boolean sameContract(SubscriptionPaymentRecoveryEntity root,Contract current){
        if(current==null)return false;
        var admission=JsonParser.parseString(root.getContractSnapshot()).getAsJsonObject().getAsJsonObject("recovery_admission");
        if(Objects.equals(string(admission,"contractHash"),current.hash()))return true;
        boolean released=actions.selectByRecovery(root.getId()).stream().anyMatch(a->"RELEASE_SCHEDULE".equals(a.getActionType())&&"SUCCEEDED".equals(a.getStatus()));
        var now=JsonParser.parseString(current.json()).getAsJsonObject().getAsJsonObject("recovery_admission");
        if(released&&"NO_SCHEDULE".equals(string(now,"scheduleClass"))&&Objects.equals(string(admission,"cancelMode"),string(now,"cancelMode"))
                &&Objects.equals(root.getPlanCode(),current.plan().getPlanCode())&&Objects.equals(string(JsonParser.parseString(root.getContractSnapshot()).getAsJsonObject(),"price_id"),current.priceId()))return true;
        return "EQUIVALENT_SCHEDULE".equals(string(admission,"scheduleClass"))
                && "EQUIVALENT_SCHEDULE".equals(string(now,"scheduleClass"))
                && Objects.equals(string(admission,"cancelMode"),string(now,"cancelMode"))
                && RecoveryContractClassifier.sameEffectiveContract(admission.getAsJsonObject("scheduleContract"),now.getAsJsonObject("scheduleContract"));
    }

    private void completeAction(SubscriptionRecoveryActionEntity claimed,SubscriptionPaymentRecoveryEntity before,SubscriptionRecoveryStripeGateway.WriteResult result){
        completeAction(claimed,before,result,null);
    }
    private void completeAction(SubscriptionRecoveryActionEntity claimed,SubscriptionPaymentRecoveryEntity before,SubscriptionRecoveryStripeGateway.WriteResult result,SubscriptionRecoveryStripeGateway.Snapshot beforeSnapshot){
        locked(before.getId(),(user,root)->{
            var action=actions.selectForUpdate(claimed.getId());
            if(action==null||!"DISPATCHING".equals(action.getStatus())||!Objects.equals(action.getLeaseToken(),claimed.getLeaseToken())||!Objects.equals(action.getVersion(),claimed.getVersion()))return null;
            var details=action.getResultJson()==null?new LinkedHashMap<String,Object>():new LinkedHashMap<>(SubscriptionRecoveryStripeGateway.thaw(action.getResultJson()));
            boolean mail="SEND_EMAIL".equals(action.getActionType());
            if(!mail)action.setProviderRequestId(result.requestId());
            if(mail)details.put("provider_message_id",result.requestId());
            // The token/version CAS below owns all result fields; no stale worker may persist them.
            details.put("provider_request_id",mail?null:result.requestId());details.put("provider_state",result.state());details.put("error",result.error());
            if(result.object() instanceof com.stripe.model.billingportal.Session portal)details.put("portal_session_id",portal.getId());
            if(result.object() instanceof Session session){details.put("checkout_session_id",session.getId());details.put("subscription_id",session.getSubscription());}
            if(result.object() instanceof Charge charge){details.put("charge_id",charge.getId());if(charge.getPaymentIntent()!=null)details.put("payment_intent_id",charge.getPaymentIntent());}
            if(result.object() instanceof PaymentIntent intent){details.put("payment_intent_id",intent.getId());details.put("charge_id",intent.getLatestCharge());}
            if(result.object() instanceof Invoice invoice){details.put("payment_intent_id",invoice.getPaymentIntent());details.put("charge_id",invoice.getCharge());}
            if(result.object() instanceof Subscription sub){details.put("subscription_id",sub.getId());details.put("latest_invoice",sub.getLatestInvoice());details.put("trial_end",sub.getTrialEnd());}
            String status="DECLINED".equals(result.state())?"SUCCEEDED":result.state();
            if(actions.complete(action.getId(),action.getVersion(),action.getLeaseToken(),status,JSON.toJson(details),DateTimeFormats.now())!=1)return null;
            if("RETRY_PAY".equals(action.getActionType()) && "DECLINED".equals(result.state()) && beforeSnapshot!=null){
                var intent=result.object() instanceof PaymentIntent pi?pi:new PaymentIntent();
                if(intent.getId()==null)intent.setId(beforeSnapshot.invoice().getPaymentIntent());
                if(intent.getLastPaymentError()==null){var error=new StripeError();error.setDeclineCode(result.error());intent.setLastPaymentError(error);}
                Charge failed=result.object() instanceof Charge charge?charge:null;
                if(failed==null && intent.getLatestCharge()!=null){failed=new Charge();failed.setId(intent.getLatestCharge());}
                var receipt=new SubscriptionRecoveryStripeGateway.Snapshot(beforeSnapshot.invoice(),beforeSnapshot.subscription(),beforeSnapshot.schedule(),beforeSnapshot.lines(),intent,failed,beforeSnapshot.businessNow());
                var event=syntheticEvent("recovery-api-decline:"+action.getId(),"invoice.payment_failed",root.getStripeInvoiceId());
                if(!"PAID".equals(root.getStatus()) && root.getAccountClosedAt()==null){
                    setFailure(root,receipt,event);if(!automatic(receipt)){root.setStatus("OPEN_MANUAL");skipRetries(root,"MANUAL_ONLY");}
                }
                action.setResultJson(JSON.toJson(details));failureActions(root,receipt,event,action,failed);recoveries.updateById(root);
            }
            if(result.object() instanceof Subscription sub && "SET_CANCEL_PREFERENCE".equals(action.getActionType()) && currentRecovery(user,root)){
                user.setCancelAtPeriodEnd(sub.getCancelAtPeriodEnd());saveRecoveryUser(user);
            }

            if(result.object() instanceof Session session&&"CREATE_CHECKOUT".equals(action.getActionType())){
                String order=string(JsonParser.parseString(action.getPayloadJson()).getAsJsonObject().getAsJsonObject("metadata"),"order_no");var replacement=replacements.selectByOrder(order);
                if(replacement==null)throw incomplete(session.getId());replacement.setCheckoutSessionId(session.getId());replacement.setNewSubscriptionId(session.getSubscription());replacement.setExpiresAt(local(epoch(session.getExpiresAt())));replacement.setState("OPEN");replacements.updateById(replacement);
                var recharge=orders.selectOne(new LambdaQueryWrapper<RechargeOrderEntity>().eq(RechargeOrderEntity::getOrderNo,order));if(recharge!=null){recharge.setStripeSessionId(session.getId());orders.updateById(recharge);}
            }
            if("FAILED".equals(status)){review(root,"REMOTE_ACTION_FAILED:"+action.getActionType());if("SHIFT_RENEWAL".equals(action.getActionType()))root.setAnchorSyncState("REVIEW");recoveries.updateById(root);}
            if("UNKNOWN".equals(status)){root.setNextReconcileAt(DateTimeFormats.now().plusMinutes(1));recoveries.updateById(root);}
            return null;
        });
    }
    private void reconcileUnknownReminder(SubscriptionRecoveryActionEntity candidate,SubscriptionPaymentRecoveryEntity root) {
        var snapshot=stripe.readInvoice(root.getStripeInvoiceId());
        applyInvoice(root,snapshot,syntheticEvent("mail-reconcile:"+candidate.getId(),"invoice.reconciled",root.getStripeInvoiceId()));
        var claimed=locked(root.getId(),(user,r)->{
            var action=actions.selectForUpdate(candidate.getId());if(action==null)return null;
            LocalDateTime now=DateTimeFormats.now();
            if("DISPATCHING".equals(action.getStatus())){
                if(action.getLeaseUntil()!=null && action.getLeaseUntil().isAfter(now))return null;
                action.setStatus("UNKNOWN");action.setLeaseToken(null);action.setLeaseUntil(null);actions.updateById(action);
            }
            if(!"UNKNOWN".equals(action.getStatus()))return null;
            if(!config.isEmailEnabled() || !com.studyagent.infra.notification.BillingPaymentRecoveryEmailSender.eligible(r) || !reminderProviderEligible(snapshot)
                    || !Objects.equals(user.getPaymentRecoveryId(),r.getId()) || !Objects.equals(user.getStripeSubscriptionId(),r.getStripeSubscriptionId())){
                action.setStatus("SKIPPED");action.setSkipReason("SKIPPED_NO_FURTHER_REMINDER");actions.updateById(action);return null;
            }
            // Reserve a minute for transport. The original first dispatch is never reset.
            Instant first=instant(action.getFirstDispatchedAt());
            if(first==null || !instant(now).plusSeconds(60).isBefore(first.plusSeconds(86400))){
                action.setStatus("REVIEW");action.setLastError("EMAIL_DELIVERY_UNKNOWN_AFTER_IDEMPOTENCY_WINDOW");actions.updateById(action);
                Map<String,Object> notice=new LinkedHashMap<>();notice.put("reason","email_delivery_unknown");notice.put("assignee",config.getReviewAssignee());notice.put("due_at",DateTimeFormats.formatApi(now));
                addAction(r,"NOTIFY_REVIEW",action.getActionKey()+":delivery-review",r.getStripeInvoiceId(),notice,null,Instant.now());return null;
            }
            if(paymentInFlight(snapshot) || hasUncertainPayment(r,null) || replacements.selectActive(r.getStripeSubscriptionId())!=null)return null;
            if(action.getUpdatedAt()!=null && action.getUpdatedAt().isAfter(now.minusSeconds(60)))return null;
            String token=UUID.randomUUID().toString();int version=action.getVersion()==null?0:action.getVersion();
            if(actions.claimUnknownMail(action.getId(),version,token,now.plusSeconds(config.getLeaseSeconds()),now)!=1)return null;
            action.setVersion(version+1);action.setStatus("DISPATCHING");action.setLeaseToken(token);action.setLeaseUntil(now.plusSeconds(config.getLeaseSeconds()));
            var proof=action.getResultJson()==null?new LinkedHashMap<String,Object>():new LinkedHashMap<>(SubscriptionRecoveryStripeGateway.thaw(action.getResultJson()));proof.put("email_transport_replay_at",instant(now).toString());
            if(actions.markDispatched(action.getId(),action.getVersion(),token,now,JSON.toJson(proof))!=1)throw new IllegalStateException("Reminder replay lost its lease");
            action.setResultJson(JSON.toJson(proof));return action;
        });
        if(claimed!=null){
            var sent=emailSender.send(claimed.getPayloadJson(),claimed.getProviderIdempotencyKey());
            completeAction(claimed,root,new SubscriptionRecoveryStripeGateway.WriteResult(sent.state(),sent.messageId(),sent.error(),null));
        }
    }
    private void reconcileUnknown(SubscriptionRecoveryActionEntity candidate,SubscriptionPaymentRecoveryEntity record){
        var root=record.getOwnerRecoveryId()==null?record:recoveries.selectById(record.getOwnerRecoveryId());if(root==null)return;
        if("SEND_EMAIL".equals(candidate.getActionType())){reconcileUnknownReminder(candidate,root);return;}
        if("END_STUDY_PASS".equals(candidate.getActionType())){
            boolean eligible=locked(root.getId(),(u,r)->{var a=actions.selectForUpdate(candidate.getId());return a!=null && ("UNKNOWN".equals(a.getStatus()) || "DISPATCHING".equals(a.getStatus()) && a.getLeaseUntil()!=null && !a.getLeaseUntil().isAfter(DateTimeFormats.now()));});
            if(!eligible)return;
            executeApplicationAction(candidate,root);
            locked(root.getId(),(u,r)->{var a=actions.selectForUpdate(candidate.getId());if(a!=null && Set.of("UNKNOWN","DISPATCHING").contains(a.getStatus())){a.setStatus("SUCCEEDED");a.setLeaseToken(null);a.setLeaseUntil(null);actions.updateById(a);}return null;});return;
        }
        if(Set.of("CAPTURE_FAILURE","NOTIFY_INTERNAL_FAILURE","NOTIFY_REVIEW").contains(candidate.getActionType())){
            locked(root.getId(),(u,r)->{
                var action=actions.selectForUpdate(candidate.getId());if(action==null)return null;
                if("DISPATCHING".equals(action.getStatus()) && action.getLeaseUntil()!=null && action.getLeaseUntil().isAfter(DateTimeFormats.now()))return null;
                if(Set.of("DISPATCHING","UNKNOWN").contains(action.getStatus())){
                    action.setStatus(action.getFirstDispatchedAt()==null || !SubscriptionRecoveryPolicy.canReplayUnknown(instant(action.getFirstDispatchedAt()),Instant.now())?"REVIEW":"UNKNOWN");
                    action.setLeaseToken(null);action.setLeaseUntil(null);actions.updateById(action);
                }
                return null;
            });return;
        }
        var snapshot=stripe.readInvoice(root.getStripeInvoiceId());
        var target=Set.of("DISABLE_ADVANCE","FINALIZE_INVOICE","VOID_INVOICE","UPDATE_METHOD","RESTORE_ADVANCE").contains(candidate.getActionType())
                && !Objects.equals(candidate.getTargetId(),root.getStripeSubscriptionId()) && !Objects.equals(candidate.getTargetId(),root.getStripeInvoiceId())
                ?stripe.readInvoice(candidate.getTargetId()):snapshot;
        var createdCheckout="CREATE_CHECKOUT".equals(candidate.getActionType())?readUnknownCheckout(candidate,root):null;
        var expiredCheckout="EXPIRE_CHECKOUT".equals(candidate.getActionType())?stripe.readCheckout(candidate.getTargetId()):null;
        var released="RELEASE_SCHEDULE".equals(candidate.getActionType())?stripe.readSchedule(candidate.getTargetId()):null;
        var canceledSub="CANCEL_SUBSCRIPTION".equals(candidate.getActionType())
                ?Objects.equals(candidate.getTargetId(),root.getStripeSubscriptionId())?snapshot.subscription():stripe.readSubscription(candidate.getTargetId()):null;
        var canceledSchedule="CANCEL_SCHEDULE".equals(candidate.getActionType())?stripe.readSchedule(candidate.getTargetId()):null;
        locked(root.getId(),(u,r)->{
            var action=actions.selectForUpdate(candidate.getId());if(action==null)return null;
            if("DISPATCHING".equals(action.getStatus())){if(action.getLeaseUntil()!=null&&action.getLeaseUntil().isAfter(DateTimeFormats.now()))return null;action.setStatus("UNKNOWN");action.setLeaseToken(null);action.setLeaseUntil(null);actions.updateById(action);}
            if(!"UNKNOWN".equals(action.getStatus()))return null;
            boolean confirmed=switch(action.getActionType()){
                case "RESTORE_ADVANCE" -> Objects.equals(action.getTargetId(),target.invoice().getId()) && Boolean.TRUE.equals(target.invoice().getAutoAdvance()) && target.invoice().getMetadata()!=null && Objects.equals(action.getActionKey(),target.invoice().getMetadata().get("payment_recovery_restore_action"));
                case "DISABLE_ADVANCE" -> Objects.equals(action.getTargetId(),target.invoice().getId())&&Objects.equals(r.getStripeSubscriptionId(),target.invoice().getSubscription())&&Boolean.FALSE.equals(target.invoice().getAutoAdvance());
                case "FINALIZE_INVOICE" -> Objects.equals(action.getTargetId(),target.invoice().getId()) && "open".equals(target.invoice().getStatus()) && !Boolean.TRUE.equals(target.invoice().getAutoAdvance());
                case "VOID_INVOICE" -> Objects.equals(action.getTargetId(),target.invoice().getId()) && "void".equals(target.invoice().getStatus());
                case "UPDATE_METHOD" -> {
                    var params=SubscriptionRecoveryStripeGateway.thaw(action.getPayloadJson());String resource=Objects.toString(params.get("resource"),"");String method=Objects.toString(params.get("default_payment_method"),"");
                    yield !method.isEmpty() && ("subscription".equals(resource) && Objects.equals(action.getTargetId(),snapshot.subscription().getId()) && method.equals(snapshot.subscription().getDefaultPaymentMethod())
                            || "invoice".equals(resource) && Objects.equals(action.getTargetId(),target.invoice().getId()) && method.equals(target.invoice().getDefaultPaymentMethod()));
                }
                case "CREATE_CHECKOUT" -> createdCheckout!=null;
                case "EXPIRE_CHECKOUT" -> expiredCheckout!=null && Objects.equals(action.getTargetId(),expiredCheckout.getId()) && "expired".equals(expiredCheckout.getStatus());
                case "RETRY_PAY" -> Objects.equals(action.getTargetId(),snapshot.invoice().getId()) && isPaid(snapshot) && verifiedCash(snapshot);
                case "RELEASE_SCHEDULE" -> released!=null && "released".equals(released.getStatus()) && Objects.equals(released.getReleasedSubscription(),r.getStripeSubscriptionId()) && snapshot.subscription().getSchedule()==null;
                case "PAUSE_COLLECTION" -> snapshot.subscription().getPauseCollection()!=null&&"keep_as_draft".equals(snapshot.subscription().getPauseCollection().getBehavior());
                case "SET_CANCEL_PREFERENCE" -> Objects.equals(SubscriptionRecoveryStripeGateway.thaw(action.getPayloadJson()).get("cancel_at_period_end"),snapshot.subscription().getCancelAtPeriodEnd());
                case "CANCEL_SUBSCRIPTION" -> canceledSub!=null && Objects.equals(action.getTargetId(),canceledSub.getId()) && "canceled".equals(canceledSub.getStatus());
                case "CANCEL_SCHEDULE" -> canceledSchedule!=null && Objects.equals(action.getTargetId(),canceledSchedule.getId()) && "canceled".equals(canceledSchedule.getStatus());
                case "SHIFT_RENEWAL" -> r.getEntitlementEndAt()!=null && snapshot.subscription().getPauseCollection()==null
                        && Objects.equals(snapshot.subscription().getTrialEnd(),DateTimeFormats.toEpochSecond(r.getEntitlementEndAt()))
                        && Objects.equals(snapshot.subscription().getCurrentPeriodEnd(),DateTimeFormats.toEpochSecond(r.getEntitlementEndAt()));
                default -> false;
            };
            if(confirmed){
                if(createdCheckout!=null){
                    var details=action.getResultJson()==null?new LinkedHashMap<String,Object>():new LinkedHashMap<>(SubscriptionRecoveryStripeGateway.thaw(action.getResultJson()));details.put("checkout_session_id",createdCheckout.getId());details.put("confirmed_by_read",true);action.setResultJson(JSON.toJson(details));
                    var replacement=replacements.selectByOrder(createdCheckout.getMetadata().get("order_no"));replacement.setCheckoutSessionId(createdCheckout.getId());replacement.setNewSubscriptionId(createdCheckout.getSubscription());replacements.updateById(replacement);
                    var order=orders.selectOne(new LambdaQueryWrapper<RechargeOrderEntity>().eq(RechargeOrderEntity::getOrderNo,replacement.getOrderNo()));
                    if(order==null)throw incomplete(replacement.getOrderNo());order.setStripeSessionId(createdCheckout.getId());orders.updateById(order);
                }
                action.setStatus("SUCCEEDED");actions.updateById(action);
                if("SET_CANCEL_PREFERENCE".equals(action.getActionType()) && currentRecovery(u,r)){u.setCancelAtPeriodEnd(snapshot.subscription().getCancelAtPeriodEnd());saveRecoveryUser(u);}
            }
            else if(action.getFirstDispatchedAt()==null||!SubscriptionRecoveryPolicy.canReplayUnknown(instant(action.getFirstDispatchedAt()),Instant.now())){
                action.setStatus("REVIEW");actions.updateById(action);review(r,"PROVIDER_RESULT_UNKNOWN:"+action.getActionType());recoveries.updateById(r);
            }
            // Never create a new key or blindly resubmit an UNKNOWN payment/checkout/email.
            return null;
        });
        if(Set.of("CANCEL_SUBSCRIPTION","CANCEL_SCHEDULE").contains(candidate.getActionType()))locked(root.getId(),(u,r)->{queueCollectionCheck(r,actions.selectForUpdate(candidate.getId()));return null;});
        applyInvoice(root,snapshot,syntheticEvent("reconcile:"+root.getId(),"invoice.reconciled",root.getStripeInvoiceId()));
        if(createdCheckout!=null)reconcileReplacement(replacements.selectByOrder(createdCheckout.getMetadata().get("order_no")),null);
    }
    private Session readUnknownCheckout(SubscriptionRecoveryActionEntity action,SubscriptionPaymentRecoveryEntity root) {
        var params=SubscriptionRecoveryStripeGateway.thaw(action.getPayloadJson());
        if(!(params.get("metadata") instanceof Map<?,?> metadata))throw incomplete(action.getActionKey());
        String orderNo=Objects.toString(metadata.get("order_no"),null);var replacement=orderNo==null?null:replacements.selectByOrder(orderNo);
        if(replacement==null || !Objects.equals(root.getId(),replacement.getRecoveryId()) || !Objects.equals(root.getClerkUserId(),replacement.getClerkUserId()) || !Objects.equals(root.getStripeSubscriptionId(),replacement.getOldSubscriptionId()))throw incomplete(action.getActionKey());
        var found=replacement.getCheckoutSessionId()==null?stripe.findCheckout(root.getStripeCustomerId(),orderNo):stripe.readCheckout(replacement.getCheckoutSessionId());
        if(found==null)return null;var session=stripe.readCheckout(found.getId());
        if(session==null || !Objects.equals(found.getId(),session.getId()) || !Objects.equals(root.getStripeCustomerId(),session.getCustomer()) || !"subscription".equals(session.getMode()) || session.getMetadata()==null)throw incomplete(found.getId());
        var expected=Map.of("order_no",orderNo,"purchase_type","subscription_replacement","recovery_id",String.valueOf(root.getId()),"clerk_user_id",root.getClerkUserId(),"old_subscription_id",root.getStripeSubscriptionId());
        for(var entry:expected.entrySet())if(!Objects.equals(entry.getValue(),session.getMetadata().get(entry.getKey())))throw incomplete(session.getId());
        for(var entry:metadata.entrySet())if(!Objects.equals(Objects.toString(entry.getValue(),null),session.getMetadata().get(Objects.toString(entry.getKey()))))throw incomplete(session.getId());
        if(!(params.get("line_items") instanceof List<?> quoted) || quoted.size()!=1 || !(quoted.get(0) instanceof Map<?,?> line)
                || session.getLineItems()==null || Boolean.TRUE.equals(session.getLineItems().getHasMore()) || session.getLineItems().getData()==null || session.getLineItems().getData().size()!=1)throw incomplete(session.getId());
        var actual=session.getLineItems().getData().get(0);
        if(actual.getPrice()==null || !Objects.equals(line.get("price"),actual.getPrice().getId()) || !Objects.equals(line.get("quantity"),actual.getQuantity()))throw incomplete(session.getId());
        return session;
    }
    public void reconcile(long id){
        var row=recoveries.selectById(id);if(row==null)return;
        var root=row.getOwnerRecoveryId()==null?row:recoveries.selectById(row.getOwnerRecoveryId());if(root==null)return;
        if("PAID".equals(root.getStatus()) && !currentRecovery(users.selectByUser(root.getClerkUserId()),root)){
            locked(root.getId(),(u,r)->{if(!currentRecovery(u,r)){r.setNextReconcileAt(null);recoveries.updateById(r);}return null;});return;
        }
        var snapshot=stripe.readInvoice(root.getStripeInvoiceId());
        applyInvoice(root,snapshot,syntheticEvent("reconcile:"+id,"invoice.reconciled",root.getStripeInvoiceId()));
        root=recoveries.selectById(root.getId());
        if(root==null)return;
        if("PAID".equals(root.getStatus()))reconcileRenewal(root,snapshot);
        synchronizeConfirmedMethod(root,snapshot);
        var active=replacements.selectActive(root.getStripeSubscriptionId());if(active!=null)reconcileReplacement(active,null);
    }
    private void synchronizeConfirmedMethod(SubscriptionPaymentRecoveryEntity root,SubscriptionRecoveryStripeGateway.Snapshot snapshot) {
        if(snapshot.subscription()==null)return;
        boolean paid="PAID".equals(root.getStatus());
        if(paid && "CONFIRMED".equals(root.getAnchorSyncState()))return;
        if(root.getAccountClosedAt()!=null || !Set.of("OPEN_AUTO","OPEN_MANUAL","PAID").contains(root.getStatus())
                || !paid && (!"ACTIVE".equals(root.getProtectionState()) || snapshot.subscription().getPauseCollection()==null || isPaid(snapshot)))return;
        var customer=stripe.readCustomer(root.getStripeCustomerId());
        if(customer==null || !Objects.equals(root.getStripeCustomerId(),customer.getId()) || customer.getInvoiceSettings()==null)return;
        String method=customer.getInvoiceSettings().getDefaultPaymentMethod();if(method==null)return;
        if("PAYMENT_METHOD_SCHEDULE_SYNC_REQUIRES_REVIEW".equals(root.getReviewReason())
                && schedulePaymentMethodCompatible(snapshot,method))resolveScheduleMethodReview(root);
        if(Objects.equals(method,snapshot.subscription().getDefaultPaymentMethod()) && Objects.equals(method,snapshot.invoice().getDefaultPaymentMethod())
                && schedulePaymentMethodCompatible(snapshot,method))return;
        var paymentMethod=stripe.readPaymentMethod(method);
        if(paymentMethod==null || !Objects.equals(root.getStripeCustomerId(),paymentMethod.getCustomer()))return;
        locked(root.getId(),(u,r)->{
            if(!Objects.equals(u.getPaymentRecoveryId(),r.getId()) || !Objects.equals(u.getStripeSubscriptionId(),r.getStripeSubscriptionId())
                    || !Set.of("OPEN_AUTO","OPEN_MANUAL","PAID").contains(r.getStatus()))return null;
            if(snapshot.subscription().getSchedule()!=null && schedulePhaseMethodConflict(snapshot,method)){
                holdScheduleMethodReview(r,snapshot,method);return null;
            }
            if(snapshot.subscription().getSchedule()!=null){
                resolveScheduleMethodReview(r);
                var schedule=snapshot.schedule();
                if(schedule==null){holdScheduleMethodReview(r,snapshot,method);return null;}
                String scheduleMethod=schedule.getDefaultSettings()==null?null:schedule.getDefaultSettings().getDefaultPaymentMethod();
                if(!Objects.equals(method,scheduleMethod)){
                    queueMethodSetting(r,"schedule",schedule.getId(),method);
                }
            }
            if(!Objects.equals(method,snapshot.subscription().getDefaultPaymentMethod()))
                queueMethodSetting(r,"subscription",r.getStripeSubscriptionId(),method);
            if(!Objects.equals(method,snapshot.invoice().getDefaultPaymentMethod()) && "open".equals(snapshot.invoice().getStatus()))
                queueMethodSetting(r,"invoice",r.getStripeInvoiceId(),method);
            return null;
        });
    }
    /** Phase-level overrides cannot be rewritten by the default-settings write; keep them for review. */
    private static boolean schedulePhaseMethodConflict(SubscriptionRecoveryStripeGateway.Snapshot snapshot,String method) {
        var schedule=snapshot.schedule();
        if(snapshot.subscription().getSchedule()==null)return false;
        if(schedule==null || !Objects.equals(snapshot.subscription().getSchedule(),schedule.getId()))return true;
        long now=snapshot.businessNow().getEpochSecond();
        for(var phase:schedule.getPhases()){
            if(phase.getEndDate()==null)return true;
            if(phase.getEndDate()<=now)continue;
            String override=phase.getDefaultPaymentMethod();
            if(override!=null && !Objects.equals(method,override))return true;
        }
        return false;
    }
    private void resolveScheduleMethodReview(SubscriptionPaymentRecoveryEntity root) {
        for(var action:actions.selectByRecovery(root.getId())){
            if("UPDATE_METHOD".equals(action.getActionType()) && "REVIEW".equals(action.getStatus())
                    && "PAYMENT_METHOD_SCHEDULE_SYNC_REQUIRES_REVIEW".equals(action.getLastError())){
                action.setStatus("SUPERSEDED");action.setLastError(null);actions.updateById(action);
            }
        }
        if("PAYMENT_METHOD_SCHEDULE_SYNC_REQUIRES_REVIEW".equals(root.getReviewReason())){
            root.setReviewState("NONE");root.setReviewReason(null);root.setReviewEnteredAt(null);root.setReviewDueAt(null);root.setReviewNextFollowupAt(null);recoveries.updateById(root);
        }
    }
    private void holdScheduleMethodReview(SubscriptionPaymentRecoveryEntity root,SubscriptionRecoveryStripeGateway.Snapshot snapshot,String method) {
        var hold=addAction(root,"UPDATE_METHOD","recovery:"+root.getId()+":method:schedule-review:"+method,root.getStripeSubscriptionId(),Map.of("resource","subscription","default_payment_method",method),null,Instant.now());
        if("READY".equals(hold.getStatus())){
            hold.setStatus("REVIEW");hold.setLastError("PAYMENT_METHOD_SCHEDULE_SYNC_REQUIRES_REVIEW");
            hold.setResultJson(JSON.toJson(Map.of("pending_schedule_id",snapshot.subscription().getSchedule(),"requires_read_confirmation",true)));actions.updateById(hold);
        }
        review(root,"PAYMENT_METHOD_SCHEDULE_SYNC_REQUIRES_REVIEW");recoveries.updateById(root);
    }
    private static boolean schedulePaymentMethodCompatible(SubscriptionRecoveryStripeGateway.Snapshot snapshot,String method) {
        if(snapshot.subscription().getSchedule()==null)return true;
        var schedule=snapshot.schedule();
        if(schedule==null || !Objects.equals(snapshot.subscription().getSchedule(),schedule.getId())
                || !Objects.equals(snapshot.subscription().getId(),schedule.getSubscription()) || !"active".equals(schedule.getStatus())
                || schedule.getDefaultSettings()==null || schedule.getPhases()==null || schedule.getPhases().isEmpty() || schedule.getCurrentPhase()==null)return false;
        var defaults=JsonParser.parseString(schedule.getDefaultSettings().toJson()).getAsJsonObject();String inherited=string(defaults,"default_payment_method");
        if(inherited!=null && !Objects.equals(method,inherited))return false;
        for(var phase:schedule.getPhases()){
            if(phase.getEndDate()==null)return false;
            if(phase.getEndDate()<=snapshot.businessNow().getEpochSecond())continue;
            String override=string(JsonParser.parseString(phase.toJson()).getAsJsonObject(),"default_payment_method");
            if(override!=null && !Objects.equals(method,override))return false;
        }
        return true;
    }
    private void queueMethodSetting(SubscriptionPaymentRecoveryEntity root,String resource,String target,String method) {
        for(var previous:actions.selectByRecovery(root.getId())){
            if(!"UPDATE_METHOD".equals(previous.getActionType()) || !Objects.equals(target,previous.getTargetId())
                    || !Set.of("READY","FAILED").contains(previous.getStatus()))continue;
            if(!Objects.equals(method,SubscriptionRecoveryStripeGateway.thaw(previous.getPayloadJson()).get("default_payment_method"))){
                previous.setStatus("SKIPPED");previous.setSkipReason("SKIPPED_SUPERSEDED");actions.updateById(previous);
            }
        }
        var pending=actions.selectByRecovery(root.getId()).stream().filter(a->"UPDATE_METHOD".equals(a.getActionType()) && Objects.equals(target,a.getTargetId())
                && Set.of("READY","DISPATCHING","UNKNOWN","REVIEW","FAILED").contains(a.getStatus()))
                .anyMatch(a->Objects.equals(method,SubscriptionRecoveryStripeGateway.thaw(a.getPayloadJson()).get("default_payment_method")));
        if(pending)return;
        String key="recovery:"+root.getId()+":method:"+resource+":"+method;var prior=actions.selectByKey(key);
        if(prior!=null && Set.of("SUCCEEDED","SKIPPED").contains(prior.getStatus()))key+=":repair:"+root.getVersion();
        addAction(root,"UPDATE_METHOD",key,target,Map.of("resource",resource,"default_payment_method",method),null,Instant.now());
    }
    private boolean methodSyncPending(SubscriptionPaymentRecoveryEntity root) {
        return actions.selectByRecovery(root.getId()).stream().anyMatch(a->"UPDATE_METHOD".equals(a.getActionType()) && Set.of("READY","DISPATCHING","UNKNOWN","REVIEW","FAILED").contains(a.getStatus()));
    }
    public void setCancellationPreference(String userId,long id,boolean cancel) {
        var root=owned(userId,id);var snapshot=stripe.readInvoice(root.getStripeInvoiceId());
        if(snapshot.subscription()==null || snapshot.subscription().getSchedule()!=null || "canceled".equals(snapshot.subscription().getStatus())){
            locked(id,(u,r)->{review(r,"CANCELLATION_PREFERENCE_REQUIRES_MANUAL_SYNC");recoveries.updateById(r);return null;});
            throw new BillingDomainException("PAYMENT_PROCESSING","Cancellation preference requires verification");
        }
        Long actionId=locked(id,(u,r)->{
            if(!Objects.equals(u.getPaymentRecoveryId(),r.getId()) || !Objects.equals(u.getStripeSubscriptionId(),r.getStripeSubscriptionId()) || r.getAccountClosedAt()!=null)
                throw new BillingDomainException("PAYMENT_PROCESSING","Subscription changed; refresh before continuing");
            var saved=r.getContractSnapshot()==null?new JsonObject():JsonParser.parseString(r.getContractSnapshot()).getAsJsonObject();
            var prior=saved.getAsJsonObject("cancellation_intent");
            if(prior!=null && prior.has("requested") && prior.get("requested").getAsBoolean()==cancel){
                var existing=actions.selectByKey(string(prior,"action_key"));
                if(existing!=null && Set.of("READY","DISPATCHING","UNKNOWN").contains(existing.getStatus()))return existing.getId();
            }
            boolean olderPending=actions.selectByRecovery(r.getId()).stream().anyMatch(a->"SET_CANCEL_PREFERENCE".equals(a.getActionType()) && Set.of("READY","DISPATCHING","UNKNOWN","REVIEW").contains(a.getStatus()));
            if(!olderPending && Boolean.valueOf(cancel).equals(snapshot.subscription().getCancelAtPeriodEnd())){u.setCancelAtPeriodEnd(cancel);saveRecoveryUser(u);return null;}
            String key="recovery:"+r.getId()+":cancel-preference:"+UUID.randomUUID();
            var intent=new JsonObject();intent.addProperty("action_key",key);intent.addProperty("requested",cancel);intent.addProperty("requested_at",Instant.now().toString());
            saved.add("cancellation_intent",intent);r.setContractSnapshot(saved.toString());
            var action=addAction(r,"SET_CANCEL_PREFERENCE",key,r.getStripeSubscriptionId(),Map.of("cancel_at_period_end",cancel),null,Instant.now());
            r.setNextReconcileAt(DateTimeFormats.now());recoveries.updateById(r);return action.getId();
        });
        if(actionId!=null){
            runDueAction(actionId);var saved=actions.selectById(actionId);
            if(saved==null || !"SUCCEEDED".equals(saved.getStatus()))throw new BillingDomainException("PAYMENT_PROCESSING","Cancellation preference is still being reconciled");
        }
    }
    public void requestReconcile(long id){locked(id,(u,r)->{r.setNextReconcileAt(DateTimeFormats.now());recoveries.updateById(r);return null;});}
    private SubscriptionPaymentRecoveryEntity latestRoot(String subscriptionId){
        if(subscriptionId==null)return null;long after=0;SubscriptionPaymentRecoveryEntity latest=null;
        while(true){var page=recoveries.selectBySubscription(subscriptionId,after,100);if(page.isEmpty())break;
            for(var r:page){after=r.getId();if(r.getOwnerRecoveryId()==null)latest=r;}if(page.size()<100)break;}
        return latest;
    }
    private static boolean currentRecovery(UserSubscriptionEntity user,SubscriptionPaymentRecoveryEntity root) {
        return user!=null && Objects.equals(user.getPaymentRecoveryId(),root.getId()) && Objects.equals(user.getStripeSubscriptionId(),root.getStripeSubscriptionId());
    }
    private boolean obsoletePause(SubscriptionPaymentRecoveryEntity root,SubscriptionRecoveryActionEntity action,SubscriptionRecoveryStripeGateway.Snapshot snapshot) {
        if(!"PAID".equals(root.getStatus()) || !"GRANTED".equals(root.getGrantState()))return false;
        if("CONFIRMED".equals(root.getAnchorSyncState()))return true;
        if(!"PENDING".equals(root.getAnchorSyncState()) || root.getEntitlementEndAt()==null || snapshot.subscription().getPauseCollection()!=null
                || !Objects.equals(snapshot.subscription().getTrialEnd(),DateTimeFormats.toEpochSecond(root.getEntitlementEndAt())))return false;
        return actions.selectByRecovery(root.getId()).stream().anyMatch(a->"SHIFT_RENEWAL".equals(a.getActionType()) && Set.of("SUCCEEDED","UNKNOWN").contains(a.getStatus())
                && a.getFirstDispatchedAt()!=null && action.getScheduledAt()!=null && !action.getScheduledAt().isAfter(a.getFirstDispatchedAt()));
    }
    private boolean hasPendingShift(SubscriptionPaymentRecoveryEntity root){return actions.selectByRecovery(root.getId()).stream().anyMatch(a->"SHIFT_RENEWAL".equals(a.getActionType())&&Set.of("READY","DISPATCHING","UNKNOWN").contains(a.getStatus()));}
    /** A successful remote update can emit its invoice callback before local registration completes. */
    private boolean awaitsShiftInvoiceRegistration(SubscriptionPaymentRecoveryEntity root,String invoiceId) {
        return actions.selectByRecovery(root.getId()).stream().filter(a->"SHIFT_RENEWAL".equals(a.getActionType())
                && "SUCCEEDED".equals(a.getStatus()) && a.getResultJson()!=null).anyMatch(a->{
            var result=SubscriptionRecoveryStripeGateway.thaw(a.getResultJson());
            return Objects.equals(invoiceId,result.get("latest_invoice"))
                    && !Objects.equals(invoiceId,result.get("before_latest_invoice"))
                    && !Objects.equals(invoiceId,root.getEntitlementInvoiceId());
        });
    }
    private boolean isTechnicalInvoice(SubscriptionPaymentRecoveryEntity root,String id){return root.getTechnicalInvoiceIds()!=null&&SubscriptionRecoveryStripeGateway.thaw("{\"ids\":"+root.getTechnicalInvoiceIds()+"}").get("ids") instanceof List<?> ids&&ids.contains(id);}
    private static Event syntheticEvent(String id,String type,String invoice){return ApiResource.GSON.fromJson(JSON.toJson(Map.of("id",id,"type",type,"created",Instant.now().getEpochSecond(),"data",Map.of("object",Map.of("id",invoice,"object","invoice")))),Event.class);}

    private void reconcileRenewal(SubscriptionPaymentRecoveryEntity before,SubscriptionRecoveryStripeGateway.Snapshot snapshot){
        if(snapshot.subscription()==null)return;
        var root=recoveries.selectById(before.getId());if(root==null||!"PAID".equals(root.getStatus())||root.getEntitlementEndAt()==null)return;
        if(!currentRecovery(users.selectByUser(root.getClerkUserId()),root) || "CONFIRMED".equals(root.getAnchorSyncState()) && !snapshot.businessNow().isBefore(instant(root.getEntitlementEndAt())))return;
        var shifts=actions.selectByRecovery(root.getId()).stream().filter(a->"SHIFT_RENEWAL".equals(a.getActionType())&&"SUCCEEDED".equals(a.getStatus())).toList();
        if(shifts.isEmpty())return;
        var shift=shifts.get(shifts.size()-1);var details=shift.getResultJson()==null?Map.<String,Object>of():SubscriptionRecoveryStripeGateway.thaw(shift.getResultJson());
        String created=Objects.toString(details.get("latest_invoice"),null);String previous=Objects.toString(details.get("before_latest_invoice"),null);
        List<String> technical=new ArrayList<>();
        if(created!=null&&!Objects.equals(created,previous)&&!created.equals(root.getEntitlementInvoiceId())){
            var technicalSnapshot=stripe.readInvoice(created);var invoice=technicalSnapshot.invoice();
            if(Objects.equals(invoice.getSubscription(),root.getStripeSubscriptionId())&&invoice.getAmountDue()!=null&&invoice.getAmountDue()==0
                    &&"subscription_update".equals(invoice.getBillingReason()))technical.add(created);
            else {locked(root.getId(),(u,r)->{r.setAnchorSyncState("REVIEW");review(r,"TECHNICAL_INVOICE_UNVERIFIED");repairProtection(r,snapshot);recoveries.updateById(r);return null;});return;}
        }
        var all=stripe.listInvoices(root.getStripeSubscriptionId());
        long end=DateTimeFormats.toEpochSecond(root.getEntitlementEndAt());
        var admission=JsonParser.parseString(root.getContractSnapshot()).getAsJsonObject().getAsJsonObject("recovery_admission");
        boolean periodEnd="PERIOD_END".equals(string(admission,"cancelMode"));
        boolean aligned=Objects.equals(snapshot.subscription().getTrialEnd(),end)&&Objects.equals(snapshot.subscription().getCurrentPeriodEnd(),end)
                &&(!periodEnd||(Boolean.TRUE.equals(snapshot.subscription().getCancelAtPeriodEnd())&&Objects.equals(snapshot.subscription().getCancelAt(),end)));
        boolean nextInvoiceVerified=periodEnd || verifyRenewalPreview(root,snapshot,stripe.preview(root.getStripeSubscriptionId()),end);
        locked(root.getId(),(u,r)->{
            if(!currentRecovery(u,r))return null;
            r.setTechnicalInvoiceIds(JSON.toJson(technical));
            if("canceled".equals(snapshot.subscription().getStatus())){r.setAnchorSyncState("REVIEW");review(r,"REMOTE_CANCELED_PAID_CYCLE_PRESERVED");}
            else if(!nextInvoiceVerified){r.setAnchorSyncState("REVIEW");review(r,"NEXT_INVOICE_CONTRACT_OR_AMOUNT_MISMATCH");repairProtection(r,snapshot);}
            else if(snapshot.subscription().getPauseCollection()!=null){r.setAnchorSyncState("REVIEW");review(r,"RENEWAL_COLLECTION_STILL_PAUSED");repairProtection(r,snapshot);}
            else if(!aligned){r.setAnchorSyncState("REVIEW");review(r,"RENEWAL_BOUNDARY_OR_CANCELLATION_MISMATCH");repairProtection(r,snapshot);}
            else{
                boolean safe=true;
                for(var invoice:all){
                    if(invoice.getId().equals(r.getEntitlementInvoiceId())||"paid".equals(invoice.getStatus())||"void".equals(invoice.getStatus()))continue;
                    safe=false;
                    if(Boolean.TRUE.equals(invoice.getAutoAdvance())){addAction(r,"DISABLE_ADVANCE","recovery:"+invoice.getId()+":disable-advance",invoice.getId(),Map.of("auto_advance",false),null,Instant.now());}
                    else if("draft".equals(invoice.getStatus())){
                        if(silentDraftCleanupVerified(r))addAction(r,"FINALIZE_INVOICE","recovery:"+r.getId()+":FINALIZE_INVOICE:"+invoice.getId(),invoice.getId(),SubscriptionRecoveryStripeGateway.finalizeParams(),null,Instant.now());
                        else review(r,"SILENT_DRAFT_CLEANUP_UNVERIFIED");
                    }
                    else if(invoice.getAmountPaid()!=null&&invoice.getAmountPaid()==0&&!paymentInFlight(snapshot))addAction(r,"VOID_INVOICE","recovery:"+r.getId()+":VOID_INVOICE:"+invoice.getId(),invoice.getId(),Map.of(),null,Instant.now());
                }
                if(safe){r.setAnchorSyncState("CONFIRMED");r.setProtectionState("RELEASED");}
            }
            recoveries.updateById(r);return null;
        });
    }
    private boolean verifyRenewalPreview(SubscriptionPaymentRecoveryEntity root,SubscriptionRecoveryStripeGateway.Snapshot snapshot,SubscriptionRecoveryStripeGateway.Preview preview,long end) {
        if(preview==null || preview.invoice()==null || preview.lines()==null || !Objects.equals(root.getCurrency(),preview.invoice().getCurrency())
                || preview.invoice().getStartingBalance()==null || preview.invoice().getStartingBalance()!=0 || !equivalentPaidLine(root,snapshot))return false;
        var saved=JsonParser.parseString(root.getContractSnapshot()).getAsJsonObject();
        String priceId=string(saved,"price_id");long quantity=saved.has("quantity")?saved.get("quantity").getAsLong():1;
        var items=snapshot.subscription().getItems();
        if(items==null || items.getData()==null || items.getData().size()!=1)return false;
        var item=items.getData().get(0);
        if(item.getPrice()==null || !Objects.equals(priceId,item.getPrice().getId()) || !Objects.equals(quantity,item.getQuantity())
                || item.getPrice().getUnitAmount()==null)return false;
        var lines=preview.lines();if(lines.size()!=1)return false;var line=lines.get(0);
        if(line.getPrice()==null || !Objects.equals(priceId,line.getPrice().getId()) || !Objects.equals(quantity,line.getQuantity())
                || !Objects.equals(root.getCurrency(),line.getCurrency()) || Boolean.TRUE.equals(line.getProration())
                || line.getPeriod()==null || !Objects.equals(end,line.getPeriod().getStart()))return false;
        var original=snapshot.lines().get(0);
        long expected;
        if(Objects.equals(root.getCurrency(),item.getPrice().getCurrency()))expected=Math.multiplyExact(item.getPrice().getUnitAmount(),quantity);
        else{
            // Price.unit_amount is in its default currency. A multi-currency subscription's
            // original invoice line already contains the selected currency's full quantity amount.
            if(original.getAmount()==null||original.getAmount()<0||!Objects.equals(original.getAmount(),line.getAmount()))return false;
            expected=original.getAmount();
        }
        // A changed/expired discount or tax must be reviewed rather than silently accepting a different bill.
        if(original.getDiscountAmounts()!=null)for(var discount:original.getDiscountAmounts())expected=Math.subtractExact(expected,discount.getAmount());
        if(original.getTaxAmounts()!=null)for(var tax:original.getTaxAmounts())if(!Boolean.TRUE.equals(tax.getInclusive()))expected=Math.addExact(expected,tax.getAmount());
        return Objects.equals(expected,preview.invoice().getAmountDue());
    }
    public ReplacementReservation reserveReplacement(String userId,long id,RechargeOrderEntity order,SessionCreateParams params,Instant expires){
        var root=owned(userId,id);var snapshot=stripe.readInvoice(root.getStripeInvoiceId());
        return locked(id,(user,r)->{
            requireReplacementEligible(user,r,snapshot);
            if(expires.isAfter(instant(r.getDeadlineAt()))||expires.isBefore(Instant.now().plusSeconds(1800)))throw new BillingDomainException("BILLING_INVOICE_NOT_AVAILABLE","Not enough time remains for checkout");
            var existing=replacements.selectActive(r.getStripeSubscriptionId());if(existing!=null)throw new BillingDomainException("PAYMENT_PROCESSING","Checkout is being verified");
            if(!Objects.equals(order.getClerkUserId(),userId)||!"subscription_replacement".equals(order.getOrderType()))throw new IllegalArgumentException("Invalid replacement order");
            orders.insert(order);
            var replacement=new SubscriptionRecoveryReplacementEntity();replacement.setRecoveryId(id);replacement.setClerkUserId(userId);replacement.setOldSubscriptionId(r.getStripeSubscriptionId());replacement.setOrderNo(order.getOrderNo());replacement.setTargetPlanCode(order.getTargetPlanCode());
            replacement.setState("CREATING");replacement.setActiveSubscriptionKey(r.getStripeSubscriptionId());replacement.setPausedAt(local(snapshot.businessNow()));replacement.setExpiresAt(local(expires));replacement.setQuoteSnapshot(SubscriptionRecoveryStripeGateway.freeze(params.toMap()));replacement.setVersion(0);replacements.insert(replacement);
            var action=addAction(r,"CREATE_CHECKOUT","recovery-replacement:"+order.getOrderNo()+":create",r.getStripeCustomerId(),params.toMap(),null,Instant.now());
            return new ReplacementReservation(id,replacement.getId(),action.getId(),order.getOrderNo(),expires);
        });
    }
    public void assertReplacementEligible(String userId,long id){var root=owned(userId,id);var snapshot=stripe.readInvoice(root.getStripeInvoiceId());locked(id,(u,r)->{requireReplacementEligible(u,r,snapshot);return null;});}
    private void requireReplacementEligible(UserSubscriptionEntity user,SubscriptionPaymentRecoveryEntity root,SubscriptionRecoveryStripeGateway.Snapshot snapshot){
        if(!Objects.equals(user.getPaymentRecoveryId(),root.getId())||!Objects.equals(user.getStripeSubscriptionId(),root.getStripeSubscriptionId())
                ||root.getOwnerRecoveryId()!=null||!Set.of("OPEN_AUTO","OPEN_MANUAL").contains(root.getStatus())||!"ACTIVE".equals(root.getProtectionState())
                ||root.getAmountPaidMinor()==null||root.getAmountPaidMinor()!=0||snapshot.invoice().getAmountPaid()==null||snapshot.invoice().getAmountPaid()!=0
                ||!freshProtection(snapshot)||!snapshot.businessNow().isBefore(instant(root.getDeadlineAt()))
                ||paymentInFlight(snapshot)||hasUncertainPayment(root,null)||isPaid(snapshot))throw new BillingDomainException("PAYMENT_PROCESSING","Payment status must be refreshed");
    }
    private void reconcileReplacement(SubscriptionRecoveryReplacementEntity before,Event event){
        var root=recoveries.selectById(before.getRecoveryId());if(root==null)throw incomplete(before.getOrderNo());
        Session session=before.getCheckoutSessionId()==null?stripe.findCheckout(root.getStripeCustomerId(),before.getOrderNo()):stripe.readCheckout(before.getCheckoutSessionId());
        if(session==null){locked(root.getId(),(u,r)->{var replacement=replacements.selectByOrder(before.getOrderNo());replacement.setState("UNKNOWN");if(replacement.getUnknownSince()==null)replacement.setUnknownSince(DateTimeFormats.now());
            if(!Instant.now().isBefore(instant(replacement.getUnknownSince()).plusSeconds(86400))||!Instant.now().isBefore(instant(r.getDeadlineAt())))review(r,"REPLACEMENT_RESULT_UNKNOWN");
            replacements.updateById(replacement);recoveries.updateById(r);return null;});return;}
        if(session.getMetadata()==null||!Objects.equals(before.getOrderNo(),session.getMetadata().get("order_no"))||!Objects.equals(root.getStripeCustomerId(),session.getCustomer()))throw incomplete(before.getOrderNo());
        if("paid".equals(session.getPaymentStatus())){
            if(session.getInvoice()==null)throw incomplete(session.getId());var snapshot=stripe.readInvoice(session.getInvoice());
            locked(root.getId(),(user,r)->{var replacement=replacements.selectByOrder(before.getOrderNo());if("PAID".equals(replacement.getState()))return null;
                var order=orders.selectOne(new LambdaQueryWrapper<RechargeOrderEntity>().eq(RechargeOrderEntity::getOrderNo,before.getOrderNo()));if(order==null)throw incomplete(before.getOrderNo());
                if(!verifiedReplacementPayment(r,replacement,order,session,snapshot))throw incomplete(session.getId());
                boolean refunded=snapshot.charge().getAmountRefunded()!=null && snapshot.charge().getAmountCaptured()!=null && snapshot.charge().getAmountRefunded()>=snapshot.charge().getAmountCaptured();
                if(refunded || Boolean.TRUE.equals(snapshot.charge().getDisputed())){
                    order.setStatus(refunded?"refunded":"disputed");order.setPriceCents(Math.toIntExact(snapshot.invoice().getAmountPaid()));order.setPaidAt(local(epoch(snapshot.invoice().getStatusTransitions().getPaidAt())));
                    order.setStripeInvoiceId(snapshot.invoice().getId());order.setStripeSubscriptionId(snapshot.subscription().getId());order.setStripePaymentIntentId(snapshot.invoice().getPaymentIntent());orders.updateById(order);
                    replacement.setState("PROCESSING");replacement.setNewSubscriptionId(snapshot.subscription().getId());replacements.updateById(replacement);
                    review(r,"REPLACEMENT_PAYMENT_FINANCIAL_HOLD");recoveries.updateById(r);return null;
                }
                if(r.getAccountClosedAt()!=null || !Objects.equals(user.getPaymentRecoveryId(),r.getId())
                        || !Objects.equals(user.getStripeSubscriptionId(),r.getStripeSubscriptionId())){
                    order.setStatus("completed");order.setPriceCents(Math.toIntExact(snapshot.invoice().getAmountPaid()));order.setPaidAt(local(epoch(snapshot.invoice().getStatusTransitions().getPaidAt())));
                    order.setStripeInvoiceId(snapshot.invoice().getId());order.setStripeSubscriptionId(snapshot.invoice().getSubscription());order.setStripePaymentIntentId(snapshot.invoice().getPaymentIntent());orders.updateById(order);
                    replacement.setState("PROCESSING");replacement.setNewSubscriptionId(snapshot.invoice().getSubscription());replacements.updateById(replacement);
                    review(r,"REPLACEMENT_RECEIPT_AFTER_OWNERSHIP_CHANGED");recoveries.updateById(r);return null;
                }
                var plan=plans.selectOne(new LambdaQueryWrapper<SubscriptionPlanEntity>().eq(SubscriptionPlanEntity::getPlanCode,replacement.getTargetPlanCode()));if(plan==null)throw incomplete(order.getOrderNo());
                Instant paid=epoch(snapshot.invoice().getStatusTransitions().getPaidAt());Instant end=epoch(snapshot.subscription().getCurrentPeriodEnd());
                Instant quotaEnd="year".equals(plan.getBillingInterval())?SubscriptionRecoveryPolicy.quotaPeriodEnd(paid,"year",1):end;
                replacement.setState("PAID");replacement.setNewSubscriptionId(snapshot.subscription().getId());replacement.setCheckoutSessionId(session.getId());replacement.setClosedAt(DateTimeFormats.now());replacement.setActiveSubscriptionKey(null);replacements.updateById(replacement);
                order.setStatus("completed");order.setPriceCents(Math.toIntExact(snapshot.invoice().getAmountPaid()));order.setPaidAt(local(paid));order.setStripeInvoiceId(snapshot.invoice().getId());order.setStripeSubscriptionId(snapshot.subscription().getId());order.setStripePaymentIntentId(snapshot.invoice().getPaymentIntent());orders.updateById(order);
                user.setStripeSubscriptionId(snapshot.subscription().getId());user.setStripeCustomerId(snapshot.invoice().getCustomer());user.setStripeScheduleId(null);user.setPlanCode(plan.getPlanCode());user.setTier(plan.getTier());user.setStatus(snapshot.subscription().getStatus());user.setPaymentRecoveryId(null);user.setGraceEndAt(null);
                user.setCurrentPeriodStart(local(paid));user.setCurrentPeriodEnd(local(end));user.setQuotaPeriodStart(local(paid));user.setQuotaPeriodEnd(local(quotaEnd));user.setSubscriptionPhase(IntroTrialPlans.PHASE_STANDARD);
                user.setCurrentPeriodTimeZone(DateTimeFormats.APP_ZONE.getId());user.setQuotaPeriodTimeZone(DateTimeFormats.APP_ZONE.getId());saveRecoveryUser(user);
                var quota=quotaProvider==null?null:quotaProvider.getIfAvailable();if(quota==null)throw new IllegalStateException("BillingQuotaGateway unavailable");
                quota.resetFromPaidInvoiceAt(user.getClerkUserId(),user.getStripeSubscriptionId(),plan.getPlanCode(),paid,quotaEnd,snapshot.invoice().getId(),requiredBusinessTime(snapshot));quota.resumeEligibleAddonsAt(user.getClerkUserId(),user.getStripeSubscriptionId(),"replacement:"+order.getOrderNo()+":addons",requiredBusinessTime(snapshot));endStudyPasses(r);
                if(r.getEntitlementInvoiceId()!=null)review(r,"OLD_PAYMENT_AFTER_REPLACEMENT");r.setCloseReason("REPLACEMENT_PAID");r.setStatus("CLOSING");skipRetries(r,"SWITCHED");r.setNextReconcileAt(DateTimeFormats.now());recoveries.updateById(r);return null;
            });
        }else if("expired".equals(session.getStatus())){
            locked(root.getId(),(u,r)->{var replacement=replacements.selectByOrder(before.getOrderNo());if(!"PAID".equals(replacement.getState())){replacement.setState("EXPIRED");replacement.setCheckoutSessionId(session.getId());replacement.setClosedAt(DateTimeFormats.now());replacement.setActiveSubscriptionKey(null);replacements.updateById(replacement);}return null;});
        }else{
            locked(root.getId(),(u,r)->{var replacement=replacements.selectByOrder(before.getOrderNo());replacement.setCheckoutSessionId(session.getId());replacement.setNewSubscriptionId(session.getSubscription());replacement.setState("complete".equals(session.getStatus())?"PROCESSING":"OPEN");replacements.updateById(replacement);return null;});
        }
    }
    private boolean verifiedReplacementPayment(SubscriptionPaymentRecoveryEntity root,SubscriptionRecoveryReplacementEntity replacement,
            RechargeOrderEntity order,Session session,SubscriptionRecoveryStripeGateway.Snapshot snapshot) {
        if(snapshot.subscription()==null || !isPaid(snapshot) || !verifiedCash(snapshot) || snapshot.invoice().getStatusTransitions()==null || snapshot.invoice().getStatusTransitions().getPaidAt()==null
                || !Objects.equals(root.getStripeCustomerId(),snapshot.invoice().getCustomer()) || !Objects.equals(order.getCurrency(),snapshot.invoice().getCurrency())
                || !Objects.equals(order.getCurrency(),session.getCurrency()) || !Objects.equals(snapshot.invoice().getAmountPaid(),session.getAmountTotal())
                || !Objects.equals(session.getSubscription(),snapshot.invoice().getSubscription()) || !Objects.equals(session.getSubscription(),snapshot.subscription().getId())
                || !Objects.equals(order.getClerkUserId(),root.getClerkUserId()) || !Objects.equals(order.getOrderNo(),replacement.getOrderNo())
                || !"subscription_replacement".equals(order.getOrderType()) || !Objects.equals(replacement.getTargetPlanCode(),order.getTargetPlanCode()))return false;
        var metadata=session.getMetadata();
        if(metadata==null || !"subscription_replacement".equals(metadata.get("purchase_type")) || !Objects.equals(String.valueOf(root.getId()),metadata.get("recovery_id"))
                || !Objects.equals(root.getClerkUserId(),metadata.get("clerk_user_id")) || !Objects.equals(root.getStripeSubscriptionId(),metadata.get("old_subscription_id")))return false;
        var subscriptionMetadata=snapshot.subscription().getMetadata();
        if(subscriptionMetadata==null || !Objects.equals(root.getStripeCustomerId(),snapshot.subscription().getCustomer()))return false;
        for(String key:List.of("order_no","purchase_type","recovery_id","clerk_user_id","old_subscription_id"))
            if(!Objects.equals(metadata.get(key),subscriptionMetadata.get(key)))return false;
        if(order.getQuotedAmountCents()==null || !Objects.equals(order.getQuotedAmountCents().longValue(),session.getAmountSubtotal()))return false;
        var frozen=JsonParser.parseString(replacement.getQuoteSnapshot()).getAsJsonObject();
        if(!frozen.has("line_items") || frozen.getAsJsonArray("line_items").size()!=1 || snapshot.lines()==null || snapshot.lines().size()!=1)return false;
        var quoted=frozen.getAsJsonArray("line_items").get(0).getAsJsonObject();var actual=snapshot.lines().get(0);
        return actual.getPrice()!=null && "subscription".equals(actual.getType()) && !Boolean.TRUE.equals(actual.getProration())
                && Objects.equals(string(quoted,"price"),actual.getPrice().getId()) && quoted.has("quantity")
                && Objects.equals(quoted.get("quantity").getAsLong(),actual.getQuantity()) && Objects.equals(order.getCurrency(),actual.getCurrency());
    }
    public BillingHostedInvoiceResult createHostedInvoice(String userId,long id){
        var root=owned(userId,id);var snapshot=stripe.readInvoice(root.getStripeInvoiceId());
        locked(id,(user,r)->{requireReplacementEligible(user,r,snapshot);if(replacements.selectActive(r.getStripeSubscriptionId())!=null)throw new BillingDomainException("PAYMENT_PROCESSING","Checkout is in progress");return null;});
        if(!"open".equals(snapshot.invoice().getStatus())||snapshot.invoice().getHostedInvoiceUrl()==null)throw new BillingDomainException("BILLING_INVOICE_NOT_AVAILABLE","Invoice is not available");
        return BillingHostedInvoiceResult.builder().url(snapshot.invoice().getHostedInvoiceUrl()).build();
    }
    public PaymentRecoverySummary summary(String userId,long id){
        var root=owned(userId,id);var user=users.selectByUser(userId);var current=access.resolve(userId,user==null?null:user.getStripeSubscriptionId());
        if(current.summary()!=null&&String.valueOf(id).equals(current.summary().recoveryId()))return current.summary();
        return new PaymentRecoverySummary(String.valueOf(id),root.getStatus(),false,root.getDisplayTier(),root.getPlanCode(),root.getAmountDueMinor(),root.getAmountPaidMinor(),root.getAmountRemainingMinor(),root.getCurrency(),root.getFirstFailedAt(),root.getDeadlineAt(),null,List.of(),root.getAnchorSyncState(),root.getReviewState(),null);
    }
    public void requestReplacementExit(String userId,long id,String orderNo){
        var root=owned(userId,id);locked(id,(u,r)->{var replacement=replacements.selectByOrder(orderNo);
            if(replacement==null||!Objects.equals(replacement.getRecoveryId(),id)||!Objects.equals(replacement.getClerkUserId(),userId))throw new BillingDomainException("BILLING_RECORD_NOT_FOUND","Order not found");
            if(replacement.getCheckoutSessionId()!=null&&!"PAID".equals(replacement.getState()))addAction(r,"EXPIRE_CHECKOUT","recovery:"+id+":expire:"+replacement.getCheckoutSessionId(),replacement.getCheckoutSessionId(),Map.of(),null,Instant.now());
            return null;});
    }
    public SubscriptionRecoveryReplacementEntity replacement(String userId,String orderNo){var result=replacements.selectByOrder(orderNo);if(result==null||!userId.equals(result.getClerkUserId()))throw new BillingDomainException("BILLING_RECORD_NOT_FOUND","Order not found");return result;}
    private SubscriptionPaymentRecoveryEntity owned(String userId,long id){var r=recoveries.selectById(id);if(r==null||!userId.equals(r.getClerkUserId())||r.getOwnerRecoveryId()!=null)throw new BillingDomainException("BILLING_RECORD_NOT_FOUND","Recovery not found");return r;}

    private boolean processFinancialEvent(Event event,JsonObject object){
        String chargeId=event.getType().equals("charge.refunded")?string(object,"id"):string(object,"charge");
        String invoiceId=string(object,"invoice");
        Invoice invoice=invoiceId==null?stripe.invoiceForPayment(string(object,"payment_intent"),chargeId):stripe.readInvoice(invoiceId).invoice();
        if(invoice==null)return false;
        var owned=recoveries.selectByInvoice(invoice.getId());
        var parent=owned==null?latestRoot(invoice.getSubscription()):owned.getOwnerRecoveryId()==null?owned:recoveries.selectById(owned.getOwnerRecoveryId());
        if(parent==null)return false;
        var snapshot=stripe.readInvoice(invoice.getId());
        if(owned==null && !belongsToWindow(parent,snapshot))return false;
        if(!Objects.equals(parent.getStripeCustomerId(),snapshot.invoice().getCustomer()) || !Objects.equals(parent.getStripeSubscriptionId(),snapshot.invoice().getSubscription()))throw incomplete(invoice.getId());
        Charge charge=chargeId==null?snapshot.charge():stripe.readCharge(chargeId);
        Dispute dispute=event.getType().startsWith("charge.dispute.")?stripe.readDispute(string(object,"id")):null;
        locked(parent.getId(),(user,root)->{
            var row=Objects.equals(root.getStripeInvoiceId(),invoice.getId())?root:recoveries.selectByInvoice(invoice.getId());
            if(row==null){insertChild(root,snapshot,event);row=recoveries.selectByInvoice(invoice.getId());}
            if(row==null || row!=root && !Objects.equals(row.getOwnerRecoveryId(),root.getId()))throw incomplete(invoice.getId());
            fillInvoice(row,snapshot);
            if(charge!=null){long refunded=Math.max(row.getRefundedAmountMinor()==null?0:row.getRefundedAmountMinor(),charge.getAmountRefunded()==null?0:charge.getAmountRefunded());row.setRefundedAmountMinor(refunded);row.setRefundState(refunded<=0?"NONE":charge.getAmount()!=null&&refunded>=charge.getAmount()?"FULL":"PARTIAL");}
            if(dispute!=null){
                String incoming="won".equals(dispute.getStatus())?"WON":"lost".equals(dispute.getStatus())?"LOST":"OPEN";
                boolean same=Objects.equals(row.getLastDisputeId(),dispute.getId());
                if(!same || !"WON".equals(row.getDisputeState()) && (!"LOST".equals(row.getDisputeState()) || !"OPEN".equals(incoming))){row.setLastDisputeId(dispute.getId());row.setDisputeState(incoming);}
            }
            if(isPaid(snapshot))applyPaid(user,root,row,snapshot);
            boolean source=Objects.equals(root.getEntitlementInvoiceId(),row.getStripeInvoiceId());
            if(source){
                if("OPEN".equals(row.getDisputeState())||"LOST".equals(row.getDisputeState())||"FULL".equals(row.getRefundState())){
                    root.setGrantState("HELD");review(root,"SELECTED_PAYMENT_FINANCIAL_HOLD");
                    if(Objects.equals(user.getStripeSubscriptionId(),root.getStripeSubscriptionId())&&Objects.equals(user.getPaymentRecoveryId(),root.getId())){
                        var quota=quotaProvider.getIfAvailable();if(quota==null)throw new IllegalStateException("BillingQuotaGateway unavailable");
                        quota.pauseAddonsAt(user.getClerkUserId(),root.getStripeSubscriptionId(),"recovery:financial:"+event.getId(),requiredBusinessTime(snapshot));
                        if("FULL".equals(row.getRefundState())){root.setCloseReason("PAYMENT_REFUNDED");root.setStatus("CLOSING");root.setNextReconcileAt(DateTimeFormats.now());}
                    }
                }else if("WON".equals(row.getDisputeState())&&root.getEntitlementStartAt()!=null&&root.getEntitlementEndAt()!=null&&!"FULL".equals(row.getRefundState())){
                    root.setGrantState("GRANTED");var quota=quotaProvider.getIfAvailable();if(quota!=null&&Objects.equals(user.getStripeSubscriptionId(),root.getStripeSubscriptionId()))quota.resumeEligibleAddonsAt(user.getClerkUserId(),root.getStripeSubscriptionId(),"recovery:dispute-won:"+dispute.getId(),requiredBusinessTime(snapshot));
                }
            }else review(root,"NON_ENTITLEMENT_PAYMENT_FINANCIAL_REVIEW");
            var order=orders.selectOne(new LambdaQueryWrapper<RechargeOrderEntity>().eq(RechargeOrderEntity::getStripeInvoiceId,row.getStripeInvoiceId()).last("LIMIT 1"));
            if(order!=null){if("FULL".equals(row.getRefundState()))order.setStatus("refunded");else if("PARTIAL".equals(row.getRefundState()))order.setStatus("partially_refunded");else if("OPEN".equals(row.getDisputeState())||"LOST".equals(row.getDisputeState()))order.setStatus("disputed");else if("WON".equals(row.getDisputeState()))order.setStatus("completed");orders.updateById(order);}
            if(row!=root)recoveries.updateById(row);recoveries.updateById(root);return null;
        });return true;
    }
    private SubscriptionRecoveryStripeGateway.WriteResult executeApplicationAction(SubscriptionRecoveryActionEntity action,SubscriptionPaymentRecoveryEntity root){
        var payload=SubscriptionRecoveryStripeGateway.thaw(action.getPayloadJson());
        switch(action.getActionType()){
            case "CAPTURE_FAILURE" -> {
                if(analytics==null)throw new IllegalStateException("Analytics unavailable");
                analytics.capture(root.getClerkUserId(),com.studyagent.common.analytics.AnalyticsEvents.BILLING_PAYMENT_FAILED,payload);
                return new SubscriptionRecoveryStripeGateway.WriteResult("UNKNOWN",null,"local_adapter_completed; external delivery not acknowledged",null);
            }
            case "NOTIFY_INTERNAL_FAILURE" -> {
                var gateway=internalNotifications==null?null:internalNotifications.getIfAvailable();if(gateway==null)throw new IllegalStateException("Internal notification gateway unavailable");
                gateway.notifyPaymentFailed(BillingPaymentFailedNotifyRequest.builder().notifyEventId(action.getActionKey()).clerkUserId(root.getClerkUserId()).purchaseType("subscription")
                        .planCode(root.getPlanCode()).priceCents(Math.toIntExact(((Number)payload.get("amount_minor")).longValue())).currency(root.getCurrency()).invoiceId(root.getStripeInvoiceId())
                        .failureReason(Objects.toString(payload.get("decline_code"),"unknown")).stripeEventType(Objects.toString(payload.get("event_type"),"unknown")).build());
                return new SubscriptionRecoveryStripeGateway.WriteResult("UNKNOWN",null,"local_adapter_completed; provider delivery not acknowledged",null);
            }
            case "NOTIFY_REVIEW" -> {
                var gateway=internalNotifications==null?null:internalNotifications.getIfAvailable();if(gateway==null)throw new IllegalStateException("Internal notification gateway unavailable");
                gateway.notifyBillingReviewRequired(BillingReviewNotifyRequest.builder().stripeEventId(action.getActionKey()).stripeEventType("payment_recovery.review_due").objectId(root.getStripeInvoiceId()).status(root.getStatus()).reason(Objects.toString(payload.get("reason"),"review_due")+"; assignee="+Objects.toString(payload.get("assignee"),"UNASSIGNED")+"; due="+Objects.toString(payload.get("due_at"),"")).attemptCount(0).build());
                return new SubscriptionRecoveryStripeGateway.WriteResult("UNKNOWN",null,"review notification adapter completed; provider delivery not acknowledged",null);
            }
            case "SEND_EMAIL" -> {
                var result=emailSender.send(action.getPayloadJson(),action.getProviderIdempotencyKey());
                return new SubscriptionRecoveryStripeGateway.WriteResult(result.state(),result.messageId(),result.error(),null);
            }
            case "END_STUDY_PASS" -> {
                // Local supersession already committed. Register each network target as a separate durable action.
                String subscription=Objects.toString(payload.get("subscription_id"),null),checkout=Objects.toString(payload.get("checkout_id"),null);
                Session session=checkout==null?null:stripe.readCheckout(checkout);
                if(session!=null && (!Objects.equals(checkout,session.getId()) || !Objects.equals(root.getStripeCustomerId(),session.getCustomer())))throw incomplete(checkout);
                if(subscription==null && session!=null)subscription=session.getSubscription();
                final Subscription current=subscription==null?null:stripe.readSubscription(subscription);
                if(current!=null && !Objects.equals(root.getStripeCustomerId(),current.getCustomer()))throw incomplete(subscription);
                var receipt=session!=null && "paid".equals(session.getPaymentStatus()) && session.getInvoice()!=null?stripe.readInvoice(session.getInvoice()):null;
                locked(root.getId(),(u,r)->{
                    if(current!=null&& !"canceled".equals(current.getStatus())){
                        String target=current.getSchedule()==null?current.getId():current.getSchedule();
                        addAction(r,current.getSchedule()==null?"CANCEL_SUBSCRIPTION":"CANCEL_SCHEDULE",action.getActionKey()+":cancel:"+target,target,SubscriptionRecoveryStripeGateway.cancelParams(),null,Instant.now());
                    }
                    if(session!=null&&"open".equals(session.getStatus())&&!"paid".equals(session.getPaymentStatus()))addAction(r,"EXPIRE_CHECKOUT",action.getActionKey()+":expire:"+session.getId(),session.getId(),Map.of(),null,Instant.now());
                    else if(session!=null&&"paid".equals(session.getPaymentStatus()))recordSupersededPassReceipt(r,action,session,receipt);
                    recoveries.updateById(r);return null;
                });return new SubscriptionRecoveryStripeGateway.WriteResult("SUCCEEDED",null,"child cleanup intents persisted",null);
            }
            default -> throw new IllegalArgumentException("Unknown application action");
        }
    }

    private void recordSupersededPassReceipt(SubscriptionPaymentRecoveryEntity root,SubscriptionRecoveryActionEntity cleanup,Session session,SubscriptionRecoveryStripeGateway.Snapshot payment) {
        var payload=SubscriptionRecoveryStripeGateway.thaw(cleanup.getPayloadJson());long passId=((Number)payload.get("study_pass_id")).longValue();
        var pass=passes==null?null:passes.selectById(passId);
        if(pass==null || !Objects.equals(root.getClerkUserId(),pass.getClerkUserId()))throw incomplete(String.valueOf(passId));
        review(root,"SUPERSEDED_STUDY_PASS_PAYMENT:"+(payment==null?session==null?String.valueOf(passId):session.getId():payment.invoice().getId()));
        if(payment==null || !isPaid(payment) || !verifiedCash(payment))return;
        String expectedSub=pass.getStripeSubscriptionId()==null?session==null?null:session.getSubscription():pass.getStripeSubscriptionId();
        if(expectedSub==null || !Objects.equals(expectedSub,payment.invoice().getSubscription()) || !Objects.equals(root.getStripeCustomerId(),payment.invoice().getCustomer()))throw incomplete(payment.invoice().getId());
        var order=orders.selectOne(new LambdaQueryWrapper<RechargeOrderEntity>().eq(RechargeOrderEntity::getStripeInvoiceId,payment.invoice().getId()).last("LIMIT 1"));
        if(order==null && pass.getOrderId()!=null){var pending=orders.selectById(pass.getOrderId());if(pending!=null && (pending.getStripeInvoiceId()==null || Objects.equals(pending.getStripeInvoiceId(),payment.invoice().getId())))order=pending;}
        boolean insert=order==null;
        if(insert){order=new RechargeOrderEntity();order.setOrderNo("recovery_pass_"+payment.invoice().getId());order.setOrderType("study_pass");order.setClerkUserId(root.getClerkUserId());order.setPackageCode(pass.getPassCode());}
        if(!Objects.equals(root.getClerkUserId(),order.getClerkUserId()) || !"study_pass".equals(order.getOrderType()))throw incomplete(payment.invoice().getId());
        String incoming=payment.charge().getAmountRefunded()!=null && payment.charge().getAmountRefunded()>=payment.charge().getAmountCaptured()?"refunded":Boolean.TRUE.equals(payment.charge().getDisputed())?"disputed":"completed";
        if("refunded".equals(incoming) || !Set.of("refunded","partially_refunded","disputed").contains(Objects.toString(order.getStatus(),"")))order.setStatus(incoming);
        order.setCurrency(payment.invoice().getCurrency());order.setPriceCents(Math.toIntExact(payment.invoice().getAmountPaid()));order.setPaidAt(payment.invoice().getStatusTransitions()==null?null:local(epoch(payment.invoice().getStatusTransitions().getPaidAt())));
        order.setStripeInvoiceId(payment.invoice().getId());order.setStripeSubscriptionId(expectedSub);order.setStripePaymentIntentId(payment.invoice().getPaymentIntent());if(session!=null)order.setStripeSessionId(session.getId());order.setFailureReason("superseded_pass_paid_review");
        if(insert)orders.insert(order);else orders.updateById(order);
        if(!"refunded".equals(pass.getStatus()))pass.setStatus("superseded");if(pass.getLastPaidInvoiceId()==null)pass.setLastPaidInvoiceId(payment.invoice().getId());passes.updateById(pass);
    }
    private LocalDateTime reviewDue(LocalDateTime entered){
        LocalDateTime due=entered;int days=0;while(days<2){due=due.plusDays(1);var date=due.toLocalDate();
            if(config.getReviewWorkingDates().contains(date)||(!config.getReviewHolidays().contains(date)&&due.getDayOfWeek()!=DayOfWeek.SATURDAY&&due.getDayOfWeek()!=DayOfWeek.SUNDAY))days++;
        }return due;
    }
    public void registerOverdueReviewAlerts(){
        var now=DateTimeFormats.now();
        for(long id:recoveries.selectOverdueReviewIds(now,config.getBatchSize()))locked(id,(u,r)->{
            if(!Set.of("OPEN","IN_PROGRESS").contains(r.getReviewState()))return null;
            LocalDateTime due=r.getReviewNextFollowupAt()==null?r.getReviewDueAt():r.getReviewNextFollowupAt();if(due==null||due.isAfter(now))return null;
            Map<String,Object> payload=new LinkedHashMap<>();payload.put("reason",r.getReviewReason());payload.put("assignee",r.getReviewAssignee());payload.put("due_at",DateTimeFormats.formatApi(due));
            addAction(r,"NOTIFY_REVIEW","recovery:"+r.getId()+":review:"+DateTimeFormats.toEpochSecond(due),r.getStripeInvoiceId(),payload,null,Instant.now());return null;
        });
    }
    public BillingPortalSessionResult createPortalSession(String userId,long id,String purpose,Map<String,Object> params){
        var root=owned(userId,id);
        if(!Objects.equals(root.getStripeCustomerId(),params.get("customer")))throw new IllegalArgumentException("Portal customer must match current recovery");
        if(!Set.of("manage","payment_method_update").contains(purpose))throw new BillingDomainException("INVALID_PORTAL_PURPOSE","Invalid portal purpose");
        var action=locked(id,(user,r)->{
            if(!Objects.equals(user.getPaymentRecoveryId(),r.getId())||!Objects.equals(user.getStripeSubscriptionId(),r.getStripeSubscriptionId()))throw new BillingDomainException("PAYMENT_PROCESSING","Refresh current account before opening portal");
            boolean methodOnly="payment_method_update".equals(purpose)||methodOnlyPortal(r);
            String expected=methodOnly?config.getPortalMethodOnlyConfigurationId():config.getPortalManagementConfigurationId();
            if(expected==null||expected.isBlank()||!expected.equals(params.get("configuration")))throw new BillingDomainException("BILLING_INVOICE_NOT_AVAILABLE","Verified restricted portal configuration is required");
            return addAction(r,"CREATE_PORTAL","recovery:"+id+":portal:"+UUID.randomUUID(),r.getStripeCustomerId(),params,null,Instant.now());
        });
        var response=new java.util.concurrent.atomic.AtomicReference<com.stripe.model.billingportal.Session>();
        immediateResponse.set(object->{if(object instanceof com.stripe.model.billingportal.Session portal)response.set(portal);});
        try{
            runDueAction(action.getId());var saved=actions.selectById(action.getId());
            if(saved==null||!"SUCCEEDED".equals(saved.getStatus())||response.get()==null)throw new BillingDomainException("PAYMENT_PROCESSING","Portal session result is being verified");
            return BillingPortalSessionResult.builder().url(response.get().getUrl()).build();
        }finally{immediateResponse.remove();}
    }
    public boolean requiresMethodOnlyPortal(String user,long id){return methodOnlyPortal(owned(user,id));}
    private boolean methodOnlyPortal(SubscriptionPaymentRecoveryEntity r){
        return replacements.selectActive(r.getStripeSubscriptionId())!=null||Set.of("CLOSING","REVIEW").contains(r.getStatus())||hasUncertainPayment(r,null)
                ||("PAID".equals(r.getStatus())&&!"CONFIRMED".equals(r.getAnchorSyncState()))||Set.of("OPEN","IN_PROGRESS").contains(Objects.toString(r.getReviewState(),"NONE"))
                ||(r.getAmountPaidMinor()!=null&&r.getAmountPaidMinor()>0&&!"PAID".equals(r.getStatus()));
    }
    public SubscriptionRecoveryReplacementEntity activeReplacement(String user,long id){var r=owned(user,id);return replacements.selectActive(r.getStripeSubscriptionId());}
}
