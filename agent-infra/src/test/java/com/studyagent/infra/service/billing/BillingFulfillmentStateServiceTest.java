package com.studyagent.infra.service.billing;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.studyagent.infra.entity.BillingEntitlementFulfillmentEntity;
import com.studyagent.infra.mapper.BillingEntitlementFulfillmentMapper;
import com.studyagent.infra.testutil.MybatisPlusTableInfoTestHelper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class BillingFulfillmentStateServiceTest {

    @BeforeAll
    static void initTableInfo() {
        MybatisPlusTableInfoTestHelper.initTableInfo(BillingEntitlementFulfillmentEntity.class);
    }

    @Mock
    private BillingEntitlementFulfillmentMapper mapper;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Test
    void paymentAcceptedCreatesDurablePendingFulfillment() {
        when(mapper.insert(any())).thenReturn(1);
        BillingFulfillmentStateService service = new BillingFulfillmentStateService(mapper, eventPublisher);

        assertThat(service.recordPaymentAccepted(command())).isTrue();

        ArgumentCaptor<BillingEntitlementFulfillmentEntity> captor =
                ArgumentCaptor.forClass(BillingEntitlementFulfillmentEntity.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue())
                .extracting(
                        BillingEntitlementFulfillmentEntity::getPaymentKey,
                        BillingEntitlementFulfillmentEntity::getPaymentStatus,
                        BillingEntitlementFulfillmentEntity::getFulfillmentStatus,
                        BillingEntitlementFulfillmentEntity::getAttemptCount)
                .containsExactly("invoice:in_123", "accepted", "pending", 1);
    }

    @Test
    void paymentFailureIsCountedOnceAndCanLaterAdvanceToAccepted() {
        when(mapper.insert(any()))
                .thenReturn(1)
                .thenThrow(new DuplicateKeyException("same payment key"));
        when(mapper.update(isNull(), any())).thenReturn(1);
        BillingFulfillmentStateService service = new BillingFulfillmentStateService(mapper, eventPublisher);
        BillingFulfillmentStateService.PaymentFailedCommand failed =
                new BillingFulfillmentStateService.PaymentFailedCommand(
                        "invoice:in_123", "invoice", "in_123", "evt_failed", 42L,
                        "subscription_renewal", "plus_monthly", "payment_failed", "declined");

        assertThat(service.recordPaymentFailed(failed)).isTrue();
        assertThat(service.recordPaymentAccepted(command())).isTrue();

        verify(eventPublisher).publishEvent(new BillingPaymentAcceptedEvent(
                "subscription_renewal", "plus_monthly", "error"));
        verify(eventPublisher).publishEvent(new BillingPaymentAcceptedEvent(
                "subscription_renewal", "plus_monthly", "success"));
        verify(mapper, times(2)).insert(any());
    }

    @Test
    void succeededCanOnlyAdvanceAcceptedPendingOrFailed() {
        when(mapper.update(isNull(), any())).thenReturn(1);
        BillingFulfillmentStateService service = new BillingFulfillmentStateService(mapper, eventPublisher);

        assertThat(service.markSucceeded(
                "invoice:in_123", "subscription_renewal", "plus_monthly")).isTrue();

        LambdaUpdateWrapper<?> update = capturedUpdate();
        assertThat(update.getSqlSegment()).contains("payment_status", "fulfillment_status");
        assertThat(update.getParamNameValuePairs().values())
                .contains("accepted", "pending", "failed", "succeeded");
    }

    @Test
    void failureOnlyAdvancesAcceptedPendingAndTruncatesMessage() {
        BillingEntitlementFulfillmentEntity current = new BillingEntitlementFulfillmentEntity();
        current.setPurchaseType("subscription_renewal");
        current.setProductCode("plus_monthly");
        when(mapper.selectOne(any())).thenReturn(current);
        when(mapper.update(isNull(), any())).thenReturn(1);
        BillingFulfillmentStateService service = new BillingFulfillmentStateService(mapper, eventPublisher);

        assertThat(service.markFailedBySourceEvent("evt_123", "quota_grant", "x".repeat(2500))).isTrue();

        LambdaUpdateWrapper<?> update = capturedUpdate();
        assertThat(update.getSqlSegment())
                .contains("source_event_id", "payment_status", "fulfillment_status");
        assertThat(update.getParamNameValuePairs().values())
                .contains("failed", "quota_grant");
        assertThat(update.getParamNameValuePairs().values()).anySatisfy(value ->
                assertThat(value).isInstanceOf(String.class)
                        .asString().hasSize(2000));
        assertThat(update.getSqlSet()).contains("attempt_count = attempt_count + 1");
        verify(eventPublisher).publishEvent(new BillingEntitlementFulfilledEvent(
                "subscription_renewal", "plus_monthly", "error"));
    }

    @Test
    void retryOnlyMovesAcceptedFailedBackToPending() {
        when(mapper.update(isNull(), any())).thenReturn(0);
        BillingFulfillmentStateService service = new BillingFulfillmentStateService(mapper, eventPublisher);

        assertThat(service.markRetrying("invoice:in_123")).isFalse();

        LambdaUpdateWrapper<?> update = capturedUpdate();
        assertThat(update.getSqlSegment())
                .contains("payment_key", "payment_status", "fulfillment_status");
        assertThat(update.getParamNameValuePairs().values())
                .contains("pending");
    }

    @Test
    void fullRefundClosesAnyAcceptedOrSucceededFulfillmentWithoutAllowingRegression() {
        when(mapper.update(isNull(), any())).thenReturn(1);
        BillingFulfillmentStateService service = new BillingFulfillmentStateService(mapper, eventPublisher);

        assertThat(service.markRefunded("checkout:cs_123")).isTrue();

        LambdaUpdateWrapper<?> update = capturedUpdate();
        assertThat(update.getSqlSegment()).contains("payment_key", "payment_status");
        assertThat(update.getParamNameValuePairs().values())
                .contains("accepted", "refunded", "not_required");
    }

    @SuppressWarnings("unchecked")
    private LambdaUpdateWrapper<BillingEntitlementFulfillmentEntity> capturedUpdate() {
        ArgumentCaptor<Wrapper<BillingEntitlementFulfillmentEntity>> captor =
                ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).update(isNull(), captor.capture());
        return (LambdaUpdateWrapper<BillingEntitlementFulfillmentEntity>) captor.getValue();
    }

    private BillingFulfillmentStateService.PaymentAcceptedCommand command() {
        return new BillingFulfillmentStateService.PaymentAcceptedCommand(
                "invoice:in_123",
                "invoice",
                "in_123",
                "evt_123",
                42L,
                "subscription_renewal",
                "plus_monthly");
    }
}
