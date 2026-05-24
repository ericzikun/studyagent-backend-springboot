package com.studyagent.service.application.verla.dto;

/**
 * Java-computed Assignment runtime progress facts for V2 recovery and SSE enrichment.
 */
public record AssignmentRuntimeProgressEstimate(
        String label,
        int estimatedRemainingSeconds,
        double completePercent,
        Integer completedTaskCount,
        Integer totalTaskCount,
        Integer composeCurrentRound,
        Integer composeTotalRounds
) {
}
