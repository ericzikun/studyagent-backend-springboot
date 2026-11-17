package com.studyagent.infra.service.billing;

import com.stripe.model.testhelpers.TestClock;
import com.studyagent.infra.entity.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Evidence guards shared by native acceptance; these tests never access SQL or Stripe. */
class RecoveryAnnualQuotaEvidenceTest {
    private static final Instant PAID=Instant.parse("2028-02-29T12:34:56Z");
    private static final Map<String,Long> GRANTS=Map.of("task_create",12L,"ai_detection",20000L,"humanizer",10000L);
    private static LocalDateTime month(int index){return com.studyagent.common.datetime.DateTimeFormats.fromInstant(PAID.atZone(ZoneOffset.UTC).plusMonths(index).toInstant());}
    private List<UserAiQuotaEntity> quotas(){
        var rows=new ArrayList<UserAiQuotaEntity>();GRANTS.forEach((feature,amount)->{var q=new UserAiQuotaEntity();q.setClerkUserId("run");q.setFeatureCode(feature);q.setPlanBalance(amount);q.setPlanPeriodStart(month(1));q.setPlanPeriodEnd(month(2));rows.add(q);});return rows;
    }
    private List<QuotaLedgerEntity> ledgers(){
        var rows=new ArrayList<QuotaLedgerEntity>();GRANTS.forEach((feature,amount)->{
            for(int i=0;i<=1;i++){var l=new QuotaLedgerEntity();l.setId((long)rows.size()+1);l.setClerkUserId("run");l.setFeatureCode(feature);l.setLedgerType(i==0?"plan_reset":"plan_refresh");l.setSubscriptionId("sub_owned");l.setSourceType(i==0?"invoice":"subscription");l.setSourceId(i==0?"in_owned":"sub_owned");l.setInvoiceId(i==0?"in_owned":null);l.setAmount(amount);l.setPlanBalanceAfter(amount);l.setIdempotencyKey((i==0?"invoice:in_owned:":"plan-refresh:run:")+feature+(i==0?"":":"+month(1)));l.setBizContext("{\"quota_period_start\":\""+month(i)+"\",\"quota_period_end\":\""+month(i+1)+"\"}");rows.add(l);}
        });rows.add(lifecycle());return rows;
    }
    private QuotaLedgerEntity lifecycle(){
        var row=new QuotaLedgerEntity();row.setId(99L);row.setClerkUserId("run");row.setFeatureCode("addon");row.setLedgerType("addon_resume");row.setAmount(0L);row.setSourceType("subscription");row.setSourceId("sub_owned");row.setSubscriptionId("sub_owned");row.setIdempotencyKey("recovery:in_owned:addons");return row;
    }
    @Test void acceptsInitialGrantWithOwnedZeroLifecycleLedger(){
        var rows=ledgers().stream().filter(l->!"plan_refresh".equals(l.getLedgerType())).toList();var quotas=quotas();for(var row:quotas){row.setPlanPeriodStart(month(0));row.setPlanPeriodEnd(month(1));}
        RecoverySandboxApplicationHarness.assertAnnualQuotaEvidence(quotas,rows,"run","sub_owned","in_owned",PAID,0);
    }
    @Test void rejectsMissingOrDuplicateLifecycleLedger(){
        var missing=ledgers();missing.removeIf(l->"addon_resume".equals(l.getLedgerType()));assertThrows(AssertionError.class,()->RecoverySandboxApplicationHarness.assertAnnualQuotaEvidence(quotas(),missing,"run","sub_owned","in_owned",PAID,1));
        var duplicate=ledgers();var extra=lifecycle();extra.setId(100L);duplicate.add(extra);assertThrows(AssertionError.class,()->RecoverySandboxApplicationHarness.assertAnnualQuotaEvidence(quotas(),duplicate,"run","sub_owned","in_owned",PAID,1));
    }
    @Test void rejectsForeignOrNonzeroLifecycleEvidence(){
        for(String field:List.of("invoice","subscription","source","user","amount","type")){
            var rows=ledgers();var row=rows.get(rows.size()-1);
            switch(field){case "invoice"->row.setIdempotencyKey("recovery:in_foreign:addons");case "subscription"->row.setSubscriptionId("sub_foreign");case "source"->row.setSourceId("sub_foreign");case "user"->row.setClerkUserId("foreign");case "amount"->row.setAmount(1L);case "type"->row.setLedgerType("addon_pause");}
            assertThrows(AssertionError.class,()->RecoverySandboxApplicationHarness.assertAnnualQuotaEvidence(quotas(),rows,"run","sub_owned","in_owned",PAID,1),field);
        }
    }
    @Test void rejectsConsistentResetAndLifecycleEvidenceForForeignInvoice(){
        var rows=ledgers();
        for(var row:rows){
            if("plan_reset".equals(row.getLedgerType())){row.setInvoiceId("in_foreign");row.setSourceId("in_foreign");row.setIdempotencyKey(row.getIdempotencyKey().replace("in_owned","in_foreign"));}
            if("addon_resume".equals(row.getLedgerType()))row.setIdempotencyKey("recovery:in_foreign:addons");
        }
        assertThrows(AssertionError.class,()->RecoverySandboxApplicationHarness.assertAnnualQuotaEvidence(quotas(),rows,"run","sub_owned","in_owned",PAID,1));
    }
    @Test void rejectsAdditionalArbitraryLedger(){var rows=ledgers();var extra=lifecycle();extra.setId(100L);extra.setLedgerType("other");extra.setIdempotencyKey("other");rows.add(extra);assertThrows(AssertionError.class,()->RecoverySandboxApplicationHarness.assertAnnualQuotaEvidence(quotas(),rows,"run","sub_owned","in_owned",PAID,1));}
    @Test void acceptsCompleteOwnedMonthlyEvidence(){RecoverySandboxApplicationHarness.assertAnnualQuotaEvidence(quotas(),ledgers(),"run","sub_owned","in_owned",PAID,1);}
    @Test void rejectsDuplicateFeatureEvenWhenRowCountIsThree(){var q=quotas();q.set(1,q.get(0));assertThrows(AssertionError.class,()->RecoverySandboxApplicationHarness.assertAnnualQuotaEvidence(q,ledgers(),"run","sub_owned","in_owned",PAID,1));}
    @Test void rejectsCalendarWindowDrift(){var q=quotas();q.get(0).setPlanPeriodEnd(month(2).plusSeconds(1));assertThrows(AssertionError.class,()->RecoverySandboxApplicationHarness.assertAnnualQuotaEvidence(q,ledgers(),"run","sub_owned","in_owned",PAID,1));}
    @Test void rejectsDuplicateLedgerAndWrongScope(){var l=ledgers();l.set(1,l.get(3));assertThrows(AssertionError.class,()->RecoverySandboxApplicationHarness.assertAnnualQuotaEvidence(quotas(),l,"run","sub_owned","in_owned",PAID,1));var foreign=ledgers();foreign.get(1).setSubscriptionId("sub_foreign");assertThrows(AssertionError.class,()->RecoverySandboxApplicationHarness.assertAnnualQuotaEvidence(quotas(),foreign,"run","sub_owned","in_owned",PAID,1));}
    private TestClock clock(){var c=new TestClock();c.setId("clock_owned");c.setName("run");c.setStatus("ready");c.setLivemode(false);c.setFrozenTime(PAID.getEpochSecond());return c;}
    @Test void acceptsOnlyReadyOwnedClockAtExactObservedTime(){assertEquals(PAID,RecoverySandboxApplicationHarness.verifiedAnnualClock(clock(),"clock_owned","run",PAID));}
    @Test void rejectsWrongClockOrTimeOrReadiness(){var c=clock();c.setName("foreign");assertThrows(AssertionError.class,()->RecoverySandboxApplicationHarness.verifiedAnnualClock(c,"clock_owned","run",PAID));c.setName("run");c.setStatus("advancing");assertThrows(AssertionError.class,()->RecoverySandboxApplicationHarness.verifiedAnnualClock(c,"clock_owned","run",PAID));c.setStatus("ready");assertThrows(AssertionError.class,()->RecoverySandboxApplicationHarness.verifiedAnnualClock(c,"clock_owned","run",PAID.plusSeconds(1)));}
}
