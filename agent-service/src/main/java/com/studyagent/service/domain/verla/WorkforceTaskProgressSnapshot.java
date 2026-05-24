package com.studyagent.service.domain.verla;

/**
 * Aggregated workforce task progress for a Verla agent session.
 *
 * Mirrors the SQL in docs/V2/算法侧提供的耗时统计思路.md.
 */
public record WorkforceTaskProgressSnapshot(
        int totalTaskCount,
        int completedTaskCount,
        int activeTaskCount,
        Integer composeTotalRounds
) {
    public static WorkforceTaskProgressSnapshot empty() {
        return new WorkforceTaskProgressSnapshot(0, 0, 0, null);
    }

    public boolean hasTaskData() {
        return totalTaskCount > 0;
    }
}
