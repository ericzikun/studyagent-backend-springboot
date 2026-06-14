package com.studyagent.infra.repository.verla;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.studyagent.infra.entity.verla.VerlaSessionEntity;
import com.studyagent.infra.mapper.verla.VerlaSessionMapper;
import com.studyagent.service.domain.verla.VerlaSession;
import com.studyagent.service.domain.verla.repo.VerlaSessionRepository;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Repository
public class VerlaSessionRepositoryImpl
        extends ServiceImpl<VerlaSessionMapper, VerlaSessionEntity>
        implements VerlaSessionRepository {

    @Override
    public VerlaSession save(VerlaSession session) {
        VerlaSessionEntity entity = toEntity(session);
        this.saveOrUpdate(entity);
        session.setId(entity.getId());
        return toDomain(entity);
    }

    @Override
    public VerlaSession findById(Long id) {
        return toDomain(this.getById(id));
    }

    @Override
    public VerlaSession findByIdForUpdate(Long id) {
        return toDomain(this.baseMapper.selectByIdForUpdate(id));
    }

    @Override
    public List<VerlaSession> findByTurn(Long turnId) {
        return this.baseMapper.selectByTurn(turnId)
                .stream().map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public List<VerlaSession> findCompletedSiblings(Long turnId, Long excludeSessionId) {
        Long exclude = excludeSessionId == null ? -1L : excludeSessionId;
        return this.baseMapper.selectCompletedSiblings(turnId, exclude)
                .stream().map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public VerlaSession findByCorrelationId(String correlationId) {
        return toDomain(this.baseMapper.selectByCorrelationId(correlationId));
    }

    @Override
    public boolean bindQuotaLedger(Long sessionId, Long ledgerId, Long amount) {
        if (sessionId == null || ledgerId == null) {
            return false;
        }
        int rows = this.baseMapper.bindQuotaLedger(sessionId, ledgerId, amount);
        return rows > 0;
    }

    @Override
    public List<VerlaSession> findByIds(List<Long> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return List.of();
        }
        return this.listByIds(sessionIds).stream().map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public Map<Long, List<VerlaSession>> findByTurnIds(List<Long> turnIds) {
        if (turnIds == null || turnIds.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = turnIds.stream()
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return this.baseMapper.selectByTurnIds(ids).stream()
                .map(this::toDomain)
                .collect(Collectors.groupingBy(VerlaSession::getTurnId, LinkedHashMap::new, Collectors.toList()));
    }

    private VerlaSession toDomain(VerlaSessionEntity e) {
        if (e == null) {
            return null;
        }
        return VerlaSession.builder()
                .id(e.getId())
                .conversationId(e.getConversationId())
                .turnId(e.getTurnId())
                .kind(e.getKind())
                .featureCode(e.getFeatureCode())
                .quotaLedgerId(e.getQuotaLedgerId())
                .quotaAmount(e.getQuotaAmount())
                .status(e.getStatus())
                .correlationId(e.getCorrelationId())
                .contextRefJson(e.getContextRefJson())
                .resultJson(e.getResultJson())
                .errorJson(e.getErrorJson())
                .expectedSeq(e.getExpectedSeq())
                .lastEventSeq(e.getLastEventSeq())
                .lastProgressAt(e.getLastProgressAt())
                .startedAt(e.getStartedAt())
                .endedAt(e.getEndedAt())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }

    private VerlaSessionEntity toEntity(VerlaSession d) {
        if (d == null) {
            return null;
        }
        return new VerlaSessionEntity()
                .setId(d.getId())
                .setConversationId(d.getConversationId())
                .setTurnId(d.getTurnId())
                .setKind(d.getKind())
                .setFeatureCode(d.getFeatureCode())
                .setQuotaLedgerId(d.getQuotaLedgerId())
                .setQuotaAmount(d.getQuotaAmount())
                .setStatus(d.getStatus())
                .setCorrelationId(d.getCorrelationId())
                .setContextRefJson(d.getContextRefJson())
                .setResultJson(d.getResultJson())
                .setErrorJson(d.getErrorJson())
                .setExpectedSeq(d.getExpectedSeq())
                .setLastEventSeq(d.getLastEventSeq())
                .setLastProgressAt(d.getLastProgressAt())
                .setStartedAt(d.getStartedAt())
                .setEndedAt(d.getEndedAt())
                .setCreatedAt(d.getCreatedAt())
                .setUpdatedAt(d.getUpdatedAt());
    }

    @Override
    public int countActiveAssignmentRuns() {
        Integer count = this.baseMapper.countActiveAssignmentRuns();
        return count == null ? 0 : count;
    }

    @Override
    public int countActiveCapabilityRuns(String action) {
        Integer count = this.baseMapper.countActiveCapabilityRuns(action);
        return count == null ? 0 : count;
    }
}
