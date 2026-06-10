package com.studyagent.api.dto.verla.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * Chat With Assignment / write 模式 review 提案 commit 请求（设计 §4.8）。
 * <p>
 * 只回传每个 hunk 的 accepted/rejected 决定，最终正文由后端按已下发 hunks 重算。
 * 全部 reject 也走 commit（artifact / editor 不变，仅置 COMMITTED）。
 */
@Data
public class CommitEditProposalRequest {

    @NotNull
    private List<Decision> decisions;

    @Data
    public static class Decision {
        /** 目标 artifact uid */
        private String artifactUid;
        /** hunk id（ARTIFACT_EDIT_PROPOSAL_READY 下发，同 proposal 内唯一） */
        private String hunkId;
        /** accepted / rejected */
        private String status;
    }
}
