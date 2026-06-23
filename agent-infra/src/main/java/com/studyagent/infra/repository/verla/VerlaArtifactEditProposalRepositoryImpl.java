package com.studyagent.infra.repository.verla;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.studyagent.infra.entity.verla.VerlaArtifactEditProposalEntity;
import com.studyagent.infra.mapper.verla.VerlaArtifactEditProposalMapper;
import com.studyagent.service.domain.verla.VerlaArtifactEditProposal;
import com.studyagent.service.domain.verla.repo.VerlaArtifactEditProposalRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Repository
public class VerlaArtifactEditProposalRepositoryImpl
        extends ServiceImpl<VerlaArtifactEditProposalMapper, VerlaArtifactEditProposalEntity>
        implements VerlaArtifactEditProposalRepository {

    @Override
    @Transactional(rollbackFor = Exception.class)
    public VerlaArtifactEditProposal upsertByProposalId(VerlaArtifactEditProposal proposal) {
        if (proposal == null || proposal.getProposalId() == null || proposal.getProposalId().isBlank()) {
            throw new IllegalArgumentException("proposal_id is required for upsertByProposalId");
        }
        VerlaArtifactEditProposalEntity existing =
                this.baseMapper.selectByProposalId(proposal.getProposalId());
        LocalDateTime now = LocalDateTime.now();

        if (existing == null) {
            VerlaArtifactEditProposalEntity entity = new VerlaArtifactEditProposalEntity()
                    .setProposalId(proposal.getProposalId())
                    .setConversationId(proposal.getConversationId())
                    .setTurnId(proposal.getTurnId())
                    .setState(proposal.getState() != null
                            ? proposal.getState() : VerlaArtifactEditProposal.STATE_GENERATING)
                    .setTargetsJson(proposal.getTargetsJson())
                    .setChangesJson(proposal.getChangesJson())
                    .setErrorMessage(proposal.getErrorMessage())
                    .setCreatedAt(now)
                    .setUpdatedAt(now);
            this.baseMapper.insert(entity);
            return toDomain(entity);
        }

        if (proposal.getState() != null) {
            existing.setState(proposal.getState());
            if (isTerminal(proposal.getState())) {
                existing.setResolvedAt(now);
            }
        }
        if (proposal.getTargetsJson() != null) existing.setTargetsJson(proposal.getTargetsJson());
        if (proposal.getChangesJson() != null) existing.setChangesJson(proposal.getChangesJson());
        if (proposal.getErrorMessage() != null) existing.setErrorMessage(proposal.getErrorMessage());
        if (proposal.getTurnId() != null) existing.setTurnId(proposal.getTurnId());
        existing.setUpdatedAt(now);
        this.baseMapper.updateById(existing);
        return toDomain(existing);
    }

    @Override
    public VerlaArtifactEditProposal findByProposalId(String proposalId) {
        if (proposalId == null || proposalId.isBlank()) {
            return null;
        }
        return toDomain(this.baseMapper.selectByProposalId(proposalId));
    }

    @Override
    public VerlaArtifactEditProposal findLatestByTurnId(Long turnId) {
        if (turnId == null) {
            return null;
        }
        return toDomain(this.baseMapper.selectLatestByTurnId(turnId));
    }

    @Override
    public List<VerlaArtifactEditProposal> findActiveByConversation(Long conversationId) {
        if (conversationId == null) {
            return List.of();
        }
        return this.baseMapper.selectActiveByConversation(conversationId)
                .stream().map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public int markState(String proposalId, String newState) {
        VerlaArtifactEditProposalEntity existing = this.baseMapper.selectByProposalId(proposalId);
        if (existing == null) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now();
        existing.setState(newState);
        existing.setUpdatedAt(now);
        if (isTerminal(newState)) {
            existing.setResolvedAt(now);
        }
        return this.baseMapper.updateById(existing);
    }

    @Override
    public int supersedeActiveExcept(Long conversationId, String keepProposalId) {
        if (conversationId == null) {
            return 0;
        }
        return this.baseMapper.supersedeActiveExcept(
                conversationId,
                keepProposalId == null ? "" : keepProposalId,
                VerlaArtifactEditProposal.STATE_SUPERSEDED,
                LocalDateTime.now());
    }

    private static boolean isTerminal(String state) {
        return VerlaArtifactEditProposal.STATE_COMMITTED.equals(state)
                || VerlaArtifactEditProposal.STATE_FAILED.equals(state)
                || VerlaArtifactEditProposal.STATE_CANCELLED.equals(state)
                || VerlaArtifactEditProposal.STATE_SUPERSEDED.equals(state);
    }

    private VerlaArtifactEditProposal toDomain(VerlaArtifactEditProposalEntity e) {
        if (e == null) return null;
        return VerlaArtifactEditProposal.builder()
                .id(e.getId())
                .proposalId(e.getProposalId())
                .conversationId(e.getConversationId())
                .turnId(e.getTurnId())
                .state(e.getState())
                .targetsJson(e.getTargetsJson())
                .changesJson(e.getChangesJson())
                .errorMessage(e.getErrorMessage())
                .createdAt(e.getCreatedAt())
                .resolvedAt(e.getResolvedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }
}
