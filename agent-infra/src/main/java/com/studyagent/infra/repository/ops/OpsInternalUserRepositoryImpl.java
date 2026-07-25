package com.studyagent.infra.repository.ops;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.studyagent.infra.entity.OpsInternalUserEntity;
import com.studyagent.infra.mapper.OpsInternalUserMapper;
import com.studyagent.service.domain.ops.OpsInternalUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class OpsInternalUserRepositoryImpl implements OpsInternalUserRepository {

    public static final String STATUS_ACTIVE = "active";

    private final OpsInternalUserMapper opsInternalUserMapper;

    @Override
    public List<String> listActiveClerkUserIds() {
        List<String> ids = opsInternalUserMapper.selectActiveClerkUserIds();
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return ids.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(id -> !id.isEmpty())
                .distinct()
                .collect(Collectors.toList());
    }

    @Override
    public boolean existsActiveInternal(String clerkUserId) {
        if (clerkUserId == null || clerkUserId.isBlank()) {
            return false;
        }
        Long count = opsInternalUserMapper.selectCount(
                new LambdaQueryWrapper<OpsInternalUserEntity>()
                        .eq(OpsInternalUserEntity::getClerkUserId, clerkUserId.trim())
                        .eq(OpsInternalUserEntity::getStatus, STATUS_ACTIVE));
        return count != null && count > 0;
    }
}
