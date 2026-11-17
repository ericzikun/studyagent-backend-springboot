package com.studyagent.infra.service.billing;

import com.studyagent.infra.entity.SubscriptionPriceRolloutEntity;
import com.studyagent.infra.mapper.*;
import com.studyagent.service.domain.billing.PaymentRecoveryAccessService;
import com.studyagent.service.domain.billing.PaymentRecoveryAccess;
import com.studyagent.common.datetime.DateTimeFormats;
import java.util.Objects;
import java.util.Set;
import org.springframework.transaction.support.TransactionTemplate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

/** Local reservation only: never holds database locks while Stripe is called. */
@Component
@RequiredArgsConstructor
public class SubscriptionContractMutationCoordinator {
    private final UserSubscriptionMapper users;
    private final PaymentRecoveryAccessService access;
    private final SubscriptionPriceRolloutMapper rollouts;
    private final SubscriptionRecoveryActionMapper actions;
    private final PlatformTransactionManager transactions;

    public static final String MUTATING="recovery_mutating";
    public static final String UNKNOWN="recovery_write_unknown";

    public boolean reserveRollout(SubscriptionPriceRolloutEntity row,String operation) {
        if("study_pass".equals(row.getContractKind()))return true;
        if(!"subscription".equals(row.getContractKind()) || row.getId()==null)return false;
        return Boolean.TRUE.equals(new TransactionTemplate(transactions).execute(status->{
            var user=users.selectByUserForUpdate(row.getClerkUserId());
            if(user==null || !Objects.equals(user.getStripeSubscriptionId(),row.getStripeSubscriptionId()))return false;
            var recovery=access.resolveForUpdate(user.getClerkUserId(),user.getStripeSubscriptionId());
            if(recovery==null || recovery.locked())return false;
            var summary=recovery.summary();
            if(summary!=null){
                String state=Objects.toString(summary.state(),"");boolean history=Set.of("EXPIRED","CANCELED","SWITCHED").contains(state);
                if(summary.replacement()!=null || !history && Set.of("PENDING","REVIEW").contains(Objects.toString(summary.renewalSyncState(),""))
                        || !Set.of("PAID","EXPIRED","CANCELED","SWITCHED").contains(state))return false;
                if(!history && !actions.selectContractMutationIdsForUpdate(Long.parseLong(summary.recoveryId())).isEmpty())return false;
            }
            if(inFlight(row.getStripeSubscriptionId(),true))return false;
            if(rollouts.claimContractMutation(row.getId(),row.getStatus(),"recovery_contract_guard:"+operation,DateTimeFormats.now())!=1)return false;
            row.setStatus(MUTATING);return true;
        }));
    }
    /** A current read is required when the caller already owns the user lock. */
    public boolean inFlight(String subscriptionId,boolean currentRead) {
        if(subscriptionId==null || subscriptionId.isBlank())return false;
        return !(currentRead?rollouts.selectContractMutationsForUpdate(subscriptionId):rollouts.selectContractMutations(subscriptionId)).isEmpty()
                || !(currentRead?actions.selectCollectionRestoresForUpdate(subscriptionId):actions.selectCollectionRestores(subscriptionId)).isEmpty();
    }
}
