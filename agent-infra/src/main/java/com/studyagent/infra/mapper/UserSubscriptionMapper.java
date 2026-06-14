package com.studyagent.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.infra.entity.UserSubscriptionEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface UserSubscriptionMapper extends BaseMapper<UserSubscriptionEntity> {
    @Select("SELECT * FROM user_subscriptions WHERE clerk_user_id = #{clerkUserId} LIMIT 1 FOR UPDATE")
    UserSubscriptionEntity selectByUserForUpdate(@Param("clerkUserId") String clerkUserId);
}
