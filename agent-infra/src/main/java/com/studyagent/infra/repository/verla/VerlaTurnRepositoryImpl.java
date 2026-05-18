package com.studyagent.infra.repository.verla;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.studyagent.infra.entity.verla.VerlaTurnEntity;
import com.studyagent.infra.mapper.verla.VerlaTurnMapper;
import com.studyagent.service.domain.verla.VerlaTurn;
import com.studyagent.service.domain.verla.repo.VerlaTurnRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.stream.Collectors;

@Repository
public class VerlaTurnRepositoryImpl
        extends ServiceImpl<VerlaTurnMapper, VerlaTurnEntity>
        implements VerlaTurnRepository {

    @Override
    public VerlaTurn save(VerlaTurn turn) {
        VerlaTurnEntity entity = toEntity(turn);
        this.saveOrUpdate(entity);
        turn.setId(entity.getId());
        return toDomain(entity);
    }

    @Override
    public VerlaTurn findById(Long id) {
        return toDomain(this.getById(id));
    }

    @Override
    public VerlaTurn findByIdForUpdate(Long id) {
        return toDomain(this.baseMapper.selectByIdForUpdate(id));
    }

    @Override
    public List<VerlaTurn> findRecentByConversation(Long conversationId, int limit) {
        return this.baseMapper.selectRecentByConversation(conversationId, limit)
                .stream().map(this::toDomain).collect(Collectors.toList());
    }

    private VerlaTurn toDomain(VerlaTurnEntity e) {
        if (e == null) {
            return null;
        }
        return VerlaTurn.builder()
                .id(e.getId())
                .conversationId(e.getConversationId())
                .userMessageId(e.getUserMessageId())
                .status(e.getStatus())
                .resolvedIntent(e.getResolvedIntent())
                .resolvedSlotsJson(e.getResolvedSlotsJson())
                .activeSessionId(e.getActiveSessionId())
                .planSessionId(e.getPlanSessionId())
                .agentSessionId(e.getAgentSessionId())
                .totalSteps(e.getTotalSteps())
                .completedSteps(e.getCompletedSteps())
                .lastProgressAt(e.getLastProgressAt())
                .startedAt(e.getStartedAt())
                .endedAt(e.getEndedAt())
                .errorJson(e.getErrorJson())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }

    private VerlaTurnEntity toEntity(VerlaTurn d) {
        if (d == null) {
            return null;
        }
        return new VerlaTurnEntity()
                .setId(d.getId())
                .setConversationId(d.getConversationId())
                .setUserMessageId(d.getUserMessageId())
                .setStatus(d.getStatus())
                .setResolvedIntent(d.getResolvedIntent())
                .setResolvedSlotsJson(d.getResolvedSlotsJson())
                .setActiveSessionId(d.getActiveSessionId())
                .setPlanSessionId(d.getPlanSessionId())
                .setAgentSessionId(d.getAgentSessionId())
                .setTotalSteps(d.getTotalSteps())
                .setCompletedSteps(d.getCompletedSteps())
                .setLastProgressAt(d.getLastProgressAt())
                .setStartedAt(d.getStartedAt())
                .setEndedAt(d.getEndedAt())
                .setErrorJson(d.getErrorJson())
                .setCreatedAt(d.getCreatedAt())
                .setUpdatedAt(d.getUpdatedAt());
    }
}
