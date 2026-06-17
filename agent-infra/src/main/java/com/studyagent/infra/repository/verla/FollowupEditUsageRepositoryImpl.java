package com.studyagent.infra.repository.verla;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.studyagent.infra.entity.verla.FollowupEditUsageEntity;
import com.studyagent.infra.mapper.verla.FollowupEditUsageMapper;
import com.studyagent.service.domain.verla.FollowupEditUsage;
import com.studyagent.service.domain.verla.repo.FollowupEditUsageRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class FollowupEditUsageRepositoryImpl
        extends ServiceImpl<FollowupEditUsageMapper, FollowupEditUsageEntity>
        implements FollowupEditUsageRepository {

    @Override
    public FollowupEditUsage findByUserMessageId(Long userMessageId) {
        if (userMessageId == null) {
            return null;
        }
        return toDomain(this.baseMapper.selectByUserMessageId(userMessageId));
    }

    @Override
    public FollowupEditUsage findByAssignmentChatSessionId(Long assignmentChatSessionId) {
        if (assignmentChatSessionId == null) {
            return null;
        }
        return toDomain(this.baseMapper.selectByAssignmentChatSessionId(assignmentChatSessionId));
    }

    @Override
    public long countActiveByAssignmentSessionId(Long assignmentSessionId) {
        if (assignmentSessionId == null) {
            return 0L;
        }
        Long count = this.baseMapper.countActiveByAssignmentSessionId(assignmentSessionId);
        return count == null ? 0L : count;
    }

    @Override
    public FollowupEditUsage save(FollowupEditUsage usage) {
        if (usage == null) {
            throw new IllegalArgumentException("usage is null");
        }
        FollowupEditUsageEntity entity = toEntity(usage);
        this.saveOrUpdate(entity);
        usage.setId(entity.getId());
        return toDomain(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FollowupEditUsage updateState(Long userMessageId,
                                         String state,
                                         Long assignmentChatSessionId,
                                         String releaseReason) {
        if (userMessageId == null || state == null || state.isBlank()) {
            throw new IllegalArgumentException("userMessageId and state are required");
        }
        int rows = this.baseMapper.updateState(userMessageId, state, assignmentChatSessionId, releaseReason);
        if (rows <= 0) {
            return null;
        }
        return toDomain(this.baseMapper.selectByUserMessageId(userMessageId));
    }

    private FollowupEditUsage toDomain(FollowupEditUsageEntity entity) {
        if (entity == null) {
            return null;
        }
        return FollowupEditUsage.builder()
                .id(entity.getId())
                .conversationId(entity.getConversationId())
                .assignmentSessionId(entity.getAssignmentSessionId())
                .clerkUserId(entity.getClerkUserId())
                .userMessageId(entity.getUserMessageId())
                .assignmentChatSessionId(entity.getAssignmentChatSessionId())
                .state(entity.getState())
                .releaseReason(entity.getReleaseReason())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private FollowupEditUsageEntity toEntity(FollowupEditUsage domain) {
        if (domain == null) {
            return null;
        }
        return new FollowupEditUsageEntity()
                .setId(domain.getId())
                .setConversationId(domain.getConversationId())
                .setAssignmentSessionId(domain.getAssignmentSessionId())
                .setClerkUserId(domain.getClerkUserId())
                .setUserMessageId(domain.getUserMessageId())
                .setAssignmentChatSessionId(domain.getAssignmentChatSessionId())
                .setState(domain.getState())
                .setReleaseReason(domain.getReleaseReason())
                .setCreatedAt(domain.getCreatedAt())
                .setUpdatedAt(domain.getUpdatedAt());
    }
}
