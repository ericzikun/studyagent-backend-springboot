package com.studyagent.service.domain.verla.repo;

import com.studyagent.service.domain.verla.VerlaArtifactEditProposal;

import java.util.List;

/**
 * Chat With Assignment / write 模式 Edit Proposal 仓储接口。
 * <p>
 * 详见 chat_with_assignment 协议 §9.3 与设计 §4.3-B。
 */
public interface VerlaArtifactEditProposalRepository {

    /**
     * 按 proposalId 幂等 upsert（{@code ARTIFACT_EDIT_PROPOSAL_STARTED}/{@code _READY} 驱动）。
     * - 不存在 → insert。
     * - 存在 → 更新 state / targetsJson / changesJson / errorMessage。
     */
    VerlaArtifactEditProposal upsertByProposalId(VerlaArtifactEditProposal proposal);

    VerlaArtifactEditProposal findByProposalId(String proposalId);

    /** 当前 conversation 下尚未终结（GENERATING / REVIEWING）的提案，最新优先；快照恢复用。 */
    List<VerlaArtifactEditProposal> findActiveByConversation(Long conversationId);

    /** 仅更新状态（+ resolvedAt 自动写入），用于 COMMITTED / FAILED / CANCELLED。 */
    int markState(String proposalId, String newState);

    /**
     * 把同一 conversation 下、除 {@code keepProposalId} 外的活跃（GENERATING/REVIEWING）提案置 SUPERSEDED，
     * 避免同文件多轮编辑提案堆叠（协议 §9.3 supersede）。
     */
    int supersedeActiveExcept(Long conversationId, String keepProposalId);
}
