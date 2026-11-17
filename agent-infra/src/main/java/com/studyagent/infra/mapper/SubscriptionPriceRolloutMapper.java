package com.studyagent.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.infra.entity.SubscriptionPriceRolloutEntity;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;

public interface SubscriptionPriceRolloutMapper extends BaseMapper<SubscriptionPriceRolloutEntity> {
    @Select("SELECT id FROM subscription_price_rollouts WHERE stripe_subscription_id=#{subscriptionId} AND contract_kind='subscription' AND status IN ('recovery_mutating','recovery_write_unknown') ORDER BY id")
    List<Long> selectContractMutations(@Param("subscriptionId") String subscriptionId);
    @Select("SELECT id FROM subscription_price_rollouts WHERE stripe_subscription_id=#{subscriptionId} AND contract_kind='subscription' AND status IN ('recovery_mutating','recovery_write_unknown') ORDER BY id FOR UPDATE")
    List<Long> selectContractMutationsForUpdate(@Param("subscriptionId") String subscriptionId);
    @Update("UPDATE subscription_price_rollouts SET status='recovery_mutating',status_reason=#{reason},updated_at=#{now} WHERE id=#{id} AND status=#{expected}")
    int claimContractMutation(@Param("id") long id,@Param("expected") String expected,@Param("reason") String reason,@Param("now") LocalDateTime now);
}
