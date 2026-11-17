package com.studyagent.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.infra.entity.SubscriptionPaymentRecoveryEntity;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;

public interface SubscriptionPaymentRecoveryMapper extends BaseMapper<SubscriptionPaymentRecoveryEntity> {
    @Select("SELECT * FROM subscription_payment_recoveries WHERE stripe_invoice_id=#{invoiceId} LIMIT 1")
    SubscriptionPaymentRecoveryEntity selectByInvoice(@Param("invoiceId") String invoiceId);

    @Select("SELECT * FROM subscription_payment_recoveries WHERE stripe_invoice_id=#{invoiceId} LIMIT 1 FOR UPDATE")
    SubscriptionPaymentRecoveryEntity selectByInvoiceForUpdate(@Param("invoiceId") String invoiceId);

    @Select("SELECT * FROM subscription_payment_recoveries WHERE id=#{id} FOR UPDATE")
    SubscriptionPaymentRecoveryEntity selectForUpdate(@Param("id") long id);

    @Select("SELECT id FROM subscription_payment_recoveries WHERE review_state IN ('OPEN','IN_PROGRESS') AND (review_due_at<=#{now} OR review_next_followup_at<=#{now}) ORDER BY review_due_at,id LIMIT #{limit}")
    List<Long> selectOverdueReviewIds(@Param("now") LocalDateTime now,@Param("limit") int limit);
    @Select("SELECT * FROM subscription_payment_recoveries WHERE stripe_customer_id=#{customerId} AND owner_recovery_id IS NULL AND account_closed_at IS NULL ORDER BY id")
    List<SubscriptionPaymentRecoveryEntity> selectOpenByCustomer(@Param("customerId") String customerId);
    @Select("SELECT * FROM subscription_payment_recoveries WHERE owner_recovery_id=#{ownerId} ORDER BY id")
    List<SubscriptionPaymentRecoveryEntity> selectChildren(@Param("ownerId") long ownerId);

    @Select("SELECT * FROM subscription_payment_recoveries WHERE stripe_subscription_id=#{subscriptionId} AND id>#{afterId} ORDER BY id LIMIT #{limit}")
    List<SubscriptionPaymentRecoveryEntity> selectBySubscription(@Param("subscriptionId") String subscriptionId,
            @Param("afterId") long afterId, @Param("limit") int limit);

    @Select("""
            SELECT id FROM subscription_payment_recoveries
            WHERE owner_recovery_id IS NULL AND account_closed_at IS NULL
              AND (next_reconcile_at <= #{now} OR (status IN ('OPEN_AUTO','OPEN_MANUAL') AND deadline_at <= #{now}))
            ORDER BY COALESCE(next_reconcile_at,deadline_at),id LIMIT #{limit}
            """)
    List<Long> selectDueIds(@Param("now") LocalDateTime now, @Param("limit") int limit);
}
