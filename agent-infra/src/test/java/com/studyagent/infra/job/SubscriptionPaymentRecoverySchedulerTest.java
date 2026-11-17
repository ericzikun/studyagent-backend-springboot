package com.studyagent.infra.job;
import com.studyagent.infra.entity.SubscriptionRecoveryActionEntity;
import com.studyagent.infra.mapper.*;
import com.studyagent.infra.service.billing.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.mockito.Mockito.*;

class SubscriptionPaymentRecoverySchedulerTest {
    private final SubscriptionPaymentRecoveryMapper roots=mock(SubscriptionPaymentRecoveryMapper.class);
    private final SubscriptionRecoveryActionMapper actions=mock(SubscriptionRecoveryActionMapper.class);
    private final SubscriptionPaymentRecoveryService service=mock(SubscriptionPaymentRecoveryService.class);
    private final PaymentRecoveryProperties config=new PaymentRecoveryProperties();
    private final SubscriptionPaymentRecoveryScheduler job=new SubscriptionPaymentRecoveryScheduler(roots,actions,service,config);
    @Test void disablingNewAdmissionOrPayNeverStopsOwnedReconciliation(){when(roots.selectDueIds(any(),anyInt())).thenReturn(List.of(1L));job.reconcile();verify(service).reconcile(1L);}
    @Test void oneBadRootDoesNotStarveOtherRootsOrActions(){when(roots.selectDueIds(any(),anyInt())).thenReturn(List.of(1L,2L));doThrow(new IllegalStateException("timeout")).when(service).reconcile(1L);when(actions.selectDueIds(any(),anyInt())).thenReturn(List.of(10L));job.reconcile();verify(service).reconcile(2L);verify(service).runDueAction(10L);}
    @Test void unknownAndExpiredLeasesAreAlwaysChecked(){when(actions.selectUncertainIds(any(),anyInt())).thenReturn(List.of(10L));job.reconcile();verify(service).runDueAction(10L);}
    @Test void perRootReadyActionsReachServiceBusinessClockCheckEvenWhenAheadOfRealClock(){when(roots.selectDueIds(any(),anyInt())).thenReturn(List.of(1L));var action=new SubscriptionRecoveryActionEntity();action.setId(10L);action.setStatus("READY");when(actions.selectByRecovery(1L)).thenReturn(List.of(action));job.reconcile();verify(service).runDueAction(10L);}
    @Test void sameCandidateAcrossScansIsDispatchedOnlyOncePerBatch(){when(roots.selectDueIds(any(),anyInt())).thenReturn(List.of(1L));var action=new SubscriptionRecoveryActionEntity();action.setId(10L);action.setStatus("READY");when(actions.selectByRecovery(1L)).thenReturn(List.of(action));when(actions.selectDueIds(any(),anyInt())).thenReturn(List.of(10L));when(actions.selectUncertainIds(any(),anyInt())).thenReturn(List.of(10L));job.reconcile();verify(service,times(1)).runDueAction(10L);}
}
