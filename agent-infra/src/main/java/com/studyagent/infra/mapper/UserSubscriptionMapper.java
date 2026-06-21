package com.studyagent.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.infra.entity.UserSubscriptionEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface UserSubscriptionMapper extends BaseMapper<UserSubscriptionEntity> {
    @Select("SELECT * FROM user_subscriptions WHERE clerk_user_id = #{clerkUserId} LIMIT 1")
    UserSubscriptionEntity selectByUser(@Param("clerkUserId") String clerkUserId);

    @Select("SELECT * FROM user_subscriptions WHERE clerk_user_id = #{clerkUserId} LIMIT 1 FOR UPDATE")
    UserSubscriptionEntity selectByUserForUpdate(@Param("clerkUserId") String clerkUserId);

    @Select("""
            SELECT us.*
            FROM user_subscriptions us
            JOIN subscription_plans sp
              ON sp.plan_code = us.plan_code
             AND sp.is_active = 1
            WHERE us.status IN ('active', 'trialing')
              AND us.plan_code IS NOT NULL
              AND sp.billing_interval = 'year'
            ORDER BY us.quota_period_end ASC
            LIMIT #{limit}
            """)
    List<UserSubscriptionEntity> selectAnnualSubscriptionsDueForPlanRefresh(@Param("limit") int limit);
}
