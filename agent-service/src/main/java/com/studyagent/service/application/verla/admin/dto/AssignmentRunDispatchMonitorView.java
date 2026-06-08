package com.studyagent.service.application.verla.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssignmentRunDispatchMonitorView {

    private Summary summary;
    private List<TaskRow> tasks;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Summary {
        private boolean gateEnabled;
        private int maxConcurrency;
        private int activeCount;
        private int pendingDispatchCount;
        private int inFlightCount;
        private int queuedCount;
        private int completedLast24Hours;
        private int failedLast24Hours;
        private int startedLastHour;
        private int completedLastHour;
        private LocalDateTime generatedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TaskRow {
        private Long sessionId;
        private Long conversationId;
        private Long turnId;
        private Long outboxId;
        private String clerkUserId;
        private String featureCode;
        private String sessionStatus;
        private String outboxStatus;
        private String outboxAction;
        /** queued | dispatching | running | completed | failed | cancelled */
        private String lifecycle;
        /** Human-readable stage for ops console */
        private String stageLabel;
        private String lastEventType;
        private LocalDateTime lastEventAt;
        private Integer queuePosition;
        private LocalDateTime startedAt;
        private LocalDateTime endedAt;
        private Long durationSeconds;
        private LocalDateTime outboxCreatedAt;
    }
}
