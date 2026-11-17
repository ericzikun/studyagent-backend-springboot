package com.studyagent.infra.job;

import com.studyagent.common.datetime.DateTimeFormats;
import com.studyagent.infra.mapper.*;
import com.studyagent.infra.service.billing.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.LinkedHashSet;

/** Reconciliation remains active when admission and new payment dispatch are disabled. */
@Component
@Slf4j
@RequiredArgsConstructor
public class SubscriptionPaymentRecoveryScheduler {
    private final SubscriptionPaymentRecoveryMapper roots;
    private final SubscriptionRecoveryActionMapper actions;
    private final SubscriptionPaymentRecoveryService service;
    private final PaymentRecoveryProperties config;
    @Scheduled(cron="${billing.payment-recovery.reconciliation-cron:0 * * * * ?}")
    public void reconcile(){
        int limit=config.getBatchSize();if(limit<=0)return;
        var now=DateTimeFormats.now();var candidates=new LinkedHashSet<Long>();
        for(long id:roots.selectDueIds(now,limit)){
            try{
                service.reconcile(id);
                // The service applies Stripe Test Clock business time to original retry slots.
                for(var action:actions.selectByRecovery(id))if("READY".equals(action.getStatus()))candidates.add(action.getId());
            }catch(RuntimeException e){log.warn("Recovery reconciliation pending: id={}, failure={}",id,e.getClass().getSimpleName());}
        }
        candidates.addAll(actions.selectDueIds(now,limit));candidates.addAll(actions.selectUncertainIds(now,limit));
        for(long id:candidates){
            try{service.runDueAction(id);}catch(RuntimeException e){log.warn("Recovery action pending: id={}, failure={}",id,e.getClass().getSimpleName());}
        }
    }
    @Scheduled(cron="${billing.payment-recovery.review-cron:0 */10 * * * ?}")
    public void reviewDeadlines(){service.registerOverdueReviewAlerts();}
}
