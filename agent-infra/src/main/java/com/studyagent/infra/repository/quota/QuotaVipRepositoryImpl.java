package com.studyagent.infra.repository.quota;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.studyagent.infra.entity.QuotaVipUserEntity;
import com.studyagent.infra.mapper.QuotaVipUserMapper;
import com.studyagent.service.domain.quota.QuotaVipRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
@RequiredArgsConstructor
public class QuotaVipRepositoryImpl implements QuotaVipRepository {

    public static final String STATUS_ACTIVE = "active";

    private final QuotaVipUserMapper quotaVipUserMapper;

    @Override
    public boolean existsActiveVip(String clerkUserId) {
        if (clerkUserId == null || clerkUserId.isBlank()) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        Long count = quotaVipUserMapper.selectCount(
                new LambdaQueryWrapper<QuotaVipUserEntity>()
                        .eq(QuotaVipUserEntity::getClerkUserId, clerkUserId.trim())
                        .eq(QuotaVipUserEntity::getStatus, STATUS_ACTIVE)
                        .and(w -> w.isNull(QuotaVipUserEntity::getExpiresAt)
                                .or()
                                .gt(QuotaVipUserEntity::getExpiresAt, now)));
        return count != null && count > 0;
    }
}
