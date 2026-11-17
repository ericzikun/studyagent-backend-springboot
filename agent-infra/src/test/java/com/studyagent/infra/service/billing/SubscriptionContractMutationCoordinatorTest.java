package com.studyagent.infra.service.billing;

import com.studyagent.infra.entity.*;
import com.studyagent.infra.mapper.*;
import com.studyagent.service.domain.billing.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SubscriptionContractMutationCoordinatorTest {
    private final UserSubscriptionMapper users=mock(UserSubscriptionMapper.class);
    private final PaymentRecoveryAccessService access=mock(PaymentRecoveryAccessService.class);
    private final SubscriptionPriceRolloutMapper rollouts=mock(SubscriptionPriceRolloutMapper.class);
    private final SubscriptionRecoveryActionMapper actions=mock(SubscriptionRecoveryActionMapper.class);
    private final PlatformTransactionManager tx=mock(PlatformTransactionManager.class);
    private final SubscriptionContractMutationCoordinator guard=new SubscriptionContractMutationCoordinator(users,access,rollouts,actions,tx);
    private SubscriptionPriceRolloutEntity fixture(){
        var user=new UserSubscriptionEntity();user.setClerkUserId("user");user.setStripeSubscriptionId("sub");when(users.selectByUserForUpdate("user")).thenReturn(user);when(access.resolveForUpdate("user","sub")).thenReturn(PaymentRecoveryAccess.none());when(tx.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        var row=new SubscriptionPriceRolloutEntity();row.setId(1L);row.setContractKind("subscription");row.setClerkUserId("user");row.setStripeSubscriptionId("sub");row.setStatus("preview");when(rollouts.claimContractMutation(eq(1L),eq("preview"),anyString(),any())).thenReturn(1);return row;
    }
    @Test void reservesUnderCurrentUserAndRecoveryLocksBeforeAnyRemoteWork(){
        var row=fixture();assertTrue(guard.reserveRollout(row,"apply"));var order=inOrder(users,access,rollouts);order.verify(users).selectByUserForUpdate("user");order.verify(access).resolveForUpdate("user","sub");order.verify(rollouts).selectContractMutationsForUpdate("sub");order.verify(rollouts).claimContractMutation(eq(1L),eq("preview"),anyString(),any());assertEquals("recovery_mutating",row.getStatus());
    }
    @Test void anUnknownEarlierContractMutationCannotBeAutomaticallyOvertaken(){var row=fixture();when(rollouts.selectContractMutationsForUpdate("sub")).thenReturn(List.of(9L));assertFalse(guard.reserveRollout(row,"apply"));verify(rollouts,never()).claimContractMutation(anyLong(),anyString(),anyString(),any());}
    @Test void activeRecoveryStillBlocksTheRollout(){var row=fixture();when(access.resolveForUpdate("user","sub")).thenReturn(new PaymentRecoveryAccess(true,false,null,null,null));assertFalse(guard.reserveRollout(row,"apply"));verify(rollouts,never()).claimContractMutation(anyLong(),anyString(),anyString(),any());}
    @Test void pendingUserCancellationBlocksAConfirmedPaidRootsRollout(){var row=fixture();var summary=new PaymentRecoverySummary("7","PAID",false,"pro","pro_monthly",100L,100L,0L,"usd",null,null,null,List.of(),"CONFIRMED","NONE",null);when(access.resolveForUpdate("user","sub")).thenReturn(new PaymentRecoveryAccess(false,true,null,null,summary));when(actions.selectContractMutationIdsForUpdate(7L)).thenReturn(List.of(8L));assertFalse(guard.reserveRollout(row,"apply"));verify(rollouts,never()).claimContractMutation(anyLong(),anyString(),anyString(),any());}
    @Test void anOlderCollectionRepairMustSettleBeforeAdmissionCanProtectThatSubscription(){when(actions.selectCollectionRestoresForUpdate("sub")).thenReturn(List.of(4L));assertTrue(guard.inFlight("sub",true));verify(actions).selectCollectionRestoresForUpdate("sub");}
    @Test void lockedConsumersUseACurrentReadRatherThanTheEarlierSnapshot(){when(rollouts.selectContractMutationsForUpdate("sub")).thenReturn(List.of(1L));assertTrue(guard.inFlight("sub",true));verify(rollouts).selectContractMutationsForUpdate("sub");verify(rollouts,never()).selectContractMutations(anyString());}
}
