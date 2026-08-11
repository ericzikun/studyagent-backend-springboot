package com.studyagent.service.application.verla.metrics;

public record AssignmentTerminalTransitionedEvent(Long sessionId, Status status) {

    public enum Status {
        COMPLETED("completed"),
        FAILED("failed"),
        CANCELLED("cancelled");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
