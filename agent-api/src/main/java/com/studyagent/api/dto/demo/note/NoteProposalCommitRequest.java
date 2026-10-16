package com.studyagent.api.dto.demo.note;

import lombok.Data;

import java.util.List;

/**
 * 提交待审提案的逐块决策。
 * <p>{@code baseVersion} 做乐观锁；{@code decisions} 里没出现的 hunk 视为**未接受**（不落地）。
 */
@Data
public class NoteProposalCommitRequest {

    /** 待审提案 id（快照/事件里带出来的那个）；与库中不一致说明提案已更新。 */
    private String proposalId;

    /** 编辑器持有的版本号，乐观锁基线。 */
    private Long baseVersion;

    private List<Decision> decisions;

    /** 单块决策：status 只认 accepted / rejected。 */
    @Data
    public static class Decision {
        private String hunkId;
        private String status;
    }
}
