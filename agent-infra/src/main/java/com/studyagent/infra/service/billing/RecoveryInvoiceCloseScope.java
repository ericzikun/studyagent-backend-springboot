package com.studyagent.infra.service.billing;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.studyagent.infra.entity.SubscriptionPaymentRecoveryEntity;
import com.studyagent.service.domain.billing.IntroTrialPlans;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Set;

/** Pure scope check against frozen admission evidence; never reads a provider or mutable catalog. */
final class RecoveryInvoiceCloseScope {
    private static final long WEEK_SECONDS=7*86400L;
    private RecoveryInvoiceCloseScope(){}

    static boolean paidTrialRollover(SubscriptionPaymentRecoveryEntity root,SubscriptionRecoveryStripeGateway.Snapshot target){
        if(root==null||target==null||target.invoice()==null||target.lines()==null||target.lines().size()!=1
                ||!"PAID".equals(root.getStatus())||!"GRANTED".equals(root.getGrantState())||root.getContractSnapshot()==null||!clearUnpaidAttempt(target))return false;
        try{
            var saved=JsonParser.parseString(root.getContractSnapshot()).getAsJsonObject();var admission=object(saved,"recovery_admission");var proof=object(saved,"source_provenance");
            String source=text(admission,"sourceKey"),formalPrice=text(saved,"price_id"),item=text(saved,"subscription_item_id");Long quantity=number(saved,"quantity"),boundary=number(saved,"period_start"),formalEnd=number(saved,"period_end");
            if(source==null||!IntroTrialPlans.isIntroTrialPlanCode(source)||IntroTrialPlans.isOneTimeProTrialPlanCode(source)
                    ||root.getPlanCode()==null||!source.startsWith(root.getPlanCode().split("_")[0]+"_trial_to_")
                    ||!same("historical_invoice_pair",text(proof,"kind"))||!same(source,text(proof,"source"))||!same(source,text(proof,"original_trial_source"))
                    ||quantity==null||quantity<=0||boundary==null||formalEnd==null||formalEnd<=boundary||!Objects.equals(boundary,number(proof,"first_formal_period_start")))return false;
            var trial=object(proof,"trial_invoice");var first=object(proof,"first_formal_invoice");
            if(!ownedProof(root,trial)||!ownedProof(root,first)||!same("paid",text(trial,"status"))||!Boolean.FALSE.equals(bool(trial,"paid_out_of_band"))
                    ||number(trial,"amount_paid")==null||number(trial,"amount_paid")<=0||!same(source,text(trial,"plan_code"))
                    ||!same("week",text(trial,"recurring_interval"))||!Long.valueOf(1).equals(number(trial,"recurring_interval_count"))
                    ||!Objects.equals(boundary,number(trial,"period_end"))||!Objects.equals(Math.subtractExact(boundary,WEEK_SECONDS),number(trial,"period_start"))
                    ||!same(root.getStripeInvoiceId(),text(first,"invoice_id"))||!same(root.getPlanCode(),text(first,"plan_code"))||!same(formalPrice,text(first,"price_id"))
                    ||!Objects.equals(boundary,number(first,"period_start"))||!Objects.equals(formalEnd,number(first,"period_end"))
                    ||!same(root.getBillingInterval(),text(first,"recurring_interval"))||root.getIntervalCount()==null||!Objects.equals(root.getIntervalCount().longValue(),number(first,"recurring_interval_count"))
                    ||!Objects.equals(quantity,number(trial,"quantity"))||!Objects.equals(quantity,number(first,"quantity"))
                    ||!same(item,text(trial,"subscription_item_id"))||!same(item,text(first,"subscription_item_id")))return false;
            String trialPrice=text(trial,"price_id");if(trialPrice==null||trialPrice.equals(formalPrice))return false;
            var invoice=target.invoice();var line=target.lines().get(0);var price=line.getPrice();
            return root.getStripeLivemode()!=null&&Objects.equals(root.getStripeLivemode(),invoice.getLivemode())
                    &&same(root.getStripeCustomerId(),invoice.getCustomer())&&same(root.getStripeSubscriptionId(),invoice.getSubscription())
                    &&Set.of("draft","open","uncollectible").contains(invoice.getStatus())&&"subscription_cycle".equals(invoice.getBillingReason())
                    &&Long.valueOf(0).equals(invoice.getAmountPaid())&&Boolean.FALSE.equals(invoice.getAutoAdvance())
                    &&!Objects.equals(root.getStripeInvoiceId(),invoice.getId())&&!Objects.equals(text(trial,"invoice_id"),invoice.getId())
                    &&"subscription".equals(line.getType())&&Boolean.FALSE.equals(line.getProration())&&same(root.getStripeSubscriptionId(),line.getSubscription())
                    &&same(item,line.getSubscriptionItem())&&Objects.equals(quantity,line.getQuantity())
                    &&same(root.getCurrency(),invoice.getCurrency())&&same(root.getCurrency(),line.getCurrency())
                    &&price!=null&&same(trialPrice,price.getId())&&same(root.getCurrency(),price.getCurrency())&&price.getRecurring()!=null
                    &&"week".equals(price.getRecurring().getInterval())&&Long.valueOf(1).equals(price.getRecurring().getIntervalCount())
                    &&line.getPeriod()!=null&&Objects.equals(boundary,line.getPeriod().getStart())&&Objects.equals(Math.addExact(boundary,WEEK_SECONDS),line.getPeriod().getEnd())
                    &&line.getPeriod().getEnd()<=formalEnd;
        }catch(RuntimeException malformedEvidence){return false;}
    }
    private static boolean clearUnpaidAttempt(SubscriptionRecoveryStripeGateway.Snapshot target){
        var pi=target.paymentIntent();var charge=target.charge();
        // finalize(auto_advance=false) can create a PI awaiting its first confirmation.
        // Only that wholly unattempted state may join the known failed/canceled states.
        boolean unattempted=pi!=null&&"requires_confirmation".equals(pi.getStatus())&&pi.getLatestCharge()==null
                &&pi.getNextAction()==null&&pi.getLastPaymentError()==null&&charge==null;
        return (pi==null||(unattempted||Set.of("requires_payment_method","canceled").contains(Objects.toString(pi.getStatus(),"")))&&Long.valueOf(0).equals(pi.getAmountReceived()))
                &&(charge==null||"failed".equals(charge.getStatus())&&Boolean.FALSE.equals(charge.getPaid())&&Long.valueOf(0).equals(charge.getAmountCaptured()));
    }
    private static boolean ownedProof(SubscriptionPaymentRecoveryEntity root,JsonObject proof){
        return text(proof,"invoice_id")!=null&&same(root.getStripeCustomerId(),text(proof,"customer_id"))
                &&same(root.getStripeSubscriptionId(),text(proof,"subscription_id"))&&same(root.getCurrency(),text(proof,"currency"));
    }
    private static boolean same(String expected,String actual){return expected!=null&&!expected.isBlank()&&expected.equals(actual);}
    private static JsonObject object(JsonObject parent,String name){return parent!=null&&parent.has(name)&&parent.get(name).isJsonObject()?parent.getAsJsonObject(name):null;}
    private static String text(JsonObject parent,String name){return parent!=null&&parent.has(name)&&parent.get(name).isJsonPrimitive()&&parent.getAsJsonPrimitive(name).isString()?parent.get(name).getAsString():null;}
    private static Long number(JsonObject parent,String name){return parent!=null&&parent.has(name)&&parent.get(name).isJsonPrimitive()&&parent.getAsJsonPrimitive(name).isNumber()?new BigDecimal(parent.get(name).getAsString()).longValueExact():null;}
    private static Boolean bool(JsonObject parent,String name){return parent!=null&&parent.has(name)&&parent.get(name).isJsonPrimitive()&&parent.getAsJsonPrimitive(name).isBoolean()?parent.get(name).getAsBoolean():null;}
}
