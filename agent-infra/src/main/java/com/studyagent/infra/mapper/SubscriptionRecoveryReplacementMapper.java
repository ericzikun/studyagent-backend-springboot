package com.studyagent.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.infra.entity.SubscriptionRecoveryReplacementEntity;
import org.apache.ibatis.annotations.*;
import java.util.List;

public interface SubscriptionRecoveryReplacementMapper extends BaseMapper<SubscriptionRecoveryReplacementEntity> {
    @Select("SELECT * FROM subscription_payment_recovery_replacements WHERE active_subscription_key=#{subscriptionId} LIMIT 1")
    SubscriptionRecoveryReplacementEntity selectActive(@Param("subscriptionId") String subscriptionId);
    @Select("SELECT * FROM subscription_payment_recovery_replacements WHERE new_subscription_id=#{subscriptionId} ORDER BY id DESC LIMIT 1")
    SubscriptionRecoveryReplacementEntity selectByNewSubscription(@Param("subscriptionId") String subscriptionId);
    @Select("SELECT * FROM subscription_payment_recovery_replacements WHERE order_no=#{orderNo} LIMIT 1")
    SubscriptionRecoveryReplacementEntity selectByOrder(@Param("orderNo") String orderNo);
    @Select("SELECT * FROM subscription_payment_recovery_replacements WHERE checkout_session_id=#{sessionId} LIMIT 1")
    SubscriptionRecoveryReplacementEntity selectBySession(@Param("sessionId") String sessionId);
    @Select("SELECT * FROM subscription_payment_recovery_replacements WHERE recovery_id=#{recoveryId} ORDER BY id")
    List<SubscriptionRecoveryReplacementEntity> selectByRecovery(@Param("recoveryId") long recoveryId);
    @Select("SELECT * FROM subscription_payment_recovery_replacements WHERE id=#{id} FOR UPDATE")
    SubscriptionRecoveryReplacementEntity selectForUpdate(@Param("id") long id);
}
