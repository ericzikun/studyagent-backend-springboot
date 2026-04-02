package com.studyagent.service.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotifyDispatchResult {

    private int code;
    private String message;
    private NotifyDispatchData data;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NotifyDispatchData {
        private String eventId;
        private String sourceService;
        private String scene;
        private String level;
        private String contentType;
        private String env;
        private String status;
        private String deliveryId;
        private NotifyErrorData error;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NotifyErrorData {
        private String type;
        private String detail;
        private boolean retryable;
    }
}
