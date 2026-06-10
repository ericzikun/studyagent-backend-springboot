package com.studyagent.service.domain.verla;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Chat With Assignment / write 模式 Edit Proposal 领域对象。
 * <p>
 * 对应 {@code verla_artifact_edit_proposals} 表（chat_with_assignment 协议 §9.3）。
 * 由 {@code ARTIFACT_EDIT_PROPOSAL_STARTED} 创建（GENERATING），{@code _READY} 转
 * REVIEWING 并写 hunks，用户 commit 后置 COMMITTED 并提升 artifact 版本。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerlaArtifactEditProposal {

    public static final String STATE_GENERATING = "GENERATING";
    public static final String STATE_REVIEWING = "REVIEWING";
    public static final String STATE_COMMITTED = "COMMITTED";
    public static final String STATE_FAILED = "FAILED";
    public static final String STATE_CANCELLED = "CANCELLED";
    public static final String STATE_SUPERSEDED = "SUPERSEDED";

    private Long id;
    /** 业务唯一 ID（ep_{conversationId}_{turnId}），Py 生成 */
    private String proposalId;
    private Long conversationId;
    /** 产生该提案的 chat 轮 */
    private Long turnId;
    /** GENERATING / REVIEWING / COMMITTED / FAILED / CANCELLED / SUPERSEDED */
    private String state;
    /** 全部 target：[{artifactUid,kind,title,editMode,baseVersionNo,versionNo}] */
    private String targetsJson;
    /** review 目标的 EditChangeHunk[]，按 artifactUid 分组（overwrite 目标无） */
    private String changesJson;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime resolvedAt;
    private LocalDateTime updatedAt;
}
