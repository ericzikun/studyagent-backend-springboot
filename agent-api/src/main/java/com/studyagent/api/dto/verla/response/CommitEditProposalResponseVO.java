package com.studyagent.api.dto.verla.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Edit Proposal commit 响应（设计 §4.8）：返回每个被提升的 artifact 最新版本。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CommitEditProposalResponseVO {

    private String proposalId;
    private String conversationId;
    private List<CommittedArtifact> artifacts;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CommittedArtifact {
        private String artifactUid;
        private String kind;
        private Integer versionNo;
    }
}
