package com.studyagent.api.dto.admin.response;

import com.studyagent.service.application.verla.admin.dto.AssignmentRunDispatchMonitorView;
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
public class AssignmentRunDispatchMonitorVO {

    private Summary summary;
    private List<TaskRow> tasks;

    public static AssignmentRunDispatchMonitorVO from(AssignmentRunDispatchMonitorView view) {
        if (view == null) {
            return AssignmentRunDispatchMonitorVO.builder()
                    .tasks(List.of())
                    .build();
        }
        return AssignmentRunDispatchMonitorVO.builder()
                .summary(Summary.from(view.getSummary()))
                .tasks(view.getTasks() == null
                        ? List.of()
                        : view.getTasks().stream().map(TaskRow::from).toList())
                .build();
    }

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

        static Summary from(AssignmentRunDispatchMonitorView.Summary s) {
            if (s == null) {
                return null;
            }
            return Summary.builder()
                    .gateEnabled(s.isGateEnabled())
                    .maxConcurrency(s.getMaxConcurrency())
                    .activeCount(s.getActiveCount())
                    .pendingDispatchCount(s.getPendingDispatchCount())
                    .inFlightCount(s.getInFlightCount())
                    .queuedCount(s.getQueuedCount())
                    .completedLast24Hours(s.getCompletedLast24Hours())
                    .failedLast24Hours(s.getFailedLast24Hours())
                    .startedLastHour(s.getStartedLastHour())
                    .completedLastHour(s.getCompletedLastHour())
                    .generatedAt(s.getGeneratedAt())
                    .build();
        }
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
        private String lifecycle;
        private String stageLabel;
        private String lastEventType;
        private LocalDateTime lastEventAt;
        private Integer queuePosition;
        private LocalDateTime startedAt;
        private LocalDateTime endedAt;
        private Long durationSeconds;
        private LocalDateTime outboxCreatedAt;

        static TaskRow from(AssignmentRunDispatchMonitorView.TaskRow row) {
            if (row == null) {
                return null;
            }
            return TaskRow.builder()
                    .sessionId(row.getSessionId())
                    .conversationId(row.getConversationId())
                    .turnId(row.getTurnId())
                    .outboxId(row.getOutboxId())
                    .clerkUserId(row.getClerkUserId())
                    .featureCode(row.getFeatureCode())
                    .sessionStatus(row.getSessionStatus())
                    .outboxStatus(row.getOutboxStatus())
                    .outboxAction(row.getOutboxAction())
                    .lifecycle(row.getLifecycle())
                    .stageLabel(row.getStageLabel())
                    .lastEventType(row.getLastEventType())
                    .lastEventAt(row.getLastEventAt())
                    .queuePosition(row.getQueuePosition())
                    .startedAt(row.getStartedAt())
                    .endedAt(row.getEndedAt())
                    .durationSeconds(row.getDurationSeconds())
                    .outboxCreatedAt(row.getOutboxCreatedAt())
                    .build();
        }
    }
}
